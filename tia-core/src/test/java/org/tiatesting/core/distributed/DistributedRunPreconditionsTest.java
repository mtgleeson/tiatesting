package org.tiatesting.core.distributed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link DistributedRunPreconditions#check}'s two rules that only the plugin layer can
 * enforce - a shared database and {@code tiaCheckLocalChanges} disabled - and that the happy path
 * throws nothing. Each rejection test asserts on the exception message content, not just its type,
 * because the whole point of naming the user-facing property in the message is defeated if a test
 * would pass just as well with a generic message.
 */
class DistributedRunPreconditionsTest {

    /**
     * Verifies that an embedded H2 configuration (blank {@code dbUrl}, no dialect override) is
     * rejected, and that the message names server-mode H2 and Postgres as the alternatives and
     * {@code tiaDBUrl} as the property that selects them, so a user reading the failure knows
     * exactly what to change.
     */
    @Test
    void check_embeddedH2_throwsNamingSharedDatabaseOptions() {
        // given - a blank dbUrl, which resolves to embedded-mode H2
        String dbUrl = null;

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DistributedRunPreconditions.check(dbUrl, null, false));

        // then
        String message = ex.getMessage();
        assertTrue(message.contains("tiaDBUrl"), "message should name tiaDBUrl, was: " + message);
        assertTrue(message.toLowerCase().contains("server"), "message should mention server-mode H2, was: " + message);
        assertTrue(message.toLowerCase().contains("postgres"), "message should mention Postgres, was: " + message);
    }

    /**
     * Verifies that {@code checkLocalChanges=true} is rejected even when the database is shared,
     * and that the message names {@code tiaCheckLocalChanges}, so a user reading the failure knows
     * exactly which setting broke the every-runner-diffs-the-same-commit assumption.
     */
    @Test
    void check_localChangesEnabled_throwsNamingCheckLocalChangesProperty() {
        // given - a shared (Postgres) database, but checkLocalChanges is on
        String dbUrl = "jdbc:postgresql://localhost:5432/tiaperf";

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DistributedRunPreconditions.check(dbUrl, null, true));

        // then
        String message = ex.getMessage();
        assertTrue(message.contains("tiaCheckLocalChanges"),
                "message should name tiaCheckLocalChanges, was: " + message);
    }

    /**
     * Verifies that an embedded H2 configuration with {@code checkLocalChanges=true} - both rules
     * broken at once - fails on the shared-database rule, since that is the first check {@link
     * DistributedRunPreconditions#check} performs and the message a user sees should not depend on
     * which rule happens to be checked last.
     */
    @Test
    void check_bothRulesBroken_throwsNamingSharedDatabaseFirst() {
        // given - embedded H2 and checkLocalChanges both broken
        String dbUrl = null;

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DistributedRunPreconditions.check(dbUrl, null, true));

        // then
        assertTrue(ex.getMessage().contains("tiaDBUrl"),
                "message should name tiaDBUrl since the shared-database rule is checked first, was: "
                        + ex.getMessage());
    }

    /**
     * Verifies that a shared database (a Postgres URL) with {@code checkLocalChanges=false} - both
     * rules satisfied - throws nothing, so a correctly configured distributed run is never blocked.
     */
    @Test
    void check_sharedDatabaseAndLocalChangesDisabled_throwsNothing() {
        // given - a shared database and checkLocalChanges off
        String dbUrl = "jdbc:postgresql://localhost:5432/tiaperf";

        // when / then
        assertDoesNotThrow(() -> DistributedRunPreconditions.check(dbUrl, null, false));
    }

    /**
     * Verifies that a server-mode H2 URL also satisfies the shared-database rule alongside
     * {@code checkLocalChanges=false}, so server-mode H2 (not just Postgres) is a valid distributed
     * run configuration.
     */
    @Test
    void check_h2ServerUrlAndLocalChangesDisabled_throwsNothing() {
        // given - a server-mode H2 URL and checkLocalChanges off
        String dbUrl = "jdbc:h2:tcp://h2host:9092/tiadb";

        // when / then
        assertDoesNotThrow(() -> DistributedRunPreconditions.check(dbUrl, null, false));
    }
}
