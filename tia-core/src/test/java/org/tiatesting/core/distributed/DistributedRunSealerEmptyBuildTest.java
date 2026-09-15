package org.tiatesting.core.distributed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.DistributedRunStatus;
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

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The distributed half of the empty-run guard: a build whose groups between them executed no test
 * suite, though the plan assigned suites for them to run, is the same misconfiguration a single-host
 * run hits - and the sealer is the only place it can be recognised, since the "expected" half of the
 * question is a property of the plan rather than of any one runner.
 *
 * <p>Such a build seals nothing: no commit advance, no catalogue rebuild, no library drain cleanup, no
 * stats, no savings. It still writes its one history row and the run is still retired, so the build
 * stays visible and the barrier state does not linger.
 *
 * <p>The opposite build - a real selection that chose no suites, so the plan assigned none and nothing
 * was expected to run - is untouched by the guard and keeps recording its stats and savings. Only the
 * planner's seed-run flag separates the two empty-assignment shapes, which is why the flag is read
 * here rather than inferred.
 *
 * <p><b>How a build with suites assigned reaches the sealer having run none of them.</b> The
 * completion guard reads each group's <em>observed</em> suites, not its executed ones, so a runner
 * that saw every assigned suite get skipped - an engine that discovers classes but executes nothing -
 * completes its group and the build seals normally with {@code suitesRan == 0}. A runner that observed
 * nothing at all never closes its group, so that shape never reaches the sealer: the barrier holds and
 * the run is left open for the next build's plan write to clear. The fixtures below report the two
 * counts separately for that reason.
 */
class DistributedRunSealerEmptyBuildTest {

    private static final String RUN_ID = "run-1";
    private static final String RUNNER_A = "runner-a";
    private static final String RUNNER_B = "runner-b";
    private static final String PLAN_COMMIT = "plan-commit";
    private static final String LIB = "com.example:lib";
    private static final long PLANNED_AT_MS = 1_700_000_000_000L;
    private static final long BASELINE_MS = 900L;

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 store with a known prior commit, so an advance of the stored commit
     * is visible as a change.
     *
     * @throws Exception if the temp directory cannot be created or the schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-distributed-sealer-empty-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
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
     * A build that was assigned suites and executed none of them records no stats: not a run, not a
     * success, and nothing folded into either average.
     */
    @Test
    void aBuildAssignedSuitesThatRanNoneRecordsNoStats() {
        // given - four tracked suites split across two groups, and neither group ran anything
        seedTrackedSuites(4);
        seedAllTestsBaseline(BASELINE_MS);
        persistPlan(RUN_ID, Arrays.asList(trackedSuiteNames(0, 2), trackedSuiteNames(2, 4)));
        completeGroup(RUN_ID, 0, RUNNER_A, 80L, 0, 2, 0);
        completeGroup(RUN_ID, 1, RUNNER_B, 72L, 0, 2, 0);

        // when
        sealerFor(RUNNER_B, 1).sealIfElected(true, true, 9000L);

        // then - only the baseline seeded in setup remains, and no run was counted
        TestStats stats = dataStore.getTiaCore().getTestStats();
        assertEquals(BASELINE_MS, stats.getAllTestsRunTime());
        assertEquals(1L, stats.getNumRuns(), "the seeded run only - the empty build is not a run");
        assertEquals(0L, stats.getNumSuccessRuns(), "an empty build is not a success");
        assertEquals(0L, stats.getAvgRunTime(), "nor does its duration move the selected average");
    }

    /**
     * A seed run that executed nothing is the damaging shape: with nothing ignored it would otherwise
     * become the full-suite baseline every later savings figure is measured against.
     */
    @Test
    void aSeedRunThatRanNoSuitesDoesNotEstablishTheBaseline() {
        // given - the single empty-assignment group a seed run plans, which ran nothing
        seedTrackedSuites(4);
        persistSeedRunPlan(RUN_ID);
        completeGroup(RUN_ID, 0, RUNNER_A, 152L, 0, 0, 0);

        // when
        sealerFor(RUNNER_A, 0).sealIfElected(true, true, 9000L);

        // then
        TestStats stats = dataStore.getTiaCore().getTestStats();
        assertEquals(0L, stats.getAllTestsRunTime(), "an empty seed run must not set the baseline");
        assertEquals(0L, stats.getNumAllTestsRuns());
    }

    /**
     * The stored commit must stay where it was. Advancing it would leave the next build diffing past
     * the suites this build never covered, which is silent under-selection.
     */
    @Test
    void anEmptyBuildDoesNotAdvanceTheStoredCommitOrTheLibraryBaseline() {
        // given - a tracked library and a two-group build that ran nothing
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));
        seedTrackedSuites(4);
        persistPlan(RUN_ID, Arrays.asList(trackedSuiteNames(0, 2), trackedSuiteNames(2, 4)));
        completeGroup(RUN_ID, 0, RUNNER_A, 80L, 0, 2, 0);
        completeGroup(RUN_ID, 1, RUNNER_B, 72L, 0, 2, 0);

        // when
        sealerFor(RUNNER_B, 1).sealIfElected(true, true, 9000L);

        // then
        assertEquals("prior-commit", dataStore.getTiaCore().getCommitValue());
        assertEquals("prior-branch", dataStore.getTiaCore().getBranch());
        assertNull(dataStore.readTrackedLibraries().get(LIB).getMappingBaselineCommit(),
                "an empty build re-covered nothing, so no library baseline may advance");
    }

    /**
     * The run is still retired and still gets its one history row: an empty build that left the
     * barrier state behind would block the next build, and a build with no row at all would be an
     * unexplained gap in the history.
     */
    @Test
    void anEmptyBuildIsStillRetiredAndStillWritesItsHistoryRow() {
        // given
        seedTrackedSuites(4);
        seedAllTestsBaseline(BASELINE_MS);
        persistPlan(RUN_ID, Arrays.asList(trackedSuiteNames(0, 2), trackedSuiteNames(2, 4)));
        completeGroup(RUN_ID, 0, RUNNER_A, 80L, 0, 2, 0);
        completeGroup(RUN_ID, 1, RUNNER_B, 72L, 0, 2, 0);

        // when
        boolean sealed = sealerFor(RUNNER_B, 1).sealIfElected(true, true, 9000L);

        // then
        assertTrue(sealed, "the elected runner still performs the seal pass, it just writes no seal");
        assertEquals(DistributedRunStatus.SEALED, dataStore.readDistributedRun(RUN_ID).getStatus(),
                "the run must still be retired, or its barrier state would block the next build");

        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
        assertEquals(1, history.size());
        TestRunHistoryEntry row = history.get(0);
        assertEquals(0, row.getNumSuitesRan());
        assertEquals(0L, row.getTimeSavingsMs(), "an empty build saved nothing");
        assertEquals(0, row.getSavingsPercent());
        assertFalse(row.isUpdatedDbMapping(),
                "nothing was sealed, so the row must not claim a mapping update");
    }

    /**
     * The other side of the line: a build whose plan assigned no suites because the selection chose
     * none expected to run nothing, so running nothing is Tia working as intended. Its stats and its
     * savings are recorded exactly as before.
     */
    @Test
    void aNothingImpactedBuildStillRecordsItsStatsAndSavings() {
        // given - the three-group, no-assignment plan a selection that chose nothing produces
        seedTrackedSuites(4);
        seedAllTestsBaseline(BASELINE_MS);
        persistPlan(RUN_ID, Arrays.asList(Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList()));
        completeGroup(RUN_ID, 0, RUNNER_A, 40L, 0, 0, 0);
        completeGroup(RUN_ID, 1, RUNNER_B, 40L, 0, 0, 0);
        completeGroup(RUN_ID, 2, "runner-c", 40L, 0, 0, 0);

        // when
        sealerFor("runner-c", 2).sealIfElected(true, true, 9000L);

        // then
        TestStats stats = dataStore.getTiaCore().getTestStats();
        assertEquals(2L, stats.getNumRuns(), "the seeded run plus this one");
        assertEquals(1L, stats.getNumSuccessRuns(), "no suite failed, so the build succeeded");
        assertEquals("plan-commit", dataStore.getTiaCore().getCommitValue(),
                "a build that legitimately ran nothing still seals its commit");
        assertTrue(dataStore.readTestRunHistory().get(0).getTimeSavingsMs() > 0,
                "deselecting every suite is a real saving");
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
     * Build and persist a distributed run plan whose groups are assigned the given suite names.
     *
     * @param runId the run identifier to plan under
     * @param suitesByGroup the suite names to assign, one list per group, in group-number order
     */
    private void persistPlan(final String runId, final List<List<String>> suitesByGroup) {
        persistPlanOfKind(runId, suitesByGroup, false);
    }

    /**
     * Build and persist the plan a seed run produces: one group carrying no suite names, with the run
     * row's seed-run flag set as the planner sets it. Only that flag separates it from the
     * nothing-impacted plan, which means the opposite thing.
     *
     * @param runId the run identifier to plan under
     */
    private void persistSeedRunPlan(final String runId) {
        persistPlanOfKind(runId, Collections.singletonList(Collections.<String>emptyList()), true);
    }

    /**
     * Build and persist a distributed run plan, recording whether the planner produced it as a seed
     * run.
     *
     * @param runId the run identifier to plan under
     * @param suitesByGroup the suite names to assign, one list per group, in group-number order
     * @param seedRun whether the run row records this plan as a seed run
     */
    private void persistPlanOfKind(final String runId, final List<List<String>> suitesByGroup,
                                   final boolean seedRun) {
        int groupCount = suitesByGroup.size();
        List<DistributedRunGroup> groups = new ArrayList<>();
        Map<Integer, List<String>> suites = new HashMap<>();
        for (int i = 0; i < groupCount; i++) {
            groups.add(DistributedRunGroup.pending(runId, i, 1000L));
            suites.put(i, suitesByGroup.get(i));
        }
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(
                DistributedRun.open(runId, "main", PLAN_COMMIT, groupCount, null,
                        1000L * groupCount, PLANNED_AT_MS, seedRun), groups, suites, null));
    }

    /**
     * The names {@link #seedTrackedSuites} tracks, over a half-open index range, so a test can assign
     * a plan exactly the tracked suites it means the build to have been given.
     *
     * @param fromIndex the first suite index to name, inclusive
     * @param toIndex the last suite index to name, exclusive
     * @return the tracked suite names in that range
     */
    private static List<String> trackedSuiteNames(final int fromIndex, final int toIndex) {
        List<String> names = new ArrayList<>();
        for (int i = fromIndex; i < toIndex; i++) {
            names.add("com.example.Suite" + i + "Test");
        }
        return names;
    }

    /**
     * Claim a group, report the measurements a real runner would report, and complete it.
     *
     * <p>{@code suitesObserved} is passed separately from {@code suitesRan} because on an empty build
     * the two come apart, and only one of them lets the group close: the completion guard reads
     * observed, so a group assigned suites completes once its runner has <em>seen</em> each of them,
     * whether it executed them or skipped them. That is what makes the sealer reachable with
     * {@code suitesRan == 0}.
     *
     * @param runId the run the group belongs to
     * @param groupNumber the group to claim and complete
     * @param runnerKey the identity to claim under
     * @param actualDurationMs the group's measured test-execution time
     * @param suitesRan the number of suites the runner executed
     * @param suitesObserved the number of its assigned suites the runner saw finish or saw skipped
     * @param suitesFailed the number of the runner's suites with at least one failed test
     */
    private void completeGroup(final String runId, final int groupNumber, final String runnerKey,
                               final long actualDurationMs, final int suitesRan,
                               final int suitesObserved, final int suitesFailed) {
        DistributedRunGroup claimed = dataStore.claimNextPendingGroup(runId, runnerKey, 5000L);
        assertNotNull(claimed, "test setup expects a group to be available to claim");
        assertEquals(groupNumber, claimed.getGroupNumber(),
                "test setup expects the groups to be claimed in order");
        assertTrue(dataStore.reportGroupProgress(runId, groupNumber, runnerKey, actualDurationMs,
                suitesRan, suitesFailed, suitesObserved, 0L),
                "test setup expects the progress report to be accepted");
        assertNotNull(dataStore.completeGroup(runId, groupNumber, runnerKey, 6000L),
                "test setup expects the completion to be accepted");
    }

    /**
     * Store an existing full-suite baseline, standing in for the all-tests runs a project recorded
     * before the build under test.
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
     * Store a number of tracked suites for the sealer to measure the groups' coverage against.
     *
     * @param suiteCount how many suites to track
     */
    private void seedTrackedSuites(final int suiteCount) {
        Map<String, TestSuiteTracker> tracked = new HashMap<>();
        for (int i = 0; i < suiteCount; i++) {
            TestSuiteTracker tracker = new TestSuiteTracker("com.example.Suite" + i + "Test");
            tracker.setClassesImpacted(Collections.singletonList(
                    new ClassImpactTracker("com/example/Source" + i + ".java",
                            new HashSet<>(Collections.singletonList(Integer.valueOf(100 + i))))));
            tracked.put(tracker.getName(), tracker);
        }
        dataStore.persistTestSuites(tracked);
    }
}
