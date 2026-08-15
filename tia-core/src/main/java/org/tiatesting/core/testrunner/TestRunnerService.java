package org.tiatesting.core.testrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.distributed.DistributedRunCompletion;
import org.tiatesting.core.distributed.DistributedRunnerContext;
import org.tiatesting.core.distributed.DistributedRunnerPersist;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.report.ReportUtils;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.SealedRunDataAssembler;
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
     * {@link #sealRun} (which writes the method catalogue, the library drain cleanup and the
     * {@code commitValue} stamp together, via {@link DataStore#persistSealedRunData}) is the
     * final mapping-related write. The invariant is: <em>if commit X is the stored value, every
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
     * @param distributedRunnerContext the run id, runner identity and claimed group when this run
     *                                 is one runner's share of a distributed build, or {@code null}
     *                                 for an ordinary single-host run
     */
    public void persistTestRunData(final boolean updateDBMapping, final boolean updateDBStats,
                                   final boolean updateDBTestRunHistory,
                                   final String commitValue, final String branch,
                                   final long runStartTimestampMs,
                                   final TestRunResult testRunResult,
                                   final DistributedRunnerContext distributedRunnerContext){
        // Capture the run duration up front, before any DB read/write below. This is the
        // test-execution wall clock (test-plan start to here); it deliberately excludes Tia's own
        // mapping/seal persist work that follows, which on a seed run is seconds of bulk inserts.
        // The savings baseline (allTestsRunTime) is likewise pure test-execution time, so the two
        // must be measured on the same clock for savings = baseline - duration to be meaningful.
        final long durationMs = Math.max(0L, System.currentTimeMillis() - runStartTimestampMs);

        if (distributedRunnerContext != null){
            // One runner of a distributed build persists only its own share and stops. Everything
            // that describes the whole build - the catalogue, the seal, the commit stamp and the
            // history row - belongs to whichever runner finishes last.
            persistDistributedRunnerData(updateDBMapping, updateDBStats, updateDBTestRunHistory,
                    commitValue, branch, durationMs, testRunResult, distributedRunnerContext);
            return;
        }

        if (updateDBMapping){
            log.info("Persisting core data with commit value: " + commitValue);
        }
        if (updateDBStats){
            log.info("Persisting updated stats from the test run.");
        }

        TiaData tiaData = dataStore.getTiaCore();

        // 1. Suite mapping rows first. These are safe to be ahead of the stored commit - they
        //    carry no line coordinates, and they are marked unsealed until the seal clears them.
        updateTestSuiteMapping(tiaData, testRunResult.getTestSuiteTrackers(), testRunResult.getRunnerTestSuites(),
                testRunResult.getSelectedTests(), updateDBMapping, updateDBStats);

        // A run where Tia ignored zero suites is an all-tests run (seed run, or every suite
        // selected). getIgnoredTestSuiteCount() already excludes developer-disabled suites,
        // so this stays a plain == 0 check.
        boolean allTestsRun = testRunResult.getIgnoredTestSuiteCount() == 0;

        if (updateDBMapping){
            // 2. The failed set is incremental and safe to be ahead of the commit; over-inclusion
            //    only force-runs extra suites next time.
            updateTestSuitesFailed(tiaData, testRunResult.getSelectedTests(), testRunResult.getTestSuitesFailed());
        }

        // 3. The seal bundle: catalogue, library drain cleanup and the commit value, written in
        //    one transaction so none of them can end up ahead of the others.
        sealRun(tiaData, commitValue, branch, updateDBMapping, updateDBStats,
                testRunResult, allTestsRun);

        // 4. History row is audit-only and has no select-tests consistency implications;
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
     * Persist one runner's share of a distributed build: its suites' mapping rows, the method
     * trackers it observed and its contribution to the failed set. As one runner it rebuilds no
     * method catalogue, advances no stored commit value and writes no history row; all three
     * describe the whole build and belong to whichever runner finishes last. See the distributed
     * test runs chapter in {@code WIKI.md}.
     *
     * <p>Two orderings here are correctness properties of the run rather than tidiness:
     * <ul>
     *   <li><b>The claim is re-verified before any write.</b> A runner from a superseded build -
     *       one whose plan rows a newer build's plan write already cleared - writes nothing at all,
     *       because persisting its suites anyway would leave mapping rows describing its own older
     *       commit under the commit the newer build has already stored.</li>
     *   <li><b>The group's status is not flipped here, though its progress is reported.</b> This
     *       method runs once per finished test plan, and a retry of failed tests is another test
     *       plan in the same JVM, so flipping the group to {@code COMPLETED} here would release the
     *       barrier after the first test plan - while this runner is still executing tests. The
     *       sealer would then rebuild the catalogue from an edge set missing everything the later
     *       test plans covered, dropping every method reachable only from those suites and making
     *       them invisible to the next build's diff. This runner's duration and suite counters are
     *       still reported on every persist, so they accumulate correctly across retries; only the
     *       status flip is deferred, recorded with {@link DistributedRunCompletion} and made once,
     *       when this JVM exits.</li>
     * </ul>
     *
     * <p>The Tia-level run stats are deliberately not incremented here even when {@code
     * updateDBStats} is set: they live on the core row alongside the commit value, and a build split
     * across runners must contribute one set of stats rather than one per runner, so the sealer
     * aggregates them from the completed groups. Per-suite stats are this runner's own and are
     * written with its mapping rows as usual.
     *
     * @param updateDBMapping should the test-suite to source-code mapping be updated
     * @param updateDBStats should the per-suite run stats be updated. The Tia-level stats are
     *                      handed on to the seal instead, since they describe the whole build
     * @param updateDBTestRunHistory should the build write a history row. Recorded for the seal
     *                               unchanged: the build's one row is the sealer's to write
     * @param commitValue the VCS commit / changelist the build ran against, recorded for the seal
     *                    this runner performs if it turns out to be the last one to finish
     * @param branch the VCS branch the build targeted, likewise recorded for the seal
     * @param durationMs this runner's test-execution duration in ms, measured on the same clock a
     *                   single-host run records, so the sealer's aggregate stays comparable with
     *                   non-distributed history
     * @param testRunResult the collected results of this runner's test run
     * @param distributedRunnerContext the run id, runner identity and claimed group this runner
     *                                 holds; a context that claimed no group persists nothing
     */
    private void persistDistributedRunnerData(final boolean updateDBMapping, final boolean updateDBStats,
                                              final boolean updateDBTestRunHistory,
                                              final String commitValue, final String branch,
                                              final long durationMs, final TestRunResult testRunResult,
                                              final DistributedRunnerContext distributedRunnerContext){
        if (!distributedRunnerContext.isClaimed()){
            // A surplus runner: the pipeline fanned out wider than the plan's group count, so this
            // runner executed nothing. It has no group to complete, and persisting the tracked-suite
            // map it read would rewrite rows produced by the runners that did the work.
            log.info("Distributed run '{}': runner '{}' claimed no group and ran no test suites, so "
                            + "there is nothing to persist.", distributedRunnerContext.getRunId(),
                    distributedRunnerContext.getRunnerKey());
            return;
        }

        DistributedRunnerPersist runnerPersist =
                new DistributedRunnerPersist(dataStore, distributedRunnerContext);

        if (!runnerPersist.claimIsLive()){
            // Straggler protection. claimIsLive has already logged which of the two reasons applies.
            return;
        }

        log.info("Distributed run '{}': runner '{}' persisting group {}.",
                distributedRunnerContext.getRunId(), distributedRunnerContext.getRunnerKey(),
                distributedRunnerContext.getGroupNumber());

        TiaData tiaData = dataStore.getTiaCore();

        // 1. Suite mapping rows first, exactly as on the single-host path - they carry no line
        //    coordinates, so they are safe to be ahead of the commit the sealer will store.
        updateTestSuiteMapping(tiaData, testRunResult.getTestSuiteTrackers(), testRunResult.getRunnerTestSuites(),
                testRunResult.getSelectedTests(), updateDBMapping, updateDBStats);

        if (updateDBMapping){
            // 2. The failed set is incremental, so several runners updating it concurrently is
            //    exactly what it was built for.
            updateTestSuitesFailed(tiaData, testRunResult.getSelectedTests(), testRunResult.getTestSuitesFailed());

            // 3. Staging replaces the catalogue write a single-host run makes here. Method ids hash
            //    the class, method and descriptor only, so the ids this runner staged stay valid
            //    against the catalogue the sealer writes at the end of the build.
            runnerPersist.stageMethodTrackers(testRunResult.getMethodTrackersFromTestRun());
        }

        // 4. No history row and no core row: one distributed build produces one aggregated history
        //    row and one set of Tia-level stats, both written by the sealer from the figures every
        //    group recorded, rather than one of each per runner.

        // 5. Report this test plan's progress. suitesRan and the duration accumulate onto whatever
        //    is already stored for the group, so a Surefire retry within this JVM sums correctly
        //    instead of this retry's per-attempt count overwriting the ones before it; suitesFailed
        //    replaces what was stored, since it is current state and a passing retry must be able
        //    to shrink it back to zero. suitesDiscovered also replaces what was stored, but for a
        //    different reason: getRunnerTestSuites() is already cumulative across every test plan in
        //    this JVM, so accumulating it here would double-count. It is what the completeness guard
        //    later reads, not suitesRan, so a suite the runner discovered but never executed (a
        //    disabled class, a Surefire/Gradle filter, a class deleted since the last mapping run)
        //    does not block the group from completing. The status flip itself is not made here: this
        //    runs once per finished test plan, and a retry is another test plan in the same JVM, so
        //    flipping the group to COMPLETED here would release the barrier after the first test plan
        //    while this runner is still executing tests. It is recorded instead, and made once when
        //    the JVM exits.
        int suitesRan = Math.max(0, testRunResult.getSuitesRanThisAttempt());
        int suitesFailed = testRunResult.getTestSuitesFailed() != null
                ? testRunResult.getTestSuitesFailed().size() : 0;
        int suitesDiscovered = testRunResult.getRunnerTestSuites() != null
                ? testRunResult.getRunnerTestSuites().size() : 0;
        runnerPersist.reportGroupProgress(durationMs, suitesRan, suitesFailed, suitesDiscovered);
        DistributedRunCompletion.recordTestPlanPersist(dataStore, distributedRunnerContext,
                commitValue, branch, updateDBMapping, updateDBStats, updateDBTestRunHistory);
    }

    /**
     * Assemble and write the run's seal. The method catalogue, the library drain cleanup and the
     * commit value all describe the commit being sealed, so they are handed to the data store as
     * one bundle and written in a single transaction via {@link DataStore#persistSealedRunData}.
     * On a run that does not own mapping updates the only write is the core row - it carries the
     * Tia-level run stats as well as the commit value, but there is no catalogue rewrite, no
     * drain cleanup and no commit advance, exactly as on today's stats-only path.
     *
     * @param tiaData the core data read at the start of the persist, mutated with the new commit
     *                and stats before being written
     * @param commitValue the VCS commit / changelist the run was against
     * @param branch the VCS branch the run targeted
     * @param updateDBMapping whether this run owns mapping-DB updates
     * @param updateDBStats whether the run stats should be updated
     * @param testRunResult the collected results of the test run
     * @param allTestsRun {@code true} when Tia ignored zero suites this run
     */
    private void sealRun(final TiaData tiaData, final String commitValue, final String branch,
                         final boolean updateDBMapping, final boolean updateDBStats,
                         final TestRunResult testRunResult, final boolean allTestsRun){
        if (updateDBStats){
            tiaData.incrementStats(testRunResult.getTestStats(), allTestsRun);
        }

        if (!updateDBMapping) {
            // This run does not own mapping updates, so there is nothing to seal. The only write
            // is the core row, which carries the Tia-level run stats as well as the commit value.
            // tia_source_method, the library baselines and the unsealed flags are all mapping
            // concerns and stay untouched, exactly as on a stats-only run today.
            dataStore.persistCoreData(tiaData);
            return;
        }

        tiaData.setCommitValue(commitValue);
        tiaData.setBranch(branch);
        tiaData.setLastUpdated(Instant.now());

        dataStore.persistSealedRunData(new SealedRunDataAssembler(dataStore).assemble(tiaData,
                testRunResult.getMethodTrackersFromTestRun(),
                testRunResult.getLibraryImpactDrainResult(), commitValue, allTestsRun));
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
