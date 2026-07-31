package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cross-branch integration test proving the property the schema-per-branch feature exists to
 * provide: two {@link DataStore}s built by {@link DataStoreFactory#fromConfig} against the SAME
 * physical database, differing only in the {@code branch} argument, write into and read from
 * separate schemas rather than sharing state. See the per-branch schema WIKI chapter.
 *
 * <p>Run for both H2 (always) and Postgres (guarded, skipped when no local Postgres is reachable,
 * mirroring {@link DataStoreFactoryPostgresTest} and {@link PostgresPersistTest}).
 */
class SchemaPerBranchIsolationTest {

    private static final String BRANCH_A = "branchA";
    private static final String BRANCH_B = "branchB";
    private static final Set<String> SUITES_A = Collections.singleton("A_only");
    private static final Set<String> SUITES_B = Collections.singleton("B_only");

    private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/tia_junit5_pg";
    private static final String POSTGRES_USER = "tia";
    private static final String POSTGRES_PASSWORD = "tia";

    /**
     * Verifies branch isolation on H2: two stores built from the same {@code @TempDir} database
     * directory but different branches must each read back only the failed-suite set persisted
     * into that branch's own schema, never the other branch's set or their union.
     *
     * @param dir a fresh temp directory shared as the embedded H2 database location for both stores
     */
    @Test
    void h2StoresOnDifferentBranchesAreIsolated(@TempDir Path dir) {
        // given two H2 stores against the same database directory, differing only in branch
        DataStore storeA = DataStoreFactory.fromConfig(dir.toString(), null, "sa", "", null, BRANCH_A);
        DataStore storeB = DataStoreFactory.fromConfig(dir.toString(), null, "sa", "", null, BRANCH_B);

        try {
            // when a distinct failed-suite set is persisted into each branch's schema
            storeA.getTiaData(true);
            storeA.persistTestSuitesFailed(new HashSet<>(SUITES_A));
            storeB.getTiaData(true);
            storeB.persistTestSuitesFailed(new HashSet<>(SUITES_B));

            // then each store reads back exactly its own branch's data, never the other's
            assertEquals(SUITES_A, storeA.getTestSuitesFailed(), "branchA should see only its own failed suites");
            assertEquals(SUITES_B, storeB.getTestSuitesFailed(), "branchB should see only its own failed suites");
        } finally {
            storeA.close();
            storeB.close();
        }
    }

    /**
     * Verifies branch isolation on Postgres, mirroring {@link #h2StoresOnDifferentBranchesAreIsolated}
     * against the shared {@code tia_junit5_pg} database. Skipped (not failed) when no local Postgres
     * is reachable on {@code localhost:5432}.
     *
     * @throws SQLException if dropping the two branch schemas for a clean run fails
     */
    @Test
    void postgresStoresOnDifferentBranchesAreIsolated() throws SQLException {
        // given a reachable Postgres with the two branch schemas dropped for a clean, repeatable run
        assumePg();
        dropBranchSchemas();

        DataStore storeA = DataStoreFactory.fromConfig(null, POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD,
                null, BRANCH_A);
        DataStore storeB = DataStoreFactory.fromConfig(null, POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD,
                null, BRANCH_B);

        try {
            // when a distinct failed-suite set is persisted into each branch's schema
            storeA.getTiaData(true);
            storeA.persistTestSuitesFailed(new HashSet<>(SUITES_A));
            storeB.getTiaData(true);
            storeB.persistTestSuitesFailed(new HashSet<>(SUITES_B));

            // then each store reads back exactly its own branch's data, never the other's
            assertEquals(SUITES_A, storeA.getTestSuitesFailed(), "branchA should see only its own failed suites");
            assertEquals(SUITES_B, storeB.getTestSuitesFailed(), "branchB should see only its own failed suites");
        } finally {
            storeA.close();
            storeB.close();
        }
    }

    /**
     * Skip the Postgres test (rather than fail it) when no local Postgres instance is reachable, via
     * a quick raw TCP connect with a short timeout. Mirrors {@link DataStoreFactoryPostgresTest} and
     * {@link PostgresPersistTest}'s guards so the normal build stays green without the
     * {@code spike/postgres/} harness running.
     */
    private static void assumePg() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 500);
        } catch (IOException e) {
            assumeTrue(false, "spike Postgres not running");
        }
    }

    /**
     * Drop the two branch schemas used by this test ({@code tia_brancha} and {@code tia_branchb},
     * the exact names {@link BranchSchema#schemaName} derives for {@link #BRANCH_A} and
     * {@link #BRANCH_B}) so each run starts clean and is repeatable regardless of prior runs.
     *
     * @throws SQLException if the drop connection or statements fail
     */
    private static void dropBranchSchemas() throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP SCHEMA IF EXISTS " + BranchSchema.schemaName(BRANCH_A) + " CASCADE");
            statement.executeUpdate("DROP SCHEMA IF EXISTS " + BranchSchema.schemaName(BRANCH_B) + " CASCADE");
        }
    }
}
