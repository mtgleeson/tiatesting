package org.tiatesting.core.persistence;

import org.tiatesting.core.persistence.connection.ConnectionProvider;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.connection.JdbcConnectionProvider;
import org.tiatesting.core.persistence.connection.PostgresConnectionProvider;
import org.tiatesting.core.persistence.dialect.SqlDialect;
import org.tiatesting.core.persistence.dialect.SqlDialectRegistry;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.util.function.Function;

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

    /**
     * The username H2 defaults to when none is configured and the environment supplies none. An
     * H2-specific convention, so it is not applied to any other dialect.
     */
    private static final String H2_DEFAULT_USER = "tia";

    /** System property holding an explicit dialect override id (e.g. {@code "h2"}). */
    public static final String PROP_DB_DIALECT = "tiaDBDialect";

    /**
     * System property naming the schema suffix a forked test JVM should isolate itself into. Unset
     * means no suffix, which is the schema Tia has always used for the branch - so a project that
     * never declares one is unaffected.
     */
    public static final String PROP_DB_SCHEMA_SUFFIX = "tiaDBSchemaSuffix";

    private DataStoreFactory() {
    }

    /**
     * Build a {@link DataStore} from explicit configuration values, resolving the dialect from
     * {@code dialectOverride} (if set) or by sniffing {@code dbUrl}, then constructing the
     * matching {@link ConnectionProvider} and wrapping it in a {@link JdbcDataStore}.
     *
     * @param dbFilePath     embedded-mode database directory (H2: used only when {@code dbUrl} is blank)
     * @param dbUrl          server-mode JDBC URL, or {@code null}/blank for embedded mode
     * @param user           the configured database username, or {@code null}/blank to fall back to
     *                       {@value CredentialResolver#ENV_DB_USER} and then, on H2 only, to
     *                       {@code tia}
     * @param password       the configured database password, or {@code null} to fall back to
     *                       {@value CredentialResolver#ENV_DB_PASSWORD}; an explicit empty string
     *                       is honoured verbatim
     * @param dialectOverride an explicit dialect id (e.g. {@code "h2"}), or {@code null}/blank to
     *                        infer the dialect from {@code dbUrl}
     * @param branch         VCS branch name, the base of the schema name
     *                       ({@link BranchSchema#schemaName(String, String)}) selected on each connection
     * @param schemaSuffix   the caller-declared schema suffix, or null for none. Isolates one
     *                       datastore per test task where several share a database; null gives the
     *                       schema Tia has always used for the branch
     * @return the constructed {@link DataStore} for the resolved dialect
     * @throws IllegalArgumentException if the dialect cannot be resolved (see
     *         {@link SqlDialectRegistry#forUrl(String, String)})
     * @throws IllegalStateException if the resolved dialect is not H2 and its JDBC driver is not on
     *         the classpath (see {@link #missingDriverMessage(String)})
     */
    public static DataStore fromConfig(final String dbFilePath, final String dbUrl, final String user,
                                       final String password, final String dialectOverride,
                                       final String branch, final String schemaSuffix) {
        return fromConfig(dbFilePath, dbUrl, user, password, dialectOverride, branch, schemaSuffix,
                System::getenv);
    }

    /**
     * Test seam for {@link #fromConfig(String, String, String, String, String, String, String)}:
     * takes the environment lookup as a parameter so the
     * {@value CredentialResolver#ENV_DB_USER} / {@value CredentialResolver#ENV_DB_PASSWORD}
     * fallback can be exercised for each dialect without mutating the real process environment.
     * Mirrors the seam {@code H2ConnectionSettings.server} already uses for the same reason.
     *
     * @param dbFilePath     embedded-mode database directory (H2: used only when {@code dbUrl} is blank)
     * @param dbUrl          server-mode JDBC URL, or {@code null}/blank for embedded mode
     * @param user           the configured database username, or {@code null}/blank to fall back
     * @param password       the configured database password, or {@code null} to fall back
     * @param dialectOverride an explicit dialect id, or {@code null}/blank to infer it from {@code dbUrl}
     * @param branch         VCS branch name, the base of the schema name
     * @param schemaSuffix   the caller-declared schema suffix, or null for none
     * @param env            lookup from environment variable name to value, e.g. {@code System::getenv}
     * @return the constructed {@link DataStore} for the resolved dialect
     */
    static DataStore fromConfig(final String dbFilePath, final String dbUrl, final String user,
                                final String password, final String dialectOverride,
                                final String branch, final String schemaSuffix,
                                final Function<String, String> env) {
        SqlDialect dialect = SqlDialectRegistry.forUrl(dbUrl, dialectOverride);
        String schema = BranchSchema.schemaName(branch, schemaSuffix);
        boolean h2 = "h2".equals(dialect.id());

        // Resolved here, before the dialect branch, so every dialect honours the environment
        // fallback identically. It previously lived inside H2ConnectionSettings, which meant
        // Postgres and generic JDBC silently ignored TIA_DB_USER / TIA_DB_PASSWORD and had no way
        // to keep the password out of checked-in build config. The tia username default stays H2's
        // own convention rather than being imposed on a vendor that never agreed to it.
        String resolvedUser = CredentialResolver.resolveUser(user, h2 ? H2_DEFAULT_USER : null, env);
        String resolvedPassword = CredentialResolver.resolvePassword(password, env);

        if (h2) {
            H2ConnectionSettings settings = H2ConnectionSettings.fromConfig(dbFilePath, dbUrl,
                    resolvedUser, resolvedPassword);
            ConnectionProvider connectionProvider = new H2ConnectionProvider(settings);
            return new JdbcDataStore(dialect, connectionProvider, schema);
        }

        requireDriverPresent(dialect.id());
        ConnectionProvider connectionProvider = "postgres".equals(dialect.id())
                ? new PostgresConnectionProvider(dbUrl, resolvedUser, resolvedPassword)
                : new JdbcConnectionProvider(dialect.id(), dbUrl, resolvedUser, resolvedPassword);
        return new JdbcDataStore(dialect, connectionProvider, schema);
    }

    /**
     * Report whether the datastore {@code fromConfig} would build for these settings is one every
     * runner in a distributed test run could reach concurrently. Mirrors {@code fromConfig}'s own
     * resolution exactly - the dialect comes from {@link SqlDialectRegistry#forUrl(String, String)}
     * and H2 runs embedded exactly when {@code dbUrl} is blank - so the two cannot drift apart: a
     * datastore is shared unless the resolved dialect is H2 and {@code dbUrl} is blank, since that
     * is the only combination {@code fromConfig} treats as an embedded, file-on-disk H2 database
     * private to whichever process opened it. Every other combination (a server-mode H2 URL, a
     * Postgres URL, or a blank URL paired with an explicit non-H2 override) resolves to a
     * networked database every runner can reach.
     *
     * @param dbUrl          server-mode JDBC URL, or {@code null}/blank for embedded mode
     * @param dialectOverride an explicit dialect id (e.g. {@code "h2"}), or {@code null}/blank to
     *                        infer the dialect from {@code dbUrl}
     * @return true if the resolved datastore is reachable by more than one process; false only for
     *         embedded H2
     * @throws IllegalArgumentException if the dialect cannot be resolved (see
     *         {@link SqlDialectRegistry#forUrl(String, String)})
     */
    public static boolean isSharedDatabase(final String dbUrl, final String dialectOverride) {
        SqlDialect dialect = SqlDialectRegistry.forUrl(dbUrl, dialectOverride);
        boolean blankUrl = dbUrl == null || dbUrl.trim().isEmpty();
        return !("h2".equals(dialect.id()) && blankUrl);
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
     * {@value H2ConnectionSettings#PROP_DB_USER} /
     * {@value CredentialResolver#PROP_DB_PASSWORD_FILE} for server mode, falling back to
     * {@value H2ConnectionSettings#PROP_DB_FILE_PATH} for embedded mode, plus the optional
     * {@value #PROP_DB_DIALECT} override.
     *
     * <p>The password is never carried as a system property, only named by one. A value here would
     * be published: surefire dumps the fork's system properties into
     * {@code target/surefire-reports/TEST-*.xml}, and Gradle turns one into a {@code -D} on the
     * worker command line. With no path forwarded the password resolves from the environment this
     * JVM inherited from the build, which is the channel a build that configured nothing uses. Used by the JUnit/Spock
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
        // The password arrives as a path, never as a value: a value here would have been a system
        // property in this JVM, and surefire dumps the fork's system properties into the report XML.
        // A null path means no password was configured, so resolution falls through to the
        // environment this JVM inherited from the build.
        String passwordFile = System.getProperty(CredentialResolver.PROP_DB_PASSWORD_FILE);
        String password = passwordFile != null
                ? CredentialResolver.readPasswordFile(passwordFile)
                : null;

        return fromConfig(
                System.getProperty(H2ConnectionSettings.PROP_DB_FILE_PATH),
                System.getProperty(H2ConnectionSettings.PROP_DB_URL),
                System.getProperty(H2ConnectionSettings.PROP_DB_USER),
                password,
                System.getProperty(PROP_DB_DIALECT),
                branch,
                System.getProperty(PROP_DB_SCHEMA_SUFFIX));
    }
}
