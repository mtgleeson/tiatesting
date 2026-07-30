package org.tiatesting.core.persistence.connection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Generic {@link ConnectionProvider} for any vendor reachable via a plain JDBC URL through
 * {@link DriverManager} (e.g. Postgres), as opposed to {@link H2ConnectionProvider}'s H2-specific
 * embedded/server-mode handling. See the pluggable-datastore WIKI chapter for the surrounding
 * dialect/connection-provider design.
 */
public class JdbcConnectionProvider implements ConnectionProvider {

    private final String jdbcUrl;
    private final String user;
    private final String password;

    /**
     * Construct a connection provider for a fixed JDBC URL and credentials.
     *
     * @param jdbcUrl  the JDBC URL to connect to
     * @param user     the database username
     * @param password the database password
     */
    public JdbcConnectionProvider(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    /**
     * Open a new connection via {@link DriverManager} using the configured URL and credentials.
     *
     * @return an open JDBC connection
     * @throws SQLException if the connection cannot be established
     */
    @Override
    public Connection get() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    /**
     * Expose the configured JDBC URL this provider connects with.
     *
     * @return the JDBC URL in use for this provider
     */
    @Override
    public String jdbcUrl() {
        return jdbcUrl;
    }
}
