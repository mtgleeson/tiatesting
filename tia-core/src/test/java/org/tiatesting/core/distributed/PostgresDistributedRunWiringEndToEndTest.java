package org.tiatesting.core.distributed;

import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.DataStoreFactory;
import org.tiatesting.core.persistence.dialect.PostgresDialect;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Re-run the whole distributed lifecycle of {@link DistributedRunWiringEndToEndTest} against a real
 * Postgres. This mirror is not redundancy for its own sake: a distributed run's preconditions reject
 * an embedded database outright, so Postgres is what a real distributed build is actually pointed
 * at, and every guard the lifecycle rests on - the claim, the completion and the sealer election -
 * is a conditional single-row {@code UPDATE} whose row count the algorithm depends on. This project
 * has been bitten once already by behaviour that passed on H2 and failed on Postgres (see
 * {@code PostgresPersistTest}).
 *
 * <p>Guarded like the other Postgres tests: skipped, not failed, when nothing is listening on
 * {@code localhost:5432}, so the ordinary build stays green without the Postgres harness running.
 * See the pluggable-datastore chapter in {@code WIKI.md}.
 */
class PostgresDistributedRunWiringEndToEndTest extends DistributedRunWiringEndToEndTest {

    private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/tiaperf";
    private static final String POSTGRES_USER = "tia";
    private static final String POSTGRES_PASSWORD = "tia";

    /**
     * Skip the test rather than fail it when the local Postgres is not reachable, via a quick raw
     * TCP connect with a short timeout.
     */
    private static void assumePg() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 500);
        } catch (IOException e) {
            assumeTrue(false, "spike Postgres not running");
        }
    }

    /**
     * Drop every table this lifecycle writes, so each test starts from an empty store and the
     * current DDL is recreated on first contact. The per-branch schema is selected first: the
     * store's tables live there rather than in the {@code public} schema a raw connection starts
     * in, and a drop issued against the default schema would silently no-op.
     *
     * @throws SQLException if the cleanup connection or the drop statement fails
     */
    private static void cleanTables() throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute(new PostgresDialect().selectSchemaSql(BranchSchema.schemaName(BRANCH, null)));
            statement.executeUpdate("DROP TABLE IF EXISTS tia_distributed_run_group_suite, "
                    + "tia_distributed_run_method_stage, tia_distributed_run_group, "
                    + "tia_distributed_run, tia_source_class_method, tia_source_class, "
                    + "tia_test_suite, tia_test_suites_failed, tia_source_method, tia_core, "
                    + "tia_pending_library_impacted_method, tia_library_publish, tia_library, "
                    + "tia_test_run_history CASCADE");
        }
    }

    /**
     * Skip when Postgres is unreachable, clear the tables under test, and open the store through
     * the production factory so the runners in this build share exactly the datastore a real
     * distributed build gives them.
     *
     * @return an open Postgres-backed datastore
     * @throws Exception if the cleanup or the store construction fails
     */
    @Override
    DataStore openStore() throws Exception {
        assumePg();
        cleanTables();
        return DataStoreFactory.fromConfig(null, POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD,
                null, BRANCH, null);
    }
}
