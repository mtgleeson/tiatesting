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
     * Release any process-level resources this provider holds open (for example an embedded
     * database file lock), issuing any vendor-specific shutdown needed before a different process
     * can open the same database. Called from {@code JdbcDataStore.close()}. The default is a no-op
     * so networked providers (which hold no process-level lock) need not override it.
     */
    default void close() { }
}
