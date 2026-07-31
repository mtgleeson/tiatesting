package org.tiatesting.core.persistence.h2;

import org.tiatesting.core.persistence.JdbcDataStore;

import java.util.function.Function;

/**
 * Immutable connection settings for {@link JdbcDataStore}. Centralises the single decision of
 * whether Tia talks to an embedded (file-on-disk) H2 database or a remote H2 running in server
 * (TCP) mode, so the choice is resolved in one place rather than duplicated across every
 * build-tool plugin and test-runner listener that constructs a datastore.
 *
 * <p>The mode is driven entirely by whether a JDBC URL is supplied:
 * <ul>
 *   <li><b>Embedded</b> (no {@code dbUrl}): the {@code dbFilePath} directory produces a single
 *       fixed {@code jdbc:h2:<path>/tiadb} URL, with the historical {@code sa}/{@code 1234}
 *       credentials.</li>
 *   <li><b>Server</b> ({@code dbUrl} present): the supplied URL is used verbatim (Tia does not
 *       append any embedded-only engine options). Credentials come from the configured values,
 *       falling back to the {@value #ENV_DB_USER} / {@value #ENV_DB_PASSWORD} environment
 *       variables so the password need not live in checked-in build config.</li>
 * </ul>
 *
 * <p>Both modes connect to a single fixed {@code tiadb} database; per-branch isolation is provided
 * by a per-branch schema (derived from the branch by the caller) selected on each connection, not
 * by a per-branch database name - so these settings no longer carry the branch.
 */
public class H2ConnectionSettings {

    private static final String EMBEDDED_DEFAULT_USER = "sa";
    private static final String EMBEDDED_DEFAULT_PASSWORD = "1234";

    /** System property holding the embedded-mode database directory. */
    public static final String PROP_DB_FILE_PATH = "tiaDBFilePath";
    /** System property holding the server-mode JDBC URL. */
    public static final String PROP_DB_URL = "tiaDBUrl";
    /** System property holding the server-mode database username. */
    public static final String PROP_DB_USER = "tiaDBUser";
    /** System property holding the server-mode database password. */
    public static final String PROP_DB_PASSWORD = "tiaDBPassword";

    /** Environment variable consulted for the server-mode username when none is configured. */
    public static final String ENV_DB_USER = "TIA_DB_USER";
    /** Environment variable consulted for the server-mode password when none is configured. */
    public static final String ENV_DB_PASSWORD = "TIA_DB_PASSWORD";

    private final String dbFilePath;
    private final String dbUrl;
    private final String username;
    private final String password;

    private H2ConnectionSettings(final String dbFilePath, final String dbUrl, final String username,
                                 final String password) {
        this.dbFilePath = dbFilePath;
        this.dbUrl = dbUrl;
        this.username = username;
        this.password = password;
    }

    /**
     * Build embedded-mode settings backed by a file-on-disk H2 database, using the historical
     * {@code sa}/{@code 1234} credentials. All branches share the single fixed {@code tiadb}
     * database file; per-branch isolation is provided by the schema, not the file name.
     *
     * @param dbFilePath the directory that holds (or will hold) the H2 database file
     * @return embedded-mode connection settings
     */
    public static H2ConnectionSettings embedded(final String dbFilePath) {
        return new H2ConnectionSettings(dbFilePath, null, EMBEDDED_DEFAULT_USER,
                EMBEDDED_DEFAULT_PASSWORD);
    }

    /**
     * Build server-mode settings backed by a remote H2 reached over the supplied JDBC URL. The
     * URL is used exactly as given.
     *
     * <p>Credentials resolve in precedence order: the explicitly configured value, then the
     * {@value #ENV_DB_USER} / {@value #ENV_DB_PASSWORD} environment variable, then a default
     * ({@code sa} for the user, an empty password). The environment-variable fallback lets a
     * build keep the password out of its checked-in Gradle/Maven config entirely - CI sets the
     * secret in the environment and leaves {@code dbPassword} unset.
     *
     * <p>Tia connects to whatever database the URL names; per-branch isolation is provided by the
     * schema selected on each connection, so these settings do not carry the branch.
     *
     * @param dbUrl    the {@code jdbc:h2:tcp://...} (or {@code ssl://}) URL, used verbatim
     * @param username the database user, or {@code null}/blank to fall back to the environment
     * @param password the database password, or {@code null} to fall back to the environment;
     *                 an explicit empty string is honoured verbatim (see
     *                 {@link #resolvePassword(String, String)})
     * @return server-mode connection settings
     */
    public static H2ConnectionSettings server(final String dbUrl, final String username, final String password) {
        return server(dbUrl, username, password, System::getenv);
    }

    /**
     * Test seam for {@link #server(String, String, String)}: takes the environment lookup as a
     * parameter so the {@value #ENV_DB_USER} / {@value #ENV_DB_PASSWORD} fallback can be exercised
     * without mutating the real process environment.
     *
     * @param dbUrl    the JDBC URL, used verbatim
     * @param username the configured database user, or {@code null}/blank to fall back
     * @param password the configured database password, or {@code null} to fall back; an
     *                 explicit empty string is honoured verbatim
     * @param env      lookup from environment-variable name to value (e.g. {@code System::getenv})
     * @return server-mode connection settings with credentials resolved
     */
    static H2ConnectionSettings server(final String dbUrl, final String username, final String password,
                                       final Function<String, String> env) {
        return new H2ConnectionSettings(null, dbUrl,
                resolve(username, env.apply(ENV_DB_USER), EMBEDDED_DEFAULT_USER),
                resolvePassword(password, env.apply(ENV_DB_PASSWORD)));
    }

    /**
     * Resolve a value by precedence: the configured value if non-blank, else the environment
     * value if non-blank, else the supplied default. Used for the username, where a blank value
     * is meaningless and is therefore treated as "not configured".
     *
     * @param configured   the explicitly configured value (highest precedence)
     * @param envValue     the environment-variable value (used when {@code configured} is blank)
     * @param defaultValue the fallback used when both above are blank
     * @return the first non-blank of {@code configured}, {@code envValue}, otherwise {@code defaultValue}
     */
    private static String resolve(final String configured, final String envValue, final String defaultValue) {
        if (configured != null && !configured.trim().isEmpty()) {
            return configured;
        }
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue;
        }
        return defaultValue;
    }

    /**
     * Resolve the password, distinguishing "not configured" from "configured as empty". Unlike
     * {@link #resolve(String, String, String)}, only {@code null} means "not configured": any
     * non-null configured value - including an empty string - is honoured verbatim and is never
     * trimmed (leading/trailing whitespace can be significant in a password). This lets a build
     * specify an empty password explicitly ({@code dbPassword = ''} / {@code <tiaDBPassword></tiaDBPassword>})
     * and bypass the environment fallback. Only when no password is configured at all does it fall
     * back to {@value #ENV_DB_PASSWORD}, then to an empty password.
     *
     * @param configured the configured password, or {@code null} when not configured
     * @param envValue   the {@value #ENV_DB_PASSWORD} environment value
     * @return the configured password verbatim if non-null, else the env value if non-blank, else {@code ""}
     */
    private static String resolvePassword(final String configured, final String envValue) {
        if (configured != null) {
            return configured;
        }
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue;
        }
        return "";
    }

    /**
     * Resolve connection settings from raw user configuration. When {@code dbUrl} is non-blank
     * the result is {@link #server(String, String, String) server mode}; otherwise it is
     * {@link #embedded(String) embedded mode} and the URL credentials are ignored.
     *
     * @param dbFilePath embedded-mode database directory (used only when {@code dbUrl} is blank)
     * @param dbUrl      server-mode JDBC URL, or {@code null}/blank for embedded mode
     * @param dbUser     server-mode database user
     * @param dbPassword server-mode database password
     * @return the resolved connection settings for the requested mode
     */
    public static H2ConnectionSettings fromConfig(final String dbFilePath, final String dbUrl,
                                                  final String dbUser, final String dbPassword) {
        if (dbUrl != null && !dbUrl.trim().isEmpty()) {
            return server(dbUrl, dbUser, dbPassword);
        }
        return embedded(dbFilePath);
    }

    /**
     * Resolve connection settings from the Tia system properties set on the forked test JVM by
     * the build-tool plugins: {@value #PROP_DB_URL} / {@value #PROP_DB_USER} /
     * {@value #PROP_DB_PASSWORD} for server mode, falling back to {@value #PROP_DB_FILE_PATH} for
     * embedded mode. Used by the JUnit/Spock test-runner listeners, which read connection config
     * from system properties rather than a build-tool extension.
     *
     * @return the resolved embedded- or server-mode connection settings
     */
    public static H2ConnectionSettings fromSystemProperties() {
        return fromConfig(
                System.getProperty(PROP_DB_FILE_PATH),
                System.getProperty(PROP_DB_URL),
                System.getProperty(PROP_DB_USER),
                System.getProperty(PROP_DB_PASSWORD));
    }

    /**
     * Report whether these settings target a remote server-mode H2 (as opposed to an embedded
     * file-on-disk database).
     *
     * @return {@code true} for server mode, {@code false} for embedded mode
     */
    public boolean isServerMode() {
        return dbUrl != null;
    }

    /**
     * @return the embedded-mode database directory, or {@code null} in server mode
     */
    public String getDbFilePath() {
        return dbFilePath;
    }

    /**
     * @return the server-mode JDBC URL used verbatim, or {@code null} in embedded mode
     */
    public String getDbUrl() {
        return dbUrl;
    }

    /**
     * @return the database username
     */
    public String getUsername() {
        return username;
    }

    /**
     * @return the database password
     */
    public String getPassword() {
        return password;
    }
}
