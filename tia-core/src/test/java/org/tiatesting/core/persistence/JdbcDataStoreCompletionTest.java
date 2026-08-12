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
 * Cover the three operations that close a distributed run against embedded H2:
 * {@link DataStore#completeGroup(String, int, String, long, long, int, int)} (the guarded group
 * completion that is also the straggler protection),
 * {@link DataStore#electSealer(String, String, long)} (the barrier) and
 * {@link DataStore#markDistributedRunSealed(String)}.
 *
 * <p>Both guards carry correctness rather than tidiness, so they get dedicated tests: the
 * completion's {@code status = 'CLAIMED' AND runner_key = ?} predicate is what tells a runner from
 * a superseded build that its claim is dead so it must not write, and the election's
 * {@code sealed_by IS NULL AND NOT EXISTS (incomplete group)} predicate is what makes the sealer's
 * {@code SELECT DISTINCT} over the edge table see a complete method id set. See the "Sealing" and
 * "Straggler protection" material in the distributed test runs chapter of {@code WIKI.md}.
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
     * Verify the happy path: the runner holding a live claim completes its group and the stored row
     * gains {@code COMPLETED}, the completion timestamp, the measured duration and both suite
     * counters - the figures the sealer later aggregates into the build's single history row.
     */
    @Test
    void shouldRecordDurationAndCountersWhenTheClaimIsStillLive() {
        // given
        persistPlanWithGroups("run-1", 2);
        dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);

        // when
        DistributedRunGroup completed = dataStore.completeGroup("run-1", 0, "runner-a", 9000L,
                4321L, 7, 2);

        // then
        assertNotNull(completed);
        assertEquals(DistributedRunGroupStatus.COMPLETED, completed.getStatus());
        assertEquals("runner-a", completed.getRunnerKey());
        assertEquals(Long.valueOf(9000L), completed.getCompletedAtMs());
        assertEquals(Long.valueOf(4321L), completed.getActualDurationMs());
        assertEquals(7, completed.getSuitesRan());
        assertEquals(2, completed.getSuitesFailed());
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
        DistributedRunGroup completed = dataStore.completeGroup("run-1", 0, "runner-b", 9000L,
                4321L, 7, 2);

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
        dataStore.completeGroup("run-1", 0, "runner-a", 9000L, 4321L, 7, 2);

        // when
        DistributedRunGroup secondCompletion = dataStore.completeGroup("run-1", 0, "runner-a",
                9999L, 1L, 1, 1);

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
                9000L, 4321L, 7, 2);

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
        dataStore.completeGroup("run-1", 0, "runner-a", 9000L, 100L, 1, 0);

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
        dataStore.completeGroup("run-1", 0, "runner-a", 9000L, 100L, 1, 0);
        dataStore.completeGroup("run-1", 1, "runner-b", 9100L, 200L, 1, 0);

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
        dataStore.completeGroup("run-1", 0, "runner-a", 9000L, 100L, 1, 0);
        dataStore.completeGroup("run-1", 1, "runner-b", 9100L, 200L, 1, 0);
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
            assertNotNull(dataStore.completeGroup(runId, i, claimed.getRunnerKey(), 9000L, 100L, 1, 0));
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
