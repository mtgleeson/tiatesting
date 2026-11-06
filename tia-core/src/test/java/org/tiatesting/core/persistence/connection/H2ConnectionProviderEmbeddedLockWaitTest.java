package org.tiatesting.core.persistence.connection;

import org.h2.api.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers embedded H2 waiting for a database file another JVM - a parallel test fork - has open.
 */
class H2ConnectionProviderEmbeddedLockWaitTest {

    /**
     * A provider whose first {@code lockedAttempts} connections fail as if another process held the
     * file, on a clock that advances only by the backoffs it is asked to wait.
     */
    private static final class LockedProvider extends H2ConnectionProvider {
        private int lockedAttempts;
        private long nowMs;
        private int attempts;
        private final List<Long> backoffs = new ArrayList<>();

        /**
         * Construct the provider.
         *
         * @param dir the embedded database directory
         * @param lockedAttempts how many attempts find the file held
         */
        LockedProvider(Path dir, int lockedAttempts) {
            super(H2ConnectionSettings.embedded(dir.toString()));
            this.lockedAttempts = lockedAttempts;
        }

        @Override
        Connection acquireConnection() throws SQLException {
            attempts++;
            if (lockedAttempts > 0) {
                lockedAttempts--;
                throw new SQLException("Database may be already in use", "90020", ErrorCode.DATABASE_ALREADY_OPEN_1);
            }
            return super.acquireConnection();
        }

        @Override
        void backoffBeforeRetry(long backoffMs) {
            backoffs.add(backoffMs);
            nowMs += backoffMs;
        }

        @Override
        long currentTimeMs() {
            return nowMs;
        }
    }

    @Test
    void waitsUntilTheOtherJvmReleasesTheFile(@TempDir Path dir) throws Exception {
        // given a file another fork holds for three attempts
        LockedProvider provider = new LockedProvider(dir, 3);

        // when
        try (Connection connection = provider.get()) {
            // then the fourth attempt connects, after a doubling backoff
            assertNotNull(connection);
            assertEquals(4, provider.attempts);
            assertEquals(Arrays.asList(100L, 200L, 400L), provider.backoffs);
        } finally {
            provider.close();
        }
    }

    @Test
    void givesUpAfterTheBoundedWait(@TempDir Path dir) {
        // given a file that stays locked
        LockedProvider provider = new LockedProvider(dir, Integer.MAX_VALUE);

        // when
        SQLException e = assertThrows(SQLException.class, provider::get);

        // then the wait stopped at its bound, with the backoff capped
        assertEquals(ErrorCode.DATABASE_ALREADY_OPEN_1, e.getErrorCode());
        assertTrue(provider.nowMs >= H2ConnectionProvider.EMBEDDED_LOCK_WAIT_MS);
        assertTrue(provider.nowMs < H2ConnectionProvider.EMBEDDED_LOCK_WAIT_MS
                + H2ConnectionProvider.EMBEDDED_LOCK_MAX_BACKOFF_MS + 1);
        assertEquals(Long.valueOf(H2ConnectionProvider.EMBEDDED_LOCK_MAX_BACKOFF_MS),
                provider.backoffs.get(provider.backoffs.size() - 1));
    }

    @Test
    void closeDoesNotWaitForAFileAnotherJvmHolds(@TempDir Path dir) {
        // given a file another fork holds, which this JVM never opened
        LockedProvider provider = new LockedProvider(dir, Integer.MAX_VALUE);

        // when
        provider.close();

        // then one attempt, no wait
        assertEquals(1, provider.attempts);
        assertTrue(provider.backoffs.isEmpty());
    }
}
