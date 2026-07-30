package org.tiatesting.core.persistence;

import org.tiatesting.core.persistence.connection.ConnectionProvider;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.SqlDialect;
import org.tiatesting.core.persistence.dialect.SqlDialectRegistry;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

/**
 * Builds a {@link DataStore} for the configured (or inferred) SQL dialect, so build-tool plugins
 * and test-runner listeners construct a datastore through one place instead of hard-coding
 * {@code new JdbcDataStore(new H2Dialect(), ...)} at every call site. H2-only for now; a future
 * dialect (e.g. Postgres, see the pluggable-datastore WIKI chapter) is added here as a new branch
 * alongside the H2 one, once {@link SqlDialectRegistry} resolves it.
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
     * @param branch         VCS branch name; the embedded-mode file suffix, or the value a
     *                       server-mode branch placeholder token expands to
     * @return the constructed {@link DataStore} for the resolved dialect
     * @throws IllegalArgumentException if the dialect cannot be resolved (see
     *         {@link SqlDialectRegistry#forUrl(String, String)})
     */
    public static DataStore fromConfig(final String dbFilePath, final String dbUrl, final String user,
                                       final String password, final String dialectOverride, final String branch) {
        SqlDialect dialect = SqlDialectRegistry.forUrl(dbUrl, dialectOverride);

        if ("h2".equals(dialect.id())) {
            H2ConnectionSettings settings = H2ConnectionSettings.fromConfig(dbFilePath, dbUrl, user, password, branch);
            ConnectionProvider connectionProvider = new H2ConnectionProvider(settings);
            return new JdbcDataStore(dialect, connectionProvider);
        }

        // Unreachable while SqlDialectRegistry only resolves "h2"; a future dialect adds its own
        // branch here alongside its ConnectionProvider construction.
        throw new IllegalArgumentException("No connection provider wired for dialect '" + dialect.id() + "'.");
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
     * @param branch the VCS branch name for the embedded-mode file suffix
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
