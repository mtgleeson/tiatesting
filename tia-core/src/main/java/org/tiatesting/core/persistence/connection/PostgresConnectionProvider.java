package org.tiatesting.core.persistence.connection;

/**
 * Postgres-specific {@link ConnectionProvider}. Beyond the generic {@link JdbcConnectionProvider}
 * behaviour it inherits, it auto-creates the configured database when it does not yet exist and the
 * connecting role is allowed to (see {@code get()} in a later stage), bringing Postgres to parity
 * with H2's auto-create. This class also owns the pure helpers that derive the maintenance-connection
 * URL and build the CREATEDB-gate error message. See the pluggable-datastore WIKI chapter.
 */
public class PostgresConnectionProvider extends JdbcConnectionProvider {

    /** JDBC URL prefix every Postgres URL this provider handles starts with. */
    private static final String POSTGRES_URL_PREFIX = "jdbc:postgresql://";

    /** The always-present administrative database used as the maintenance-connection target. */
    static final String MAINTENANCE_DB = "postgres";

    /**
     * Construct a Postgres connection provider for a fixed JDBC URL and credentials.
     *
     * @param jdbcUrl  the target Postgres JDBC URL to connect to
     * @param user     the database username
     * @param password the database password
     */
    public PostgresConnectionProvider(final String jdbcUrl, final String user, final String password) {
        super(jdbcUrl, user, password);
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
