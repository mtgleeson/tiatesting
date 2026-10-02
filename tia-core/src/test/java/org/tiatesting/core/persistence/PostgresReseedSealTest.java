package org.tiatesting.core.persistence;


import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Re-runs {@link ReseedSealTest} against Postgres, since the clear-out's correlated deletes are
 * hand-written SQL that must behave the same on both vendors. Skipped when no local Postgres is
 * reachable, like the other Postgres mirrors.
 */
class PostgresReseedSealTest extends ReseedSealTest {

    private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/tiaperf";
    private static final String POSTGRES_USER = "tia";
    private static final String POSTGRES_PASSWORD = "tia";
    private static final String BRANCH = "reseed-seal";

    /**
     * Skip the test rather than fail it when the local Postgres is not reachable.
     */
    private static void assumePg() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 500);
        } catch (IOException e) {
            assumeTrue(false, "spike Postgres not running");
        }
    }

    /**
     * Drop the branch schema so each test starts from an empty store and the current DDL is
     * recreated on first contact.
     *
     * @throws SQLException if the cleanup connection or the drop statement fails
     */
    private static void cleanSchema() throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP SCHEMA IF EXISTS " + BranchSchema.schemaName(BRANCH, null)
                    + " CASCADE");
        }
    }

    /**
     * Skip when Postgres is unreachable, clear the branch schema, and open a Postgres-backed store
     * through the production factory.
     *
     * @return an open Postgres-backed datastore
     * @throws Exception if the cleanup or the store construction fails
     */
    @Override
    DataStore openStore() throws Exception {
        assumePg();
        cleanSchema();
        return DataStoreFactory.fromConfig(null, POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD,
                null, BRANCH, null);
    }
}
