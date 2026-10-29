package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TEMPORARY: covers the one-off migrations that add the {@code observed} and {@code unrunnable}
 * columns to an existing {@code tia_distributed_run_group_suite} table, and the {@code
 * num_test_support_dependants} column to the history and selection-staging tables, against a real
 * Postgres -
 * Postgres folds identifiers to lower case, unlike H2, so the column-existence check is exercised on
 * both. Remove each test together with its migration in {@link SchemaManager}.
 *
 * <p>Skipped (not failed) when no Postgres is reachable on {@code localhost:5432}, exactly like
 * {@link PostgresClaimTest}.
 */
class PostgresSchemaMigrationTest {

    private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/tiaperf";
    private static final String POSTGRES_USER = "tia";
    private static final String POSTGRES_PASSWORD = "tia";
    private static final String BRANCH = "migration";

    private DataStore postgresStore;

    /**
     * Skip the test when the local Postgres instance is not reachable.
     */
    private static void assumePg() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 500);
        } catch (IOException e) {
            assumeTrue(false, "spike Postgres not running");
        }
    }

    /**
     * Run statements on a raw connection with the test branch's schema on the search path.
     *
     * @param sql the statements to run in order
     * @throws SQLException if a statement fails
     */
    private static void executeInBranchSchema(final String... sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO \"" + BranchSchema.schemaName(BRANCH, null) + "\"");
            for (String each : sql) {
                statement.execute(each);
            }
        }
    }

    /**
     * Skip when Postgres is unreachable, then drop the branch schema so the test creates it from
     * scratch.
     *
     * @throws Exception if the guard or the drop fails
     */
    @BeforeEach
    void setUp() throws Exception {
        assumePg();
        try (Connection connection = DriverManager.getConnection(POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP SCHEMA IF EXISTS " + BranchSchema.schemaName(BRANCH, null) + " CASCADE");
        }
    }

    /**
     * Close the datastore the test opened.
     */
    @AfterEach
    void tearDown() {
        if (postgresStore != null) {
            postgresStore.close();
        }
    }

    @Test
    void existingPostgresSchemaMissingTheGroupSuiteFlagsGetsThemAdded() throws Exception {
        // given a schema created by one store, then stripped back to the pre-flag table shape
        DataStore creator = DataStoreFactory.fromConfig(null, POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD,
                null, BRANCH, null);
        creator.getTiaData();
        creator.close();
        executeInBranchSchema(
                "ALTER TABLE " + TiaSchema.TABLE_TIA_DISTRIBUTED_RUN_GROUP_SUITE + " DROP COLUMN " + TiaSchema.COL_OBSERVED,
                "ALTER TABLE " + TiaSchema.TABLE_TIA_DISTRIBUTED_RUN_GROUP_SUITE + " DROP COLUMN " + TiaSchema.COL_UNRUNNABLE);

        // when a later store makes first contact
        postgresStore = DataStoreFactory.fromConfig(null, POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD,
                null, BRANCH, null);
        postgresStore.getTiaData();

        // then both columns are back
        try (Connection connection = DriverManager.getConnection(POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT " + TiaSchema.COL_OBSERVED + ", "
                     + TiaSchema.COL_UNRUNNABLE + " FROM " + BranchSchema.schemaName(BRANCH, null) + "."
                     + TiaSchema.TABLE_TIA_DISTRIBUTED_RUN_GROUP_SUITE)) {
            assertFalse(resultSet.next());
        }
    }

    @Test
    void existingPostgresSchemaMissingTheTestSupportCounterGetsItAdded() throws Exception {
        // given a schema created by one store, then stripped back to the pre-counter table shapes
        DataStore creator = DataStoreFactory.fromConfig(null, POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD,
                null, BRANCH, null);
        creator.getTiaData();
        creator.close();
        executeInBranchSchema(
                "ALTER TABLE " + TiaSchema.TABLE_TIA_TEST_RUN_HISTORY + " DROP COLUMN "
                        + TiaSchema.COL_NUM_TEST_SUPPORT_DEPENDANTS,
                "ALTER TABLE " + TiaSchema.TABLE_TIA_DISTRIBUTED_RUN_SELECTION + " DROP COLUMN "
                        + TiaSchema.COL_NUM_TEST_SUPPORT_DEPENDANTS);

        // when a later store makes first contact
        postgresStore = DataStoreFactory.fromConfig(null, POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD,
                null, BRANCH, null);
        postgresStore.getTiaData();

        // then the column is back on both tables
        try (Connection connection = DriverManager.getConnection(POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD);
             Statement statement = connection.createStatement()) {
            for (String table : new String[]{TiaSchema.TABLE_TIA_TEST_RUN_HISTORY,
                    TiaSchema.TABLE_TIA_DISTRIBUTED_RUN_SELECTION}) {
                try (ResultSet resultSet = statement.executeQuery("SELECT "
                        + TiaSchema.COL_NUM_TEST_SUPPORT_DEPENDANTS + " FROM "
                        + BranchSchema.schemaName(BRANCH, null) + "." + table)) {
                    assertFalse(resultSet.next());
                }
            }
        }
    }
}
