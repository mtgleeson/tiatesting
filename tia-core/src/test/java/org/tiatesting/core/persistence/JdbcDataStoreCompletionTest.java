package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.DistributedRunStatus;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the four operations that close a distributed run against embedded H2:
 * {@link DataStore#reportGroupProgress(String, int, String, long, int, int, int)} (the progress
 * report, where {@code suitesRan}/{@code actualDurationMs} accumulate and {@code suitesFailed}/
 * {@code suitesObserved} replace), {@link DataStore#completeGroup(String, int, String, long)}
 * (the guarded status flip that is also the straggler protection and the completeness guard),
 * {@link DataStore#electSealer(String, String, long)} (the barrier) and
 * {@link DataStore#markDistributedRunSealed(String)}.
 *
 * <p>All three guards carry correctness rather than tidiness, so they get dedicated tests: the
 * completion's {@code status = 'CLAIMED' AND runner_key = ?} predicate is what tells a runner from
 * a superseded build that its claim is dead so it must not write; its {@code suites_observed >=
 * COUNT(*)} predicate is what stands in for the crash protection a JVM shutdown hook used to
 * provide, blocking a group that has not yet observed every suite it was assigned - deliberately
 * {@code suites_observed} and not {@code suites_ran}, since a suite the runner observed but
 * never executed (a class-level {@code @Disabled}, a Surefire/Gradle filter, a class deleted since
 * the last mapping run) must not block a group forever; and the election's {@code sealed_by IS
 * NULL AND NOT EXISTS (incomplete group)} predicate is what makes the sealer's {@code SELECT
 * DISTINCT} over the edge table see a complete method id set. See the "Sealing" and "Straggler
 * protection" material in the distributed test runs chapter of {@code WIKI.md}.
 */
class JdbcDataStoreCompletionTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database in a new temp directory and bootstrap its schema, so
     * each test starts from an isolated store with no distributed runs planned.
     *
     * @throws Exception if the temp directory cannot be created or schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-complete-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test"));
        dataStore.getTiaData(true);
    }

    /**
     * Close the data store so its embedded H2 database releases its file lock.
     */
    @AfterEach
    void tearDown() {
        if (dataStore != null) {
            dataStore.close();
        }
    }

    /**
     * Build and persist a plan with {@code groupCount} groups, each carrying one suite, so the
     * completion and election tests have a concrete run to advance through its lifecycle.
     *
     * @param runId the run identifier to plan under
     * @param groupCount how many groups to create
     */
    private void persistPlanWithGroups(String runId, int groupCount) {
        List<DistributedRunGroup> groups = new ArrayList<>();
        Map<Integer, List<String>> suites = new HashMap<>();
        for (int i = 0; i < groupCount; i++) {
            groups.add(DistributedRunGroup.pending(runId, i, 1000L));
            suites.put(i, Arrays.asList("com.example.Suite" + i + "Test"));
        }
        DistributedRun run = DistributedRun.open(runId, "main", "commit-1", groupCount, null,
                1000L * groupCount, 1234L);
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(run, groups, suites, null));
    }

    /**
     * Build and persist a single-group plan with {@code suiteCount} suites all assigned to that one
     * group, so the completeness guard has a concrete assigned count to compare
     * {@code suites_observed} against.
     *
     * @param runId the run identifier to plan under
     * @param suiteCount how many suites to assign to the run's one group
     */
    private void persistPlanWithOneGroupOfSuites(String runId, int suiteCount) {
        List<DistributedRunGroup> groups = new ArrayList<>();
        groups.add(DistributedRunGroup.pending(runId, 0, 1000L));
        List<String> suiteNames = new ArrayList<>();
        for (int i = 0; i < suiteCount; i++) {
            suiteNames.add("com.example.Suite" + i + "Test");
        }
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, suiteNames);
        DistributedRun run = DistributedRun.open(runId, "main", "commit-1", 1, null, 1000L, 1234L);
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(run, groups, suites, null));
    }

    /**
     * Read one group back from the store by number, so a test can assert what the guarded update
     * actually left on disk rather than trusting the value the call returned.
     *
     * @param runId the run identifier
     * @param groupNumber the group's zero-based index within the run
     * @return the stored group, or null when the run has no such group
     */
    private DistributedRunGroup storedGroup(String runId, int groupNumber) {
        for (DistributedRunGroup group : dataStore.readDistributedRunGroups(runId)) {
            if (group.getGroupNumber() == groupNumber) {
                return group;
            }
        }
        return null;
    }

    /**
     * Verify the happy path: the runner holding a live claim reports its progress, then completes
     * its group, and the stored row gains {@code COMPLETED}, the completion timestamp, the measured
     * duration and both suite counters - the figures the sealer later aggregates into the build's
     * single history row.
     */
    @Test
    void shouldRecordDurationAndCountersWhenTheClaimIsStillLive() {
        // given
        persistPlanWithGroups("run-1", 2);
        dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);
        assertTrue(dataStore.reportGroupProgress("run-1", 0, "runner-a", 4321L, 7, 2, 7));

        // when
        DistributedRunGroup completed = dataStore.completeGroup("run-1", 0, "runner-a", 9000L);

        // then
        assertNotNull(completed);
        assertEquals(DistributedRunGroupStatus.COMPLETED, completed.getStatus());
        assertEquals("runner-a", completed.getRunnerKey());
        assertEquals(Long.valueOf(9000L), completed.getCompletedAtMs());
        assertEquals(Long.valueOf(4321L), completed.getActualDurationMs());
        assertEquals(7, completed.getSuitesRan());
        assertEquals(2, completed.getSuitesFailed());
        assertEquals(7, completed.getSuitesObserved());
        assertEquals(completed, storedGroup("run-1", 0));
    }

    /**
     * Verify the straggler guard: a runner completing a group another runner holds gets
     * {@code null} and leaves the stored row exactly as the holder left it. This is the signal that
     * tells the caller its claim is dead and it must skip its mapping writes, so the call must not
     * degrade into an unconditional update.
     */
    @Test
    void shouldReturnNullAndWriteNothingWhenAnotherRunnerHoldsTheGroup() {
        // given
        persistPlanWithGroups("run-1", 2);
        dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);

        // when
        DistributedRunGroup completed = dataStore.completeGroup("run-1", 0, "runner-b", 9000L);

        // then
        assertNull(completed);
        DistributedRunGroup stored = storedGroup("run-1", 0);
        assertEquals(DistributedRunGroupStatus.CLAIMED, stored.getStatus());
        assertEquals("runner-a", stored.getRunnerKey());
        assertNull(stored.getCompletedAtMs());
        assertNull(stored.getActualDurationMs());
        assertEquals(0, stored.getSuitesRan());
        assertEquals(0, stored.getSuitesFailed());
    }

    /**
     * Verify that a progress report on a group another runner holds gets {@code false} and leaves
     * the stored row untouched, mirroring the straggler guard on the completion itself.
     */
    @Test
    void shouldReturnFalseAndWriteNothingWhenAnotherRunnerHoldsTheGroupOnProgress() {
        // given
        persistPlanWithGroups("run-1", 2);
        dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);

        // when
        boolean applied = dataStore.reportGroupProgress("run-1", 0, "runner-b", 4321L, 7, 2, 7);

        // then
        assertFalse(applied);
        DistributedRunGroup stored = storedGroup("run-1", 0);
        assertEquals(0, stored.getSuitesRan());
        assertNull(stored.getActualDurationMs());
    }

    /**
     * The {@code GREATEST} behaviour {@code JdbcDataStore.java}'s {@code reportGroupProgress} SQL
     * relies on, isolated from every other test in this class: every existing progress test feeds a
     * non-decreasing observed count across calls, so reverting the column write from {@code
     * GREATEST(COALESCE(suites_observed, 0), ?)} to a plain {@code = ?} would still pass the whole
     * suite. A smaller observed count following a larger one - the shape a late-arriving report with
     * a shrunk view of the JVM's cumulative observed set could produce - must leave the larger,
     * already-stored value in place rather than regress it.
     */
    @Test
    void shouldNotLowerTheStoredObservedCountWhenALaterReportIsSmaller() {
        // given - the first report observes 49 of the group's 50 assigned suites
        persistPlanWithOneGroupOfSuites("run-1", 50);
        dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);
        assertTrue(dataStore.reportGroupProgress("run-1", 0, "runner-a", 40_000L, 49, 0, 49));

        // when - a later report in the same JVM carries a smaller observed count
        assertTrue(dataStore.reportGroupProgress("run-1", 0, "runner-a", 1_000L, 1, 0, 30));

        // then
        assertEquals(49, storedGroup("run-1", 0).getSuitesObserved(),
                "a smaller later report must not regress the stored observed count below the "
                        + "larger value an earlier report already established");
    }

    /**
     * Verify that completing a group that already reached {@code COMPLETED} returns {@code null}
     * and leaves the first completion's figures untouched, so a duplicate completion (a retried
     * process finishing a group its predecessor already finished) can never overwrite the recorded
     * duration and counters or release the barrier a second time.
     */
    @Test
    void shouldReturnNullWhenTheGroupIsAlreadyCompleted() {
        // given
        persistPlanWithGroups("run-1", 2);
        dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);
        dataStore.reportGroupProgress("run-1", 0, "runner-a", 4321L, 7, 2, 7);
        dataStore.completeGroup("run-1", 0, "runner-a", 9000L);

        // when
        DistributedRunGroup secondCompletion = dataStore.completeGroup("run-1", 0, "runner-a", 9999L);

        // then
        assertNull(secondCompletion);
        DistributedRunGroup stored = storedGroup("run-1", 0);
        assertEquals(Long.valueOf(9000L), stored.getCompletedAtMs());
        assertEquals(Long.valueOf(4321L), stored.getActualDurationMs());
        assertEquals(7, stored.getSuitesRan());
    }

    /**
     * Verify that completing a group of a run nobody planned returns {@code null} rather than
     * throwing - the shape a runner from a superseded build sees once the superseding plan write
     * cleared the tables out from under it.
     */
    @Test
    void shouldReturnNullForAnUnknownRun() {
        // given
        persistPlanWithGroups("run-1", 2);

        // when
        DistributedRunGroup completed = dataStore.completeGroup("run-does-not-exist", 0, "runner-a",
                9000L);

        // then
        assertNull(completed);
    }

    /**
     * Verify the barrier holds while any group is still outstanding: with one group complete and
     * one still {@code CLAIMED}, the election returns false and writes no seal, so no runner can
     * rebuild the method catalogue from an edge set that is still missing the outstanding group's
     * rows.
     */
    @Test
    void shouldNotElectASealerWhileAnyGroupIsIncomplete() {
        // given
        persistPlanWithGroups("run-1", 2);
        dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);
        dataStore.claimNextPendingGroup("run-1", "runner-b", 5100L);
        dataStore.reportGroupProgress("run-1", 0, "runner-a", 100L, 1, 0, 1);
        dataStore.completeGroup("run-1", 0, "runner-a", 9000L);

        // when
        boolean elected = dataStore.electSealer("run-1", "runner-a", 9500L);

        // then
        assertFalse(elected);
        DistributedRun run = dataStore.readDistributedRun("run-1");
        assertNull(run.getSealedBy());
        assertNull(run.getSealedAtMs());
    }

    /**
     * Verify the barrier releases exactly once every group has completed: the election returns true
     * and stamps the winning runner's key and timestamp onto the run row.
     */
    @Test
    void shouldElectASealerOnceEveryGroupIsComplete() {
        // given
        persistPlanWithGroups("run-1", 2);
        dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);
        dataStore.claimNextPendingGroup("run-1", "runner-b", 5100L);
        dataStore.reportGroupProgress("run-1", 0, "runner-a", 100L, 1, 0, 1);
        dataStore.completeGroup("run-1", 0, "runner-a", 9000L);
        dataStore.reportGroupProgress("run-1", 1, "runner-b", 200L, 1, 0, 1);
        dataStore.completeGroup("run-1", 1, "runner-b", 9100L);

        // when
        boolean elected = dataStore.electSealer("run-1", "runner-b", 9500L);

        // then
        assertTrue(elected);
        DistributedRun run = dataStore.readDistributedRun("run-1");
        assertEquals("runner-b", run.getSealedBy());
        assertEquals(Long.valueOf(9500L), run.getSealedAtMs());
    }

    /**
     * Verify that a second candidate arriving after a winner already exists loses, and that the
     * losing attempt does not overwrite the winner's identity - the run must have exactly one
     * sealer, since the seal rebuilds the whole catalogue in one transaction.
     */
    @Test
    void shouldRefuseASecondSealerAfterAWinnerExists() {
        // given
        persistPlanWithGroups("run-1", 2);
        dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);
        dataStore.claimNextPendingGroup("run-1", "runner-b", 5100L);
        dataStore.reportGroupProgress("run-1", 0, "runner-a", 100L, 1, 0, 1);
        dataStore.completeGroup("run-1", 0, "runner-a", 9000L);
        dataStore.reportGroupProgress("run-1", 1, "runner-b", 200L, 1, 0, 1);
        dataStore.completeGroup("run-1", 1, "runner-b", 9100L);
        dataStore.electSealer("run-1", "runner-a", 9500L);

        // when
        boolean elected = dataStore.electSealer("run-1", "runner-b", 9600L);

        // then
        assertFalse(elected);
        DistributedRun run = dataStore.readDistributedRun("run-1");
        assertEquals("runner-a", run.getSealedBy());
        assertEquals(Long.valueOf(9500L), run.getSealedAtMs());
    }

    /**
     * Case 1 of the completeness guard: a group that observed (and ran) every suite it was
     * assigned completes. The ordinary case, and the baseline the other cases are measured against.
     */
    @Test
    void shouldCompleteAGroupThatRanEveryAssignedSuite() {
        // given - 50 assigned suites, all 50 reported as run and observed
        persistPlanWithOneGroupOfSuites("run-1", 50);
        dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);
        assertTrue(dataStore.reportGroupProgress("run-1", 0, "runner-a", 40_000L, 50, 0, 50));

        // when
        DistributedRunGroup completed = dataStore.completeGroup("run-1", 0, "runner-a", 9000L);

        // then
        assertNotNull(completed, "a group that ran every assigned suite must complete");
        assertEquals(50, completed.getSuitesRan());
    }

    /**
     * <b>The case that motivates the completeness guard reading {@code suites_observed} rather
     * than {@code suites_ran}.</b> A class-level {@code @Disabled} suite (or one excluded by a
     * Surefire/Gradle filter, or a class deleted since the last mapping run) is observed by the
     * runner but never finishes, so {@code suites_ran} under-counts against the plan's assigned
     * total even though nothing is actually missing. Guarding on {@code suites_ran} would block
     * this group forever - every build re-running everything, with the stored commit never
     * advancing. Guarding on {@code suites_observed} lets it complete correctly.
     */
    @Test
    void shouldCompleteAGroupThatObservedEveryAssignedSuiteEvenThoughItExecutedFewer() {
        // given - 50 assigned suites, all 50 observed but only 49 executed (one @Disabled suite)
        persistPlanWithOneGroupOfSuites("run-1", 50);
        dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);
        assertTrue(dataStore.reportGroupProgress("run-1", 0, "runner-a", 40_000L, 49, 0, 50));

        // when
        DistributedRunGroup completed = dataStore.completeGroup("run-1", 0, "runner-a", 9000L);

        // then
        assertNotNull(completed, "a group that observed every assigned suite must complete even "
                + "when it executed fewer of them, e.g. one was @Disabled");
        assertEquals(49, completed.getSuitesRan(),
                "the executed count stored for the sealer must still reflect only what actually ran");
        assertEquals(50, completed.getSuitesObserved());
    }

    /**
     * Case 2 of the completeness guard: attempt 1 reports all 50 assigned suites with 3 failures,
     * then a retry starts and its JVM dies before reporting anything further. The guard sees
     * {@code 50 >= 50} from attempt 1's own report and completes anyway - the 3 failures stay in
     * {@code suites_failed} exactly as attempt 1 left them, so the next build re-runs them
     * regardless of its diff. Nothing is lost and the error is conservative; this case must
     * complete.
     */
    @Test
    void shouldCompleteWhenARetryDiesAfterAttemptOneAlreadyReportedEveryAssignedSuite() {
        // given - attempt 1 reports every assigned suite with 3 failures; no further report follows
        persistPlanWithOneGroupOfSuites("run-1", 50);
        dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);
        assertTrue(dataStore.reportGroupProgress("run-1", 0, "runner-a", 40_000L, 50, 3, 50));

        // when
        DistributedRunGroup completed = dataStore.completeGroup("run-1", 0, "runner-a", 9000L);

        // then
        assertNotNull(completed, "attempt 1 already satisfied the completeness guard, so a dead "
                + "retry must not block the completion");
        assertEquals(3, completed.getSuitesFailed(),
                "the 3 failures attempt 1 reported must survive so the next build re-runs them");
    }

    /**
     * Case 3 of the completeness guard: a JVM that dies before making any persist reports nothing
     * at all, so {@code suites_observed} stays at its planned default of 0. {@code 0 < 50} blocks
     * the completion.
     */
    @Test
    void shouldBlockCompletionWhenNoProgressWasEverReported() {
        // given - 50 assigned suites, no reportGroupProgress call at all
        persistPlanWithOneGroupOfSuites("run-1", 50);
        dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);

        // when
        DistributedRunGroup completed = dataStore.completeGroup("run-1", 0, "runner-a", 9000L);

        // then
        assertNull(completed, "a group with no reported progress must not be completable");
        assertEquals(DistributedRunGroupStatus.CLAIMED, storedGroup("run-1", 0).getStatus());
    }

    /**
     * Case 4 of the completeness guard: a Gradle worker that dies partway through its group reports
     * only the suites it observed before dying. {@code 30 < 50} blocks the completion.
     */
    @Test
    void shouldBlockCompletionWhenOnlyPartOfTheGroupWasReported() {
        // given - 50 assigned suites, a report covering only 30 of them
        persistPlanWithOneGroupOfSuites("run-1", 50);
        dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);
        assertTrue(dataStore.reportGroupProgress("run-1", 0, "runner-a", 24_000L, 30, 0, 30));

        // when
        DistributedRunGroup completed = dataStore.completeGroup("run-1", 0, "runner-a", 9000L);

        // then
        assertNull(completed, "a group that has reported only part of its assigned suites must not "
                + "be completable");
        assertEquals(30, storedGroup("run-1", 0).getSuitesRan(),
                "the partial report must still be stored, even though it did not complete the group");
    }

    /**
     * A group with no suites assigned at all - the degenerate plan shape - completes on
     * {@code 0 >= 0} without ever needing a progress report. Documents that the guard's {@code >=}
     * is deliberately not a {@code >}.
     */
    @Test
    void shouldCompleteAGroupWithNoAssignedSuites() {
        // given
        persistPlanWithOneGroupOfSuites("run-1", 0);
        dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);

        // when
        DistributedRunGroup completed = dataStore.completeGroup("run-1", 0, "runner-a", 9000L);

        // then
        assertNotNull(completed, "a group with zero assigned suites must complete on 0 >= 0");
    }

    /**
     * Verify that a runner whose run row no longer exists loses the election rather than winning by
     * default. This is the straggler sealer case and the one the whole row-count contract exists
     * for: a superseded run's tables were cleared by the superseding plan write, so a straggler
     * that believed it had won would read an empty staging table, resolve every method id from disk
     * and drop from the catalogue every id the disk catalogue lacks - silent under-selection.
     */
    @Test
    void shouldReturnFalseWhenTheRunRowIsAbsent() {
        // given
        persistPlanWithGroups("run-1", 1);

        // when
        boolean elected = dataStore.electSealer("run-does-not-exist", "runner-a", 9500L);

        // then
        assertFalse(elected);
    }

    /**
     * Verify that marking a run sealed flips its status to {@code SEALED}, the terminal state the
     * planner inspects when deciding whether the previous build finished.
     */
    @Test
    void shouldFlipTheRunToSealed() {
        // given
        persistPlanWithGroups("run-1", 1);
        assertEquals(DistributedRunStatus.OPEN, dataStore.readDistributedRun("run-1").getStatus());

        // when
        dataStore.markDistributedRunSealed("run-1");

        // then
        assertEquals(DistributedRunStatus.SEALED, dataStore.readDistributedRun("run-1").getStatus());
    }

    /**
     * Verify that marking a run nobody planned as sealed is a no-op rather than a failure, so a
     * straggler sealer that lost its run to a superseding plan write does not turn a lost election
     * into a crashed build.
     */
    @Test
    void shouldIgnoreMarkingAnUnknownRunSealed() {
        // given
        persistPlanWithGroups("run-1", 1);

        // when
        dataStore.markDistributedRunSealed("run-does-not-exist");

        // then
        assertEquals(DistributedRunStatus.OPEN, dataStore.readDistributedRun("run-1").getStatus());
    }

    /**
     * The point of the barrier: every runner in a build finishes its own group and then races to
     * elect itself sealer, so the election is contended by construction rather than by accident.
     * Runs {@code runnerCount} threads all released together via a {@link CyclicBarrier} against
     * the single shared file-backed H2 database this class opens in {@code setUp}, and asserts that
     * exactly one of them wins and that the run row names a runner that actually competed.
     *
     * <p>A sequential pair of calls cannot demonstrate this: it never gives two callers the chance
     * to observe {@code sealed_by IS NULL} before either has written back, which is exactly the
     * race the predicate on the election's single-row UPDATE exists to resolve. Each thread opens
     * its own JDBC connection (via the {@link JdbcDataStore#getConnection()} call inside
     * {@code electSealer}) to the same underlying H2 engine instance, so the contention is real
     * database-level contention.
     *
     * @throws Exception if the executor is interrupted or an election call throws
     */
    @Test
    void concurrentSealerElectionsAcrossThreadsProduceExactlyOneWinner() throws Exception {
        // given
        int runnerCount = 8;
        String runId = "concurrent-seal-run";
        persistPlanWithGroups(runId, runnerCount);
        for (int i = 0; i < runnerCount; i++) {
            dataStore.claimNextPendingGroup(runId, "runner-" + i, 5000L);
        }
        for (int i = 0; i < runnerCount; i++) {
            DistributedRunGroup claimed = storedGroup(runId, i);
            assertTrue(dataStore.reportGroupProgress(runId, i, claimed.getRunnerKey(), 100L, 1, 0, 1));
            assertNotNull(dataStore.completeGroup(runId, i, claimed.getRunnerKey(), 9000L));
        }

        ExecutorService executor = Executors.newFixedThreadPool(runnerCount);
        CyclicBarrier barrier = new CyclicBarrier(runnerCount);
        List<Callable<Boolean>> jobs = new ArrayList<>();
        for (int i = 0; i < runnerCount; i++) {
            final String runnerKey = "runner-" + i;
            jobs.add(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    barrier.await();
                    return dataStore.electSealer(runId, runnerKey, 9500L);
                }
            });
        }

        // when
        List<Future<Boolean>> futures = executor.invokeAll(jobs);
        executor.shutdown();

        // then
        int winners = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                winners++;
            }
        }
        assertEquals(1, winners, "exactly one of the " + runnerCount
                + " racing runners must win the sealer election");
        DistributedRun run = dataStore.readDistributedRun(runId);
        assertTrue(run.getSealedBy() != null && run.getSealedBy().startsWith("runner-"),
                "the run row must name the runner that won, not be left unsealed");
    }
}
