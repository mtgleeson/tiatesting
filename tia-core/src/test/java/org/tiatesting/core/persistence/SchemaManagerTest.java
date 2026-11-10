package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link SchemaManager}'s bootstrap contract: the first {@code ensureSchema} on an empty
 * database creates every table and reports the DB as new, an existing DB runs no DDL, and later
 * calls are memoised.
 */
class SchemaManagerTest {

    @Test
    void ensureSchemaCreatesTablesOnFirstContactAndReportsNewDb(@TempDir Path dir) throws Exception {
        // given a schema manager over an empty embedded H2 database
        H2ConnectionProvider provider = new H2ConnectionProvider(H2ConnectionSettings.embedded(dir.toString()));
        H2Dialect dialect = new H2Dialect();
        SchemaManager schemaManager = new SchemaManager(dialect, opening(provider, new AtomicInteger()), provider.jdbcUrl());

        try (Connection connection = provider.get()) {
            // when the schema is ensured for the first time
            boolean existed = schemaManager.ensureSchema(connection);

            // then the DB is reported as just created and the core, mapping and distributed tables exist
            assertFalse(existed);
            assertTrue(schemaManager.checkTableExists(connection, TiaSchema.TABLE_TIA_CORE));
            assertTrue(schemaManager.checkTableExists(connection, TiaSchema.TABLE_TIA_SOURCE_CLASS_METHOD));
            assertTrue(schemaManager.checkTableExists(connection, TiaSchema.TABLE_TIA_DISTRIBUTED_RUN));
            assertTrue(schemaManager.checkTableExists(connection, TiaSchema.TABLE_TIA_TEST_RUN_HISTORY_TRIGGER));
        } finally {
            provider.close();
        }
    }

    @Test
    void ensureSchemaCreatesEveryTiaSchemaTableOnFirstContact(@TempDir Path dir) throws Exception {
        // given a schema manager over an empty embedded H2 database, and every table name
        // TiaSchema declares
        H2ConnectionProvider provider = new H2ConnectionProvider(H2ConnectionSettings.embedded(dir.toString()));
        SchemaManager schemaManager = new SchemaManager(new H2Dialect(), opening(provider, new AtomicInteger()), provider.jdbcUrl());
        List<String> tableNames = declaredTableNames();

        try (Connection connection = provider.get()) {
            // when the schema is ensured for the first time
            schemaManager.ensureSchema(connection);

            // then every declared table exists - there are no migrations to add one later
            assertFalse(tableNames.isEmpty());
            for (String tableName : tableNames) {
                assertTrue(schemaManager.checkTableExists(connection, tableName), tableName + " was not created");
            }
        } finally {
            provider.close();
        }
    }

    @Test
    void groupSuiteRowsCarryObservedAndUnrunnableFlagsDefaultingToFalse(@TempDir Path dir) throws Exception {
        // given a freshly bootstrapped database
        H2ConnectionProvider provider = new H2ConnectionProvider(H2ConnectionSettings.embedded(dir.toString()));
        SchemaManager schemaManager = new SchemaManager(new H2Dialect(), opening(provider, new AtomicInteger()), provider.jdbcUrl());

        try (Connection connection = provider.get()) {
            schemaManager.ensureSchema(connection);
            try (Statement statement = connection.createStatement()) {
                // when a plan row is inserted naming only the assignment columns, as the plan write does
                statement.execute("INSERT INTO " + TiaSchema.TABLE_TIA_DISTRIBUTED_RUN_GROUP_SUITE + " ("
                        + TiaSchema.COL_RUN_ID + ", " + TiaSchema.COL_GROUP_NUMBER + ", "
                        + TiaSchema.COL_TEST_SUITE_NAME + ") VALUES ('run-1', 0, 'com.example.FooTest')");

                // then both completeness flags default to false
                try (ResultSet resultSet = statement.executeQuery("SELECT " + TiaSchema.COL_OBSERVED + ", "
                        + TiaSchema.COL_UNRUNNABLE + " FROM " + TiaSchema.TABLE_TIA_DISTRIBUTED_RUN_GROUP_SUITE)) {
                    assertTrue(resultSet.next());
                    assertFalse(resultSet.getBoolean(1));
                    assertFalse(resultSet.getBoolean(2));
                }
            }
        } finally {
            provider.close();
        }
    }

    @Test
    void existingDbMissingTheGroupSuiteFlagsGetsThemAddedOnce(@TempDir Path dir) throws Exception {
        // given an existing DB whose group-suite table predates the observed/unrunnable flags
        H2ConnectionProvider provider = new H2ConnectionProvider(H2ConnectionSettings.embedded(dir.toString()));
        try (Connection connection = provider.get()) {
            new SchemaManager(new H2Dialect(), opening(provider, new AtomicInteger()), provider.jdbcUrl())
                    .ensureSchema(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + TiaSchema.TABLE_TIA_DISTRIBUTED_RUN_GROUP_SUITE
                        + " DROP COLUMN " + TiaSchema.COL_OBSERVED);
                statement.execute("ALTER TABLE " + TiaSchema.TABLE_TIA_DISTRIBUTED_RUN_GROUP_SUITE
                        + " DROP COLUMN " + TiaSchema.COL_UNRUNNABLE);
            }

            // when two fresh managers (two later JVMs) ensure the schema in turn
            AtomicInteger openedByFirst = new AtomicInteger();
            boolean existed = new SchemaManager(new H2Dialect(), opening(provider, openedByFirst), provider.jdbcUrl())
                    .ensureSchema(connection);
            AtomicInteger openedBySecond = new AtomicInteger();
            new SchemaManager(new H2Dialect(), opening(provider, openedBySecond), provider.jdbcUrl())
                    .ensureSchema(connection);

            // then the first adds both columns on a connection of its own, and the second finds
            // them already there and opens nothing
            assertTrue(existed);
            assertEquals(1, openedByFirst.get());
            assertEquals(0, openedBySecond.get());
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT " + TiaSchema.COL_OBSERVED + ", "
                         + TiaSchema.COL_UNRUNNABLE + " FROM " + TiaSchema.TABLE_TIA_DISTRIBUTED_RUN_GROUP_SUITE)) {
                assertFalse(resultSet.next());
            }
        } finally {
            provider.close();
        }
    }

    @Test
    void existingDbMissingTheTestSupportCounterGetsItAddedOnce(@TempDir Path dir) throws Exception {
        // given an existing DB whose history and selection-staging tables predate the counter
        H2ConnectionProvider provider = new H2ConnectionProvider(H2ConnectionSettings.embedded(dir.toString()));
        try (Connection connection = provider.get()) {
            new SchemaManager(new H2Dialect(), opening(provider, new AtomicInteger()), provider.jdbcUrl())
                    .ensureSchema(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + TiaSchema.TABLE_TIA_TEST_RUN_HISTORY
                        + " DROP COLUMN " + TiaSchema.COL_NUM_TEST_SUPPORT_DEPENDANTS);
                statement.execute("ALTER TABLE " + TiaSchema.TABLE_TIA_DISTRIBUTED_RUN_SELECTION
                        + " DROP COLUMN " + TiaSchema.COL_NUM_TEST_SUPPORT_DEPENDANTS);
            }

            // when two fresh managers (two later JVMs) ensure the schema in turn
            AtomicInteger openedByFirst = new AtomicInteger();
            boolean existed = new SchemaManager(new H2Dialect(), opening(provider, openedByFirst), provider.jdbcUrl())
                    .ensureSchema(connection);
            AtomicInteger openedBySecond = new AtomicInteger();
            new SchemaManager(new H2Dialect(), opening(provider, openedBySecond), provider.jdbcUrl())
                    .ensureSchema(connection);

            // then the first adds the column to both tables on a connection of its own, and the
            // second finds them already there and opens nothing
            assertTrue(existed);
            assertEquals(1, openedByFirst.get());
            assertEquals(0, openedBySecond.get());
            try (Statement statement = connection.createStatement()) {
                for (String table : new String[]{TiaSchema.TABLE_TIA_TEST_RUN_HISTORY,
                        TiaSchema.TABLE_TIA_DISTRIBUTED_RUN_SELECTION}) {
                    try (ResultSet resultSet = statement.executeQuery("SELECT "
                            + TiaSchema.COL_NUM_TEST_SUPPORT_DEPENDANTS + " FROM " + table)) {
                        assertFalse(resultSet.next());
                    }
                }
            }
        } finally {
            provider.close();
        }
    }

    @Test
    void existingDbMissingTheTestRunTablesGetsThemAddedOnce(@TempDir Path dir) throws Exception {
        // given an existing DB created before the run and contribution tables existed
        H2ConnectionProvider provider = new H2ConnectionProvider(H2ConnectionSettings.embedded(dir.toString()));
        try (Connection connection = provider.get()) {
            new SchemaManager(new H2Dialect(), opening(provider, new AtomicInteger()), provider.jdbcUrl())
                    .ensureSchema(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE " + TiaSchema.TABLE_TIA_TEST_RUN_JVM);
                statement.execute("DROP TABLE " + TiaSchema.TABLE_TIA_TEST_RUN);
            }

            // when two fresh managers (two later JVMs) ensure the schema in turn
            AtomicInteger openedByFirst = new AtomicInteger();
            new SchemaManager(new H2Dialect(), opening(provider, openedByFirst), provider.jdbcUrl())
                    .ensureSchema(connection);
            AtomicInteger openedBySecond = new AtomicInteger();
            new SchemaManager(new H2Dialect(), opening(provider, openedBySecond), provider.jdbcUrl())
                    .ensureSchema(connection);

            // then the first creates both tables on a connection of its own, and the second opens nothing
            assertEquals(1, openedByFirst.get());
            assertEquals(0, openedBySecond.get());
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT * FROM " + TiaSchema.TABLE_TIA_TEST_RUN_JVM)) {
                assertFalse(resultSet.next());
            }
        } finally {
            provider.close();
        }
    }

    @Test
    void existingDbMissingTheUpdatesMappingColumnGetsItAddedOnce(@TempDir Path dir) throws Exception {
        // given an existing DB whose distributed-run table predates the column and holds one run
        H2ConnectionProvider provider = new H2ConnectionProvider(H2ConnectionSettings.embedded(dir.toString()));
        try (Connection connection = provider.get()) {
            new SchemaManager(new H2Dialect(), opening(provider, new AtomicInteger()), provider.jdbcUrl())
                    .ensureSchema(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + TiaSchema.TABLE_TIA_DISTRIBUTED_RUN
                        + " DROP COLUMN " + TiaSchema.COL_UPDATES_MAPPING);
                statement.execute("DROP INDEX " + TiaSchema.IDX_DISTRIBUTED_RUN_TRIGGER_RUN_ID);
                statement.execute("INSERT INTO " + TiaSchema.TABLE_TIA_DISTRIBUTED_RUN
                        + " (run_id, branch, commit_value, status, group_count, estimated_total_ms, created_at)"
                        + " VALUES ('old-run', 'main', 'c1', 'OPEN', 1, 0, 1)");
            }

            // when two fresh managers (two later JVMs) ensure the schema in turn
            AtomicInteger openedByFirst = new AtomicInteger();
            boolean existed = new SchemaManager(new H2Dialect(), opening(provider, openedByFirst), provider.jdbcUrl())
                    .ensureSchema(connection);
            AtomicInteger openedBySecond = new AtomicInteger();
            new SchemaManager(new H2Dialect(), opening(provider, openedBySecond), provider.jdbcUrl())
                    .ensureSchema(connection);

            // then the first adds the column and index on a connection of its own, the existing row
            // reads back as a mapping-owning run, and the second finds them already there
            assertTrue(existed);
            assertEquals(1, openedByFirst.get());
            assertEquals(0, openedBySecond.get());
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT " + TiaSchema.COL_UPDATES_MAPPING
                         + " FROM " + TiaSchema.TABLE_TIA_DISTRIBUTED_RUN)) {
                assertTrue(resultSet.next());
                assertTrue(resultSet.getBoolean(1));
            }
            try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, connection.getSchema(),
                    TiaSchema.TABLE_TIA_DISTRIBUTED_RUN_TRIGGER.toUpperCase(), false, false)) {
                boolean found = false;
                while (indexes.next()) {
                    found |= TiaSchema.IDX_DISTRIBUTED_RUN_TRIGGER_RUN_ID.equalsIgnoreCase(indexes.getString("INDEX_NAME"));
                }
                assertTrue(found, "the trigger run_id index should exist after the migration");
            }
        } finally {
            provider.close();
        }
    }

    @Test
    void existingDbWithTheGroupCounterColumnsGetsThemDroppedOnce(@TempDir Path dir) throws Exception {
        // given an existing DB whose group table still stores the figures on its row
        H2ConnectionProvider provider = new H2ConnectionProvider(H2ConnectionSettings.embedded(dir.toString()));
        try (Connection connection = provider.get()) {
            new SchemaManager(new H2Dialect(), opening(provider, new AtomicInteger()), provider.jdbcUrl())
                    .ensureSchema(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + TiaSchema.TABLE_TIA_DISTRIBUTED_RUN_GROUP + " ADD COLUMN "
                        + TiaSchema.COL_ACTUAL_DURATION_MS + " BIGINT");
                statement.execute("ALTER TABLE " + TiaSchema.TABLE_TIA_DISTRIBUTED_RUN_GROUP + " ADD COLUMN "
                        + TiaSchema.COL_SUITES_RAN + " INT DEFAULT 0");
                statement.execute("ALTER TABLE " + TiaSchema.TABLE_TIA_DISTRIBUTED_RUN_GROUP + " ADD COLUMN "
                        + TiaSchema.COL_SUITES_FAILED + " INT DEFAULT 0");
                statement.execute("ALTER TABLE " + TiaSchema.TABLE_TIA_DISTRIBUTED_RUN_GROUP + " ADD COLUMN "
                        + TiaSchema.COL_SUITES_DURATION_MS + " BIGINT DEFAULT 0");
            }

            // when two fresh managers (two later JVMs) ensure the schema in turn
            AtomicInteger openedByFirst = new AtomicInteger();
            new SchemaManager(new H2Dialect(), opening(provider, openedByFirst), provider.jdbcUrl())
                    .ensureSchema(connection);
            AtomicInteger openedBySecond = new AtomicInteger();
            new SchemaManager(new H2Dialect(), opening(provider, openedBySecond), provider.jdbcUrl())
                    .ensureSchema(connection);

            // then the first drops all four columns on a connection of its own, and the second
            // opens nothing
            assertEquals(1, openedByFirst.get());
            assertEquals(0, openedBySecond.get());
            List<String> columns = new ArrayList<>();
            try (ResultSet resultSet = connection.getMetaData().getColumns(null, connection.getSchema(),
                    TiaSchema.TABLE_TIA_DISTRIBUTED_RUN_GROUP.toUpperCase(), null)) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString("COLUMN_NAME").toLowerCase());
                }
            }
            assertTrue(columns.contains(TiaSchema.COL_SUITES_OBSERVED));
            for (String dropped : new String[]{TiaSchema.COL_ACTUAL_DURATION_MS, TiaSchema.COL_SUITES_RAN,
                    TiaSchema.COL_SUITES_FAILED, TiaSchema.COL_SUITES_DURATION_MS}) {
                assertFalse(columns.contains(dropped), dropped + " was not dropped");
            }
        } finally {
            provider.close();
        }
    }

    @Test
    void ensureSchemaIsMemoisedAfterTheFirstCall(@TempDir Path dir) throws Exception {
        // given a schema manager that has already bootstrapped an empty database
        H2ConnectionProvider provider = new H2ConnectionProvider(H2ConnectionSettings.embedded(dir.toString()));
        AtomicInteger connectionsOpened = new AtomicInteger();
        SchemaManager schemaManager = new SchemaManager(new H2Dialect(), opening(provider, connectionsOpened), provider.jdbcUrl());

        try (Connection connection = provider.get()) {
            schemaManager.ensureSchema(connection);
            int openedByBootstrap = connectionsOpened.get();

            // when the schema is ensured again
            boolean existed = schemaManager.ensureSchema(connection);

            // then it reports the DB as existing without opening another connection to create tables
            assertTrue(existed);
            assertEquals(1, openedByBootstrap);
            assertEquals(openedByBootstrap, connectionsOpened.get());
        } finally {
            provider.close();
        }
    }

    @Test
    void ensureSchemaReportsExistingDbForAFreshManager(@TempDir Path dir) throws Exception {
        // given a database bootstrapped by one schema manager
        H2ConnectionProvider provider = new H2ConnectionProvider(H2ConnectionSettings.embedded(dir.toString()));
        try (Connection connection = provider.get()) {
            new SchemaManager(new H2Dialect(), opening(provider, new AtomicInteger()), provider.jdbcUrl())
                    .ensureSchema(connection);

            // when a second, fresh manager ensures the same schema
            AtomicInteger connectionsOpened = new AtomicInteger();
            boolean existed = new SchemaManager(new H2Dialect(), opening(provider, connectionsOpened), provider.jdbcUrl())
                    .ensureSchema(connection);

            // then it sees the existing DB and does not run the create path
            assertTrue(existed);
            assertEquals(0, connectionsOpened.get());
        } finally {
            provider.close();
        }
    }

    /**
     * Collect the value of every {@code TABLE_} constant declared on {@link TiaSchema}, so the
     * bootstrap test covers a newly added table without being edited.
     *
     * @return the declared table names
     * @throws IllegalAccessException if a constant cannot be read
     */
    private static List<String> declaredTableNames() throws IllegalAccessException {
        List<String> names = new ArrayList<>();
        for (Field field : TiaSchema.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getName().startsWith("TABLE_")) {
                field.setAccessible(true);
                names.add((String) field.get(null));
            }
        }
        return names;
    }

    /**
     * Build a connection supplier over the provider that counts the connections it opens.
     *
     * @param provider the provider to open connections from
     * @param counter incremented once per connection opened
     * @return the counting supplier
     */
    private static Supplier<Connection> opening(H2ConnectionProvider provider, AtomicInteger counter) {
        return () -> {
            counter.incrementAndGet();
            try {
                return provider.get();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        };
    }

    @Test
    void existingDbMissingTheCoverageFailureColumnGetsItAddedOnce(@TempDir Path dir) throws Exception {
        // given an existing DB whose run table predates the column and holds one run
        H2ConnectionProvider provider = new H2ConnectionProvider(H2ConnectionSettings.embedded(dir.toString()));
        try (Connection connection = provider.get()) {
            new SchemaManager(new H2Dialect(), opening(provider, new AtomicInteger()), provider.jdbcUrl())
                    .ensureSchema(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + TiaSchema.TABLE_TIA_TEST_RUN
                        + " DROP COLUMN " + TiaSchema.COL_COVERAGE_FAILURE);
                statement.execute("INSERT INTO " + TiaSchema.TABLE_TIA_TEST_RUN + " (id, start_ms) VALUES ('old-run', 1)");
            }

            // when two fresh managers (two later JVMs) ensure the schema in turn
            AtomicInteger openedByFirst = new AtomicInteger();
            boolean existed = new SchemaManager(new H2Dialect(), opening(provider, openedByFirst), provider.jdbcUrl())
                    .ensureSchema(connection);
            AtomicInteger openedBySecond = new AtomicInteger();
            new SchemaManager(new H2Dialect(), opening(provider, openedBySecond), provider.jdbcUrl())
                    .ensureSchema(connection);

            // then the first adds the column on a connection of its own, the existing run reads
            // back with no coverage failure, and the second finds it already there
            assertTrue(existed);
            assertEquals(1, openedByFirst.get());
            assertEquals(0, openedBySecond.get());
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT " + TiaSchema.COL_COVERAGE_FAILURE
                         + " FROM " + TiaSchema.TABLE_TIA_TEST_RUN)) {
                assertTrue(resultSet.next());
                assertNull(resultSet.getString(1));
            }
        } finally {
            provider.close();
        }
    }
}
