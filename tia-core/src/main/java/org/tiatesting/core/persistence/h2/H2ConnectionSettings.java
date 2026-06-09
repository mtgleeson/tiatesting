package org.tiatesting.core.persistence.h2;

/**
 * Immutable connection settings for {@link H2DataStore}. Centralises the single decision of
 * whether Tia talks to an embedded (file-on-disk) H2 database or a remote H2 running in server
 * (TCP) mode, so the choice is resolved in one place rather than duplicated across every
 * build-tool plugin and test-runner listener that constructs a datastore.
 *
 * <p>The mode is driven entirely by whether a JDBC URL is supplied:
 * <ul>
 *   <li><b>Embedded</b> (no {@code dbUrl}): the {@code dbFilePath} directory plus the branch
 *       suffix produce a {@code jdbc:h2:<path>/tiadb-<suffix>} URL, with the historical
 *       {@code sa}/{@code 1234} credentials.</li>
 *   <li><b>Server</b> ({@code dbUrl} present): the supplied URL is used verbatim - Tia does not
 *       append the branch suffix or any embedded-only engine options - with the supplied
 *       credentials.</li>
 * </ul>
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

    private final String dbFilePath;
    private final String dbUrl;
    private final String username;
    private final String password;
    private final String branchSuffix;

    private H2ConnectionSettings(final String dbFilePath, final String dbUrl, final String username,
                                 final String password, final String branchSuffix) {
        this.dbFilePath = dbFilePath;
        this.dbUrl = dbUrl;
        this.username = username;
        this.password = password;
        this.branchSuffix = branchSuffix;
    }

    /**
     * Build embedded-mode settings backed by a file-on-disk H2 database, using the historical
     * {@code sa}/{@code 1234} credentials.
     *
     * @param dbFilePath   the directory that holds (or will hold) the H2 database file
     * @param branchSuffix the VCS branch name, appended as {@code tiadb-<suffix>} to give each
     *                     branch its own database file
     * @return embedded-mode connection settings
     */
    public static H2ConnectionSettings embedded(final String dbFilePath, final String branchSuffix) {
        return new H2ConnectionSettings(dbFilePath, null, EMBEDDED_DEFAULT_USER,
                EMBEDDED_DEFAULT_PASSWORD, branchSuffix);
    }

    /**
     * Build server-mode settings backed by a remote H2 reached over the supplied JDBC URL. The
     * URL is used exactly as given. If no username is supplied it defaults to {@code sa}; if no
     * password is supplied it defaults to the empty string.
     *
     * @param dbUrl    the full {@code jdbc:h2:tcp://...} (or {@code ssl://}) URL, used verbatim
     * @param username the database user, or {@code null} to default to {@code sa}
     * @param password the database password, or {@code null} to default to an empty password
     * @return server-mode connection settings
     */
    public static H2ConnectionSettings server(final String dbUrl, final String username, final String password) {
        return new H2ConnectionSettings(null, dbUrl,
                username != null ? username : EMBEDDED_DEFAULT_USER,
                password != null ? password : "",
                null);
    }

    /**
     * Resolve connection settings from raw user configuration. When {@code dbUrl} is non-blank
     * the result is {@link #server(String, String, String) server mode}; otherwise it is
     * {@link #embedded(String, String) embedded mode} and the URL credentials are ignored.
     *
     * @param dbFilePath   embedded-mode database directory (used only when {@code dbUrl} is blank)
     * @param dbUrl        server-mode JDBC URL, or {@code null}/blank for embedded mode
     * @param dbUser       server-mode database user
     * @param dbPassword   server-mode database password
     * @param branchSuffix VCS branch name for the embedded-mode file suffix
     * @return the resolved connection settings for the requested mode
     */
    public static H2ConnectionSettings fromConfig(final String dbFilePath, final String dbUrl,
                                                  final String dbUser, final String dbPassword,
                                                  final String branchSuffix) {
        if (dbUrl != null && !dbUrl.trim().isEmpty()) {
            return server(dbUrl, dbUser, dbPassword);
        }
        return embedded(dbFilePath, branchSuffix);
    }

    /**
     * Resolve connection settings from the Tia system properties set on the forked test JVM by
     * the build-tool plugins: {@value #PROP_DB_URL} / {@value #PROP_DB_USER} /
     * {@value #PROP_DB_PASSWORD} for server mode, falling back to {@value #PROP_DB_FILE_PATH} for
     * embedded mode. Used by the JUnit/Spock test-runner listeners, which read connection config
     * from system properties rather than a build-tool extension.
     *
     * @param branchSuffix the VCS branch name for the embedded-mode file suffix
     * @return the resolved embedded- or server-mode connection settings
     */
    public static H2ConnectionSettings fromSystemProperties(final String branchSuffix) {
        return fromConfig(
                System.getProperty(PROP_DB_FILE_PATH),
                System.getProperty(PROP_DB_URL),
                System.getProperty(PROP_DB_USER),
                System.getProperty(PROP_DB_PASSWORD),
                branchSuffix);
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

    /**
     * @return the embedded-mode branch suffix, or {@code null} in server mode
     */
    public String getBranchSuffix() {
        return branchSuffix;
    }
}
