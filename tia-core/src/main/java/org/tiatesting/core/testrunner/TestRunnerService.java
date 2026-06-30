package org.tiatesting.core.testrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.report.ReportUtils;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.sourcefile.FileExtensions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TestRunnerService {

    private static final Logger log = LoggerFactory.getLogger(TestRunnerService.class);

    private final DataStore dataStore;

    public TestRunnerService(final DataStore dataStore){
        this.dataStore = dataStore;
    }

    /**
     * Persist the data accumulated by a Tia test run.
     *
     * <p><b>Write ordering and crash safety.</b> The DB calls are sequenced so that
     * {@link #updateTiaCoreData} (which writes the {@code commitValue} stamp) is the final
     * mapping-related write. The invariant is: <em>if commit X is the stored value, every
     * mapping write for X has completed.</em> A crash before the seal leaves the stored
     * commit at the prior value; the next run diffs against that older commit and re-does
     * the impacted work. Slightly wasteful on recovery, never under-selects. See the
     * "Persist flow and crash safety" chapter in {@code WIKI.md} for the failure-mode
     * taxonomy and the per-call atomicity guarantees that the H2 backend provides.
     *
     * @param updateDBMapping          should the test-suite to source-code mapping be updated
     * @param updateDBStats            should the run stats be updated
     * @param updateDBTestRunHistory   should this run write a row to {@code tia_test_run_history}
     * @param commitValue              the VCS commit / changelist the run was against
     * @param branch                   the VCS branch the run targeted (recorded with the history entry)
     * @param runStartTimestampMs      UTC epoch millis when the test run started (used as both the
     *                                 history entry timestamp and the duration baseline)
     * @param testRunResult            the collected results of the test run
     */
    public void persistTestRunData(final boolean updateDBMapping, final boolean updateDBStats,
                                   final boolean updateDBTestRunHistory,
                                   final String commitValue, final String branch,
                                   final long runStartTimestampMs,
                                   final TestRunResult testRunResult){
        // Capture the run duration up front, before any DB read/write below. This is the
        // test-execution wall clock (test-plan start to here); it deliberately excludes Tia's own
        // mapping/seal persist work that follows, which on a seed run is seconds of bulk inserts.
        // The savings baseline (allTestsRunTime) is likewise pure test-execution time, so the two
        // must be measured on the same clock for savings = baseline - duration to be meaningful.
        final long durationMs = Math.max(0L, System.currentTimeMillis() - runStartTimestampMs);

        if (updateDBMapping){
            log.info("Persisting core data with commit value: " + commitValue);
        }
        if (updateDBStats){
            log.info("Persisting updated stats from the test run.");
        }

        TiaData tiaData = dataStore.getTiaCore();

        // 1. Mapping writes first. A crash anywhere in this block leaves the stored commit
        //    value at its prior setting, so the next run re-diffs against that older commit
        //    and re-does the work.
        updateTestSuiteMapping(tiaData, testRunResult.getTestSuiteTrackers(), testRunResult.getRunnerTestSuites(),
                testRunResult.getSelectedTests(), updateDBMapping, updateDBStats);

        if (updateDBMapping){
            updateMethodsTracked(tiaData, testRunResult.getMethodTrackersFromTestRun());
            updateTestSuitesFailed(tiaData, testRunResult.getSelectedTests(), testRunResult.getTestSuitesFailed());
            applyLibraryImpactDrainResult(testRunResult.getLibraryImpactDrainResult());
        }

        // 2. Seal: writing the commit value last is what makes commit X "official". Until this
        //    line executes, the stored commit value is unchanged and the next run will treat
        //    everything since the prior commit as unmapped.
        // A run where Tia ignored zero suites is an all-tests run (seed run, or every suite
        // selected). getIgnoredTestSuiteCount() already excludes developer-disabled suites
        // (Stage 1), so this stays a plain == 0 check.
        boolean allTestsRun = testRunResult.getIgnoredTestSuiteCount() == 0;
        updateTiaCoreData(tiaData, commitValue, branch, updateDBMapping, updateDBStats,
                testRunResult.getTestStats(), allTestsRun);

        // 3. History row is audit-only and has no select-tests consistency implications;
        //    written after the seal so history rows only exist for fully-sealed runs.
        if (updateDBTestRunHistory) {
            // Baseline for this run's savings: the all-tests average as it stands now. Partial runs
            // don't move it, so it is the established full-suite time to compare against; for an
            // all-tests run the savings are 0 regardless.
            long allTestsRunTimeMs = tiaData.getTestStats().getAllTestsRunTime();
            persistTestRunHistory(updateDBMapping, commitValue, branch, runStartTimestampMs,
                    durationMs, testRunResult, allTestsRunTimeMs);
        }
    }

    /**
     * Build and persist a {@link TestRunHistoryEntry} for this run.
     *
     * <p>Counts: {@code ran} is the per-attempt count of suites that finished in this listener
     * attempt only, taken from {@link TestRunResult#getSuitesRanThisAttempt()}. This avoids
     * inflating retry-row counts with prior-attempt entries that the shared
     * {@link TestRunResult#getTestSuiteTrackers()} map deliberately carries forward for the
     * mapping path. {@code ignored} is the number of suites Tia chose to ignore, taken directly
     * from the selector via {@link TestRunResult#getIgnoredTestSuiteCount()} (which sources it
     * from the {@code tiaIgnoredTestSuiteCount} system property). Engine-level skips that Tia
     * did not cause (user {@code @Disabled}, surefire {@code groups} filters, etc.) are
     * deliberately excluded so the history column reflects Tia's selection decision only.
     * {@code failed} is the failed-suite set size. {@code durationMs} is the test-execution wall
     * clock captured by the caller before its DB persist work, so it excludes Tia's own
     * mapping/seal overhead and stays comparable to the savings baseline.
     *
     * @param updateDBMapping       was this run also updating the Tia mapping DB (stamped on the row)
     * @param commitValue           VCS commit / changelist the run was against
     * @param branch                VCS branch the run targeted
     * @param runStartTimestampMs   when the run started (UTC epoch ms); recorded as the row timestamp
     * @param durationMs            the run's test-execution duration (ms), captured before the
     *                              persist work so it excludes Tia's mapping/seal overhead
     * @param testRunResult         the collected results of the test run
     * @param allTestsRunTimeMs     the all-tests-run baseline (ms) to freeze this run's savings
     *                              against; partial runs don't move it, so it is the established
     *                              full-suite time
     */
    private void persistTestRunHistory(final boolean updateDBMapping, final String commitValue,
                                       final String branch, final long runStartTimestampMs,
                                       final long durationMs, final TestRunResult testRunResult,
                                       final long allTestsRunTimeMs) {
        int ran = Math.max(0, testRunResult.getSuitesRanThisAttempt());
        int ignored = Math.max(0, testRunResult.getIgnoredTestSuiteCount());
        int failed = testRunResult.getTestSuitesFailed() != null
                ? testRunResult.getTestSuitesFailed().size() : 0;

        // Freeze the savings for this run: 0 for an all-tests run (ignored == 0) or when no
        // baseline exists, else the baseline minus this run's duration.
        long timeSavingsMs = ReportUtils.runSavingsMs(allTestsRunTimeMs, durationMs, ignored == 0);
        int savingsPercent = (int) ReportUtils.percentOfTotal(timeSavingsMs, allTestsRunTimeMs);

        TestRunHistoryEntry entry = TestRunHistoryEntry.create(
                branch, commitValue, runStartTimestampMs, ran, ignored, failed, durationMs,
                updateDBMapping, timeSavingsMs, savingsPercent);
        dataStore.persistTestRunHistoryEntry(entry);
        log.debug("Persisted test run history entry {} (ran={}, ignored={}, failed={}, durationMs={}, savingsMs={}, savings%={})",
                entry.getId(), ran, ignored, failed, durationMs, timeSavingsMs, savingsPercent);
    }

    /**
     *
     * @param tiaData the Tia DB
     * @param commitValue the new commit value the test run was executed against
     * @param branch the VCS branch the run targeted, stamped alongside the commit value
     * @param updateDBMapping should the test to source code mapping be updated in the DB for the test run
     * @param updateDBStats should the test stats be updated for the test run
     * @param testStats the stats for the test run
     * @param allTestsRun {@code true} when Tia ignored zero suites this run, routing the duration
     *                    into the all-tests-run average instead of the selected-run average
     */
    private void updateTiaCoreData(final TiaData tiaData, final String commitValue, final String branch,
                                   final boolean updateDBMapping, final boolean updateDBStats, final TestStats testStats,
                                   final boolean allTestsRun){
        if (updateDBMapping) {
            tiaData.setCommitValue(commitValue);
            tiaData.setBranch(branch);
            tiaData.setLastUpdated(Instant.now());
        }

        if (updateDBStats){
            tiaData.incrementStats(testStats, allTestsRun);
        }

        dataStore.persistCoreData(tiaData);
    }

    /**
     * Update the test suite mapping to source code in the DB.
     * Remove any deleted test suites from the DB.
     * <p>
     * Also update the stats for the test suites if configured for the test run.
     *
     * <p><b>Persistence routing.</b>
     * <ul>
     *   <li>Both flags false (history-only / SE-developer runs): early-return; no read,
     *       no write. The test suite mapping table is not touched at all.</li>
     *   <li>{@code updateDBMapping=true}: the merged map (with mapping + optional stats)
     *       is persisted via {@link DataStore#persistTestSuites(Map)} - includes the
     *       suite-to-source-class-to-method edges.</li>
     *   <li>{@code updateDBStats=true} and {@code updateDBMapping=false}: only the stats
     *       columns of each suite row are persisted via
     *       {@link DataStore#persistTestSuiteStatsOnly(Map)} - the mapping edges remain
     *       untouched.</li>
     * </ul>
     *
     * @param tiaData the Tia DB
     * @param testSuiteTrackers the mapping of test suites to source code impacted from the current test run
     * @param runnerTestSuites the lists of test suites known to the runner for the current workspace
     * @param selectedTests the suites Tia selected to run, used to maintain the developer-disabled flag
     * @param updateDBMapping should the test suite to source code mapping be updated for the test run
     * @param updateDBStats should the test stats be updated for the test run
     */
    private void updateTestSuiteMapping(final TiaData tiaData, final Map<String, TestSuiteTracker> testSuiteTrackers,
                                        final Set<String> runnerTestSuites, final Set<String> selectedTests,
                                        final boolean updateDBMapping, final boolean updateDBStats){

        if (!updateDBMapping && !updateDBStats) {
            // History-only / SE-developer runs do not touch the test-suite mapping table.
            // Skipping the read+persist here avoids a full delete-then-reinsert of every
            // tia_source_class / tia_source_class_method row on every non-update run.
            return;
        }

        Map<String, TestSuiteTracker> testSuiteTrackersOnDisk = dataStore.getTestSuitesTracked();
        tiaData.setTestSuitesTracked(testSuiteTrackersOnDisk);

        if (updateDBMapping){
            Map<String, TestSuiteTracker> mergedTestSuiteTrackers = mergeTestMappingMaps(testSuiteTrackersOnDisk, testSuiteTrackers);
            tiaData.setTestSuitesTracked(mergedTestSuiteTrackers);

            //mergedTestMappings.forEach( (testClass, methodsCalled) ->
            //        log.debug(methodsCalled.stream().map(String::valueOf).collect(Collectors.joining("\n", testClass+":\n", ""))));

            // remove any test suites that have been deleted
            removeDeletedTestSuites(tiaData.getTestSuitesTracked(), runnerTestSuites);

            // Maintain the developer-disabled flag before persisting the mapping rows. Only done
            // on mapping runs - the flag is mapping metadata written by persistTestSuites.
            updateDeveloperDisabledFlags(tiaData.getTestSuitesTracked(), selectedTests, runnerTestSuites,
                    testSuiteTrackers.keySet());
        }

        if (updateDBStats){
            Map<String, TestSuiteTracker> mergedTestSuiteTrackers = mergeTestMappingStats(tiaData.getTestSuitesTracked(), testSuiteTrackers);
            tiaData.setTestSuitesTracked(mergedTestSuiteTrackers);
        }

        if (updateDBMapping) {
            dataStore.persistTestSuites(tiaData.getTestSuitesTracked());
        } else {
            // stats-only branch: leave the suite-to-source-class / method-edges untouched
            dataStore.persistTestSuiteStatsOnly(tiaData.getTestSuitesTracked());
        }
    }

    /**
     * Maintain the per-suite {@code developerDisabled} flag from the current run's signals.
     *
     * <p>The flag distinguishes suites the developer disabled in source (e.g. {@code @Disabled} /
     * {@code @Ignore}) from suites Tia itself ignored. It can only be determined unambiguously
     * when Tia did not also disable the suite, so for each tracked suite:
     * <ul>
     *   <li>executed this run - cleared (executing proves it is not disabled; covers re-enable).</li>
     *   <li>Tia selected it and the runner discovered it but it did not execute - set (Tia did not
     *       disable it, so the skip is the developer's doing).</li>
     *   <li>otherwise (Tia ignored it, or it wasn't discovered) - left unchanged, carrying the
     *       stored value forward.</li>
     * </ul>
     *
     * @param trackedSuites the merged tracked suites keyed by suite name (mutated in place)
     * @param selectedTests the suites Tia selected to run
     * @param runnerTestSuites the suites the runner discovered (executed + skipped + filtered)
     * @param executedSuiteNames the suites that actually executed this run
     */
    static void updateDeveloperDisabledFlags(final Map<String, TestSuiteTracker> trackedSuites,
                                             final Set<String> selectedTests,
                                             final Set<String> runnerTestSuites,
                                             final Set<String> executedSuiteNames){
        trackedSuites.forEach((suiteName, tracker) -> {
            if (executedSuiteNames.contains(suiteName)){
                tracker.setDeveloperDisabled(false);
            } else if (selectedTests.contains(suiteName) && runnerTestSuites.contains(suiteName)){
                tracker.setDeveloperDisabled(true);
            }
        });
    }

    /**
     * Note, make sure this method is called after updateTestSuiteMapping. It relies on querying the DB for the list of
     * updated source class method ids.
     *
     * @param tiaData the Tia DB
     * @param methodTrackersFromTestRun a map of all source code methods that were covered by any of the test suites executed in the test run
     */
    private void updateMethodsTracked(final TiaData tiaData, final Map<Integer, MethodImpactTracker> methodTrackersFromTestRun){
        Map<Integer, MethodImpactTracker> methodTrackersOnDisk = dataStore.getMethodsTracked();
        Map<Integer, MethodImpactTracker> updatedMethodTrackers = updateMethodTracker(methodTrackersOnDisk, methodTrackersFromTestRun);
        tiaData.setMethodsTracked(updatedMethodTrackers);
        dataStore.persistSourceMethods(updatedMethodTrackers);
    }

    /**
     *  The list of failed tests is updated on each test run (not rebuilt from scratch). This accounts for
     *  scenarios where the test suite is split across multiple hosts which can be updating the stored TIA DB.
     *  First, remove all the existing test suites that were selected for this run, and then add back any that failed.
     *
     * @param tiaData the Tia DB
     * @param selectedTests the tests selected to run by Tia
     * @param testSuitesFailed the list of test suites that contained a failure or error
     */
    private void updateTestSuitesFailed(final TiaData tiaData, final Set<String> selectedTests, final Set<String> testSuitesFailed){
        tiaData.setTestSuitesFailed(dataStore.getTestSuitesFailed());
        tiaData.getTestSuitesFailed().removeAll(selectedTests);
        tiaData.getTestSuitesFailed().addAll(testSuitesFailed);
        dataStore.persistTestSuitesFailed(tiaData.getTestSuitesFailed());
    }

    /**
     * Apply the library impact drain result after a successful test run. Deletes the drained
     * library changes pending rows from the data store and updates each drained library's
     * {@code last_source_project_version} and {@code last_source_project_jar_hash} to the
     * values observed at drain time.
     *
     * @param drainResult the drain result from test selection, or {@code null} if no drain occurred.
     */
    private void applyLibraryImpactDrainResult(final LibraryImpactDrainResult drainResult) {
        if (drainResult == null || !drainResult.hasDrainedBatches()) {
            return;
        }

        deleteDrainedPendingBatches(drainResult);
        updateTrackedLibraryVersions(drainResult);
    }

    /**
     * Delete each drained {@code (groupArtifact, stampVersion)} batch from the pending table.
     */
    private void deleteDrainedPendingBatches(final LibraryImpactDrainResult drainResult) {
        for (LibraryImpactDrainResult.DrainedBatchKey key : drainResult.getDrainedBatchKeys()) {
            log.info("Deleting drained pending batch: {}", key);
            dataStore.deletePendingLibraryImpactedMethods(key.getGroupArtifact(), key.getStampVersion());
        }
    }

    /**
     * Update each drained library's {@code last_source_project_version} and
     * {@code last_source_project_jar_hash} to the resolved values observed at drain time.
     */
    private void updateTrackedLibraryVersions(final LibraryImpactDrainResult drainResult) {
        Map<String, TrackedLibrary> trackedLibraries = dataStore.readTrackedLibraries();

        for (Map.Entry<String, LibraryImpactDrainResult.ObservedLibraryState> entry
                : drainResult.getObservedLibraryStates().entrySet()) {
            String groupArtifact = entry.getKey();
            LibraryImpactDrainResult.ObservedLibraryState state = entry.getValue();
            TrackedLibrary library = trackedLibraries.get(groupArtifact);

            if (library == null) {
                log.warn("Tracked library '{}' not found during drain cleanup — skipping version update.",
                        groupArtifact);
                continue;
            }

            library.setLastSourceProjectVersion(state.getResolvedVersion());
            library.setLastSourceProjectJarHash(state.getResolvedJarHash());
            dataStore.persistTrackedLibrary(library);
            log.info("Updated tracked library '{}': last_source_project_version='{}', last_source_project_jar_hash='{}'.",
                    groupArtifact, state.getResolvedVersion(),
                    state.getResolvedJarHash() != null ? state.getResolvedJarHash() : "N/A");
        }
    }

    /**
     * Remove all deleted test suites from the test trackers that will be updated in the DB.
     * A test suite is determined to be deleted if it was not in the list of test suites executed by the test runner,
     * but it was previously tracked by Tia and stored in the DB.
     *
     * @param testSuitesInDB the test suites stored in the Tia DB
     * @param runnerTestSuites the test suites from the runner
     */
    private void removeDeletedTestSuites(final Map<String, TestSuiteTracker> testSuitesInDB, final Set<String> runnerTestSuites){
        Set<String> deletedTestSuites = new HashSet<>();
        for (String testSuiteTracked : testSuitesInDB.keySet()){
            if (!runnerTestSuites.contains(testSuiteTracked)){
                deletedTestSuites.add(testSuiteTracked);
            }
        }

        if (!deletedTestSuites.isEmpty()) {
            log.info("Removing the following deleted test suites from the persisted mapping: {}", deletedTestSuites);
            testSuitesInDB.keySet().removeAll(deletedTestSuites);
            dataStore.deleteTestSuites(deletedTestSuites);
        }
    }

    /**
     * Update the method tracker which is stored on disk.
     *
     * @param methodTrackerOnDisk current method tracker persisted on disk
     * @param methodTrackerFromTestRun methods called from the current test run
     */
    private Map<Integer, MethodImpactTracker> updateMethodTracker(final Map<Integer, MethodImpactTracker> methodTrackerOnDisk,
                                                                  final Map<Integer, MethodImpactTracker> methodTrackerFromTestRun){

        // Set containing the combined method ids using the updated test mapping after the test run
        Set<Integer> methodsImpactedAfterTestRun = dataStore.getUniqueMethodIdsTracked();

        // We have the updated list of method ids. But the stored method data will have the details associated before the test run
        // which will potentially have incorrect line numbers. This happens for 2 reasons.
        // 1. This happens when a method(s) exist in a source file that had its line numbers changes due to a source file change.
        // 2. We also need to account for other methods that had their line numbers updated but weren't executed in the test,
        // i.e. new lines of code were added to a method, this causes that method to be executed in this test run. But, the methods in
        // the file below this will all be pushed down and have updated line numbers. So we need to update those indexed
        // methods in the DB as well.
        Map<Integer, MethodImpactTracker> newMethodTracker = new HashMap<>();

        for (Integer methodImpactedId : methodsImpactedAfterTestRun){
            MethodImpactTracker tracker = methodTrackerFromTestRun.containsKey(methodImpactedId)
                    ? methodTrackerFromTestRun.get(methodImpactedId)
                    : methodTrackerOnDisk.get(methodImpactedId);

            if (tracker == null) {
                // The id is referenced from tia_source_class_method but neither this run's
                // JaCoCo results nor the tia_source_method table on disk knows about it.
                // Most likely an orphan left behind by an earlier run that aborted between
                // updating the join table and the truncate+insert of tia_source_method.
                // Skip the orphan rather than NPE downstream in persistSourceMethods.
                log.error("Source method id {} is referenced from tia_source_class_method but " +
                        "has no entry in tia_source_method (and was not invoked in this run); " +
                        "dropping orphan reference.", methodImpactedId);
                continue;
            }

            newMethodTracker.put(methodImpactedId, tracker);
        }

        return newMethodTracker;
    }

    /**
     * Update the stored test suite trackers based on the results from the current test run.
     * For each test suite, set the new tracker including the new test to source code mappings.
     * If the test suite has an existing tracker then update it to use the new tracker.
     *
     * @param storedTestSuiteTrackers the test suites stored in the Tia DB
     * @param newTestSuiteTrackers the test suites with coverage data from the current run
     * @return mergedTestMappings
     */
    private Map<String, TestSuiteTracker> mergeTestMappingMaps(final Map<String, TestSuiteTracker> storedTestSuiteTrackers,
                                                               final Map<String, TestSuiteTracker> newTestSuiteTrackers){
        Map<String, TestSuiteTracker> mergedTestMappings = new HashMap<>(storedTestSuiteTrackers);

        newTestSuiteTrackers.forEach((testSuiteName, newTestSuiteTracker) -> {
            TestSuiteTracker storedTestSuiteTracker = storedTestSuiteTrackers.get(testSuiteName);

            if (storedTestSuiteTracker != null){
                storedTestSuiteTracker.setClassesImpacted(newTestSuiteTracker.getClassesImpacted());
            } else {
                TestSuiteTracker newTestSuiteTrackerToAdd = new TestSuiteTracker();
                // Add a new test suite tracker but don't add the stats, this gets updated separately
                newTestSuiteTrackerToAdd.setName(newTestSuiteTracker.getName());
                newTestSuiteTrackerToAdd.setClassesImpacted(newTestSuiteTracker.getClassesImpacted());
                mergedTestMappings.put(testSuiteName, newTestSuiteTrackerToAdd);
            }
        });

        return mergedTestMappings;
    }

    /**
     * Update the stored test suite trackers based on the results from the current test run.
     * For each test suite, set the new tracker including the new test to source code mappings.
     * If the test suite has an existing tracker then update it to use the new tracker.
     *
     * @param storedTestSuiteTrackers the test suites stored in the Tia DB
     * @param newTestSuiteTrackers the test suites with coverage data from the current run
     * @return mergedTestMappings
     */
    private Map<String, TestSuiteTracker> mergeTestMappingStats(final Map<String, TestSuiteTracker> storedTestSuiteTrackers,
                                                                final Map<String, TestSuiteTracker> newTestSuiteTrackers){
        Map<String, TestSuiteTracker> mergedTestMappings = new HashMap<>(storedTestSuiteTrackers);

        newTestSuiteTrackers.forEach((testSuiteName, newTestSuiteTracker) -> {
            TestSuiteTracker storedTestSuiteTracker = storedTestSuiteTrackers.get(testSuiteName);

            if (storedTestSuiteTracker != null){
                storedTestSuiteTracker.incrementStats(newTestSuiteTracker.getTestStats());
            } else {
                mergedTestMappings.put(testSuiteName, newTestSuiteTracker);
            }
        });

        return mergedTestMappings;
    }

    /**
     * Compile a set of test suite names based on test classes found in the test class directory.
     *
     * @param testClassesDir the directory containing the test class files
     * @return a set of test suite names based on test classes found in the directory
     */
    public Set<String> getTestClassesFromDir(final String testClassesDir) {
        Path path = Paths.get(testClassesDir);
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Test classes path must be a directory - " + testClassesDir);
        }

        Set<String> testClasses;
        String classFileExt = "." + FileExtensions.CLASS_FILE_EXT;

        try (Stream<Path> walk = Files.walk(path)) {
            testClasses = walk
                    .filter(p -> !Files.isDirectory(p))
                    // convert from the full file system path for the class files into the class name
                    .map(Path::toString)
                    .filter(f -> f.toLowerCase().endsWith(classFileExt))
                    .map(p -> p.replace(testClassesDir, "").replace(classFileExt, ""))
                    .map(p -> p.substring((p.startsWith(File.separator) ? 1 : 0)).replace(File.separator, "."))
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.debug("Test classes found: " + testClasses);
        return testClasses;
    }
}
