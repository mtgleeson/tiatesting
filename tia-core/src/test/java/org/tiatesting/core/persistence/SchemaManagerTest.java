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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
