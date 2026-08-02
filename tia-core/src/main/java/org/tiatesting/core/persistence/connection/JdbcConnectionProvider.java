package org.tiatesting.core.persistence.connection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Generic {@link ConnectionProvider} for any vendor reachable via a plain JDBC URL through
 * {@link DriverManager} (e.g. Postgres), as opposed to {@link H2ConnectionProvider}'s H2-specific
 * embedded/server-mode handling. See the pluggable-datastore WIKI chapter for the surrounding
 * dialect/connection-provider design.
 *
 * <p>Supplies the same datastore logging inputs {@link H2ConnectionProvider} does: a password-free
 * {@link #connectionSummary()} that {@code JdbcDataStore} logs at INFO when the datastore is
 * created, and a per-connection DEBUG line on each successful acquire. Neither includes the password.
 */
public class JdbcConnectionProvider implements ConnectionProvider {

    private final Logger log = LoggerFactory.getLogger(JdbcConnectionProvider.class);
    private final String datastoreName;
    private final String jdbcUrl;
    private final String user;
    private final String password;

    /**
     * Construct a connection provider for a fixed JDBC URL and credentials.
     *
     * @param datastoreName the human-readable datastore name used in the connection log lines
     *                      (e.g. {@code "Postgres"})
     * @param jdbcUrl       the JDBC URL to connect to
     * @param user          the database username
     * @param password      the database password
     */
    public JdbcConnectionProvider(String datastoreName, String jdbcUrl, String user, String password) {
        this.datastoreName = datastoreName;
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    /**
     * Build the password-free datastore/connection summary for the "Using ... as the Tia datastore"
     * INFO line, reporting the datastore name and the JDBC URL. The schema is appended by
     * {@code JdbcDataStore}, not here.
     *
     * @return the connection summary including the datastore name and the JDBC URL
     */
    @Override
    public String connectionSummary() {
        return datastoreName + " as the Tia datastore with the connection: " + jdbcUrl;
    }

    /**
     * Open a new connection via {@link DriverManager} using the configured URL and credentials,
     * logging the successful connection at DEBUG (password-free), mirroring
     * {@link H2ConnectionProvider}.
     *
     * @return an open JDBC connection
     * @throws SQLException if the connection cannot be established
     */
    @Override
    public Connection get() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
        log.debug("Connected to the {} database {}", datastoreName, jdbcUrl);
        return connection;
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
