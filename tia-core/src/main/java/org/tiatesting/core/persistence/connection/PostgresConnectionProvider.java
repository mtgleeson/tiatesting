package org.tiatesting.core.persistence.connection;

import org.tiatesting.core.persistence.TiaPersistenceException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Postgres-specific {@link ConnectionProvider}. Beyond the generic {@link JdbcConnectionProvider}
 * behaviour it inherits, it auto-creates the configured database when it does not yet exist and the
 * connecting role is allowed to (via the {@code get()} method), bringing Postgres to parity
 * with H2's auto-create. This class also owns the pure helpers that derive the maintenance-connection
 * URL and build the CREATEDB-gate error message. See the pluggable-datastore WIKI chapter.
 */
public class PostgresConnectionProvider extends JdbcConnectionProvider {

    /** JDBC URL prefix every Postgres URL this provider handles starts with. */
    private static final String POSTGRES_URL_PREFIX = "jdbc:postgresql://";

    /** Datastore name used in the connection log lines inherited from {@link JdbcConnectionProvider}. */
    private static final String DATASTORE_NAME = "Postgres";

    /** The always-present administrative database used as the maintenance-connection target. */
    static final String MAINTENANCE_DB = "postgres";

    /** SQLState raised by the driver when the target database does not exist. */
    private static final String SQLSTATE_DB_MISSING = "3D000";
    /** SQLState raised by {@code CREATE DATABASE} when the database already exists (create race). */
    private static final String SQLSTATE_DUPLICATE_DB = "42P04";
    /** SQLState raised by {@code CREATE DATABASE} when the role lacks the CREATEDB privilege. */
    private static final String SQLSTATE_INSUFFICIENT_PRIVILEGE = "42501";

    private final String user;
    private final String password;

    /**
     * Construct a Postgres connection provider for a fixed JDBC URL and credentials. The
     * maintenance-connection URL and target database name are derived lazily on the auto-create
     * path (not here), so an unusual but driver-valid URL shape that the auto-create path cannot
     * derive a maintenance target from does not fail construction - it falls back to the generic
     * connect behaviour. See the pluggable-datastore WIKI chapter.
     *
     * @param jdbcUrl  the target Postgres JDBC URL to connect to
     * @param user     the database username
     * @param password the database password
     */
    public PostgresConnectionProvider(final String jdbcUrl, final String user, final String password) {
        super(DATASTORE_NAME, jdbcUrl, user, password);
        this.user = user;
        this.password = password;
    }

    /**
     * Open a connection, auto-creating the target database first if it does not yet exist. The normal
     * path is a plain {@link JdbcConnectionProvider#get()}; only a {@code 3D000} (database does not
     * exist) failure triggers the create-then-retry. Every other connection failure propagates
     * unchanged. This brings Postgres to parity with H2, which already auto-creates its database on
     * first use. See the pluggable-datastore WIKI chapter.
     *
     * @return an open connection to the (now-existing) target database
     * @throws SQLException if the connection or the database creation fails for a reason other than
     *         the missing database, or if the URL shape has no derivable maintenance target (the
     *         original missing-database error is surfaced unchanged in that case)
     */
    @Override
    public Connection get() throws SQLException {
        try {
            return super.get();
        } catch (SQLException e) {
            if (!SQLSTATE_DB_MISSING.equals(e.getSQLState())) {
                throw e;
            }
            createDatabaseViaMaintenance(e);
            return super.get();
        }
    }

    /**
     * Create the target database over a maintenance connection to the {@code postgres} administrative
     * database ({@code CREATE DATABASE} cannot run while connected to the target). The maintenance URL
     * and database name are derived here (lazily); if the configured URL shape has no derivable
     * maintenance target, the original missing-database error is surfaced unchanged, so the provider
     * falls back to the generic connect behaviour rather than auto-creating. A concurrent creator
     * winning the race ({@code 42P04}) is treated as success. A role lacking {@code CREATEDB}
     * ({@code 42501}) is surfaced as a {@link TiaPersistenceException} whose message both explains the
     * fix and embeds the driver's original message inline, with the original exception chained as the
     * cause. Any other failure propagates unchanged. The database identifier is quoted, doubling any
     * embedded double-quote so the quoting is total.
     *
     * @param databaseMissing the original {@code 3D000} exception, re-thrown unchanged when no
     *                        maintenance target can be derived from the configured URL
     * @throws SQLException if the maintenance connection fails, {@code CREATE DATABASE} fails for a
     *         reason other than the database already existing or the CREATEDB gate, or no maintenance
     *         target can be derived (the original missing-database error is re-thrown)
     */
    private void createDatabaseViaMaintenance(final SQLException databaseMissing) throws SQLException {
        final String maintenanceUrl;
        final String databaseName;
        try {
            maintenanceUrl = maintenanceUrl(jdbcUrl());
            databaseName = databaseName(jdbcUrl());
        } catch (IllegalArgumentException cannotDeriveMaintenanceTarget) {
            // A Postgres URL shape with no derivable maintenance target (for example the host-less
            // jdbc:postgresql:db form the driver accepts and SqlDialectRegistry routes here). Fall
            // back to the generic behaviour: surface the original missing-database error unchanged.
            throw databaseMissing;
        }
        try (Connection maintenance = DriverManager.getConnection(maintenanceUrl, user, password);
             Statement statement = maintenance.createStatement()) {
            statement.execute("CREATE DATABASE \"" + databaseName.replace("\"", "\"\"") + "\"");
        } catch (SQLException e) {
            if (SQLSTATE_DUPLICATE_DB.equals(e.getSQLState())) {
                return;
            }
            if (SQLSTATE_INSUFFICIENT_PRIVILEGE.equals(e.getSQLState())) {
                throw new TiaPersistenceException(
                        createDbPrivilegeErrorMessage(databaseName, e.getMessage()), e);
            }
            throw e;
        }
    }

    /**
     * Extract the target database name from a Postgres JDBC URL, for use as the {@code CREATE
     * DATABASE} identifier and in error messages.
     *
     * @param jdbcUrl the Postgres JDBC URL
     * @return the database segment of the URL
     * @throws IllegalArgumentException if the URL is not a Postgres URL or has no database segment
     */
    static String databaseName(final String jdbcUrl) {
        return parseSegments(jdbcUrl)[1];
    }

    /**
     * Derive the maintenance-connection URL by replacing the target URL's database segment with the
     * {@code postgres} administrative database, preserving the authority (host(s)/port) and any query
     * parameters. {@code CREATE DATABASE} cannot run while connected to the target database, so Tia
     * connects to this maintenance database to create it.
     *
     * @param jdbcUrl the target Postgres JDBC URL
     * @return the same URL with the database segment swapped to {@code postgres}
     * @throws IllegalArgumentException if the URL is not a Postgres URL or has no database segment
     */
    static String maintenanceUrl(final String jdbcUrl) {
        String[] segments = parseSegments(jdbcUrl);
        String authority = segments[0];
        String params = segments[2];
        return POSTGRES_URL_PREFIX + authority + "/" + MAINTENANCE_DB + params;
    }

    /**
     * Build the actionable message shown when the target database does not exist and the role lacks
     * {@code CREATEDB} to create it. The driver's own message is embedded inline (in addition to being
     * chained as the exception cause) so the underlying client error is always visible to the user,
     * even if the caller's logging does not print the cause chain.
     *
     * @param databaseName  the missing database name
     * @param driverMessage the JDBC driver's original error message
     * @return the actionable, user-facing error message
     */
    static String createDbPrivilegeErrorMessage(final String databaseName, final String driverMessage) {
        return "Tia datastore database \"" + databaseName + "\" does not exist and the configured role "
                + "lacks CREATEDB to create it. Create the database first, or grant CREATEDB to the role. "
                + "Original driver error: " + driverMessage;
    }

    /**
     * Split a Postgres JDBC URL {@code jdbc:postgresql://<authority>/<database>[?<params>]} into its
     * three parts. The authority runs from after the scheme prefix to the first {@code /}; the
     * database is from that {@code /} up to {@code ?} (or the end); the params (if any) are everything
     * from {@code ?} onward, including the leading {@code ?}.
     *
     * @param jdbcUrl the Postgres JDBC URL to split
     * @return a three-element array of {authority, database, params}
     * @throws IllegalArgumentException if the URL is not a Postgres URL, has no database segment, or
     *         has an empty database segment
     */
    private static String[] parseSegments(final String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith(POSTGRES_URL_PREFIX)) {
            throw new IllegalArgumentException("Not a Postgres JDBC URL: " + jdbcUrl);
        }
        String rest = jdbcUrl.substring(POSTGRES_URL_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("Postgres JDBC URL has no database segment: " + jdbcUrl);
        }
        String authority = rest.substring(0, slash);
        String afterAuthority = rest.substring(slash + 1);
        int question = afterAuthority.indexOf('?');
        String database = question < 0 ? afterAuthority : afterAuthority.substring(0, question);
        String params = question < 0 ? "" : afterAuthority.substring(question);
        if (database.isEmpty()) {
            throw new IllegalArgumentException("Postgres JDBC URL has an empty database segment: " + jdbcUrl);
        }
        return new String[]{authority, database, params};
    }
}
