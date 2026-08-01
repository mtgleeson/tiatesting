package org.tiatesting.core.persistence;

import org.tiatesting.core.persistence.connection.ConnectionProvider;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.connection.JdbcConnectionProvider;
import org.tiatesting.core.persistence.connection.PostgresConnectionProvider;
import org.tiatesting.core.persistence.dialect.SqlDialect;
import org.tiatesting.core.persistence.dialect.SqlDialectRegistry;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

/**
 * Builds a {@link DataStore} for the configured (or inferred) SQL dialect, so build-tool plugins
 * and test-runner listeners construct a datastore through one place instead of hard-coding
 * {@code new JdbcDataStore(new H2Dialect(), ...)} at every call site. H2 gets its own
 * {@link H2ConnectionProvider} branch for its embedded/server-mode handling; Postgres gets
 * {@link PostgresConnectionProvider} for its auto-create wiring; every other non-H2 dialect
 * shares a plain {@link JdbcConnectionProvider} built from {@code dbUrl}/{@code user}/{@code password}.
 * See the pluggable-datastore WIKI chapter.
 */
public final class DataStoreFactory {

    /** System property holding an explicit dialect override id (e.g. {@code "h2"}). */
    public static final String PROP_DB_DIALECT = "tiaDBDialect";

    private DataStoreFactory() {
    }

    /**
     * Build a {@link DataStore} from explicit configuration values, resolving the dialect from
     * {@code dialectOverride} (if set) or by sniffing {@code dbUrl}, then constructing the
     * matching {@link ConnectionProvider} and wrapping it in a {@link JdbcDataStore}.
     *
     * @param dbFilePath     embedded-mode database directory (H2: used only when {@code dbUrl} is blank)
     * @param dbUrl          server-mode JDBC URL, or {@code null}/blank for embedded mode
     * @param user           database username
     * @param password       database password
     * @param dialectOverride an explicit dialect id (e.g. {@code "h2"}), or {@code null}/blank to
     *                        infer the dialect from {@code dbUrl}
     * @param branch         VCS branch name, used to derive the per-branch schema
     *                       ({@link BranchSchema#schemaName(String)}) selected on each connection
     * @return the constructed {@link DataStore} for the resolved dialect
     * @throws IllegalArgumentException if the dialect cannot be resolved (see
     *         {@link SqlDialectRegistry#forUrl(String, String)})
     * @throws IllegalStateException if the resolved dialect is not H2 and its JDBC driver is not on
     *         the classpath (see {@link #missingDriverMessage(String)})
     */
    public static DataStore fromConfig(final String dbFilePath, final String dbUrl, final String user,
                                       final String password, final String dialectOverride, final String branch) {
        SqlDialect dialect = SqlDialectRegistry.forUrl(dbUrl, dialectOverride);
        String schema = BranchSchema.schemaName(branch);

        if ("h2".equals(dialect.id())) {
            H2ConnectionSettings settings = H2ConnectionSettings.fromConfig(dbFilePath, dbUrl, user, password);
            ConnectionProvider connectionProvider = new H2ConnectionProvider(settings);
            return new JdbcDataStore(dialect, connectionProvider, schema);
        }

        requireDriverPresent(dialect.id());
        ConnectionProvider connectionProvider = "postgres".equals(dialect.id())
                ? new PostgresConnectionProvider(dbUrl, user, password)
                : new JdbcConnectionProvider(dbUrl, user, password);
        return new JdbcDataStore(dialect, connectionProvider, schema);
    }

    /**
     * Guard against opening a non-H2 connection when its JDBC driver was never added to the
     * classpath, which otherwise surfaces as an opaque {@code No suitable driver found} SQLException
     * from deep inside {@link java.sql.DriverManager}. H2 is bundled with Tia so it never reaches
     * this check (see {@link #fromConfig}); every other dialect is resolved to a driver class via
     * {@link SqlDialectRegistry#driverClassName(String)} and probed with {@link Class#forName}.
     *
     * @param dialectId the resolved dialect id, e.g. {@code "postgres"}
     * @throws IllegalStateException if the dialect's driver class is not on the classpath
     */
    private static void requireDriverPresent(final String dialectId) {
        String driverClassName = SqlDialectRegistry.driverClassName(dialectId);
        if (driverClassName == null) {
            return;
        }
        try {
            Class.forName(driverClassName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(missingDriverMessage(dialectId));
        }
    }

    /**
     * Build the actionable error message shown when a non-H2 dialect's JDBC driver is missing from
     * the classpath. Names the vendor and points at the two-classpath model a pluggable dialect
     * needs: a test-scope dependency in the project under test, and a dependency of the Tia
     * build-tool plugin itself (see the pluggable-datastore WIKI chapter).
     *
     * @param dialectId the dialect id whose driver could not be found, e.g. {@code "postgres"}
     * @return the actionable, user-facing error message
     */
    static String missingDriverMessage(final String dialectId) {
        return "Tia could not find the " + dialectId + " JDBC driver on the classpath. Add the driver "
                + "as a test-scope dependency in your project AND as a dependency of the Tia plugin "
                + "(see the pluggable-datastore WIKI chapter).";
    }

    /**
     * Build a {@link DataStore} from the Tia system properties set on the forked test JVM by the
     * build-tool plugins: {@value H2ConnectionSettings#PROP_DB_URL} /
     * {@value H2ConnectionSettings#PROP_DB_USER} / {@value H2ConnectionSettings#PROP_DB_PASSWORD}
     * for server mode, falling back to {@value H2ConnectionSettings#PROP_DB_FILE_PATH} for embedded
     * mode, plus the optional {@value #PROP_DB_DIALECT} override. Used by the JUnit/Spock
     * test-runner listeners, which read connection config from system properties rather than a
     * build-tool extension.
     *
     * @param branch the VCS branch name, used to derive the per-branch schema selected on each
     *               connection
     * @return the constructed {@link DataStore} for the resolved dialect
     * @throws IllegalArgumentException if the dialect cannot be resolved (see
     *         {@link SqlDialectRegistry#forUrl(String, String)})
     */
    public static DataStore fromSystemProperties(final String branch) {
        return fromConfig(
                System.getProperty(H2ConnectionSettings.PROP_DB_FILE_PATH),
                System.getProperty(H2ConnectionSettings.PROP_DB_URL),
                System.getProperty(H2ConnectionSettings.PROP_DB_USER),
                System.getProperty(H2ConnectionSettings.PROP_DB_PASSWORD),
                System.getProperty(PROP_DB_DIALECT),
                branch);
    }
}
