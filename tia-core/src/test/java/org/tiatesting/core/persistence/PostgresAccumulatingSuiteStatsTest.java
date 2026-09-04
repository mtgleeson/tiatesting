package org.tiatesting.core.persistence;

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
 * {@link AccumulatingSuiteStatsTest} against Postgres.
 *
 * <p>The accumulating suite upsert is the one write whose SQL genuinely differs per vendor - H2
 * needs {@code MERGE ... USING ... WHEN MATCHED} because its {@code MERGE ... KEY ... VALUES} form
 * cannot reference a column's own stored value, while Postgres uses {@code ON CONFLICT DO UPDATE}
 * with {@code EXCLUDED}. Two hand-written statements doing the same arithmetic is exactly the shape
 * that drifts, so both run the same assertions.
 *
 * <p>Skipped rather than failed when no local Postgres is listening.
 */
class PostgresAccumulatingSuiteStatsTest extends AccumulatingSuiteStatsTest {

    private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/tiaperf";
    private static final String POSTGRES_USER = "tia";
    private static final String POSTGRES_PASSWORD = "tia";
    private static final String BRANCH = "accumulating-suite-stats";

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
     * Drop the suite tables so each test starts from an empty store and the current DDL is
     * recreated on first contact. The per-branch schema is selected first - the store's tables live
     * there rather than in the {@code public} schema a raw connection starts in.
     *
     * @throws SQLException if the cleanup connection or the drop statement fails
     */
    private static void cleanTables() throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute(new PostgresDialect().selectSchemaSql(BranchSchema.schemaName(BRANCH, null)));
            statement.executeUpdate("DROP TABLE IF EXISTS tia_source_class_method, tia_source_class, "
                    + "tia_test_suite, tia_core CASCADE");
        }
    }

    /**
     * Skip when Postgres is unreachable, clear the tables under test, and open a Postgres-backed
     * store through the production factory.
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
