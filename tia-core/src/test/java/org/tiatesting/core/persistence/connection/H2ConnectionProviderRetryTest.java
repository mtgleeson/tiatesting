package org.tiatesting.core.persistence.connection;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link H2ConnectionProvider#get()} connection-acquisition resilience: server-mode
 * connections retry transient failures with a backoff before giving up, while embedded-mode
 * connections fail fast (a deterministic embedded failure will not heal on retry). The retry
 * logic moved here from {@code H2DataStore} in the pluggable-datastore extraction; the flaky
 * acquire and the backoff are exercised through package-private seams so no real server or real
 * delay is involved.
 */
class H2ConnectionProviderRetryTest {

    /**
     * Test double that fails the first {@code failuresBeforeSuccess} acquire attempts with a
     * transient-looking {@link SQLException}, then returns a real in-memory connection. Counts the
     * attempts and records the backoff values requested, and overrides the backoff to a no-op so
     * the test runs without real delays.
     */
    private static class FlakyH2ConnectionProvider extends H2ConnectionProvider {
        private final int failuresBeforeSuccess;
        private int acquireAttempts = 0;
        private final List<Long> backoffs = new ArrayList<>();
        private Connection lastReturned;

        FlakyH2ConnectionProvider(H2ConnectionSettings settings, int failuresBeforeSuccess) {
            super(settings);
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        Connection acquireConnection() throws SQLException {
            acquireAttempts++;
            if (acquireAttempts <= failuresBeforeSuccess) {
                throw new SQLNonTransientConnectionException(
                        "Connection is broken: simulated abort recv failed");
            }
            lastReturned = DriverManager.getConnection("jdbc:h2:mem:retry" + System.nanoTime());
            return lastReturned;
        }

        @Override
        void backoffBeforeRetry(long backoffMs) {
            backoffs.add(backoffMs);
        }
    }

    private static H2ConnectionSettings serverSettings() {
        return H2ConnectionSettings.server("jdbc:h2:tcp://localhost:9092/tiadb", "sa", "");
    }

    @Test
    void serverMode_retriesTransientFailure_thenSucceeds() throws SQLException {
        // given - a server-mode provider whose first two connection attempts abort, third succeeds
        FlakyH2ConnectionProvider provider = new FlakyH2ConnectionProvider(serverSettings(), 2);

        // when
        Connection connection = provider.get();

        // then - it kept trying and returned the connection from the third attempt
        assertNotNull(connection);
        assertSame(provider.lastReturned, connection);
        assertEquals(3, provider.acquireAttempts);
        // backed off once after each of the two failures, with a linear 250ms / 500ms progression
        assertEquals(Arrays.asList(H2ConnectionProvider.CONNECTION_RETRY_BACKOFF_MS,
                H2ConnectionProvider.CONNECTION_RETRY_BACKOFF_MS * 2), provider.backoffs);
        connection.close();
    }

    @Test
    void serverMode_exhaustsRetries_thenThrows() {
        // given - a server-mode provider whose connection always aborts
        FlakyH2ConnectionProvider provider = new FlakyH2ConnectionProvider(serverSettings(), Integer.MAX_VALUE);

        // when / then - it gives up after CONNECTION_MAX_ATTEMPTS and rethrows the last failure.
        // get() throws the checked SQLException directly (the TiaPersistenceException wrapping now
        // lives in JdbcDataStore.getConnection(), not in the provider).
        assertThrows(SQLException.class, provider::get);
        assertEquals(H2ConnectionProvider.CONNECTION_MAX_ATTEMPTS, provider.acquireAttempts);
        // backed off between attempts only, i.e. one fewer time than the number of attempts
        assertEquals(H2ConnectionProvider.CONNECTION_MAX_ATTEMPTS - 1, provider.backoffs.size());
    }

    @Test
    void embeddedMode_doesNotRetry_failsFast() {
        // given - an embedded-mode provider whose single connection attempt fails
        H2ConnectionSettings embedded = H2ConnectionSettings.embedded("/tmp/does-not-matter");
        FlakyH2ConnectionProvider provider = new FlakyH2ConnectionProvider(embedded, Integer.MAX_VALUE);

        // when / then - the deterministic embedded failure is surfaced immediately, no retry
        assertThrows(SQLException.class, provider::get);
        assertEquals(1, provider.acquireAttempts);
        assertEquals(0, provider.backoffs.size());
    }
}
