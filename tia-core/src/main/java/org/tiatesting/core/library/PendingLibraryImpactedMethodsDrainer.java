package org.tiatesting.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.LibraryPublish;
import org.tiatesting.core.model.PendingLibraryForcedSelection;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.staticselection.StaticTestSelectionResolver;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Consumer side of publish-time library stamping: drains the pending impacted-method stamps that
 * are contained in the library build the source project actually resolved. The resolved artifact
 * is matched against the publish ledger (jar hash first, exact version for a resolved release) to
 * find its publish sequence R; every pending stamp with {@code publishSeq <= R} drains, because
 * builds are cumulative - the jar at R physically contains every change stamped at or before R.
 * See the drain-rule section of the library publish-time stamping chapter in {@code WIKI.md}.
 *
 * <p>Hold rules - the drain selects nothing (and warns) when it cannot prove the resolved build
 * contains the pending changes:
 * <ul>
 *   <li><b>Unresolvable library</b> - the coordinate is not on the source project classpath.</li>
 *   <li><b>Unknown build</b> - the resolved artifact matches no ledger row (published before the
 *       ledger existed, or a publish where stamping was skipped). Holding cannot produce a false
 *       green; draining blindly could. Self-heals on the next publish/resolve cycle.</li>
 *   <li><b>Downgrade</b> - the resolved build's sequence is below the library's
 *       {@code lastAppliedSeq}; those tests already ran against newer code.</li>
 * </ul>
 *
 * <p>The drainer does NOT mutate the data store. It returns a {@link LibraryImpactDrainResult}
 * listing the drained batch sequences and the applied sequence per library; the caller
 * ({@code TestRunnerService}) deletes the drained rows and advances
 * {@code tia_library.last_applied_seq} / {@code mapping_baseline_commit} after the test run.
 */
public class PendingLibraryImpactedMethodsDrainer {

    private static final Logger log = LoggerFactory.getLogger(PendingLibraryImpactedMethodsDrainer.class);

    /**
     * Drain the pending stamps contained in the library builds the source project resolves, and
     * return the covering tests to add to the run set along with the drain result for post-run
     * cleanup. Reads all pending stamps first and returns immediately when none exist, so the
     * common no-pending path never pays the (expensive) source-project library resolution.
     *
     * <p>Test resolution for drained batches uses the targeted
     * {@link DataStore#getTestSuitesForMethods} query per batch, so the drain path never needs
     * the full test-to-source mapping loaded in memory.
     *
     * @param dataStore the persistence layer for pending stamps, tracked libraries, the publish
     *                  ledger and per-batch test-suite resolution.
     * @param libraryConfig the library impact analysis configuration (provides the metadata reader).
     * @param testSuitesTracked the consumer's currently tracked test suites, used to resolve
     *                          drained forced-selection batches' {@code RUN_ALL} / {@code SUITE_NAMES}
     *                          modes against the consumer's own suite set.
     * @return a {@link DrainOutcome} containing the tests to add and the drain result.
     */
    public DrainOutcome drainPendingMethods(DataStore dataStore, LibraryImpactAnalysisConfig libraryConfig,
                                            Map<String, TestSuiteTracker> testSuitesTracked) {
        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        Set<String> testsFromDrain = new LinkedHashSet<>();

        Map<String, List<PendingLibraryImpactedMethod>> pendingByLibrary =
                groupPendingByLibrary(dataStore.readAllPendingLibraryImpactedMethods());
        Map<String, List<PendingLibraryForcedSelection>> forcedByLibrary =
                groupForcedByLibrary(dataStore.readAllPendingLibraryForcedSelections());
        if (pendingByLibrary.isEmpty() && forcedByLibrary.isEmpty()) {
            return new DrainOutcome(testsFromDrain, drainResult);
        }

        Set<String> coordinates = new LinkedHashSet<>(pendingByLibrary.keySet());
        coordinates.addAll(forcedByLibrary.keySet());

        Map<String, TrackedLibrary> trackedLibraries = dataStore.readTrackedLibraries();
        Map<String, ResolvedSourceProjectLibrary> resolvedLibraries =
                resolveLibrariesOnSourceProject(libraryConfig, coordinates);

        for (Map.Entry<String, List<PendingLibraryImpactedMethod>> entry : pendingByLibrary.entrySet()) {
            TrackedLibrary library = trackedLibraries.get(entry.getKey());
            if (library == null) {
                log.warn("Pending stamps exist for '{}' but the library is not tracked - skipping.", entry.getKey());
                continue;
            }
            drainPendingMethodsForLibrary(dataStore, library, entry.getValue(), resolvedLibraries,
                    testsFromDrain, drainResult);
        }

        if (!forcedByLibrary.isEmpty()) {
            StaticTestSelectionResolver forcedResolver = new StaticTestSelectionResolver(StaticTestSelectionConfig.EMPTY);
            for (Map.Entry<String, List<PendingLibraryForcedSelection>> entry : forcedByLibrary.entrySet()) {
                TrackedLibrary library = trackedLibraries.get(entry.getKey());
                if (library == null) {
                    log.warn("Pending forced selections exist for '{}' but the library is not tracked - skipping.",
                            entry.getKey());
                    continue;
                }
                drainForcedSelectionsForLibrary(dataStore, library, entry.getValue(), resolvedLibraries,
                        testSuitesTracked, forcedResolver, testsFromDrain, drainResult);
            }
        }

        return new DrainOutcome(testsFromDrain, drainResult);
    }

    /**
     * Drain one library's pending stamps against the build the source project resolved: look the
     * resolved artifact up in the publish ledger, apply the hold rules, then drain every pending
     * batch at or below the resolved build's sequence.
     *
     * @param dataStore the persistence layer for the ledger lookup and test-suite resolution
     * @param library the tracked library whose pending batches are evaluated
     * @param pendingBatches the library's pending batches
     * @param resolvedLibraries the libraries resolved on the source project classpath, by coordinate
     * @param testsFromDrain accumulator for the test suites selected by drained batches
     * @param drainResult accumulator for the drained batch keys and applied sequences
     */
    private void drainPendingMethodsForLibrary(DataStore dataStore, TrackedLibrary library,
                                               List<PendingLibraryImpactedMethod> pendingBatches,
                                               Map<String, ResolvedSourceProjectLibrary> resolvedLibraries,
                                               Set<String> testsFromDrain,
                                               LibraryImpactDrainResult drainResult) {
        String groupArtifact = library.getGroupArtifact();
        Long resolvedSeq = resolveBuildSeqOrHold(dataStore, library, resolvedLibraries, pendingBatches.size());
        if (resolvedSeq == null) {
            return;
        }

        boolean anyDrained = false;
        for (PendingLibraryImpactedMethod batch : pendingBatches) {
            if (batch.getPublishSeq() <= resolvedSeq) {
                Set<String> testsForBatch = resolveTestSuitesFromMethodIds(batch.getSourceMethodIds(), dataStore);
                testsFromDrain.addAll(testsForBatch);
                drainResult.addDrainedBatch(groupArtifact, batch.getPublishSeq());
                anyDrained = true;

                log.info("Drained pending batch for library '{}' at seq {} (version '{}') - {} tests selected.",
                        groupArtifact, batch.getPublishSeq(), batch.getStampVersion(), testsForBatch.size());
            } else {
                log.debug("Pending batch for library '{}' at seq {} is above resolved seq {} - held.",
                        groupArtifact, batch.getPublishSeq(), resolvedSeq);
            }
        }

        if (anyDrained) {
            drainResult.setAppliedSeq(groupArtifact, resolvedSeq);
        }
    }

    /**
     * Drain one library's pending forced-selection batches against the build the source project
     * resolved: apply the identical resolved-build lookup and hold rules as the method drain, then
     * for each forced batch at or below the resolved sequence, resolve the forced suites against the
     * consumer's current tracked suites and union them into the run set.
     *
     * @param dataStore the persistence layer for the ledger lookup.
     * @param library the tracked library whose forced batches are evaluated.
     * @param forcedBatches the library's pending forced-selection batches.
     * @param resolvedLibraries the libraries resolved on the source project classpath, by coordinate.
     * @param testSuitesTracked the consumer's tracked suites, used to resolve RUN_ALL / SUITE_NAMES.
     * @param resolver the shared static resolver used for forced resolution.
     * @param testsFromDrain accumulator for the selected test suites.
     * @param drainResult accumulator for the drained forced-batch keys and applied sequences.
     */
    private void drainForcedSelectionsForLibrary(DataStore dataStore, TrackedLibrary library,
                                                 List<PendingLibraryForcedSelection> forcedBatches,
                                                 Map<String, ResolvedSourceProjectLibrary> resolvedLibraries,
                                                 Map<String, TestSuiteTracker> testSuitesTracked,
                                                 StaticTestSelectionResolver resolver,
                                                 Set<String> testsFromDrain,
                                                 LibraryImpactDrainResult drainResult) {
        String groupArtifact = library.getGroupArtifact();
        Long resolvedSeq = resolveBuildSeqOrHold(dataStore, library, resolvedLibraries, forcedBatches.size());
        if (resolvedSeq == null) {
            return;
        }

        boolean anyDrained = false;
        for (PendingLibraryForcedSelection batch : forcedBatches) {
            if (batch.getPublishSeq() <= resolvedSeq) {
                List<Pattern> patterns = new ArrayList<>();
                for (String p : batch.getSuiteNamePatterns()) {
                    patterns.add(Pattern.compile(p));
                }
                Set<String> forcedSuites = resolver.resolveForcedSelection(batch.getMode(), patterns, testSuitesTracked);
                testsFromDrain.addAll(forcedSuites);
                drainResult.addDrainedForcedBatch(groupArtifact, batch.getPublishSeq());
                anyDrained = true;

                log.info("Drained forced selection for library '{}' at seq {} (rule '{}', mode {}) - {} tests selected.",
                        groupArtifact, batch.getPublishSeq(), batch.getRuleName(), batch.getMode(), forcedSuites.size());
            } else {
                log.debug("Forced selection for library '{}' at seq {} is above resolved seq {} - held.",
                        groupArtifact, batch.getPublishSeq(), resolvedSeq);
            }
        }

        if (anyDrained) {
            drainResult.setAppliedSeq(groupArtifact, resolvedSeq);
        }
    }

    /**
     * Resolve the publish-ledger sequence the source project's resolved build corresponds to, or
     * signal that the caller must hold every pending batch for this library. Applies the three
     * hold rules shared by both the method-stamp drain and the forced-selection drain: the
     * library must resolve on the source project classpath, the resolved artifact must match a
     * known publish ledger row, and the resolved build must not be older than the library's
     * {@code lastAppliedSeq}. Logs the same INFO/WARN lines the drain has always emitted so
     * operators see identical diagnostics regardless of which batch kind triggered the hold.
     *
     * @param dataStore the persistence layer for the ledger lookup.
     * @param library the tracked library being resolved.
     * @param resolvedLibraries the libraries resolved on the source project classpath, by coordinate.
     * @param pendingCount the number of pending batches (of whichever kind the caller is draining)
     *                     to report in the hold warning/info messages.
     * @return the resolved build's publish sequence, or {@code null} when any hold rule fires.
     */
    private Long resolveBuildSeqOrHold(DataStore dataStore, TrackedLibrary library,
                                       Map<String, ResolvedSourceProjectLibrary> resolvedLibraries,
                                       int pendingCount) {
        String groupArtifact = library.getGroupArtifact();
        ResolvedSourceProjectLibrary resolved = resolvedLibraries.get(groupArtifact);
        if (resolved == null) {
            log.info("Could not resolve library '{}' on the source project classpath - holding {} pending batches.",
                    groupArtifact, pendingCount);
            return null;
        }

        String resolvedVersion = resolved.getResolvedVersion();
        String resolvedJarHash = computeResolvedJarHash(resolved);

        // Identify the resolved build in the publish ledger: jar hash matches the exact build for
        // snapshots and releases alike; the version fallback only identifies a build for release
        // versions (every snapshot build shares the same version string).
        LibraryPublish resolvedPublish = dataStore.lookupLibraryPublish(groupArtifact, resolvedJarHash,
                isSnapshotVersion(resolvedVersion) ? null : resolvedVersion);
        if (resolvedPublish == null) {
            log.warn("Library '{}' resolved (version='{}', jarHash='{}') matches no publish ledger row - "
                    + "holding {} pending batches until a known build is resolved.",
                    groupArtifact, resolvedVersion, resolvedJarHash != null ? resolvedJarHash : "N/A",
                    pendingCount);
            return null;
        }

        long resolvedSeq = resolvedPublish.getPublishSeq();
        Long lastAppliedSeq = library.getLastAppliedSeq();
        if (lastAppliedSeq != null && resolvedSeq < lastAppliedSeq) {
            log.warn("Library '{}' resolved an OLDER build (seq {}) than previously tested (seq {}) - "
                    + "holding pending batches; check the dependency resolution.",
                    groupArtifact, resolvedSeq, lastAppliedSeq);
            return null;
        }

        log.info("Library '{}' resolved to ledger seq {} (version='{}').",
                groupArtifact, resolvedSeq, resolvedPublish.getPublishedVersion());

        return resolvedSeq;
    }

    /**
     * Group pending forced-selection batches by their owning library coordinate, preserving order.
     *
     * @param forced all pending forced-selection batches.
     * @return map of {@code groupArtifact} to that library's forced batches.
     */
    private Map<String, List<PendingLibraryForcedSelection>> groupForcedByLibrary(
            List<PendingLibraryForcedSelection> forced) {
        Map<String, List<PendingLibraryForcedSelection>> byLibrary = new LinkedHashMap<>();
        for (PendingLibraryForcedSelection batch : forced) {
            byLibrary.computeIfAbsent(batch.getGroupArtifact(), k -> new ArrayList<>()).add(batch);
        }
        return byLibrary;
    }

    /**
     * Group pending batches by their owning library coordinate, preserving read order.
     *
     * @param pending all pending batches from the data store
     * @return map of {@code groupArtifact} to that library's batches; never null
     */
    private Map<String, List<PendingLibraryImpactedMethod>> groupPendingByLibrary(
            List<PendingLibraryImpactedMethod> pending) {
        Map<String, List<PendingLibraryImpactedMethod>> byLibrary = new LinkedHashMap<>();
        for (PendingLibraryImpactedMethod batch : pending) {
            byLibrary.computeIfAbsent(batch.getGroupArtifact(), k -> new ArrayList<>()).add(batch);
        }
        return byLibrary;
    }

    /**
     * Resolve the given library coordinates on the source project's classpath in a single call to
     * {@link LibraryMetadataReader#resolveLibrariesInSourceProject}. Resolving library versions is
     * expensive (e.g. loading a Maven POM or Gradle model), so only the libraries that actually
     * have pending stamps are resolved, once.
     *
     * @param libraryConfig the library impact analysis configuration.
     * @param coordinates the coordinates with pending stamps.
     * @return a map from {@code groupArtifact} to the resolved library; coordinates that could
     *         not be resolved are absent.
     */
    private Map<String, ResolvedSourceProjectLibrary> resolveLibrariesOnSourceProject(
            LibraryImpactAnalysisConfig libraryConfig, Set<String> coordinates) {

        List<ResolvedSourceProjectLibrary> resolvedList = libraryConfig.getMetadataReader()
                .resolveLibrariesInSourceProject(libraryConfig.getSourceProjectDir(), new ArrayList<>(coordinates));

        Map<String, ResolvedSourceProjectLibrary> resolvedMap = new LinkedHashMap<>();
        for (ResolvedSourceProjectLibrary resolved : resolvedList) {
            resolvedMap.put(resolved.getGroupArtifact(), resolved);
        }

        return resolvedMap;
    }

    /**
     * Compute a SHA-256 content hash of the resolved JAR file for the ledger lookup.
     * Returns {@code null} if the JAR path is not available.
     *
     * @param resolved the resolved library on the source project classpath.
     * @return the resolved jar's content hash, or null when the jar path is unknown.
     */
    private String computeResolvedJarHash(ResolvedSourceProjectLibrary resolved) {
        if (resolved.getJarFilePath() == null) {
            return null;
        }
        return LibraryJarHasher.computeSha256Hash(new File(resolved.getJarFilePath()));
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
     *
     * @param version the version string to check.
     * @return true when the version ends with {@code -SNAPSHOT} (case-insensitive).
     */
    private boolean isSnapshotVersion(String version) {
        return version != null && version.toUpperCase().endsWith("-SNAPSHOT");
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
