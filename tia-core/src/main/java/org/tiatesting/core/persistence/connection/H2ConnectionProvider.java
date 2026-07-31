package org.tiatesting.core.persistence.connection;

import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.persistence.TiaPersistenceException;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * {@link ConnectionProvider} for H2. Ported verbatim from {@code H2DataStore}'s connection-URL
 * construction and {@code getConnection()} body (see the "Library publish-time stamping" and
 * connection-retry chapters in {@code WIKI.md} for the surrounding design context) - this class
 * only relocates that logic, it does not change it.
 */
public class H2ConnectionProvider implements ConnectionProvider {

    // Server-mode connection retry: an initial attempt plus retries before giving up. Server-mode
    // connections cross a real network/server boundary, so a single transient abort (e.g. the first
    // connection after the server has sat idle) should not fail the whole task.
    static final int CONNECTION_MAX_ATTEMPTS = 3;
    // Base backoff between server-mode connection retries, multiplied by the attempt number for a
    // simple linear backoff (250ms, then 500ms, ...).
    static final long CONNECTION_RETRY_BACKOFF_MS = 250L;

    private final Logger log = LoggerFactory.getLogger(H2ConnectionProvider.class);
    private final H2ConnectionSettings settings;
    private final String jdbcURL;
    private final String username;
    private final String password;

    /**
     * Construct a connection provider from resolved connection settings. The settings determine
     * whether Tia connects to an embedded file-on-disk H2 or a remote server-mode H2; see
     * {@link H2ConnectionSettings}.
     *
     * @param settings the resolved embedded- or server-mode connection settings
     */
    public H2ConnectionProvider(H2ConnectionSettings settings) {
        this.settings = settings;
        this.username = settings.getUsername();
        this.password = settings.getPassword();
        this.jdbcURL = buildJdbcUrl();

        log.info("Using H2 as the Tia datastore in {} mode with the connection: {}",
                settings.isServerMode() ? "server" : "embedded", this.jdbcURL);
    }

    /**
     * Acquire a connection to the H2 database. In server mode the acquisition is retried a few
     * times with a short backoff, because the connection crosses a real network/server boundary
     * where a single transient abort (a reset socket, or a server still warming up after sitting
     * idle) is expected and should not fail the whole task. In embedded mode a failure is
     * deterministic (bad path, locked file), so it is surfaced immediately without retry.
     *
     * @return an open connection to the configured H2 database
     * @throws SQLException if the connection cannot be established (after exhausting the
     *         server-mode retries, or on the first embedded-mode failure)
     */
    @Override
    public Connection get() throws SQLException {
        if (settings.isServerMode()) {
            return acquireServerConnectionWithRetry();
        }
        return acquireConnection();
    }

    /**
     * Expose the resolved JDBC URL this provider connects with.
     *
     * @return the JDBC URL in use for this provider
     */
    @Override
    public String jdbcUrl() {
        return jdbcURL;
    }

    /**
     * Close the embedded H2 database, flushing pending writes to disk and releasing the underlying
     * {@code .mv.db} file lock. Required when running inside a Maven plugin's JVM that will later
     * fork a surefire/test JVM: without an explicit close, {@code DB_CLOSE_DELAY=-1} keeps the
     * database open in the plugin JVM and the forked test JVM cannot open the same file - H2 reports
     * {@code "Database may be already in use"}.
     *
     * <p>Issues a graceful {@code SHUTDOWN} (deliberately <em>not</em> {@code SHUTDOWN IMMEDIATELY})
     * via a short-lived connection. The graceful form writes the MVStore's buffered pages to the
     * file before closing; {@code IMMEDIATELY} skips that flush and would drop any committed change
     * the MVStore's delayed writer has not yet persisted. That loss is silent and small-write
     * biased: a tracked-library reconcile performed in the plugin JVM is a tiny write that has not
     * reached disk when {@code close()} runs, so under {@code IMMEDIATELY} the forked test JVM finds
     * no schema, recreates an empty DB, and the tracked library disappears. Plain {@code SHUTDOWN}
     * does not compact or defrag, so the only added cost over {@code IMMEDIATELY} is flushing pages
     * that had to be written anyway - a read-only run has no dirty pages and pays nothing. Failures
     * during close are swallowed (logged at debug) so cleanup errors never mask the real exception a
     * calling {@code try}/{@code finally} block is unwinding.
     *
     * <p>This is an <b>embedded-mode-only</b> concern. In server mode the database engine lives in
     * the remote server process and is shared by every connected client, so issuing {@code SHUTDOWN}
     * would tear down the whole server database for all of them. Server-mode {@code close()} is
     * therefore a no-op - individual connections are already closed by each operation's
     * {@code finally} block.
     */
    @Override
    public void close() {
        if (settings.isServerMode()) {
            // Never SHUTDOWN a shared server DB - it would kill the database for every other
            // connected client. Per-operation connections are already closed by their callers.
            return;
        }

        // Graceful SHUTDOWN flushes the MVStore write buffer to disk, then closes and releases the
        // file lock. The final connection/statement close() in this try-with-resources may throw
        // because the database is already shut down; that's expected and the outer catch swallows
        // it. Failures during close are logged at debug so cleanup errors never mask the real
        // exception a calling try/finally is unwinding.
        try (Connection connection = get();
             Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        } catch (Throwable t) {
            log.debug("H2ConnectionProvider.close ignoring shutdown exception for {}: {}", jdbcURL, t.toString());
        }
    }

    /**
     * Acquire a server-mode connection, retrying transient failures with a linear backoff up to
     * {@link #CONNECTION_MAX_ATTEMPTS} total attempts. Each failed attempt is logged at WARN with
     * the underlying message; if every attempt fails the last failure is rethrown.
     *
     * @return an open connection
     * @throws SQLException the last failure, when all attempts fail
     */
    private Connection acquireServerConnectionWithRetry() throws SQLException {
        SQLException lastFailure = null;
        for (int attempt = 1; attempt <= CONNECTION_MAX_ATTEMPTS; attempt++) {
            try {
                return acquireConnection();
            } catch (SQLException e) {
                lastFailure = e;
                if (attempt < CONNECTION_MAX_ATTEMPTS) {
                    long backoffMs = CONNECTION_RETRY_BACKOFF_MS * attempt;
                    log.warn("H2 server connection attempt {} of {} to {} failed: {}. Retrying in {}ms.",
                            attempt, CONNECTION_MAX_ATTEMPTS, jdbcURL, e.getMessage(), backoffMs);
                    backoffBeforeRetry(backoffMs);
                }
            }
        }

        log.error("H2 server connection to {} failed after {} attempts.", jdbcURL, CONNECTION_MAX_ATTEMPTS);
        throw lastFailure;
    }

    /**
     * Open a single raw connection to the configured H2 database with no retry. Package-private so
     * a test can override it to simulate transient connection failures.
     *
     * @return a newly opened connection
     * @throws SQLException if the connection cannot be opened
     */
    Connection acquireConnection() throws SQLException {
        DataSource dataSource = this.establishDataSource();
        Connection connection = dataSource.getConnection();
        log.debug("Connected to the H2 database {}", jdbcURL);
        return connection;
    }

    /**
     * Wait for the given backoff before the next server-mode connection retry. Package-private so a
     * test can override it to avoid real delays. Restores the interrupt flag and aborts the retry
     * loop (by throwing) if the thread is interrupted while waiting.
     *
     * @param backoffMs the number of milliseconds to wait before retrying
     */
    void backoffBeforeRetry(long backoffMs) {
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new TiaPersistenceException(ie);
        }
    }

    /**
     * Build the {@link DataSource} used to open connections, from the resolved JDBC URL and
     * credentials.
     *
     * @return a configured {@link JdbcDataSource} for the Tia database
     */
    private DataSource establishDataSource() {
        JdbcDataSource ds = Objects.requireNonNull(new JdbcDataSource());
        ds.setURL(jdbcURL);
        ds.setUser(username);
        ds.setPassword(password);
        ds.setDescription("Tia database");
        return ds;
    }

    /**
     * Build the embedded-mode H2 JDBC URL for this provider.
     *
     * <p>The {@code DB_CLOSE_DELAY=-1} flag keeps the underlying database open for the lifetime
     * of the JVM, instead of H2's embedded-mode default of closing the entire database whenever
     * the last open connection is closed. Closing the database forces an {@code MVStore.commit()}
     * which flushes dirty pages - including the temp-result pages H2 writes to spill {@code ORDER
     * BY} sorts that aren't covered by an index. With this flag, individual {@code Connection
     * .close()} calls become near-free and the per-method open/close pattern in the datastore no
     * longer triggers a full flush per call.
     *
     * <p>{@code DB_CLOSE_ON_EXIT=FALSE} is the necessary companion: it stops H2 from registering
     * its JVM shutdown hook to close the database on exit. The shutdown hook is unsafe inside
     * Maven plugins because Plexus tears down the plugin's {@code ClassRealm} before the hook
     * fires, so the hook's call to {@code DbException.get(...)} fails with a
     * {@code NoClassDefFoundError: org/h2/api/ErrorCode}. A committed transaction is immediately
     * durable <em>within this JVM</em> (later reads on any connection see it), but MVStore's delayed
     * writer means the change may not have reached the {@code .mv.db} file yet. Cross-JVM durability
     * therefore relies on the datastore's {@code close()} issuing a graceful {@code SHUTDOWN}, which
     * flushes those buffered pages to disk before releasing the file lock - so the plugin JVM must
     * close the datastore before a forked test JVM opens the same file.
     *
     * <p>In <b>server mode</b> the user-supplied URL is used verbatim. The embedded-only
     * options above are deliberately omitted: {@code PAGE_SIZE} / {@code CACHE_SIZE} /
     * {@code DB_CLOSE_DELAY} / {@code DB_CLOSE_ON_EXIT} configure the database engine instance,
     * which in server mode lives in the remote server process and is configured when that server
     * is started - not by the connecting client. Both modes connect to a single fixed {@code tiadb}
     * database; per-branch isolation is provided by a per-branch schema selected on each connection
     * (see {@link org.tiatesting.core.persistence.JdbcDataStore}), not by a per-branch database name.
     *
     * @return the H2 JDBC URL: the server URL verbatim in server mode, or the composed
     *         embedded-mode {@code tiadb} URL (with engine options) otherwise
     */
    private String buildJdbcUrl() {
        if (settings.isServerMode()) {
            return settings.getDbUrl();
        }

        long cacheSizeKB = Runtime.getRuntime().maxMemory() / 1024 / 2; // use half of the available memory
        long pageSizeByte = 1024 * 4 * 100; //4KB is the default, set it to 10 times the size
        return "jdbc:h2:" + settings.getDbFilePath() + "/tiadb"
                + ";PAGE_SIZE=" + pageSizeByte
                + ";CACHE_SIZE=" + cacheSizeKB
                + ";DB_CLOSE_DELAY=-1"
                + ";DB_CLOSE_ON_EXIT=FALSE";
    }
}
