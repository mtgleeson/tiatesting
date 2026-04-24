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
     * @param dataStore the persistence layer to read pending batches and tracked libraries.
     * @param libraryConfig the library impact analysis configuration (provides the metadata reader).
     * @param tiaData the current TIA data with test-to-source mappings for resolving tests.
     * @return a {@link DrainOutcome} containing the tests to add and the drain result.
     */
    public DrainOutcome drainPendingMethods(DataStore dataStore, LibraryImpactAnalysisConfig libraryConfig,
                                            TiaData tiaData) {
        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        Set<String> testsFromDrain = new LinkedHashSet<>();

        Map<String, TrackedLibrary> trackedLibraries = dataStore.readTrackedLibraries();
        if (trackedLibraries.isEmpty()) {
            return new DrainOutcome(testsFromDrain, drainResult);
        }

        Map<String, ResolvedSourceProjectLibrary> resolvedLibraries =
                resolveAllLibrariesOnSourceProject(libraryConfig, trackedLibraries);

        Map<Integer, Set<String>> methodToTestSuiteMap = buildMethodToTestSuiteMap(tiaData);

        for (TrackedLibrary library : trackedLibraries.values()) {
            drainPendingMethodsForLibrary(dataStore, library, resolvedLibraries,
                    methodToTestSuiteMap, testsFromDrain, drainResult);
        }

        return new DrainOutcome(testsFromDrain, drainResult);
    }

    /**
     * Drain pending batches for a single tracked library using the pre-resolved library versions.
     * Evaluates each pending batch against the drain rules and collects impacted tests for
     * batches that qualify.
     */
    private void drainPendingMethodsForLibrary(DataStore dataStore, TrackedLibrary library,
                                                Map<String, ResolvedSourceProjectLibrary> resolvedLibraries,
                                                Map<Integer, Set<String>> methodToTestSuiteMap,
                                                Set<String> testsFromDrain,
                                                LibraryImpactDrainResult drainResult) {
        List<PendingLibraryImpactedMethod> pendingBatches =
                dataStore.readPendingLibraryImpactedMethods(library.getGroupArtifact());

        if (pendingBatches.isEmpty()) {
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
        for (PendingLibraryImpactedMethod batch : pendingBatches) {
            if (shouldDrainBatch(batch, library, resolvedVersion, resolvedJarHash)) {
                Set<String> testsForBatch = resolveTestSuitesFromMethodIds(batch.getSourceMethodIds(),
                        methodToTestSuiteMap);
                testsFromDrain.addAll(testsForBatch);
                drainResult.addDrainedBatch(library.getGroupArtifact(), batch.getStampVersion());
                anyDrained = true;

                log.info("Drained pending batch for library '{}' at stamp version '{}' — {} tests selected.",
                        library.getGroupArtifact(), batch.getStampVersion(), testsForBatch.size());
            } else {
                log.debug("Pending batch for library '{}' at stamp version '{}' not yet eligible for drain.",
                        library.getGroupArtifact(), batch.getStampVersion());
            }
        }

        if (anyDrained) {
            drainResult.setObservedLibraryState(library.getGroupArtifact(), resolvedVersion, resolvedJarHash);
        }
    }

    /**
     * Determine whether a pending batch should be drained based on the drain rules.
     * For release stamps: resolved version must be &ge; stamp version AND must differ from
     * the library's {@code last_source_project_version}.
     * For SNAPSHOT stamps: the resolved JAR hash must differ from the library's
     * {@code last_source_project_jar_hash}.
     */
    private boolean shouldDrainBatch(PendingLibraryImpactedMethod batch, TrackedLibrary library,
                                      String resolvedVersion, String resolvedJarHash) {
        String stampVersion = batch.getStampVersion();

        if (isSnapshotVersion(stampVersion)) {
            return shouldDrainSnapshotBatch(library, resolvedJarHash);
        } else {
            return shouldDrainReleaseBatch(batch, library, resolvedVersion);
        }
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
     * {@code BUMP_AT_RELEASE} at the observed HWM, i.e. destined for the next, unknown release),
     * it is held as long as the source project's resolved version still equals the stamp version.
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

        log.trace("Checking if a release for lib {} should be drained: resolved version {}, tracked version {}",
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
        log.trace("Checking if a snapshot for lib {} should be drained: resolved hash {}, tracked hash {}",
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
     * those methods using the current test-to-source mapping. This resolves tests at drain time
     * (not stamp time) so the mapping reflects the consumer's current state.
     */
    private Set<String> resolveTestSuitesFromMethodIds(Set<Integer> methodIds,
                                                        Map<Integer, Set<String>> methodToTestSuiteMap) {
        Set<String> tests = new LinkedHashSet<>();
        for (Integer methodId : methodIds) {
            Set<String> testSuites = methodToTestSuiteMap.get(methodId);
            if (testSuites != null) {
                tests.addAll(testSuites);
            }
        }
        return tests;
    }

    /**
     * Build a reverse lookup from source method ID to the set of test suites that exercise
     * that method, derived from the current {@code TiaData.testSuitesTracked}.
     */
    private Map<Integer, Set<String>> buildMethodToTestSuiteMap(TiaData tiaData) {
        Map<Integer, Set<String>> map = new HashMap<>();

        if (tiaData.getTestSuitesTracked() == null) {
            return map;
        }

        for (Map.Entry<String, TestSuiteTracker> entry : tiaData.getTestSuitesTracked().entrySet()) {
            String testSuiteName = entry.getKey();
            TestSuiteTracker tracker = entry.getValue();
            for (ClassImpactTracker classImpact : tracker.getClassesImpacted()) {
                for (Integer methodId : classImpact.getMethodsImpacted()) {
                    map.computeIfAbsent(methodId, k -> new LinkedHashSet<>()).add(testSuiteName);
                }
            }
        }

        return map;
    }

    /**
     * Check whether a version string represents a SNAPSHOT build.
     */
    private boolean isSnapshotVersion(String version) {
        return version != null && version.toUpperCase().endsWith("-SNAPSHOT");
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
