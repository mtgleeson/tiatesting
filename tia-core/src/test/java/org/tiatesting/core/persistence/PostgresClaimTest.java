package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.DistributedRunPlan;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Execution-coverage test for {@link DataStore#claimNextPendingGroup(String, String, long)}
 * against a real Postgres. H2 cannot catch a Postgres-only compare-and-swap error: the claim's
 * safety rests entirely on a single-row {@code UPDATE ... WHERE status = 'PENDING'} being
 * evaluated against the latest committed row, and this project has already been bitten once by
 * behaviour that passed on H2 and failed on Postgres (see {@link PostgresPersistTest}). This class
 * mirrors the sequential-protocol coverage in {@link JdbcDataStoreClaimTest} - the genuine
 * concurrency test lives only there, since {@link JdbcDataStoreClaimTest} already demonstrates the
 * database-level race resolution and the algorithm issues the exact same SQL against either
 * vendor.
 *
 * <p>Guarded exactly like {@link PostgresPersistTest} and {@link PostgresDistributedPlanTest}:
 * skipped (not failed) when no Postgres is reachable on {@code localhost:5432}, so the normal
 * build stays green without the Postgres harness running. See the pluggable-datastore WIKI
 * chapter.
 */
class PostgresClaimTest {

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
     * Drop the four distributed-run tables so each test starts from a clean schema. Mirrors
     * {@link PostgresDistributedPlanTest#cleanDistributedTables()} exactly, since claiming shares
     * the same tables the plan store writes.
     *
     * @throws SQLException if the cleanup connection or the drop statement fails
     */
    private static void cleanDistributedTables() throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute(new PostgresDialect().selectSchemaSql(BranchSchema.schemaName(BRANCH)));
            statement.executeUpdate("DROP TABLE IF EXISTS tia_distributed_run_group_suite, "
                    + "tia_distributed_run_method_stage, tia_distributed_run_group, "
                    + "tia_distributed_run CASCADE");
        }
    }

    /**
     * Skip when Postgres is unreachable, drop the four distributed-run tables, and open a fresh
     * Postgres-backed store through the production factory, so each test starts from a clean
     * schema and the current DDL is recreated on first contact.
     *
     * @throws Exception if the connection guard, table cleanup, or store construction fails
     */
    @BeforeEach
    void setUp() throws Exception {
        assumePg();
        cleanDistributedTables();
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
     * Postgres, so claim tests have a concrete plan to claim from.
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
        postgresStore.persistDistributedRunPlan(new DistributedRunPlan(run, groups, suites));
    }

    /**
     * Verify that claiming from a freshly-planned run on Postgres returns group 0, marked
     * {@code CLAIMED} with the calling runner's key and the supplied claim timestamp - proving the
     * claim's {@code SELECT MIN(...)} and single-row {@code UPDATE} behave the same on Postgres as
     * they do on H2.
     */
    @Test
    void shouldClaimGroupZeroFromAFreshPlanOnPostgres() {
        // given
        persistPlanWithGroups("pg-claim-1", 2);

        // when
        DistributedRunGroup claimed = postgresStore.claimNextPendingGroup("pg-claim-1", "runner-a", 5000L);

        // then
        assertNotNull(claimed);
        assertEquals(0, claimed.getGroupNumber());
        assertEquals(DistributedRunGroupStatus.CLAIMED, claimed.getStatus());
        assertEquals("runner-a", claimed.getRunnerKey());
        assertEquals(5000L, claimed.getClaimedAtMs());
    }

    /**
     * Verify that a second, distinct runner claiming after the first gets the next lowest-numbered
     * group on Postgres, the same ordering H2 provides.
     */
    @Test
    void shouldClaimGroupOneForASecondRunnerOnPostgres() {
        // given
        persistPlanWithGroups("pg-claim-2", 2);
        postgresStore.claimNextPendingGroup("pg-claim-2", "runner-a", 5000L);

        // when
        DistributedRunGroup claimed = postgresStore.claimNextPendingGroup("pg-claim-2", "runner-b", 5100L);

        // then
        assertNotNull(claimed);
        assertEquals(1, claimed.getGroupNumber());
    }

    /**
     * Verify that once every group in a run has been claimed on Postgres, a further claim by a new
     * runner returns {@code null} rather than throwing or re-handing out an already-claimed group.
     */
    @Test
    void shouldReturnNullWhenAllGroupsAreAlreadyClaimedOnPostgres() {
        // given
        persistPlanWithGroups("pg-claim-3", 2);
        postgresStore.claimNextPendingGroup("pg-claim-3", "runner-a", 5000L);
        postgresStore.claimNextPendingGroup("pg-claim-3", "runner-b", 5100L);

        // when
        DistributedRunGroup claimed = postgresStore.claimNextPendingGroup("pg-claim-3", "runner-c", 5200L);

        // then
        assertNull(claimed);
    }

    /**
     * Verify that the same runner key claiming twice on Postgres gets back the exact same group it
     * claimed the first time - this is the path that actually exercises Postgres's read of the
     * already-claimed row (step 0), which H2 alone cannot be trusted to prove behaves identically.
     */
    @Test
    void shouldReturnTheSameGroupWhenTheSameRunnerKeyClaimsTwiceOnPostgres() {
        // given
        persistPlanWithGroups("pg-claim-4", 2);
        DistributedRunGroup firstClaim = postgresStore.claimNextPendingGroup("pg-claim-4", "runner-a", 5000L);

        // when
        DistributedRunGroup secondClaim = postgresStore.claimNextPendingGroup("pg-claim-4", "runner-a", 9999L);

        // then
        assertEquals(firstClaim.getGroupNumber(), secondClaim.getGroupNumber());
        assertEquals(firstClaim.getClaimedAtMs(), secondClaim.getClaimedAtMs());
    }

    /**
     * Verify that claiming against a run id nobody has planned on Postgres returns {@code null}
     * rather than throwing.
     */
    @Test
    void shouldReturnNullForAnUnknownRunIdOnPostgres() {
        // given
        persistPlanWithGroups("pg-claim-5", 2);

        // when
        DistributedRunGroup claimed = postgresStore.claimNextPendingGroup("run-does-not-exist", "runner-a", 5000L);

        // then
        assertNull(claimed);
    }
}
