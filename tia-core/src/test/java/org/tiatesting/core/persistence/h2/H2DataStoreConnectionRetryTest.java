package org.tiatesting.core.persistence.h2;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.persistence.TiaPersistenceException;

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
 * Verifies {@link H2DataStore#getConnection()} connection-acquisition resilience: server-mode
 * connections retry transient failures with a backoff before giving up, while embedded-mode
 * connections fail fast (a deterministic embedded failure will not heal on retry). The flaky
 * acquire and the backoff are exercised through package-private seams so no real server or real
 * delay is involved.
 */
class H2DataStoreConnectionRetryTest {

    /**
     * Test double that fails the first {@code failuresBeforeSuccess} acquire attempts with a
     * transient-looking {@link SQLException}, then returns a real in-memory connection. Counts the
     * attempts and records the backoff values requested, and overrides the backoff to a no-op so
     * the test runs without real delays.
     */
    private static class FlakyH2DataStore extends H2DataStore {
        private final int failuresBeforeSuccess;
        private int acquireAttempts = 0;
        private final List<Long> backoffs = new ArrayList<>();
        private Connection lastReturned;

        FlakyH2DataStore(H2ConnectionSettings settings, int failuresBeforeSuccess) {
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
        return H2ConnectionSettings.server("jdbc:h2:tcp://localhost:9092/tiadb-test", "sa", "", "test");
    }

    @Test
    void serverMode_retriesTransientFailure_thenSucceeds() throws SQLException {
        // given - a server-mode store whose first two connection attempts abort, third succeeds
        FlakyH2DataStore store = new FlakyH2DataStore(serverSettings(), 2);

        // when
        Connection connection = store.getConnection();

        // then - it kept trying and returned the connection from the third attempt
        assertNotNull(connection);
        assertSame(store.lastReturned, connection);
        assertEquals(3, store.acquireAttempts);
        // backed off once after each of the two failures, with a linear 250ms / 500ms progression
        assertEquals(Arrays.asList(H2DataStore.CONNECTION_RETRY_BACKOFF_MS,
                H2DataStore.CONNECTION_RETRY_BACKOFF_MS * 2), store.backoffs);
        connection.close();
    }

    @Test
    void serverMode_exhaustsRetries_thenThrows() {
        // given - a server-mode store whose connection always aborts
        FlakyH2DataStore store = new FlakyH2DataStore(serverSettings(), Integer.MAX_VALUE);

        // when / then - it gives up after CONNECTION_MAX_ATTEMPTS and wraps the last failure
        assertThrows(TiaPersistenceException.class, store::getConnection);
        assertEquals(H2DataStore.CONNECTION_MAX_ATTEMPTS, store.acquireAttempts);
        // backed off between attempts only, i.e. one fewer time than the number of attempts
        assertEquals(H2DataStore.CONNECTION_MAX_ATTEMPTS - 1, store.backoffs.size());
    }

    @Test
    void embeddedMode_doesNotRetry_failsFast() {
        // given - an embedded-mode store whose single connection attempt fails
        H2ConnectionSettings embedded = H2ConnectionSettings.embedded("/tmp/does-not-matter", "test");
        FlakyH2DataStore store = new FlakyH2DataStore(embedded, Integer.MAX_VALUE);

        // when / then - the deterministic embedded failure is surfaced immediately, no retry
        assertThrows(TiaPersistenceException.class, store::getConnection);
        assertEquals(1, store.acquireAttempts);
        assertEquals(0, store.backoffs.size());
    }
}
