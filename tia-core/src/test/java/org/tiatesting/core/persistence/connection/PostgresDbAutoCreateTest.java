package org.tiatesting.core.persistence.connection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guarded integration tests for {@link PostgresConnectionProvider}'s database auto-create. Skipped
 * (not failed) when no Postgres is reachable on {@code localhost:5432}, mirroring
 * {@link org.tiatesting.core.persistence.PostgresPersistTest}'s guard so the normal build stays green
 * without the {@code spike/postgres/} harness running. See the pluggable-datastore WIKI chapter.
 */
class PostgresDbAutoCreateTest {

    private static final String HOST_URL = "jdbc:postgresql://localhost:5432/";
    private static final String MAINTENANCE_URL = HOST_URL + PostgresConnectionProvider.MAINTENANCE_DB;
    private static final String USER = "tia";
    private static final String PASSWORD = "tia";

    // Unique per run so a failed teardown never collides with the next run.
    private final String dbName = "tia_autocreate_" + System.currentTimeMillis();
    private final String targetUrl = HOST_URL + dbName;

    /**
     * Skip the test (rather than fail it) when the local Postgres instance is not reachable, via a
     * quick raw TCP connect with a short timeout.
     */
    private static boolean pgReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Drop the per-test database via a maintenance connection to the {@code postgres} database, so the
     * test starts (and ends) with the database absent.
     *
     * @throws SQLException if the maintenance connection or the drop statement fails
     */
    private void dropTestDatabase() throws SQLException {
        try (Connection connection = DriverManager.getConnection(MAINTENANCE_URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS \"" + dbName + "\"");
        }
    }

    /**
     * Remove the per-test database after each test, but only when Postgres is reachable, so the
     * cleanup itself never fails the build on a machine without the harness.
     *
     * @throws SQLException if the drop fails while Postgres is reachable
     */
    @AfterEach
    void tearDown() throws SQLException {
        if (pgReachable()) {
            dropTestDatabase();
        }
    }

    /**
     * Connecting to a non-existent database creates it and returns a usable connection pinned to it.
     *
     * @throws SQLException if the drop, connect, or catalog read fails
     */
    @Test
    void createsDatabaseWhenMissing() throws SQLException {
        // given a reachable Postgres and a guaranteed-absent target database
        assumeTrue(pgReachable(), "spike Postgres not running");
        dropTestDatabase();
        PostgresConnectionProvider provider = new PostgresConnectionProvider(targetUrl, USER, PASSWORD);

        // when a connection is requested for the missing database
        try (Connection connection = provider.get()) {
            // then the database was created and the connection is pinned to it
            assertNotNull(connection);
            assertEquals(dbName, connection.getCatalog());
        }
    }

    /**
     * A second connect once the database exists is a no-op that succeeds directly ({@code 3D000} is
     * not raised, so no create is attempted).
     *
     * @throws SQLException if the drop or either connect fails
     */
    @Test
    void idempotentWhenDatabaseAlreadyExists() throws SQLException {
        // given a reachable Postgres and the target database created by a first connect
        assumeTrue(pgReachable(), "spike Postgres not running");
        dropTestDatabase();
        PostgresConnectionProvider provider = new PostgresConnectionProvider(targetUrl, USER, PASSWORD);
        try (Connection first = provider.get()) {
            assertNotNull(first);
        }

        // when a second connection is requested, now that the database exists
        try (Connection second = provider.get()) {
            // then it succeeds directly without attempting another create
            assertNotNull(second);
            assertEquals(dbName, second.getCatalog());
        }
    }
}
