package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.persistence.dialect.PostgresDialect;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Execution-coverage test for the {@code tia_distributed_run_method_stage} staging operations
 * against a real Postgres. H2 cannot catch a Postgres-only upsert error: the write is an upsert,
 * and the two dialects diverge exactly there - H2 emits {@code MERGE INTO ... KEY(...)}, while
 * Postgres emits {@code INSERT ... ON CONFLICT (...) DO UPDATE}, which requires the conflict
 * target to be backed by a real unique constraint or index. This project has already been bitten
 * by a constraint that passed on H2's lenient {@code MERGE KEY} and failed on Postgres (see
 * {@link PostgresPersistTest}). The composite primary key on {@code (run_id, id)} should satisfy
 * Postgres's conflict-target requirement, but that has to be demonstrated against the real
 * vendor rather than assumed. This class mirrors the H2 coverage in
 * {@link JdbcDataStoreMethodStagingTest}, with the same-id overwrite as the key case since that
 * is the one path that actually exercises {@code ON CONFLICT}.
 *
 * <p>Guarded exactly like {@link PostgresPersistTest} and {@link PostgresDistributedPlanTest}:
 * skipped (not failed) when no Postgres is reachable on {@code localhost:5432}, so the normal
 * build stays green without the Postgres harness running. See the pluggable-datastore WIKI
 * chapter.
 */
class PostgresMethodStagingTest {

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
     * Drop the method-staging table so each test starts from a clean schema, forcing its DDL to
     * re-execute against real Postgres on every test run. Does not rely on
     * {@link PostgresPersistTest#cleanPostgres()} because its hard-coded {@code DROP TABLE} list
     * does not include the distributed-run tables - a previous test's staged rows would otherwise
     * leak into the next test. Selects the per-branch schema first, mirroring
     * {@link PostgresDistributedPlanTest#cleanDistributedTables()}, since the store's tables live
     * there rather than in the raw connection's default {@code public} schema; a drop issued
     * against the default schema would silently no-op.
     *
     * @throws SQLException if the cleanup connection or the drop statement fails
     */
    private static void cleanMethodStageTable() throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute(new PostgresDialect().selectSchemaSql(BranchSchema.schemaName(BRANCH, null)));
            statement.executeUpdate("DROP TABLE IF EXISTS tia_distributed_run_method_stage CASCADE");
        }
    }

    /**
     * Skip when Postgres is unreachable, drop the method-staging table, and open a fresh
     * Postgres-backed store through the production factory, so each test starts from a clean
     * schema and the current DDL is recreated on first contact.
     *
     * @throws Exception if the connection guard, table cleanup, or store construction fails
     */
    @BeforeEach
    void setUp() throws Exception {
        assumePg();
        cleanMethodStageTable();
        postgresStore = DataStoreFactory.fromConfig(null, POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD,
                null, BRANCH, null);
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
     * Verify that staging a runner's method trackers and reading them back for the same run id
     * reproduces every tracker's name and line range exactly on Postgres, proving the upsert
     * write and the read agree on every column against the real vendor.
     */
    @Test
    void shouldRoundTripStagedTrackersOnPostgres() {
        // given
        Map<Integer, MethodImpactTracker> staged = new HashMap<>();
        staged.put(101, new MethodImpactTracker("com/example/A.foo.()V", 10, 20));
        staged.put(102, new MethodImpactTracker("com/example/B.bar.()V", 30, 45));

        // when
        postgresStore.persistStagedMethodTrackers("pg-run-1", staged);
        Map<Integer, MethodImpactTracker> read = postgresStore.readStagedMethodTrackers("pg-run-1");

        // then
        assertEquals(2, read.size());
        assertEquals("com/example/A.foo.()V", read.get(101).getMethodName());
        assertEquals(10, read.get(101).getLineNumberStart());
        assertEquals(20, read.get(101).getLineNumberEnd());
        assertEquals("com/example/B.bar.()V", read.get(102).getMethodName());
        assertEquals(45, read.get(102).getLineNumberEnd());
    }

    /**
     * Verify that two runners staging disjoint method ids under the same run id both survive on
     * Postgres: the read returns the union of both writes, not just the most recent one. This is
     * the property the sealer depends on to rebuild the full catalogue from every runner's
     * partial view, so the assertion checks the full merged key set rather than just the size.
     */
    @Test
    void shouldUnionTrackersStagedBySeveralRunnersOnPostgres() {
        // given
        Map<Integer, MethodImpactTracker> fromRunnerOne = new HashMap<>();
        fromRunnerOne.put(101, new MethodImpactTracker("com/example/A.foo.()V", 10, 20));
        Map<Integer, MethodImpactTracker> fromRunnerTwo = new HashMap<>();
        fromRunnerTwo.put(202, new MethodImpactTracker("com/example/C.baz.()V", 5, 9));

        // when
        postgresStore.persistStagedMethodTrackers("pg-run-1", fromRunnerOne);
        postgresStore.persistStagedMethodTrackers("pg-run-1", fromRunnerTwo);
        Map<Integer, MethodImpactTracker> read = postgresStore.readStagedMethodTrackers("pg-run-1");

        // then
        assertEquals(2, read.size());
        assertTrue(read.containsKey(101));
        assertTrue(read.containsKey(202));
        assertEquals("com/example/A.foo.()V", read.get(101).getMethodName());
        assertEquals("com/example/C.baz.()V", read.get(202).getMethodName());
    }

    /**
     * Verify that staging the same method id twice with different line numbers overwrites rather
     * than fails on Postgres: the read returns the second write's values. This is the reason this
     * test class exists - Postgres's {@code INSERT ... ON CONFLICT (...) DO UPDATE} requires the
     * conflict target to be backed by a real unique constraint or index, and a missing one would
     * throw here rather than silently pass the way H2's lenient {@code MERGE KEY} would. A real
     * distributed run always has overlapping method ids across runners, so this path is
     * load-bearing rather than a corner case.
     */
    @Test
    void shouldLetALaterStageOverwriteTheSameMethodIdOnPostgres() {
        // given
        Map<Integer, MethodImpactTracker> firstStage = new HashMap<>();
        firstStage.put(101, new MethodImpactTracker("com/example/A.foo.()V", 10, 20));
        Map<Integer, MethodImpactTracker> secondStage = new HashMap<>();
        secondStage.put(101, new MethodImpactTracker("com/example/A.foo.()V", 11, 25));

        // when
        postgresStore.persistStagedMethodTrackers("pg-run-1", firstStage);
        postgresStore.persistStagedMethodTrackers("pg-run-1", secondStage);
        Map<Integer, MethodImpactTracker> read = postgresStore.readStagedMethodTrackers("pg-run-1");

        // then
        assertEquals(1, read.size());
        assertEquals(11, read.get(101).getLineNumberStart());
        assertEquals(25, read.get(101).getLineNumberEnd());
    }

    /**
     * Verify that deleting one run's staged trackers on Postgres leaves another run's staged
     * trackers intact, so the sealer's post-seal cleanup for one run can never lose a
     * concurrently-staged peer run's data.
     */
    @Test
    void shouldDeleteOnlyTheNamedRunsStagedTrackersOnPostgres() {
        // given
        Map<Integer, MethodImpactTracker> runOneStaged = new HashMap<>();
        runOneStaged.put(101, new MethodImpactTracker("com/example/A.foo.()V", 10, 20));
        Map<Integer, MethodImpactTracker> runTwoStaged = new HashMap<>();
        runTwoStaged.put(202, new MethodImpactTracker("com/example/C.baz.()V", 5, 9));
        postgresStore.persistStagedMethodTrackers("pg-run-1", runOneStaged);
        postgresStore.persistStagedMethodTrackers("pg-run-2", runTwoStaged);

        // when
        postgresStore.deleteStagedMethodTrackers("pg-run-1");

        // then
        assertTrue(postgresStore.readStagedMethodTrackers("pg-run-1").isEmpty());
        assertEquals(1, postgresStore.readStagedMethodTrackers("pg-run-2").size());
    }
}
