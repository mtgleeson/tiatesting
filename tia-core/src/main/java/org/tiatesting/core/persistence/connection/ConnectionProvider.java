package org.tiatesting.core.persistence.connection;

import java.sql.Connection;
import java.sql.SQLException;

/** Vendor-specific JDBC connection acquisition + lifecycle for the Tia datastore. */
public interface ConnectionProvider {
    /**
     * Open (or reuse) a connection to the configured database.
     * @return an open JDBC connection
     * @throws SQLException on connection failure
     */
    Connection get() throws SQLException;

    /**
     * The JDBC URL this provider connects to (password-free), for logging/errors.
     * @return the JDBC URL
     */
    String jdbcUrl();

    /**
     * A human-readable, password-free summary of the datastore vendor and connection this provider
     * targets, used by {@code JdbcDataStore} for the one-line "Using ... as the Tia datastore" INFO
     * logged when a datastore is created. Excludes the schema (the datastore owns that) and never
     * includes the password.
     * @return the connection summary (e.g. "H2 as the Tia datastore in server mode with the
     *         connection: jdbc:h2:tcp://localhost:9092/tiadb")
     */
    String connectionSummary();

    /**
     * Release any process-level resources this provider holds open (for example an embedded
     * database file lock), issuing any vendor-specific shutdown needed before a different process
     * can open the same database. Called from {@code JdbcDataStore.close()}. The default is a no-op
     * so networked providers (which hold no process-level lock) need not override it.
     */
    default void close() { }
}
