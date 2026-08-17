package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover {@link DataStore#claimNextPendingGroup(String, String, long)} against embedded H2: the
 * sequential claim protocol (fresh plan, second runner, exhaustion, retry-reclaims-its-own-group,
 * unknown run) and, separately, a genuine concurrency test proving no two racing runners can ever
 * claim the same group and no group is left unclaimed while groups remain. See the "Distributed
 * test runs" chapter in {@code WIKI.md} for the protocol this exercises.
 */
class JdbcDataStoreClaimTest {

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
        tempDir = File.createTempFile("tia-claim-", "");
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
     * Build and persist a plan with {@code groupCount} groups, each carrying one suite, so claim
     * tests have a concrete plan to claim from.
     *
     * @param runId the run identifier to plan under
     * @param groupCount how many groups to create
     * @return the persisted plan's groups, in group-number order
     */
    private List<DistributedRunGroup> persistPlanWithGroups(String runId, int groupCount) {
        List<DistributedRunGroup> groups = new ArrayList<>();
        Map<Integer, List<String>> suites = new HashMap<>();
        for (int i = 0; i < groupCount; i++) {
            groups.add(DistributedRunGroup.pending(runId, i, 1000L));
            suites.put(i, Arrays.asList("com.example.Suite" + i + "Test"));
        }
        DistributedRun run = DistributedRun.open(runId, "main", "commit-1", groupCount, null,
                1000L * groupCount, 1234L, false);
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(run, groups, suites, null));
        return groups;
    }

    /**
     * Verify that claiming from a freshly-planned run returns group 0, marked {@code CLAIMED} with
     * the calling runner's key and the supplied claim timestamp - proving the lowest-numbered
     * {@code PENDING} group is the first one handed out and the write lands correctly.
     */
    @Test
    void shouldClaimGroupZeroFromAFreshPlan() {
        // given
        persistPlanWithGroups("run-1", 2);

        // when
        DistributedRunGroup claimed = dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);

        // then
        assertNotNull(claimed);
        assertEquals(0, claimed.getGroupNumber());
        assertEquals(DistributedRunGroupStatus.CLAIMED, claimed.getStatus());
        assertEquals("runner-a", claimed.getRunnerKey());
        assertEquals(5000L, claimed.getClaimedAtMs());
    }

    /**
     * Verify that a second, distinct runner claiming after the first gets the next lowest-numbered
     * group rather than being handed group 0 again or failing - proving the claim advances through
     * the plan's groups in order as runners arrive.
     */
    @Test
    void shouldClaimGroupOneForASecondRunner() {
        // given
        persistPlanWithGroups("run-1", 2);
        dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);

        // when
        DistributedRunGroup claimed = dataStore.claimNextPendingGroup("run-1", "runner-b", 5100L);

        // then
        assertNotNull(claimed);
        assertEquals(1, claimed.getGroupNumber());
        assertEquals("runner-b", claimed.getRunnerKey());
    }

    /**
     * Verify that once every group in a run has been claimed, a further claim by a new runner
     * returns {@code null} rather than throwing or re-handing out an already-claimed group - the
     * signal a coordinator uses to know the fan-out is exhausted.
     */
    @Test
    void shouldReturnNullWhenAllGroupsAreAlreadyClaimed() {
        // given
        persistPlanWithGroups("run-1", 2);
        dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);
        dataStore.claimNextPendingGroup("run-1", "runner-b", 5100L);

        // when
        DistributedRunGroup claimed = dataStore.claimNextPendingGroup("run-1", "runner-c", 5200L);

        // then
        assertNull(claimed);
    }

    /**
     * Verify that the same runner key claiming twice gets back the exact same group it claimed the
     * first time, rather than taking a second group - the behaviour a retried CI job attempt (same
     * stable runner key, new process) depends on to avoid running two groups for one job slot.
     */
    @Test
    void shouldReturnTheSameGroupWhenTheSameRunnerKeyClaimsTwice() {
        // given
        persistPlanWithGroups("run-1", 3);
        DistributedRunGroup firstClaim = dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);

        // when
        DistributedRunGroup secondClaim = dataStore.claimNextPendingGroup("run-1", "runner-a", 9999L);

        // then
        assertEquals(firstClaim.getGroupNumber(), secondClaim.getGroupNumber());
        assertEquals(firstClaim.getClaimedAtMs(), secondClaim.getClaimedAtMs(),
                "the re-claim must return the original claim timestamp, not overwrite it with the retry's");
        // and a fresh runner still gets a group this runner key did not take a second one of
        DistributedRunGroup otherRunnerClaim = dataStore.claimNextPendingGroup("run-1", "runner-b", 5100L);
        assertNotNull(otherRunnerClaim);
        assertTrue(otherRunnerClaim.getGroupNumber() != firstClaim.getGroupNumber());
    }

    /**
     * Verify that a runner key which already holds a <b>completed</b> group is given nothing at
     * all - neither that group back, nor a fresh {@code PENDING} one.
     *
     * <p>Handing the completed group back would have the runner re-run suites it has already
     * finished and then fail {@link DataStore#completeGroup}'s {@code status = 'CLAIMED'}
     * predicate, leaving the run unable to seal. Handing it a fresh {@code PENDING} group instead
     * would be worse: a runner that already worked one group would take a second, and the group it
     * took would be worked by nobody. The retry-reclaims-its-own-group behaviour is only ever
     * intended for a group still in {@code CLAIMED}.
     */
    @Test
    void shouldReturnNullWhenTheRunnerKeyAlreadyHoldsACompletedGroup() {
        // given - a runner that has already reported its group's one suite and completed it
        persistPlanWithGroups("run-1", 3);
        DistributedRunGroup firstClaim = dataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);
        assertTrue(dataStore.reportGroupProgress("run-1", firstClaim.getGroupNumber(), "runner-a",
                        1000L, 1, 0, 1),
                "test setup expects the progress report to be accepted");
        assertNotNull(dataStore.completeGroup("run-1", firstClaim.getGroupNumber(), "runner-a", 6000L),
                "test setup expects the completion to be accepted");

        // when - the same runner key claims again
        DistributedRunGroup reclaimed = dataStore.claimNextPendingGroup("run-1", "runner-a", 9999L);

        // then
        assertNull(reclaimed, "a runner that has finished its group must not be handed one");
        assertEquals(DistributedRunGroupStatus.COMPLETED,
                dataStore.readDistributedRunGroups("run-1").get(firstClaim.getGroupNumber()).getStatus(),
                "and the group it completed must be left exactly as it was");
        DistributedRunGroup otherRunnerClaim = dataStore.claimNextPendingGroup("run-1", "runner-b", 10000L);
        assertEquals(1, otherRunnerClaim.getGroupNumber(),
                "and the next PENDING group must still be there for another runner, proving the "
                        + "finished runner did not quietly take a second one");
    }

    /**
     * Verify that claiming against a run id nobody has planned returns {@code null} rather than
     * throwing, so a coordinator can treat "unplanned" and "exhausted" the same way at the call
     * site.
     */
    @Test
    void shouldReturnNullForAnUnknownRunId() {
        // given
        persistPlanWithGroups("run-1", 2);

        // when
        DistributedRunGroup claimed = dataStore.claimNextPendingGroup("run-does-not-exist", "runner-a", 5000L);

        // then
        assertNull(claimed);
    }

    /**
     * Verify that {@link DataStore#claimNextPendingGroup} bootstraps the schema itself on a
     * datastore that has never had {@code getTiaData} called on it, the same guarantee the plan and
     * staging operations already provide - a brand new per-branch schema's first contact could
     * plausibly be a runner claiming a group.
     *
     * @throws Exception if the temp directory for the fresh store cannot be created
     */
    @Test
    void shouldBootstrapItsOwnSchemaWithoutAPriorGetTiaDataCall() throws Exception {
        // given
        File freshTempDir = File.createTempFile("tia-claim-fresh-", "");
        freshTempDir.delete();
        freshTempDir.mkdirs();
        JdbcDataStore freshDataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(freshTempDir.getAbsolutePath())),
                BranchSchema.schemaName("test"));
        try {
            List<DistributedRunGroup> groups = Arrays.asList(DistributedRunGroup.pending("run-1", 0, 1000L));
            Map<Integer, List<String>> suites = new HashMap<>();
            suites.put(0, Arrays.asList("com.example.ATest"));
            freshDataStore.persistDistributedRunPlan(new DistributedRunPlan(
                    DistributedRun.open("run-1", "main", "commit-1", 1, null, 1000L, 1234L, false), groups, suites, null));

            // when
            DistributedRunGroup claimed = freshDataStore.claimNextPendingGroup("run-1", "runner-a", 5000L);

            // then
            assertNotNull(claimed);
            assertEquals(0, claimed.getGroupNumber());
        } finally {
            freshDataStore.close();
        }
    }

    /**
     * The point of this task: run {@code groupCount} threads claiming from the same {@code
     * groupCount}-group plan concurrently, all released together via a {@link CyclicBarrier} to
     * maximise contention, against the single shared file-backed H2 database this test class opens
     * in {@code setUp}. Asserts that every thread received a non-null, distinct group number - the
     * property the whole compare-and-swap design exists for. A sequential loop of claims (as the
     * other tests in this class use) cannot demonstrate this: it never gives two callers the chance
     * to read the same {@code PENDING} candidate before either has written back, which is exactly
     * the race the {@code status = 'PENDING'} guard on the claim's UPDATE exists to resolve. Each
     * thread opens its own JDBC connection (via the shared {@link JdbcDataStore#getConnection()}
     * call inside {@code claimNextPendingGroup}) to the same underlying H2 engine instance, so the
     * contention is real database-level contention, not merely concurrent Java code guarded by
     * nothing.
     *
     * @throws Exception if the executor is interrupted or a claim call throws
     */
    @Test
    void concurrentClaimsAcrossThreadsEachGetADistinctGroupWithNoDuplicates() throws Exception {
        // given
        int groupCount = 8;
        String runId = "concurrent-run";
        persistPlanWithGroups(runId, groupCount);

        ExecutorService executor = Executors.newFixedThreadPool(groupCount);
        CyclicBarrier barrier = new CyclicBarrier(groupCount);
        List<Callable<DistributedRunGroup>> jobs = new ArrayList<>();
        for (int i = 0; i < groupCount; i++) {
            final String runnerKey = "runner-" + i;
            jobs.add(new Callable<DistributedRunGroup>() {
                @Override
                public DistributedRunGroup call() throws Exception {
                    barrier.await();
                    return dataStore.claimNextPendingGroup(runId, runnerKey, 5000L);
                }
            });
        }

        // when
        List<Future<DistributedRunGroup>> futures = executor.invokeAll(jobs);
        executor.shutdown();

        // then
        Set<Integer> claimedGroupNumbers = new HashSet<>();
        for (Future<DistributedRunGroup> future : futures) {
            DistributedRunGroup claimed = future.get();
            assertNotNull(claimed, "every one of the " + groupCount
                    + " runners must claim a group when groupCount equals the runner count");
            boolean firstTimeSeeingThisGroup = claimedGroupNumbers.add(claimed.getGroupNumber());
            assertTrue(firstTimeSeeingThisGroup,
                    "group " + claimed.getGroupNumber() + " was claimed by more than one runner");
        }
        assertEquals(groupCount, claimedGroupNumbers.size(), "every group must have been claimed exactly once");
    }
}
