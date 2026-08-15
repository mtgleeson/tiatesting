package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.DistributedRunStatus;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.persistence.dialect.PostgresDialect;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Execution-coverage test for the operations that close a distributed run -
 * {@link DataStore#reportGroupProgress(String, int, String, long, int, int)},
 * {@link DataStore#completeGroup(String, int, String, long)},
 * {@link DataStore#electSealer(String, String, long)},
 * {@link DataStore#markDistributedRunSealed(String)} and the distributed columns on
 * {@code tia_test_run_history} - against a real Postgres. Mirrors
 * {@link JdbcDataStoreCompletionTest}, for the same reason {@link PostgresClaimTest} mirrors
 * {@link JdbcDataStoreClaimTest}: both guards rest entirely on a single-row conditional
 * {@code UPDATE} being evaluated against the latest committed row, and this project has already
 * been bitten once by behaviour that passed on H2 and failed on Postgres (see
 * {@link PostgresPersistTest}). The genuine concurrency test lives only in
 * {@link JdbcDataStoreCompletionTest}, which already demonstrates the database-level race
 * resolution the algorithm relies on and issues the exact same SQL against either vendor.
 *
 * <p>Guarded exactly like {@link PostgresClaimTest}: skipped (not failed) when no Postgres is
 * reachable on {@code localhost:5432}, so the normal build stays green without the Postgres
 * harness running. See the pluggable-datastore WIKI chapter.
 */
class PostgresCompletionTest {

    private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/tiaperf";
    private static final String POSTGRES_USER = "tia";
    private static final String POSTGRES_PASSWORD = "tia";
    private static final String BRANCH = "main";

    private DataStore postgresStore;

    /**
     * Skip the test (rather than fail it) when the local Postgres instance is not reachable, via
     * a quick raw TCP connect with a short timeout, so the normal build stays green on machines
     * without the Postgres harness running.
     */
    private static void assumePg() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 500);
        } catch (IOException e) {
            assumeTrue(false, "spike Postgres not running");
        }
    }

    /**
     * Drop the four distributed-run tables and the history table so each test starts from a clean
     * schema and the current DDL - including the distributed history columns - is recreated on
     * first contact.
     *
     * @throws SQLException if the cleanup connection or the drop statement fails
     */
    private static void cleanTables() throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute(new PostgresDialect().selectSchemaSql(BranchSchema.schemaName(BRANCH)));
            statement.executeUpdate("DROP TABLE IF EXISTS tia_distributed_run_group_suite, "
                    + "tia_distributed_run_method_stage, tia_distributed_run_group, "
                    + "tia_distributed_run, tia_test_run_history CASCADE");
        }
    }

    /**
     * Skip when Postgres is unreachable, drop the tables under test, and open a fresh
     * Postgres-backed store through the production factory.
     *
     * @throws Exception if the connection guard, table cleanup, or store construction fails
     */
    @BeforeEach
    void setUp() throws Exception {
        assumePg();
        cleanTables();
        postgresStore = DataStoreFactory.fromConfig(null, POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD,
                null, BRANCH);
        postgresStore.getTiaData(true); // bootstrap the core schema on first contact
    }

    /**
     * Close the Postgres datastore opened by the test, releasing its connection.
     */
    @AfterEach
    void tearDown() {
        if (postgresStore != null) {
            postgresStore.close();
        }
    }

    /**
     * Build and persist a plan with {@code groupCount} groups, each carrying one suite, against
     * Postgres, so the completion and election tests have a concrete run to advance.
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
        DistributedRun run = DistributedRun.open(runId, BRANCH, "commit-1", groupCount, null,
                1000L * groupCount, 1234L);
        postgresStore.persistDistributedRunPlan(new DistributedRunPlan(run, groups, suites, null));
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
        for (DistributedRunGroup group : postgresStore.readDistributedRunGroups(runId)) {
            if (group.getGroupNumber() == groupNumber) {
                return group;
            }
        }
        return null;
    }

    /**
     * Verify the happy path on Postgres: the runner holding a live claim completes its group and
     * the stored row gains {@code COMPLETED}, the completion timestamp, the measured duration and
     * both suite counters.
     */
    @Test
    void shouldRecordDurationAndCountersWhenTheClaimIsStillLiveOnPostgres() {
        // given
        persistPlanWithGroups("pg-complete-1", 2);
        postgresStore.claimNextPendingGroup("pg-complete-1", "runner-a", 5000L);
        assertTrue(postgresStore.reportGroupProgress("pg-complete-1", 0, "runner-a", 4321L, 7, 2));

        // when
        DistributedRunGroup completed = postgresStore.completeGroup("pg-complete-1", 0, "runner-a",
                9000L);

        // then
        assertNotNull(completed);
        assertEquals(DistributedRunGroupStatus.COMPLETED, completed.getStatus());
        assertEquals(Long.valueOf(9000L), completed.getCompletedAtMs());
        assertEquals(Long.valueOf(4321L), completed.getActualDurationMs());
        assertEquals(7, completed.getSuitesRan());
        assertEquals(2, completed.getSuitesFailed());
        assertEquals(completed, storedGroup("pg-complete-1", 0));
    }

    /**
     * Verify the straggler guard evaluates against the latest committed row on Postgres too: a
     * runner completing a group another runner holds gets {@code null} and leaves the stored row
     * exactly as the holder left it.
     */
    @Test
    void shouldReturnNullAndWriteNothingWhenAnotherRunnerHoldsTheGroupOnPostgres() {
        // given
        persistPlanWithGroups("pg-complete-2", 2);
        postgresStore.claimNextPendingGroup("pg-complete-2", "runner-a", 5000L);

        // when
        DistributedRunGroup completed = postgresStore.completeGroup("pg-complete-2", 0, "runner-b",
                9000L);

        // then
        assertNull(completed);
        DistributedRunGroup stored = storedGroup("pg-complete-2", 0);
        assertEquals(DistributedRunGroupStatus.CLAIMED, stored.getStatus());
        assertEquals("runner-a", stored.getRunnerKey());
        assertNull(stored.getActualDurationMs());
    }

    /**
     * Verify that the {@code NOT EXISTS} half of the election predicate holds on Postgres: with one
     * group still {@code CLAIMED}, no runner may elect itself sealer.
     */
    @Test
    void shouldNotElectASealerWhileAnyGroupIsIncompleteOnPostgres() {
        // given
        persistPlanWithGroups("pg-seal-1", 2);
        postgresStore.claimNextPendingGroup("pg-seal-1", "runner-a", 5000L);
        postgresStore.claimNextPendingGroup("pg-seal-1", "runner-b", 5100L);
        postgresStore.reportGroupProgress("pg-seal-1", 0, "runner-a", 100L, 1, 0);
        postgresStore.completeGroup("pg-seal-1", 0, "runner-a", 9000L);

        // when
        boolean elected = postgresStore.electSealer("pg-seal-1", "runner-a", 9500L);

        // then
        assertFalse(elected);
        assertNull(postgresStore.readDistributedRun("pg-seal-1").getSealedBy());
    }

    /**
     * Verify the barrier releases on Postgres once every group has completed, and that a second
     * candidate arriving afterwards loses without displacing the winner.
     */
    @Test
    void shouldElectExactlyOneSealerOnceEveryGroupIsCompleteOnPostgres() {
        // given
        persistPlanWithGroups("pg-seal-2", 2);
        postgresStore.claimNextPendingGroup("pg-seal-2", "runner-a", 5000L);
        postgresStore.claimNextPendingGroup("pg-seal-2", "runner-b", 5100L);
        postgresStore.reportGroupProgress("pg-seal-2", 0, "runner-a", 100L, 1, 0);
        postgresStore.completeGroup("pg-seal-2", 0, "runner-a", 9000L);
        postgresStore.reportGroupProgress("pg-seal-2", 1, "runner-b", 200L, 1, 0);
        postgresStore.completeGroup("pg-seal-2", 1, "runner-b", 9100L);

        // when
        boolean firstAttempt = postgresStore.electSealer("pg-seal-2", "runner-a", 9500L);
        boolean secondAttempt = postgresStore.electSealer("pg-seal-2", "runner-b", 9600L);

        // then
        assertTrue(firstAttempt);
        assertFalse(secondAttempt);
        DistributedRun run = postgresStore.readDistributedRun("pg-seal-2");
        assertEquals("runner-a", run.getSealedBy());
        assertEquals(Long.valueOf(9500L), run.getSealedAtMs());
    }

    /**
     * Verify that a straggler sealer whose run row no longer exists loses the election on Postgres
     * rather than winning by default - the case that would otherwise rebuild the catalogue from an
     * empty staging table.
     */
    @Test
    void shouldReturnFalseWhenTheRunRowIsAbsentOnPostgres() {
        // given
        persistPlanWithGroups("pg-seal-3", 1);

        // when
        boolean elected = postgresStore.electSealer("run-does-not-exist", "runner-a", 9500L);

        // then
        assertFalse(elected);
    }

    /**
     * Verify that marking a run sealed flips its status to {@code SEALED} on Postgres.
     */
    @Test
    void shouldFlipTheRunToSealedOnPostgres() {
        // given
        persistPlanWithGroups("pg-seal-4", 1);

        // when
        postgresStore.markDistributedRunSealed("pg-seal-4");

        // then
        assertEquals(DistributedRunStatus.SEALED,
                postgresStore.readDistributedRun("pg-seal-4").getStatus());
    }

    /**
     * Verify the three distributed-only history columns round-trip on Postgres, and that a
     * non-distributed row leaves them null - Postgres is stricter than H2 about nullable typed
     * parameters, so the null-setting path is worth executing against it.
     */
    @Test
    void shouldRoundTripTheDistributedHistoryColumnsOnPostgres() {
        // given
        TestRunHistoryEntry distributed = new TestRunHistoryEntry(
                "pg-dist-id", 1_700_000_000_000L, BRANCH, "abc123",
                10, 2, 1, 5_000L, true, 4_000L, 80,
                "ci-run-42", 1_800L, 4);
        TestRunHistoryEntry singleHost = TestRunHistoryEntry.create(
                BRANCH, "def456", 1_600_000_000_000L, 3, 1, 0, 300L, true, 0L, 0);

        // when
        postgresStore.persistTestRunHistoryEntry(distributed);
        postgresStore.persistTestRunHistoryEntry(singleHost);
        List<TestRunHistoryEntry> history = postgresStore.readTestRunHistory();

        // then
        assertEquals(2, history.size());
        TestRunHistoryEntry newest = history.get(0);
        assertEquals("ci-run-42", newest.getRunId());
        assertEquals(Long.valueOf(1_800L), newest.getWallClockMs());
        assertEquals(Integer.valueOf(4), newest.getGroupCount());
        TestRunHistoryEntry oldest = history.get(1);
        assertNull(oldest.getRunId());
        assertNull(oldest.getWallClockMs());
        assertNull(oldest.getGroupCount());
    }
}
