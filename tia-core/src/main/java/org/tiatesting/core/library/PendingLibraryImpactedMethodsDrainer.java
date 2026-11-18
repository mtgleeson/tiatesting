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
 *
 * <p><b>Source lines.</b> Each ledger row names the source line its build was published from.
 * A library that follows no line yet starts following the line of the build it resolves, and
 * pending batches from other lines are discarded unrun. A library that follows a line and
 * resolves a build from another has switched line: every tracked suite is selected, all of its
 * pending batches are discarded and the new line is followed. See the source-lines section of the
 * same chapter.
 *
 * <p>The drain also reports the commit each identified build was published at. The seal uses it
 * as the library's mapping baseline: the re-covered suites' line numbers describe the library
 * source the held jar was built from, which is that commit and not the consumer's sealed commit.
 * Everything here is answered by the build model, the jar on disk and the publish ledger - the
 * drain never reads the VCS.
 */
public class PendingLibraryImpactedMethodsDrainer {

    private static final Logger log = LoggerFactory.getLogger(PendingLibraryImpactedMethodsDrainer.class);

    /**
     * Drain the pending stamps contained in the library builds the source project resolves, and
     * return the covering tests to add to the run set along with the drain result for post-run
     * cleanup. Reads all pending stamps first and returns immediately when none exist, so the
     * common no-pending path never pays the (expensive) source-project library resolution - unless
     * {@code resolveAllTracked} asks for every tracked library's held build to be identified.
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
     * @param resolveAllTracked true when the run is known to select all tests, so every tracked
     *                          library's held build is identified even with nothing pending and
     *                          its mapping baseline can follow; false keeps the no-pending fast
     *                          path, which resolves nothing.
     * @return a {@link DrainOutcome} containing the tests to add and the drain result.
     */
    public DrainOutcome drainPendingMethods(DataStore dataStore, LibraryImpactAnalysisConfig libraryConfig,
                                            Map<String, TestSuiteTracker> testSuitesTracked,
                                            boolean resolveAllTracked) {
        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        Set<String> testsFromDrain = new LinkedHashSet<>();

        Map<String, List<PendingLibraryImpactedMethod>> pendingByLibrary =
                groupPendingByLibrary(dataStore.readAllPendingLibraryImpactedMethods());
        Map<String, List<PendingLibraryForcedSelection>> forcedByLibrary =
                groupForcedByLibrary(dataStore.readAllPendingLibraryForcedSelections());
        if (pendingByLibrary.isEmpty() && forcedByLibrary.isEmpty() && !resolveAllTracked) {
            return new DrainOutcome(testsFromDrain, drainResult);
        }

        Map<String, TrackedLibrary> trackedLibraries = dataStore.readTrackedLibraries();
        Set<String> coordinates = new LinkedHashSet<>(pendingByLibrary.keySet());
        coordinates.addAll(forcedByLibrary.keySet());
        if (resolveAllTracked) {
            coordinates.addAll(trackedLibraries.keySet());
        }
        if (coordinates.isEmpty()) {
            return new DrainOutcome(testsFromDrain, drainResult);
        }

        Map<String, ResolvedSourceProjectLibrary> resolvedLibraries =
                resolveLibrariesOnSourceProject(libraryConfig, coordinates);
        Map<String, LibraryPublish> resolvedBuildByLibrary = resolveBuildsOnce(dataStore, coordinates,
                pendingByLibrary, forcedByLibrary, trackedLibraries, resolvedLibraries);
        Set<String> lineSwitched = new HashSet<>();
        for (Map.Entry<String, LibraryPublish> entry : resolvedBuildByLibrary.entrySet()) {
            // A library that follows no line yet starts following the line of the build it holds.
            String resolvedLine = entry.getValue().getSourceLine();
            String followedLine = trackedLibraries.get(entry.getKey()).getFollowedLine();
            if (resolvedLine != null && followedLine == null) {
                drainResult.setLineToFollow(entry.getKey(), resolvedLine);
            }
            // The source the consumer's jar was built from - the commit the mapping's line numbers
            // describe once this run re-covers the library's suites.
            if (entry.getValue().getCommitValue() != null) {
                drainResult.setHeldBuildCommit(entry.getKey(), entry.getValue().getCommitValue());
            }
            if (!SourceLines.matches(followedLine, resolvedLine)) {
                switchLine(entry.getKey(), entry.getValue(), pendingByLibrary, forcedByLibrary,
                        testSuitesTracked, testsFromDrain, drainResult);
                lineSwitched.add(entry.getKey());
            }
        }

        // Filled only for libraries that have a pending batch and resolved a build, so a forced
        // run that merely identifies held builds reads no ledger here.
        Map<String, Map<Long, String>> lineBySeqByLibrary = new HashMap<>();

        for (Map.Entry<String, List<PendingLibraryImpactedMethod>> entry : pendingByLibrary.entrySet()) {
            TrackedLibrary library = trackedLibraries.get(entry.getKey());
            if (library == null) {
                log.warn("Pending stamps exist for '{}' but the library is not tracked - skipping.", entry.getKey());
                continue;
            }
            LibraryPublish resolved = resolvedBuildByLibrary.get(entry.getKey());
            if (resolved != null && !lineSwitched.contains(entry.getKey())) {
                drainPendingMethodsForLibrary(dataStore, library, entry.getValue(), resolved,
                        readLineBySeq(dataStore, entry.getKey(), lineBySeqByLibrary), testsFromDrain, drainResult);
            }
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
                LibraryPublish resolved = resolvedBuildByLibrary.get(entry.getKey());
                if (resolved != null && !lineSwitched.contains(entry.getKey())) {
                    drainForcedSelectionsForLibrary(library, entry.getValue(), resolved,
                            readLineBySeq(dataStore, entry.getKey(), lineBySeqByLibrary), testSuitesTracked,
                            forcedResolver, testsFromDrain, drainResult);
                }
            }
        }

        return new DrainOutcome(testsFromDrain, drainResult);
    }

    /**
     * Apply a line switch: the library holds a build from another source line than the one it
     * follows. The mapping does not record which methods belong to the library, and a diff across
     * lines cannot be matched to the mapping's line numbers, so no narrower selection exists -
     * every tracked suite is selected. All of the library's pending batches are reported as
     * discarded, whichever line they are from: the old line's no longer apply to the held jar, and
     * the new line's are covered by running everything. The seal then follows the new line from
     * the resolved sequence. See the source-lines section of the library publish-time stamping
     * chapter in {@code WIKI.md}.
     *
     * @param groupArtifact the library that switched line.
     * @param resolved the ledger row of the build the library resolved to, on the new line.
     * @param pendingByLibrary pending method batches by library coordinate.
     * @param forcedByLibrary pending forced-selection batches by library coordinate.
     * @param testSuitesTracked the consumer's currently tracked test suites, all of which are selected.
     * @param testsFromDrain accumulator for the selected test suites.
     * @param drainResult accumulator for the discarded batch keys, the line to follow and the
     *                    applied sequence.
     */
    private void switchLine(String groupArtifact, LibraryPublish resolved,
                            Map<String, List<PendingLibraryImpactedMethod>> pendingByLibrary,
                            Map<String, List<PendingLibraryForcedSelection>> forcedByLibrary,
                            Map<String, TestSuiteTracker> testSuitesTracked,
                            Set<String> testsFromDrain, LibraryImpactDrainResult drainResult) {
        testsFromDrain.addAll(testSuitesTracked.keySet());
        for (PendingLibraryImpactedMethod batch : pendingByLibrary
                .getOrDefault(groupArtifact, Collections.<PendingLibraryImpactedMethod>emptyList())) {
            drainResult.addDiscardedBatch(groupArtifact, batch.getPublishSeq());
        }
        for (PendingLibraryForcedSelection batch : forcedByLibrary
                .getOrDefault(groupArtifact, Collections.<PendingLibraryForcedSelection>emptyList())) {
            drainResult.addDiscardedForcedBatch(groupArtifact, batch.getPublishSeq());
        }
        drainResult.setLineToFollow(groupArtifact, resolved.getSourceLine());
        drainResult.setAppliedSeq(groupArtifact, resolved.getPublishSeq());
        drainResult.addLineSwitch(groupArtifact);
    }

    /**
     * Resolve each library's build (or hold decision) exactly once, over the union of
     * its pending method-batch and forced-selection-batch coordinates. Without this, a library
     * with both a pending method batch and a pending forced batch would have
     * {@link #resolveBuildOrHold} called twice - once per batch kind - each recomputing the
     * resolved jar's SHA-256 hash and re-querying the publish ledger, and logging the "resolved to
     * ledger seq" line twice for what is really one resolution.
     *
     * @param dataStore the persistence layer for the ledger lookup.
     * @param coordinates the union of coordinates with a pending method batch and/or a pending
     *                    forced batch, plus every tracked coordinate when all are being resolved.
     * @param pendingByLibrary pending method batches by library coordinate, used to size the
     *                         reported pending count.
     * @param forcedByLibrary pending forced batches by library coordinate, used to size the
     *                        reported pending count.
     * @param trackedLibraries the tracked libraries by coordinate; a coordinate absent here is
     *                         left unresolved (the per-batch-kind loops report the untracked
     *                         warning for their own kind).
     * @param resolvedLibraries the libraries resolved on the source project classpath, by coordinate.
     * @return the resolved build's ledger row per library coordinate; a coordinate is absent when
     *         it is not tracked or any hold rule fired, which the drain helpers treat identically
     *         to a {@code null} map lookup - the batch is held.
     */
    private Map<String, LibraryPublish> resolveBuildsOnce(DataStore dataStore, Set<String> coordinates,
                                                    Map<String, List<PendingLibraryImpactedMethod>> pendingByLibrary,
                                                    Map<String, List<PendingLibraryForcedSelection>> forcedByLibrary,
                                                    Map<String, TrackedLibrary> trackedLibraries,
                                                    Map<String, ResolvedSourceProjectLibrary> resolvedLibraries) {
        Map<String, LibraryPublish> resolvedBuildByLibrary = new LinkedHashMap<>();
        for (String coordinate : coordinates) {
            TrackedLibrary library = trackedLibraries.get(coordinate);
            if (library == null) {
                // Not tracked - the per-batch-kind loops in drainPendingMethods emit the specific
                // "not tracked" warning for whichever batch kind(s) exist; nothing to resolve here.
                continue;
            }
            int pendingCount = pendingByLibrary.getOrDefault(coordinate, Collections.<PendingLibraryImpactedMethod>emptyList()).size()
                    + forcedByLibrary.getOrDefault(coordinate, Collections.<PendingLibraryForcedSelection>emptyList()).size();
            LibraryPublish resolvedBuild = resolveBuildOrHold(dataStore, library, resolvedLibraries, pendingCount);
            if (resolvedBuild != null) {
                resolvedBuildByLibrary.put(coordinate, resolvedBuild);
            }
        }
        return resolvedBuildByLibrary;
    }

    /**
     * Drain one library's pending stamps against the build the source project resolved. A batch
     * published from another source line than the resolved build's is discarded - the resolved
     * jar does not contain it. Of the rest, every batch at or below the resolved build's sequence
     * drains.
     *
     * @param dataStore the persistence layer for test-suite resolution
     * @param library the tracked library whose pending batches are evaluated
     * @param pendingBatches the library's pending batches
     * @param resolved the ledger row of the build the library resolved to, from
     *                 {@link #resolveBuildsOnce}
     * @param lineBySeq the source line of each of the library's publishes, by publish sequence
     * @param testsFromDrain accumulator for the test suites selected by drained batches
     * @param drainResult accumulator for the drained batch keys and applied sequences
     */
    private void drainPendingMethodsForLibrary(DataStore dataStore, TrackedLibrary library,
                                               List<PendingLibraryImpactedMethod> pendingBatches,
                                               LibraryPublish resolved, Map<Long, String> lineBySeq,
                                               Set<String> testsFromDrain,
                                               LibraryImpactDrainResult drainResult) {
        String groupArtifact = library.getGroupArtifact();
        long resolvedSeq = resolved.getPublishSeq();

        boolean anyDrained = false;
        for (PendingLibraryImpactedMethod batch : pendingBatches) {
            if (isFromAnotherLine(groupArtifact, batch.getPublishSeq(), lineBySeq, resolved)) {
                drainResult.addDiscardedBatch(groupArtifact, batch.getPublishSeq());
                continue;
            }
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
     * resolved, by the same rules as the method drain: a batch from another source line is
     * discarded, and each remaining batch at or below the resolved sequence has its forced suites
     * resolved against the consumer's current tracked suites and unioned into the run set.
     *
     * @param library the tracked library whose forced batches are evaluated.
     * @param forcedBatches the library's pending forced-selection batches.
     * @param resolved the ledger row of the build the library resolved to, from
     *                 {@link #resolveBuildsOnce}.
     * @param lineBySeq the source line of each of the library's publishes, by publish sequence.
     * @param testSuitesTracked the consumer's tracked suites, used to resolve RUN_ALL / SUITE_NAMES.
     * @param resolver the shared static resolver used for forced resolution.
     * @param testsFromDrain accumulator for the selected test suites.
     * @param drainResult accumulator for the drained forced-batch keys and applied sequences.
     */
    private void drainForcedSelectionsForLibrary(TrackedLibrary library,
                                                 List<PendingLibraryForcedSelection> forcedBatches,
                                                 LibraryPublish resolved, Map<Long, String> lineBySeq,
                                                 Map<String, TestSuiteTracker> testSuitesTracked,
                                                 StaticTestSelectionResolver resolver,
                                                 Set<String> testsFromDrain,
                                                 LibraryImpactDrainResult drainResult) {
        String groupArtifact = library.getGroupArtifact();
        long resolvedSeq = resolved.getPublishSeq();

        boolean anyDrained = false;
        for (PendingLibraryForcedSelection batch : forcedBatches) {
            if (isFromAnotherLine(groupArtifact, batch.getPublishSeq(), lineBySeq, resolved)) {
                drainResult.addDiscardedForcedBatch(groupArtifact, batch.getPublishSeq());
                continue;
            }
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
     * Resolve the publish-ledger row the source project's resolved build corresponds to, or
     * signal that the caller must hold every pending batch for this library. Applies the three
     * hold rules shared by both the method-stamp drain and the forced-selection drain: the
     * library must resolve on the source project classpath, the resolved artifact must match a
     * known publish ledger row, and the resolved build must not be older than the library's
     * {@code lastAppliedSeq}. When several rows match, one on the followed line is preferred. A
     * row on another line than the followed one is a line switch: it is returned without the
     * older-build check, which only orders builds on one line. Logs the same INFO/WARN lines the drain has always emitted so
     * operators see identical diagnostics regardless of which batch kind triggered the hold.
     *
     * @param dataStore the persistence layer for the ledger lookup.
     * @param library the tracked library being resolved.
     * @param resolvedLibraries the libraries resolved on the source project classpath, by coordinate.
     * @param pendingCount the number of pending batches (of whichever kind the caller is draining)
     *                     to report in the hold warning/info messages.
     * @return the resolved build's publish ledger row, or {@code null} when any hold rule fires.
     */
    private LibraryPublish resolveBuildOrHold(DataStore dataStore, TrackedLibrary library,
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
                isSnapshotVersion(resolvedVersion) ? null : resolvedVersion, library.getFollowedLine());
        if (resolvedPublish == null) {
            log.info("Library '{}' resolved (version='{}', jarHash='{}') matches no publish ledger row - "
                    + "holding {} pending batches until a known build is resolved.",
                    groupArtifact, resolvedVersion, resolvedJarHash != null ? resolvedJarHash : "N/A",
                    pendingCount);
            return null;
        }

        if (!SourceLines.matches(library.getFollowedLine(), resolvedPublish.getSourceLine())) {
            // Returned before the downgrade guard: sequences on different lines do not order
            // each other, so a lower sequence on the new line is not an older build.
            log.warn("Library '{}' switched source line: this schema follows '{}' and the resolved build "
                    + "(seq {}, version '{}') is from '{}'. Selecting all tests and following the new line.",
                    groupArtifact, library.getFollowedLine(), resolvedPublish.getPublishSeq(),
                    resolvedPublish.getPublishedVersion(), resolvedPublish.getSourceLine());
            return resolvedPublish;
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

        return resolvedPublish;
    }

    /**
     * Read the source line of each of a library's publishes, once per drain.
     *
     * @param dataStore the persistence layer for the ledger read.
     * @param groupArtifact the library whose ledger to read.
     * @param cache the per-drain cache, filled on first use for a library.
     * @return the source line by publish sequence; a publish with no recorded line maps to null.
     */
    private Map<Long, String> readLineBySeq(DataStore dataStore, String groupArtifact,
                                            Map<String, Map<Long, String>> cache) {
        Map<Long, String> lineBySeq = cache.get(groupArtifact);
        if (lineBySeq == null) {
            lineBySeq = new HashMap<>();
            for (LibraryPublish row : dataStore.readLibraryPublishes(groupArtifact)) {
                lineBySeq.put(row.getPublishSeq(), row.getSourceLine());
            }
            cache.put(groupArtifact, lineBySeq);
        }
        return lineBySeq;
    }

    /**
     * Decide whether a pending batch was published from a different source line than the build
     * the consumer resolved. Every line that publishes the library stamps every consuming schema,
     * so such a batch is routine wherever a library has more than one line; the resolved jar does
     * not contain its changes, so it is discarded rather than run. A batch or build with no recorded line matches any line.
     *
     * @param groupArtifact the library, for logging.
     * @param batchSeq the pending batch's publish sequence.
     * @param lineBySeq the source line of each of the library's publishes, by publish sequence.
     * @param resolved the ledger row of the build the consumer resolved.
     * @return true when the batch is from another line and must be discarded.
     */
    private boolean isFromAnotherLine(String groupArtifact, long batchSeq, Map<Long, String> lineBySeq,
                                      LibraryPublish resolved) {
        String batchLine = lineBySeq.get(batchSeq);
        if (SourceLines.matches(batchLine, resolved.getSourceLine())) {
            return false;
        }
        log.info("Discarding pending batch for library '{}' at seq {} - published from source line '{}', "
                + "this consumer resolved a build from line '{}'.", groupArtifact, batchSeq, batchLine,
                resolved.getSourceLine());
        return true;
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
