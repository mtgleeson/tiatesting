package org.tiatesting.core.persistence;

import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.dialect.SqlDialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * Test fixture wiring a {@link JdbcRepository} subclass straight onto an embedded H2 database in
 * a temp directory, without going through {@link JdbcDataStore}, so a repository can be unit
 * tested on its own.
 */
final class H2RepositoryFixture implements AutoCloseable {
    final SqlDialect dialect = new H2Dialect();
    final Supplier<Connection> connections;
    final SchemaManager schemaManager;
    private final H2ConnectionProvider provider;

    /**
     * Open an embedded H2 database in the given directory.
     *
     * @param dir the directory to hold the database file
     */
    H2RepositoryFixture(Path dir) {
        provider = new H2ConnectionProvider(H2ConnectionSettings.embedded(dir.toString()));
        connections = () -> {
            try {
                return provider.get();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        };
        schemaManager = new SchemaManager(dialect, connections, provider.jdbcUrl());
    }

    /**
     * Bootstrap the full Tia schema, as the first read of a real run does.
     *
     * @return this fixture, for chaining
     */
    H2RepositoryFixture withSchema() {
        try (Connection connection = connections.get()) {
            schemaManager.ensureSchema(connection);
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
        return this;
    }

    /**
     * Shut the embedded database down, releasing its file lock.
     */
    @Override
    public void close() {
        provider.close();
    }
}
