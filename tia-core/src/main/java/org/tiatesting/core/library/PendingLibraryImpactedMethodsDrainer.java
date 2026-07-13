package org.tiatesting.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.*;
import org.tiatesting.core.persistence.DataStore;

import java.io.File;
import java.util.*;

/**
 * Drains pending library impacted method rows when the source project has caught up to (or past)
 * the stamped library version. For each drained batch, resolves the test suites that exercise
 * the pending source method IDs using the current test-to-source mapping, then adds those tests
 * to the run set.
 *
 * <p>Drain rules:
 * <ul>
 *   <li><b>Release stamps:</b> drain when the source project's resolved version is &ge; the stamp
 *       version AND differs from the library's {@code last_source_project_version}. It needs to differ to account
 *       for the scenario where the library changes are committed but the lib version hasn't increased yet. Those lib
 *       changes are stamped with the current lib version, not the new version. But they should be applied to the source
 *       project only when the lib version increases past the source project's current lib version.</li>
 *   <li><b>SNAPSHOT stamps:</b> drain when the resolved JAR content hash differs from the library's
 *       {@code last_source_project_jar_hash}.</li>
 * </ul>
 *
 * <p>The drainer does NOT mutate the data store. It returns a {@link LibraryImpactDrainResult}
 * listing which batches were drained and what resolved state was observed. The caller
 * ({@code TestRunnerService}) deletes the drained rows and updates {@code tia_library} after
 * the test run completes.
 */
public class PendingLibraryImpactedMethodsDrainer {

    private static final Logger log = LoggerFactory.getLogger(PendingLibraryImpactedMethodsDrainer.class);

    /**
     * Drain pending library impacted methods for all tracked libraries and return the
     * tests that should be added to the run set along with the drain result for post-run cleanup.
     * 'Drain' means select the pending libs changes from the DB and check if they have been applied to
     * the source project through the lib dependency version change.
     *
     * <p>Test resolution for drained batches uses the targeted
     * {@link DataStore#getTestSuitesForMethods} query per batch, so the drain path never needs
     * the full test-to-source mapping loaded in memory.
     *
     * @param dataStore the persistence layer to read pending batches, tracked libraries and
     *                  the per-batch test-suite resolution.
     * @param libraryConfig the library impact analysis configuration (provides the metadata reader).
     * @return a {@link DrainOutcome} containing the tests to add and the drain result.
     */
    public DrainOutcome drainPendingMethods(DataStore dataStore, LibraryImpactAnalysisConfig libraryConfig) {
        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        Set<String> testsFromDrain = new LinkedHashSet<>();

        Map<String, TrackedLibrary> trackedLibraries = dataStore.readTrackedLibraries();
        if (trackedLibraries.isEmpty()) {
            return new DrainOutcome(testsFromDrain, drainResult);
        }

        Map<String, ResolvedSourceProjectLibrary> resolvedLibraries =
                resolveAllLibrariesOnSourceProject(libraryConfig, trackedLibraries);

        for (TrackedLibrary library : trackedLibraries.values()) {
            drainPendingMethodsForLibrary(dataStore, library, resolvedLibraries,
                    testsFromDrain, drainResult);
        }

        return new DrainOutcome(testsFromDrain, drainResult);
    }

    /**
     * Dry-run drain used by the {@code select-tests} preview: evaluate the drain rules against the
     * union of the persisted pending batches and the caller-supplied in-memory {@code synthetic}
     * batches, and return the test suites that <em>would</em> be selected right now, without
     * mutating any state. Nothing is persisted, no batch is deleted, and no {@code tia_library}
     * state is advanced - so the result reflects "what would run now" for the current resolved
     * library versions, matching what a real mapping-updating run would select.
     *
     * <p>Synthetic batches let the preview account for library changes that appear for the first
     * time in the analyzed range and have therefore never been recorded as persisted rows. They
     * are merged with any persisted batch sharing the same {@code (groupArtifact, stampVersion)}
     * by unioning their impacted method ids, mirroring the recorder's MERGE semantics.
     *
     * @param dataStore the persistence layer (read-only here) for tracked libraries, persisted
     *                  pending batches and per-batch test-suite resolution.
     * @param libraryConfig the library impact analysis configuration (provides the metadata reader).
     * @param syntheticByLib in-memory candidate batches keyed by {@code groupArtifact}; may be null
     *                       or empty, in which case only persisted batches are evaluated.
     * @return the test suites that the current library state would drain; never null.
     */
    public Set<String> previewTestsForBatches(DataStore dataStore, LibraryImpactAnalysisConfig libraryConfig,
                                              Map<String, List<PendingLibraryImpactedMethod>> syntheticByLib) {
        Set<String> testsToAdd = new LinkedHashSet<>();

        Map<String, TrackedLibrary> trackedLibraries = dataStore.readTrackedLibraries();
        if (trackedLibraries.isEmpty()) {
            return testsToAdd;
        }

        Map<String, ResolvedSourceProjectLibrary> resolvedLibraries =
                resolveAllLibrariesOnSourceProject(libraryConfig, trackedLibraries);

        for (TrackedLibrary library : trackedLibraries.values()) {
            List<PendingLibraryImpactedMethod> persisted =
                    dataStore.readPendingLibraryImpactedMethods(library.getGroupArtifact());
            List<PendingLibraryImpactedMethod> synthetic =
                    syntheticByLib != null ? syntheticByLib.get(library.getGroupArtifact()) : null;
            List<PendingLibraryImpactedMethod> batches = mergeBatches(persisted, synthetic);

            // drainResult == null signals preview mode: collect tests but record no drain/cleanup state.
            evaluateBatchesForLibrary(dataStore, library, batches, resolvedLibraries, testsToAdd, null);
        }

        return testsToAdd;
    }

    /**
     * Drain pending batches for a single tracked library: read the library's persisted pending
     * batches and evaluate them against the drain rules using the pre-resolved library versions.
     *
     * @param dataStore the persistence layer for pending batches and test-suite resolution
     * @param library the tracked library whose pending batches are evaluated
     * @param resolvedLibraries the libraries resolved on the source project classpath, by coordinate
     * @param testsFromDrain accumulator for the test suites selected by drained batches
     * @param drainResult accumulator for the drained batch keys and observed library state
     */
    private void drainPendingMethodsForLibrary(DataStore dataStore, TrackedLibrary library,
                                                Map<String, ResolvedSourceProjectLibrary> resolvedLibraries,
                                                Set<String> testsFromDrain,
                                                LibraryImpactDrainResult drainResult) {
        List<PendingLibraryImpactedMethod> pendingBatches =
                dataStore.readPendingLibraryImpactedMethods(library.getGroupArtifact());
        evaluateBatchesForLibrary(dataStore, library, pendingBatches, resolvedLibraries,
                testsFromDrain, drainResult);
    }

    /**
     * Evaluate a supplied list of batches for one library against the drain rules and collect the
     * covering test suites for those that qualify. Shared by the real drain
     * ({@link #drainPendingMethodsForLibrary}) and the preview ({@link #previewTestsForBatches}).
     *
     * <p>When {@code drainResult} is non-null (real drain) the drained batch keys and the observed
     * resolved library state are recorded for post-run cleanup. When {@code drainResult} is null
     * (preview) only {@code testsFromDrain} is populated and no drain/cleanup state is recorded -
     * the evaluation itself is read-only either way.
     *
     * @param dataStore the persistence layer for per-batch test-suite resolution
     * @param library the tracked library whose batches are evaluated
     * @param batches the batches to evaluate (persisted, synthetic, or their union)
     * @param resolvedLibraries the libraries resolved on the source project classpath, by coordinate
     * @param testsFromDrain accumulator for the test suites selected by qualifying batches
     * @param drainResult accumulator for drained batch keys and observed state, or null for preview
     */
    private void evaluateBatchesForLibrary(DataStore dataStore, TrackedLibrary library,
                                           List<PendingLibraryImpactedMethod> batches,
                                           Map<String, ResolvedSourceProjectLibrary> resolvedLibraries,
                                           Set<String> testsFromDrain,
                                           LibraryImpactDrainResult drainResult) {
        if (batches.isEmpty()) {
            return;
        }

        ResolvedSourceProjectLibrary resolved = resolvedLibraries.get(library.getGroupArtifact());
        if (resolved == null) {
            log.info("Could not resolve library '{}' on source project classpath — skipping drain.",
                    library.getGroupArtifact());
            return;
        }

        String resolvedVersion = resolved.getResolvedVersion();
        String resolvedJarHash = computeResolvedJarHash(resolved);

        log.info("Library '{}' resolved on source project: version='{}', jarHash='{}'.",
                library.getGroupArtifact(), resolvedVersion,
                resolvedJarHash != null ? resolvedJarHash : "N/A");

        boolean anyDrained = false;
        for (PendingLibraryImpactedMethod batch : batches) {
            if (shouldDrainBatch(batch, library, resolvedVersion, resolvedJarHash)) {
                Set<String> testsForBatch = resolveTestSuitesFromMethodIds(batch.getSourceMethodIds(),
                        dataStore);
                testsFromDrain.addAll(testsForBatch);
                if (drainResult != null) {
                    drainResult.addDrainedBatch(library.getGroupArtifact(), batch.getStampVersion());
                }
                anyDrained = true;

                log.info("Drained pending batch for library '{}' at stamp version '{}' — {} tests selected.",
                        library.getGroupArtifact(), batch.getStampVersion(), testsForBatch.size());
            } else {
                log.debug("Pending batch for library '{}' at stamp version '{}' not yet eligible for drain.",
                        library.getGroupArtifact(), batch.getStampVersion());
            }
        }

        if (anyDrained && drainResult != null) {
            drainResult.setObservedLibraryState(library.getGroupArtifact(), resolvedVersion, resolvedJarHash);
        }
    }

    /**
     * Merge a library's persisted and synthetic pending batches into a single list, deduplicating
     * by {@code stampVersion} (all batches here belong to one library) and unioning the impacted
     * method ids of batches that share a stamp. Copies each batch so neither the caller's synthetic
     * list nor the data store's returned rows are mutated by the union.
     *
     * @param persisted the library's persisted pending batches; may be null
     * @param synthetic the in-memory candidate batches for the library; may be null
     * @return the merged, de-duplicated batch list; never null
     */
    private List<PendingLibraryImpactedMethod> mergeBatches(List<PendingLibraryImpactedMethod> persisted,
                                                            List<PendingLibraryImpactedMethod> synthetic) {
        Map<String, PendingLibraryImpactedMethod> byStampVersion = new LinkedHashMap<>();
        addBatchesByStampVersion(byStampVersion, persisted);
        addBatchesByStampVersion(byStampVersion, synthetic);
        return new ArrayList<>(byStampVersion.values());
    }

    /**
     * Add batches into the stamp-version-keyed accumulator, creating a defensive copy on first
     * insertion and unioning impacted method ids when a later batch shares an existing stamp.
     *
     * @param byStampVersion the accumulator keyed by {@code stampVersion}
     * @param batches the batches to add; may be null
     */
    private void addBatchesByStampVersion(Map<String, PendingLibraryImpactedMethod> byStampVersion,
                                          List<PendingLibraryImpactedMethod> batches) {
        if (batches == null) {
            return;
        }
        for (PendingLibraryImpactedMethod batch : batches) {
            PendingLibraryImpactedMethod existing = byStampVersion.get(batch.getStampVersion());
            if (existing == null) {
                PendingLibraryImpactedMethod copy = new PendingLibraryImpactedMethod(
                        batch.getGroupArtifact(), batch.getStampVersion(), batch.getStampJarHash(),
                        new LinkedHashSet<>(batch.getSourceMethodIds()));
                copy.setUnknownNextVersion(batch.isUnknownNextVersion());
                byStampVersion.put(batch.getStampVersion(), copy);
            } else {
                existing.getSourceMethodIds().addAll(batch.getSourceMethodIds());
            }
        }
    }

    /**
     * Determine whether a pending batch should be drained based on the drain rules. The path is
     * chosen by both the stamp's version form and the source project's resolved version form:
     * <ul>
     *     <li><b>Both SNAPSHOT</b> — JAR-hash comparison (a new SNAPSHOT build has been picked up).</li>
     *     <li><b>Both release</b> — release-style version comparison.</li>
     *     <li><b>SNAPSHOT stamp, release resolved</b> — release-style comparison against the
     *         SNAPSHOT's target version (i.e. the stamp with {@code -SNAPSHOT} stripped). A hash
     *         compare here would always trigger because the resolved JAR is for a release line,
     *         not the SNAPSHOT, so the hashes differ trivially and would cause a false drain.</li>
     *     <li><b>Release stamp, SNAPSHOT resolved</b> — release-style comparison (the SNAPSHOT
     *         orders past prior releases under the existing comparator).</li>
     * </ul>
     */
    private boolean shouldDrainBatch(PendingLibraryImpactedMethod batch, TrackedLibrary library,
                                      String resolvedVersion, String resolvedJarHash) {
        String stampVersion = batch.getStampVersion();
        boolean stampIsSnapshot = isSnapshotVersion(stampVersion);
        boolean resolvedIsSnapshot = isSnapshotVersion(resolvedVersion);

        if (stampIsSnapshot && !resolvedIsSnapshot) {
            return shouldDrainSnapshotStampAgainstReleaseSource(batch, library, resolvedVersion);
        }

        if (stampIsSnapshot) {
            return shouldDrainSnapshotBatch(library, resolvedJarHash);
        }

        return shouldDrainReleaseBatch(batch, library, resolvedVersion);
    }

    /**
     * Drain rule for the case where the pending stamp is a SNAPSHOT but the source project has
     * moved to a release version. The SNAPSHOT's target release (its version with the
     * {@code -SNAPSHOT} suffix removed) is compared against the resolved release version using
     * release-style semantics: drain only when the resolved release is &ge; the SNAPSHOT's target.
     * The same {@code lastSourceProjectVersion} differs check from the release path applies, to
     * avoid re-draining when the source project has not advanced since the previous drain.
     */
    private boolean shouldDrainSnapshotStampAgainstReleaseSource(PendingLibraryImpactedMethod batch,
                                                                  TrackedLibrary library,
                                                                  String resolvedVersion) {
        if (resolvedVersion == null) {
            return false;
        }

        String stampVersion = batch.getStampVersion();
        String stampTargetVersion = stripSnapshotSuffix(stampVersion);

        if (Objects.equals(resolvedVersion, library.getLastSourceProjectVersion())) {
            log.debug("Resolved version '{}' matches last_source_project_version — skipping drain " +
                    "of SNAPSHOT stamp '{}' against release source.", resolvedVersion, stampVersion);
            return false;
        }

        log.debug("Checking if SNAPSHOT stamp {} should be drained against release-resolved version {} " +
                "for lib {} — comparing stamp target {} to resolved release",
                stampVersion, resolvedVersion, library.getGroupArtifact(), stampTargetVersion);
        return compareVersions(resolvedVersion, stampTargetVersion) >= 0;
    }

    /**
     * A release batch drains when the source project's resolved version is at least as high
     * as the stamp version, AND the resolved version differs from the library's last tracked
     * version. It needs to differ to account for the scenario where the library changes are committed
     * but the lib version hasn't increased yet. Those lib changes are stamped with the current lib version,
     * not the new version. But they should be applied to the source project only when the lib
     * version increases past the source project's current lib version.
     *
     * <p>Hold rule: when the batch carries {@code unknownNextVersion=true} (stamped under
     * {@code BUMP_AT_RELEASE} at the observed high water mark, i.e. destined for the next,
     * unknown release), it is held as long as the source project's resolved version still
     * equals the stamp version.
     * The drain only fires once the resolved version moves past the stamp. See {@code WIKI.md}
     * for the full model.
     *
     * @param batch the pending batch under evaluation
     * @param library the tracked library
     * @param resolvedVersion the resolved version of the library on the source project classpath
     */
    private boolean shouldDrainReleaseBatch(PendingLibraryImpactedMethod batch, TrackedLibrary library,
                                             String resolvedVersion) {
        if (resolvedVersion == null) {
            return false;
        }

        String stampVersion = batch.getStampVersion();

        if (batch.isUnknownNextVersion() && compareVersions(resolvedVersion, stampVersion) == 0) {
            log.debug("Holding pending batch for library '{}' at stamp '{}' — unknownNextVersion=true " +
                    "and resolved version has not advanced past the stamp.",
                    library.getGroupArtifact(), stampVersion);
            return false;
        }

        if (Objects.equals(resolvedVersion, library.getLastSourceProjectVersion())) {
            log.debug("Resolved version '{}' matches last_source_project_version — skipping drain.",
                    resolvedVersion);
            return false;
        }

        log.debug("Checking if a release for lib {} should be drained: resolved version {}, tracked version {}",
                library.getGroupArtifact(), resolvedVersion, stampVersion);
        return compareVersions(resolvedVersion, stampVersion) >= 0;
    }

    /**
     * A SNAPSHOT batch drains when the resolved JAR content hash differs from the library's
     * last tracked JAR hash. This detects when a new SNAPSHOT build has been published and
     * picked up by the source project.
     */
    private boolean shouldDrainSnapshotBatch(TrackedLibrary library, String resolvedJarHash) {
        if (resolvedJarHash == null) {
            return false;
        }
        log.debug("Checking if a snapshot for lib {} should be drained: resolved hash {}, tracked hash {}",
                library.getGroupArtifact(), resolvedJarHash, library.getLastSourceProjectJarHash());
        return !Objects.equals(resolvedJarHash, library.getLastSourceProjectJarHash());
    }

    /**
     * Resolve all tracked library coordinates on the source project's classpath in a single
     * call to {@link LibraryMetadataReader#resolveLibrariesInSourceProject}. Resolving library
     * versions is expensive (e.g. loading a Maven POM or Gradle model), so this ensures we
     * only pay that cost once regardless of how many libraries have pending changes.
     *
     * @return a map from {@code groupArtifact} to the resolved library, or an empty map entry
     *         for coordinates that could not be resolved.
     */
    private Map<String, ResolvedSourceProjectLibrary> resolveAllLibrariesOnSourceProject(
            LibraryImpactAnalysisConfig libraryConfig, Map<String, TrackedLibrary> trackedLibraries) {

        List<String> allCoordinates = new ArrayList<>(trackedLibraries.keySet());

        List<ResolvedSourceProjectLibrary> resolvedList = libraryConfig.getMetadataReader()
                .resolveLibrariesInSourceProject(libraryConfig.getSourceProjectDir(), allCoordinates);

        Map<String, ResolvedSourceProjectLibrary> resolvedMap = new LinkedHashMap<>();
        for (ResolvedSourceProjectLibrary resolved : resolvedList) {
            resolvedMap.put(resolved.getGroupArtifact(), resolved);
        }

        return resolvedMap;
    }

    /**
     * Compute a SHA-256 content hash of the resolved JAR file for SNAPSHOT version comparison.
     * Returns {@code null} if the JAR path is not available.
     */
    private String computeResolvedJarHash(ResolvedSourceProjectLibrary resolved) {
        if (resolved.getJarFilePath() == null) {
            return null;
        }
        return PendingLibraryImpactedMethodsRecorder.computeSha256Hash(new File(resolved.getJarFilePath()));
    }

    /**
     * Given a set of impacted source method IDs, find all test suites that exercise any of
     * those methods using the targeted {@link DataStore#getTestSuitesForMethods} query against
     * the current test-to-source mapping. This resolves tests at drain time (not stamp time)
     * so the mapping reflects the consumer's current state, and only reads the mapping rows
     * for the batch's method ids rather than the full mapping.
     *
     * @param methodIds the batch's impacted source method ids
     * @param dataStore the persistence layer to resolve covering test suites from
     * @return the names of the test suites covering any of the batch's methods
     */
    private Set<String> resolveTestSuitesFromMethodIds(Set<Integer> methodIds, DataStore dataStore) {
        Set<String> tests = new LinkedHashSet<>();
        for (Set<String> testSuites : dataStore.getTestSuitesForMethods(methodIds).values()) {
            tests.addAll(testSuites);
        }
        return tests;
    }

    /**
     * Check whether a version string represents a SNAPSHOT build.
     */
    private boolean isSnapshotVersion(String version) {
        return version != null && version.toUpperCase().endsWith("-SNAPSHOT");
    }

    /**
     * Return the SNAPSHOT version's target release — the version string with a trailing
     * {@code -SNAPSHOT} suffix removed (case-insensitive). Returns the input unchanged when
     * it does not end with {@code -SNAPSHOT}.
     */
    private static String stripSnapshotSuffix(String version) {
        if (version == null) {
            return null;
        }
        int suffixStart = version.length() - "-SNAPSHOT".length();
        if (suffixStart >= 0 && version.substring(suffixStart).equalsIgnoreCase("-SNAPSHOT")) {
            return version.substring(0, suffixStart);
        }
        return version;
    }

    /**
     * Compare two version strings using segment-by-segment comparison.
     * Each segment is compared numerically when both are valid integers,
     * otherwise compared lexicographically. Returns a negative integer,
     * zero, or a positive integer as {@code v1} is less than, equal to,
     * or greater than {@code v2}.
     */
    static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("[.\\-]");
        String[] parts2 = v2.split("[.\\-]");
        int maxLen = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < maxLen; i++) {
            String p1 = i < parts1.length ? parts1[i] : "0";
            String p2 = i < parts2.length ? parts2[i] : "0";

            int cmp = compareSegments(p1, p2);
            if (cmp != 0) {
                return cmp;
            }
        }

        return 0;
    }

    /**
     * Compare two individual version segments. If both parse as integers, compare numerically.
     * Otherwise fall back to lexicographic comparison.
     */
    private static int compareSegments(String s1, String s2) {
        try {
            int n1 = Integer.parseInt(s1);
            int n2 = Integer.parseInt(s2);
            return Integer.compare(n1, n2);
        } catch (NumberFormatException e) {
            return s1.compareTo(s2);
        }
    }

    /**
     * Holds the outcome of a drain operation: the test suites to add to the run set
     * and the drain result for post-test-run cleanup.
     */
    public static class DrainOutcome {
        private final Set<String> testsToAdd;
        private final LibraryImpactDrainResult drainResult;

        public DrainOutcome(Set<String> testsToAdd, LibraryImpactDrainResult drainResult) {
            this.testsToAdd = testsToAdd;
            this.drainResult = drainResult;
        }

        public Set<String> getTestsToAdd() {
            return testsToAdd;
        }

        public LibraryImpactDrainResult getDrainResult() {
            return drainResult;
        }
    }
}
