package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.persistence.dialect.PostgresDialect;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Execution-coverage test for the distributed-run plan store against a real Postgres. H2 cannot
 * catch a Postgres-only DDL or type error - the binary/uuid handling and the {@code TRUNCATE}
 * versus {@code DELETE FROM} table-clearing SQL differ between dialects, and this project has
 * already been bitten once by a constraint that passed on H2 and failed on Postgres (see
 * {@link PostgresPersistTest}). Round-trips a plan, a null {@code targetRunTimeMs}, and the
 * previous-run clear-out that {@link JdbcDataStoreDistributedPlanTest} covers on H2, so the same
 * behaviour is proven against Postgres too.
 *
 * <p>Guarded exactly like {@link PostgresPersistTest}: skipped (not failed) when no Postgres is
 * reachable on {@code localhost:5432}, so the normal build stays green without the Postgres
 * harness running. See the pluggable-datastore WIKI chapter.
 */
class PostgresDistributedPlanTest {

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
     * Drop the four distributed-run tables so each test starts from a clean schema. Does not rely
     * on {@link PostgresPersistTest#cleanPostgres()} because its hard-coded {@code DROP TABLE}
     * list does not include these tables - a previous run's rows would otherwise leak into the
     * next test. Selects the per-branch schema first, mirroring
     * {@link PostgresPersistTest#cleanPostgres()}, since the store's tables live there rather than
     * in the raw connection's default {@code public} schema.
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
     * Verify that persisting a two-group plan against Postgres round-trips the run and its group
     * count exactly, and that a group's suite assignment reads back in suite-name order - proving
     * the plan-write transaction and its {@code BYTEA}/varchar columns behave the same on Postgres
     * as they do on H2.
     */
    @Test
    void shouldRoundTripAPlanOnPostgres() {
        // given
        DistributedRun run = DistributedRun.open("pg-run-1", BRANCH, "commit-1", 2, 60000L, 90000L, 1234L);
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Arrays.asList("com.example.BTest", "com.example.ATest"));
        suites.put(1, Arrays.asList("com.example.CTest"));
        DistributedRunPlan plan = new DistributedRunPlan(run, Arrays.asList(
                DistributedRunGroup.pending("pg-run-1", 0, 50000L),
                DistributedRunGroup.pending("pg-run-1", 1, 40000L)), suites);

        // when
        postgresStore.persistDistributedRunPlan(plan);

        // then
        assertEquals(run, postgresStore.readDistributedRun("pg-run-1"));
        assertEquals(2, postgresStore.readDistributedRunGroups("pg-run-1").size());
        assertEquals(Arrays.asList("com.example.ATest", "com.example.BTest"),
                postgresStore.readDistributedRunGroupSuites("pg-run-1", 0));
    }

    /**
     * Verify that a run planned in static-groups mode (no configured target run time) round-trips
     * a null {@code targetRunTimeMs} on Postgres rather than a coerced zero, since a JDBC
     * nullable-long read that only checked {@code getLong} would silently turn "not configured"
     * into "configured as zero" - and Postgres's JDBC driver handles SQL NULL differently enough
     * from H2's that this is worth proving separately.
     */
    @Test
    void shouldPreserveNullTargetRunTimeOnPostgres() {
        // given
        DistributedRun run = DistributedRun.open("pg-run-2", BRANCH, "commit-1", 1, null, 10L, 7L);
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Arrays.asList("com.example.ATest"));
        postgresStore.persistDistributedRunPlan(new DistributedRunPlan(run,
                Arrays.asList(DistributedRunGroup.pending("pg-run-2", 0, 10L)), suites));

        // when
        DistributedRun read = postgresStore.readDistributedRun("pg-run-2");

        // then
        assertNull(read.getTargetRunTimeMs());
    }

    /**
     * Verify that writing a second plan on Postgres clears every trace of the first run before
     * inserting the new one, and that only the new run is visible afterwards. Postgres clears the
     * distributed tables with {@code TRUNCATE TABLE} rather than H2's {@code DELETE FROM}; a stray
     * {@code TRUNCATE} that ran outside the persist transaction would still pass this assertion
     * on its own, but would leave the clear un-rollback-able if a later insert failed - both
     * dialects must clear identically inside the same transaction.
     */
    @Test
    void shouldClearThePreviousRunOnPostgres() {
        // given
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Arrays.asList("com.example.ATest"));
        postgresStore.persistDistributedRunPlan(new DistributedRunPlan(
                DistributedRun.open("pg-run-3", BRANCH, "commit-1", 1, null, 10L, 7L),
                Arrays.asList(DistributedRunGroup.pending("pg-run-3", 0, 10L)), suites));

        // when
        postgresStore.persistDistributedRunPlan(new DistributedRunPlan(
                DistributedRun.open("pg-run-4", BRANCH, "commit-2", 1, null, 10L, 8L),
                Arrays.asList(DistributedRunGroup.pending("pg-run-4", 0, 10L)), suites));

        // then
        assertNull(postgresStore.readDistributedRun("pg-run-3"));
        assertEquals("pg-run-4", postgresStore.readDistributedRun("pg-run-4").getRunId());
        assertEquals(1, postgresStore.readAllDistributedRuns().size());
    }
}
