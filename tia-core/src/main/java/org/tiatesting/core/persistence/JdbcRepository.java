package org.tiatesting.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.persistence.dialect.SqlDialect;

import java.sql.Connection;
import java.util.function.Supplier;

/**
 * Base for the JDBC repositories {@link JdbcDataStore} delegates to. Each repository owns the
 * reads and writes for one area of the Tia schema; this base carries what they all share - the
 * connection source (already pinned to the per-branch schema), the SQL dialect, and the
 * {@link SchemaManager} that bootstraps and migrates the schema.
 */
abstract class JdbcRepository {
    // Upper bound on parameterized IN-clause size for the targeted queries. Keeps each
    // statement well under H2's parameter limits and bounds per-statement parse cost;
    // larger inputs are split into multiple queries and merged client-side.
    static final int IN_CLAUSE_CHUNK_SIZE = 1000;
    final Logger log = LoggerFactory.getLogger(getClass());
    final SqlDialect dialect;
    final SchemaManager schemaManager;
    private final Supplier<Connection> connections;

    /**
     * Construct a repository over the datastore's shared JDBC plumbing.
     *
     * @param dialect the SQL dialect rendering vendor-specific statements
     * @param connections opens a connection with the per-branch schema selected
     * @param schemaManager bootstraps and migrates the schema before first use
     */
    JdbcRepository(SqlDialect dialect, Supplier<Connection> connections, SchemaManager schemaManager) {
        this.dialect = dialect;
        this.connections = connections;
        this.schemaManager = schemaManager;
    }

    /**
     * Open a connection with the per-branch schema selected.
     *
     * @return an open connection the caller must close
     * @throws TiaPersistenceException if the connection cannot be opened or the schema selected
     */
    Connection getConnection() {
        return connections.get();
    }
}
