package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link SchemaManager}'s bootstrap contract: the first {@code ensureSchema} on an empty
 * database creates the tables and reports the DB as new, and later calls are memoised.
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
