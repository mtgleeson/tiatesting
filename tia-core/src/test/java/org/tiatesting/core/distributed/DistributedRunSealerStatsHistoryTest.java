package org.tiatesting.core.distributed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.testrunner.TestRunResult;
import org.tiatesting.core.testrunner.TestRunnerService;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the build-level figures the sealer records once for a whole distributed build: its single
 * history row, the Tia-level stats no runner may write, and the all-tests-run baseline that savings
 * are measured against.
 *
 * <p>Two durations are recorded and they are not interchangeable. The <b>serial-equivalent</b>
 * duration - the sum of every group's test time - is the primary one, because it is what the same
 * selection would have cost on one host and therefore what keeps savings meaning "time saved by not
 * running unimpacted tests" whether or not the build was distributed. The <b>wall clock</b> - the
 * slowest group - is recorded alongside so the user can see what the build actually took, but never
 * as the primary figure: that would credit Tia with the parallelism the CI system provided and would
 * silently change what {@code avgRunTime} means the moment distributed mode was switched on.
 *
 * <p>The baseline repair matters just as much. A single-host run only advances the all-tests
 * baseline when it ignored zero suites, and no runner in a split build ever ignores zero suites - so
 * without the union check here, switching a project to distributed mode would freeze the baseline
 * and savings would decay into meaninglessness with nothing failing to say so.
 */
class DistributedRunSealerStatsHistoryTest {

    private static final String RUN_ID = "run-1";
    private static final String RUNNER_A = "runner-a";
    private static final String RUNNER_B = "runner-b";
    private static final String PLAN_COMMIT = "plan-commit";
    private static final String LIB = "com.example:lib";
    private static final long PLANNED_AT_MS = 1_700_000_000_000L;

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 store in its own temp directory and seed a known prior commit, so
     * every test starts from a build that has already recorded something.
     *
     * @throws Exception if the temp directory cannot be created or the schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-distributed-sealer-stats-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test"));
        dataStore.getTiaData(true);

        TiaData tiaData = dataStore.getTiaCore();
        tiaData.setCommitValue("prior-commit");
        tiaData.setBranch("prior-branch");
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);
    }

    /**
     * Close the store so embedded H2 releases its file lock, then remove the temp directory.
     */
    @AfterEach
    void tearDown() {
        if (dataStore != null) {
            dataStore.close();
        }
        if (tempDir != null && tempDir.exists()) {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            tempDir.delete();
        }
    }

    /**
     * One distributed build produces one history row carrying the whole build's figures: the serial
     * duration as the primary one, the wall clock alongside it, and the group count and run id that
     * say the row describes a split build.
     */
    @Test
    void theSealWritesOneHistoryRowCarryingTheWholeBuildsFigures() {
        // given - two groups that between them ran 5 of the 8 tracked suites
        seedTrackedSuites(8, 0);
        persistPlan(RUN_ID, 2);
        completeGroup(RUN_ID, 0, RUNNER_A, 3_000L, 2, 0);
        completeGroup(RUN_ID, 1, RUNNER_B, 5_000L, 3, 1);

        // when
        sealerFor(RUNNER_B, 1).sealIfElected(true, true, true, 9000L);

        // then
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
        assertEquals(1, history.size(), "a distributed build writes exactly one history row");
        TestRunHistoryEntry entry = history.get(0);
        assertEquals(8_000L, entry.getDurationMs(),
                "the primary duration must be the serial-equivalent time - the sum of the groups");
        assertEquals(Long.valueOf(5_000L), entry.getWallClockMs(),
                "the wall clock must be the slowest group, recorded alongside rather than instead");
        assertEquals(Integer.valueOf(2), entry.getGroupCount(), "the row must record the group count");
        assertEquals(RUN_ID, entry.getRunId(), "the row must name the distributed run it summarises");
        assertEquals(5, entry.getNumSuitesRan(), "the suites ran must be summed across the groups");
        assertEquals(1, entry.getNumSuitesFailed(), "the failed suites must be summed across the groups");
        assertEquals(3, entry.getNumSuitesIgnored(),
                "the ignored count is the tracked suites the build did not run");
        assertEquals(PLAN_COMMIT, entry.getCommit(), "the row must carry the sealed commit");
        assertEquals("main", entry.getBranch(), "the row must carry the build's branch");
    }

    /**
     * The history row is stamped with the moment the build was planned, not the moment the last
     * runner happened to finish. Every runner shares that one timestamp, so the row describes the
     * build rather than whichever job was slowest.
     */
    @Test
    void theHistoryRowIsStampedWithTheTimeTheBuildWasPlanned() {
        // given
        seedTrackedSuites(2, 0);
        persistPlan(RUN_ID, 1);
        completeGroup(RUN_ID, 0, RUNNER_A, 3_000L, 2, 0);

        // when
        sealerFor(RUNNER_A, 0).sealIfElected(true, true, true, 9000L);

        // then
        assertEquals(PLANNED_AT_MS, dataStore.readTestRunHistory().get(0).getRunTimestampMs(),
                "the build's history row must be stamped with the time its plan was written");
    }

    /**
     * Two runners driven through the real persist flow produce one row between them, not one each.
     * A row per runner would multiply the history, and every savings total computed off it, by the
     * fan-out.
     */
    @Test
    void twoRunnersPersistingProduceOneRowBetweenThemRatherThanOneEach() {
        // given
        persistPlan(RUN_ID, 2);
        TestRunnerService service = new TestRunnerService(dataStore);
        DistributedRunnerContext firstRunner = claim(RUN_ID, RUNNER_A);
        DistributedRunnerContext lastRunner = claim(RUN_ID, RUNNER_B);

        // when - each runner persists and then the build tool makes its explicit completion
        service.persistTestRunData(true, true, true, PLAN_COMMIT, "main",
                System.currentTimeMillis(), runResultFor("com.example.ATest", "com/example/A.java",
                        101, "com/example/A.a.()V"), firstRunner);
        DistributedRunCompleter.completeAndSeal(dataStore, firstRunner, true, true, true,
                System.currentTimeMillis());
        service.persistTestRunData(true, true, true, PLAN_COMMIT, "main",
                System.currentTimeMillis(), runResultFor("com.example.BTest", "com/example/B.java",
                        202, "com/example/B.b.()V"), lastRunner);
        DistributedRunCompleter.completeAndSeal(dataStore, lastRunner, true, true, true,
                System.currentTimeMillis());

        // then
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
        assertEquals(1, history.size(), "two runners must produce one build-level row: " + history);
        assertEquals(RUN_ID, history.get(0).getRunId(), "and it must be the build's row");
        assertEquals(Integer.valueOf(2), history.get(0).getGroupCount(),
                "the one row must describe both groups");
    }

    /**
     * Savings are measured against the serial-equivalent duration, so they stay comparable with the
     * single-host rows either side of them. Measuring them against the wall clock would report the
     * CI system's parallelism as time Tia saved.
     */
    @Test
    void savingsAreComputedFromTheSerialDurationNotTheWallClock() {
        // given - a 20s full-suite baseline and a build that ran 2 of 8 suites in 3s + 5s
        seedAllTestsBaseline(20_000L);
        seedTrackedSuites(8, 0);
        persistPlan(RUN_ID, 2);
        completeGroup(RUN_ID, 0, RUNNER_A, 3_000L, 1, 0);
        completeGroup(RUN_ID, 1, RUNNER_B, 5_000L, 1, 0);

        // when
        sealerFor(RUNNER_B, 1).sealIfElected(true, true, true, 9000L);

        // then
        TestRunHistoryEntry entry = dataStore.readTestRunHistory().get(0);
        assertEquals(12_000L, entry.getTimeSavingsMs(),
                "savings must be the baseline minus the serial duration (20s - 8s), not minus the "
                        + "5s wall clock");
        assertEquals(60, entry.getSavingsPercent(), "the percentage must follow the same figure");
    }

    /**
     * The Tia-level stats are incremented once for the whole build, from the aggregated figures. No
     * runner writes them - the commit stamp and the stats share the core row and the stamp belongs
     * to the sealer - so if the sealer did not do this, a distributed build would silently record
     * no Tia-level stats at all.
     */
    @Test
    void theTiaLevelStatsAreIncrementedOnceForTheWholeBuild() {
        // given
        seedTrackedSuites(8, 0);
        persistPlan(RUN_ID, 2);
        completeGroup(RUN_ID, 0, RUNNER_A, 3_000L, 1, 0);
        completeGroup(RUN_ID, 1, RUNNER_B, 5_000L, 1, 0);

        // when
        sealerFor(RUNNER_B, 1).sealIfElected(true, true, true, 9000L);

        // then
        TestStats stats = dataStore.getTiaCore().getTestStats();
        assertEquals(1L, stats.getNumRuns(), "the build counts as one run, not one per runner");
        assertEquals(8_000L, stats.getAvgRunTime(),
                "the selected-run average must fold in the serial-equivalent duration");
        assertEquals(1L, stats.getNumSuccessRuns(), "a build with no failed suites is a success");
        assertEquals(0L, stats.getNumFailRuns(), "a build with no failed suites is not a failure");
    }

    /**
     * A build in which any group reported a failed suite counts once as a failed run, matching what
     * a single-host run records for the same outcome.
     */
    @Test
    void aBuildWithAFailedSuiteInAnyGroupCountsOnceAsAFailedRun() {
        // given
        seedTrackedSuites(8, 0);
        persistPlan(RUN_ID, 2);
        completeGroup(RUN_ID, 0, RUNNER_A, 3_000L, 1, 0);
        completeGroup(RUN_ID, 1, RUNNER_B, 5_000L, 1, 1);

        // when
        sealerFor(RUNNER_B, 1).sealIfElected(true, true, true, 9000L);

        // then
        TestStats stats = dataStore.getTiaCore().getTestStats();
        assertEquals(0L, stats.getNumSuccessRuns(), "a build with a failed suite is not a success");
        assertEquals(1L, stats.getNumFailRuns(), "and it counts once, not once per group");
    }

    /**
     * A build that does not update stats leaves the core row's stats alone, exactly as a single-host
     * run with {@code updateDBStats=false} does.
     */
    @Test
    void aBuildThatDoesNotUpdateStatsLeavesTheCoreStatsAlone() {
        // given
        seedTrackedSuites(8, 0);
        persistPlan(RUN_ID, 1);
        completeGroup(RUN_ID, 0, RUNNER_A, 3_000L, 1, 0);

        // when
        sealerFor(RUNNER_A, 0).sealIfElected(true, false, true, 9000L);

        // then
        TestStats stats = dataStore.getTiaCore().getTestStats();
        assertEquals(0L, stats.getNumRuns(), "a build that does not update stats must record none");
        assertEquals(0L, stats.getAvgRunTime(), "and must not move the average");
    }

    /**
     * A stats-only build - one that does not own mapping updates - still writes its aggregated
     * stats to the core row, without advancing the stored commit value. That mirrors the single-host
     * stats-only path, where the core row is written but nothing is sealed.
     */
    @Test
    void aStatsOnlyBuildRecordsItsStatsWithoutAdvancingTheCommit() {
        // given
        seedTrackedSuites(8, 0);
        persistPlan(RUN_ID, 1);
        completeGroup(RUN_ID, 0, RUNNER_A, 3_000L, 1, 0);

        // when
        sealerFor(RUNNER_A, 0).sealIfElected(false, true, true, 9000L);

        // then
        TiaData reloaded = dataStore.getTiaCore();
        assertEquals(1L, reloaded.getTestStats().getNumRuns(),
                "a stats-only build must still contribute its run to the Tia-level stats");
        assertEquals(3_000L, reloaded.getTestStats().getAvgRunTime(),
                "and must fold in the serial-equivalent duration");
        assertEquals("prior-commit", reloaded.getCommitValue(),
                "a build that does not own mapping updates must not advance the stored commit");
    }

    /**
     * A build that is not recording history writes no row, while still sealing and still recording
     * its stats.
     */
    @Test
    void aBuildThatIsNotRecordingHistoryWritesNoRow() {
        // given
        seedTrackedSuites(8, 0);
        persistPlan(RUN_ID, 1);
        completeGroup(RUN_ID, 0, RUNNER_A, 3_000L, 1, 0);

        // when
        sealerFor(RUNNER_A, 0).sealIfElected(true, true, false, 9000L);

        // then
        assertTrue(dataStore.readTestRunHistory().isEmpty(),
                "history logging was off, so the build must write no row");
        assertEquals(1L, dataStore.getTiaCore().getTestStats().getNumRuns(),
                "the stats are a separate concern and must still be recorded");
    }

    /**
     * <b>The baseline repair.</b> When the groups between them ran every tracked suite, the build is
     * an all-tests run and its serial-equivalent duration becomes the full-suite baseline every
     * later run's savings are measured against. Without this, no distributed build could ever
     * advance the baseline, because no single runner in a split build ignores zero suites.
     */
    @Test
    void theBaselineAdvancesWhenTheGroupsBetweenThemRanEveryTrackedSuite() {
        // given - 4 tracked suites and two groups that between them ran all 4
        seedTrackedSuites(4, 0);
        persistPlan(RUN_ID, 2);
        completeGroup(RUN_ID, 0, RUNNER_A, 3_000L, 2, 0);
        completeGroup(RUN_ID, 1, RUNNER_B, 5_000L, 2, 0);

        // when
        sealerFor(RUNNER_B, 1).sealIfElected(true, true, true, 9000L);

        // then
        TestStats stats = dataStore.getTiaCore().getTestStats();
        assertEquals(8_000L, stats.getAllTestsRunTime(),
                "the union of the groups covered every tracked suite, so the baseline must advance "
                        + "to the serial-equivalent duration");
        assertEquals(1L, stats.getNumAllTestsRuns(), "and the build counts as one all-tests run");
        assertEquals(0L, stats.getAvgRunTime(),
                "an all-tests run must not pollute the selected-run average");
        assertEquals(0, dataStore.readTestRunHistory().get(0).getNumSuitesIgnored(),
                "an all-tests build ignored nothing");
        assertEquals(0L, dataStore.readTestRunHistory().get(0).getTimeSavingsMs(),
                "an all-tests build saved nothing, so its savings are zero");
    }

    /**
     * A build that ran only some of the tracked suites leaves the baseline where it was. The
     * baseline is the full-suite time, so folding a partial build's time into it would understate
     * the full-suite cost and quietly shrink every future savings figure.
     */
    @Test
    void theBaselineDoesNotAdvanceWhenTheBuildRanOnlySomeTrackedSuites() {
        // given - a 20s baseline, 8 tracked suites, and a build that ran 2 of them
        seedAllTestsBaseline(20_000L);
        seedTrackedSuites(8, 0);
        persistPlan(RUN_ID, 2);
        completeGroup(RUN_ID, 0, RUNNER_A, 3_000L, 1, 0);
        completeGroup(RUN_ID, 1, RUNNER_B, 5_000L, 1, 0);

        // when
        sealerFor(RUNNER_B, 1).sealIfElected(true, true, true, 9000L);

        // then
        TestStats stats = dataStore.getTiaCore().getTestStats();
        assertEquals(20_000L, stats.getAllTestsRunTime(),
                "a partial build must leave the full-suite baseline alone");
        assertEquals(1L, stats.getNumAllTestsRuns(), "and must not count as an all-tests run");
        assertEquals(8_000L, stats.getAvgRunTime(),
                "its time belongs in the selected-run average instead");
    }

    /**
     * Suites the developer disabled in source do not hold the baseline back. They would not run
     * without Tia either, so a build that ran everything else still ran everything Tia could have
     * selected - the same rule the single-host ignored-count applies.
     */
    @Test
    void developerDisabledSuitesDoNotHoldTheBaselineBack() {
        // given - 4 tracked suites, one of them disabled in source, and a build that ran the other 3
        seedTrackedSuites(4, 1);
        persistPlan(RUN_ID, 2);
        completeGroup(RUN_ID, 0, RUNNER_A, 3_000L, 2, 0);
        completeGroup(RUN_ID, 1, RUNNER_B, 5_000L, 1, 0);

        // when
        sealerFor(RUNNER_B, 1).sealIfElected(true, true, true, 9000L);

        // then
        assertEquals(8_000L, dataStore.getTiaCore().getTestStats().getAllTestsRunTime(),
                "a suite the developer disabled cannot keep a build from being an all-tests run");
        assertEquals(0, dataStore.readTestRunHistory().get(0).getNumSuitesIgnored(),
                "and it is not counted as a suite Tia ignored");
    }

    /**
     * A build in which no group ran anything is not an all-tests run, however few suites are
     * tracked. Folding a build that ran nothing into the full-suite baseline would drive the
     * baseline - and therefore every later savings figure - to nothing.
     */
    @Test
    void aBuildWhereNoGroupRanAnythingIsNotAnAllTestsRun() {
        // given - nothing tracked yet, and a group assigned no suites at all, so it can complete on
        // 0 >= 0 without reporting any progress
        persistPlanWithNoAssignedSuites(RUN_ID);
        completeGroup(RUN_ID, 0, RUNNER_A, 40L, 0, 0);

        // when
        sealerFor(RUNNER_A, 0).sealIfElected(true, true, true, 9000L);

        // then
        TestStats stats = dataStore.getTiaCore().getTestStats();
        assertEquals(0L, stats.getAllTestsRunTime(), "a build that ran nothing sets no baseline");
        assertEquals(0L, stats.getNumAllTestsRuns(), "and counts as no all-tests run");
    }

    /**
     * An all-tests distributed build advances every tracked library's mapping baseline, because
     * every suite was just re-covered against the sealed commit. The single-host path does this from
     * its own all-tests flag; the sealer's union check is what supplies the same answer for a build
     * split across runners.
     */
    @Test
    void anAllTestsDistributedBuildAdvancesTheTrackedLibraryMappingBaselines() {
        // given - a tracked library whose mapping baseline predates this build
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));
        seedTrackedSuites(2, 0);
        persistPlan(RUN_ID, 1);
        completeGroup(RUN_ID, 0, RUNNER_A, 3_000L, 2, 0);

        // when
        sealerFor(RUNNER_A, 0).sealIfElected(true, true, true, 9000L);

        // then
        assertEquals(PLAN_COMMIT, dataStore.readTrackedLibraries().get(LIB).getMappingBaselineCommit(),
                "an all-tests build re-covered every library, so their baselines advance");
    }

    /**
     * A runner that loses the election writes no history row and no stats. Only the winner records
     * the build, so a build cannot end up with a row per runner.
     */
    @Test
    void aRunnerThatLosesTheElectionRecordsNothing() {
        // given - two groups, only one of them complete
        seedTrackedSuites(4, 0);
        persistPlan(RUN_ID, 2);
        completeGroup(RUN_ID, 0, RUNNER_A, 3_000L, 2, 0);

        // when
        sealerFor(RUNNER_A, 0).sealIfElected(true, true, true, 9000L);

        // then
        assertTrue(dataStore.readTestRunHistory().isEmpty(),
                "a runner that lost the election must not write the build's history row");
        assertEquals(0L, dataStore.getTiaCore().getTestStats().getNumRuns(),
                "nor may it record the build's stats");
    }

    /**
     * Build the sealer a given runner would use.
     *
     * @param runnerKey the identity the runner claimed under
     * @param groupNumber the group that runner holds
     * @return the sealer bound to the test's store
     */
    private DistributedRunSealer sealerFor(final String runnerKey, final int groupNumber) {
        return new DistributedRunSealer(dataStore,
                DistributedRunnerContext.forClaimedGroup(RUN_ID, runnerKey, groupNumber));
    }

    /**
     * Build and persist a distributed run plan with one suite per group. Groups 0 and 1 are assigned
     * {@code com.example.ATest} and {@code com.example.BTest} respectively - the two suite names
     * {@link #runResultFor} always reports as observed - so a persist driven through the real
     * {@link TestRunnerService} (rather than {@link #completeGroup}'s raw counts) still finds its
     * group's assignment inside its observed set.
     *
     * @param runId the run identifier to plan under
     * @param groupCount how many groups the plan is split into
     */
    private void persistPlan(final String runId, final int groupCount) {
        List<DistributedRunGroup> groups = new ArrayList<>();
        Map<Integer, List<String>> suites = new HashMap<>();
        for (int i = 0; i < groupCount; i++) {
            groups.add(DistributedRunGroup.pending(runId, i, 1000L));
            if (i == 0) {
                suites.put(i, Arrays.asList("com.example.ATest"));
            } else if (i == 1) {
                suites.put(i, Arrays.asList("com.example.BTest"));
            } else {
                suites.put(i, Arrays.asList("com.example.Suite" + i + "Test"));
            }
        }
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(
                DistributedRun.open(runId, "main", PLAN_COMMIT, groupCount, null,
                        1000L * groupCount, PLANNED_AT_MS), groups, suites, null));
    }

    /**
     * Build and persist a single-group plan with no suites assigned to it at all, standing in for a
     * runner whose group genuinely ran nothing - the completeness guard's degenerate {@code 0 >= 0}
     * case, which must not be confused with a group that was assigned suites but never reported
     * running any of them.
     *
     * @param runId the run identifier to plan under
     */
    private void persistPlanWithNoAssignedSuites(final String runId) {
        List<DistributedRunGroup> groups = new ArrayList<>();
        groups.add(DistributedRunGroup.pending(runId, 0, 1000L));
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Collections.<String>emptyList());
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(
                DistributedRun.open(runId, "main", PLAN_COMMIT, 1, null, 1000L, PLANNED_AT_MS),
                groups, suites, null));
    }

    /**
     * Claim a group, report the measurements a real runner would report, and complete it. Reports
     * {@code suitesRan} as the observed count too, standing in for the ordinary case where a
     * runner observes exactly the suites it executes.
     *
     * @param runId the run the group belongs to
     * @param groupNumber the group to claim and complete
     * @param runnerKey the identity to claim under
     * @param actualDurationMs the group's measured test-execution time
     * @param suitesRan the number of suites the runner executed
     * @param suitesFailed the number of the runner's suites with at least one failed test
     */
    private void completeGroup(final String runId, final int groupNumber, final String runnerKey,
                               final long actualDurationMs, final int suitesRan,
                               final int suitesFailed) {
        DistributedRunGroup claimed = dataStore.claimNextPendingGroup(runId, runnerKey, 5000L);
        assertNotNull(claimed, "test setup expects a group to be available to claim");
        assertEquals(groupNumber, claimed.getGroupNumber(),
                "test setup expects the groups to be claimed in order");
        assertTrue(dataStore.reportGroupProgress(runId, groupNumber, runnerKey, actualDurationMs,
                suitesRan, suitesFailed, suitesRan),
                "test setup expects the progress report to be accepted");
        assertNotNull(dataStore.completeGroup(runId, groupNumber, runnerKey, 6000L),
                "test setup expects the completion to be accepted");
    }

    /**
     * Claim a group for a runner key and wrap it in the context that runner carries to its persist.
     *
     * @param runId the run to claim from
     * @param runnerKey the identity to claim under
     * @return the claimed runner's context
     */
    private DistributedRunnerContext claim(final String runId, final String runnerKey) {
        DistributedRunGroup claimed = dataStore.claimNextPendingGroup(runId, runnerKey, 5000L);
        assertNotNull(claimed, "test setup expects a group to be available to claim");
        return DistributedRunnerContext.forClaimedGroup(runId, runnerKey, claimed.getGroupNumber());
    }

    /**
     * Store an existing full-suite baseline, standing in for the all-tests runs a project has
     * already recorded before the build under test.
     *
     * @param allTestsRunTimeMs the baseline to store, in ms
     */
    private void seedAllTestsBaseline(final long allTestsRunTimeMs) {
        TiaData tiaData = dataStore.getTiaCore();
        tiaData.getTestStats().setAllTestsRunTime(allTestsRunTimeMs);
        tiaData.getTestStats().setNumAllTestsRuns(1);
        tiaData.getTestStats().setNumRuns(1);
        dataStore.persistCoreData(tiaData);
    }

    /**
     * Store a number of tracked suites, some of which the developer disabled in source. These are
     * what the sealer measures the groups' coverage against when deciding whether the build was an
     * all-tests run.
     *
     * @param suiteCount how many suites to track
     * @param developerDisabledCount how many of them to flag as disabled by the developer
     */
    private void seedTrackedSuites(final int suiteCount, final int developerDisabledCount) {
        Map<String, TestSuiteTracker> tracked = new HashMap<>();
        for (int i = 0; i < suiteCount; i++) {
            TestSuiteTracker tracker = new TestSuiteTracker("com.example.Suite" + i + "Test");
            tracker.setClassesImpacted(Collections.singletonList(
                    new ClassImpactTracker("com/example/Source" + i + ".java",
                            new HashSet<>(Collections.singletonList(Integer.valueOf(100 + i))))));
            tracker.setDeveloperDisabled(i < developerDisabledCount);
            tracked.put(tracker.getName(), tracker);
        }
        dataStore.persistTestSuites(tracked);
    }

    /**
     * Build the result one runner of a two-group build reports: its own suite's coverage of one
     * method, and the full discovered suite set both runners see.
     *
     * @param suiteName the suite this runner executed
     * @param sourceFile the source file that suite covered
     * @param methodId the covered method's id
     * @param methodName the covered method's name
     * @return the result to hand to the persist
     */
    private TestRunResult runResultFor(final String suiteName, final String sourceFile,
                                       final int methodId, final String methodName) {
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        TestSuiteTracker tracker = new TestSuiteTracker(suiteName);
        tracker.setClassesImpacted(Collections.singletonList(new ClassImpactTracker(sourceFile,
                new HashSet<>(Collections.singletonList(Integer.valueOf(methodId))))));
        trackers.put(suiteName, tracker);

        Map<Integer, MethodImpactTracker> methodTrackers = new HashMap<>();
        methodTrackers.put(Integer.valueOf(methodId), new MethodImpactTracker(methodName, 1, 10));

        Set<String> runnerSuites = new HashSet<>(Arrays.asList("com.example.ATest", "com.example.BTest"));
        TestStats stats = new TestStats();
        stats.setNumRuns(1);
        return new TestRunResult(trackers, new HashSet<String>(), runnerSuites, runnerSuites,
                new HashSet<>(Collections.singletonList(suiteName)), methodTrackers, stats,
                null, 1, 1);
    }

    /**
     * A single-host history row keeps every distributed column null, so it stays exactly the row it
     * was before distributed runs existed and the two modes remain distinguishable in the report.
     */
    @Test
    void aSingleHostRunsRowKeepsEveryDistributedColumnNull() {
        // given
        TestRunnerService service = new TestRunnerService(dataStore);

        // when
        service.persistTestRunData(true, true, true, "new-commit", "main",
                System.currentTimeMillis(), runResultFor("com.example.ATest", "com/example/A.java",
                        101, "com/example/A.a.()V"), null);

        // then
        TestRunHistoryEntry entry = dataStore.readTestRunHistory().get(0);
        assertNull(entry.getRunId(), "a single-host row names no distributed run");
        assertNull(entry.getWallClockMs(), "a single-host row has no separate wall clock");
        assertNull(entry.getGroupCount(), "a single-host row has no groups");
    }
}
