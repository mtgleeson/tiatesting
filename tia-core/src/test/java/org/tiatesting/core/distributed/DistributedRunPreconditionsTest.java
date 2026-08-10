package org.tiatesting.core.distributed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link DistributedRunPreconditions#check}'s three rules that only the plugin layer can
 * enforce - Tia enabled, a shared database, and {@code tiaCheckLocalChanges} disabled - and that
 * the happy path throws nothing. Each rejection test asserts on the exception message content, not
 * just its type, because the whole point of naming the user-facing property in the message is
 * defeated if a test would pass just as well with a generic message.
 */
class DistributedRunPreconditionsTest {

    /**
     * Verifies that {@code tiaEnabled=false} is rejected, and that the message names {@code
     * tiaEnabled} as the property that selects it, so a user reading the failure knows exactly
     * what to change.
     */
    @Test
    void check_tiaDisabled_throwsNamingTiaEnabledProperty() {
        // given - Tia disabled, otherwise a valid distributed-run configuration
        boolean tiaEnabled = false;
        String dbUrl = "jdbc:postgresql://localhost:5432/tiaperf";

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DistributedRunPreconditions.check(tiaEnabled, dbUrl, null, false));

        // then
        assertTrue(ex.getMessage().contains("tiaEnabled"),
                "message should name tiaEnabled, was: " + ex.getMessage());
    }

    /**
     * Verifies that an embedded H2 configuration (blank {@code dbUrl}, no dialect override) is
     * rejected, and that the message names server-mode H2 and Postgres as the alternatives and
     * {@code tiaDBUrl} as the property that selects them, so a user reading the failure knows
     * exactly what to change.
     */
    @Test
    void check_embeddedH2_throwsNamingSharedDatabaseOptions() {
        // given - Tia enabled, but a blank dbUrl, which resolves to embedded-mode H2
        String dbUrl = null;

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DistributedRunPreconditions.check(true, dbUrl, null, false));

        // then
        String message = ex.getMessage();
        assertTrue(message.contains("tiaDBUrl"), "message should name tiaDBUrl, was: " + message);
        assertTrue(message.toLowerCase().contains("server"), "message should mention server-mode H2, was: " + message);
        assertTrue(message.toLowerCase().contains("postgres"), "message should mention Postgres, was: " + message);
    }

    /**
     * Verifies that {@code checkLocalChanges=true} is rejected even when Tia is enabled and the
     * database is shared, and that the message names {@code tiaCheckLocalChanges}, so a user
     * reading the failure knows exactly which setting broke the every-runner-diffs-the-same-commit
     * assumption.
     */
    @Test
    void check_localChangesEnabled_throwsNamingCheckLocalChangesProperty() {
        // given - Tia enabled, a shared (Postgres) database, but checkLocalChanges is on
        String dbUrl = "jdbc:postgresql://localhost:5432/tiaperf";

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DistributedRunPreconditions.check(true, dbUrl, null, true));

        // then
        String message = ex.getMessage();
        assertTrue(message.contains("tiaCheckLocalChanges"),
                "message should name tiaCheckLocalChanges, was: " + message);
    }

    /**
     * Verifies that an embedded H2 configuration with {@code checkLocalChanges=true} - both of the
     * remaining rules broken at once, with Tia enabled - fails on the shared-database rule, since
     * that is the first check {@link DistributedRunPreconditions#check} performs once Tia is
     * confirmed enabled, and the message a user sees should not depend on which rule happens to be
     * checked last.
     */
    @Test
    void check_bothRemainingRulesBroken_throwsNamingSharedDatabaseFirst() {
        // given - Tia enabled, but embedded H2 and checkLocalChanges both broken
        String dbUrl = null;

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DistributedRunPreconditions.check(true, dbUrl, null, true));

        // then
        assertTrue(ex.getMessage().contains("tiaDBUrl"),
                "message should name tiaDBUrl since the shared-database rule is checked first among "
                        + "the rules that apply once Tia is enabled, was: " + ex.getMessage());
    }

    /**
     * Verifies that {@code tiaEnabled=false} together with an embedded H2 database and {@code
     * checkLocalChanges=true} - all three rules broken at once - fails on the disabled-Tia rule,
     * since that is the very first check {@link DistributedRunPreconditions#check} performs. A
     * user who has disabled Tia should be told they are disabled rather than being told their
     * database is the wrong kind or that local-changes checking is on.
     */
    @Test
    void check_allRulesBroken_throwsNamingTiaEnabledFirst() {
        // given - Tia disabled, embedded H2, and checkLocalChanges all broken at once
        boolean tiaEnabled = false;
        String dbUrl = null;

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DistributedRunPreconditions.check(tiaEnabled, dbUrl, null, true));

        // then
        assertTrue(ex.getMessage().contains("tiaEnabled"),
                "message should name tiaEnabled since the disabled-Tia rule is checked first of all, "
                        + "was: " + ex.getMessage());
    }

    /**
     * Verifies that Tia enabled with a shared database (a Postgres URL) and {@code
     * checkLocalChanges=false} - all three rules satisfied - throws nothing, so a correctly
     * configured distributed run is never blocked.
     */
    @Test
    void check_tiaEnabledSharedDatabaseAndLocalChangesDisabled_throwsNothing() {
        // given - Tia enabled, a shared database, and checkLocalChanges off
        String dbUrl = "jdbc:postgresql://localhost:5432/tiaperf";

        // when / then
        assertDoesNotThrow(() -> DistributedRunPreconditions.check(true, dbUrl, null, false));
    }

    /**
     * Verifies that a server-mode H2 URL also satisfies the shared-database rule alongside Tia
     * enabled and {@code checkLocalChanges=false}, so server-mode H2 (not just Postgres) is a
     * valid distributed run configuration.
     */
    @Test
    void check_h2ServerUrlAndLocalChangesDisabled_throwsNothing() {
        // given - Tia enabled, a server-mode H2 URL, and checkLocalChanges off
        String dbUrl = "jdbc:h2:tcp://h2host:9092/tiadb";

        // when / then
        assertDoesNotThrow(() -> DistributedRunPreconditions.check(true, dbUrl, null, false));
    }
}
