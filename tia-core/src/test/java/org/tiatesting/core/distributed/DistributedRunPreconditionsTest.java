package org.tiatesting.core.distributed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link DistributedRunPreconditions#check}'s four rules that only the plugin layer can
 * enforce - Tia enabled, a single-project reactor, a shared database, and {@code
 * tiaCheckLocalChanges} disabled - and that the happy path throws nothing. Each rejection test
 * asserts on the exception message content, not just its type, because the whole point of naming
 * the user-facing property (or, for the reactor rule, the project count) in the message is
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
                () -> DistributedRunPreconditions.check(tiaEnabled, 1, dbUrl, null, false));

        // then
        assertTrue(ex.getMessage().contains("tiaEnabled"),
                "message should name tiaEnabled, was: " + ex.getMessage());
    }

    /**
     * Verifies that a reactor of more than one project is rejected, and that the message states
     * the project count and the reason a build with more than one project cannot be planned - the
     * planning goal is not bound as an aggregator, so it would run once per project and each
     * project's plan write would clear the previous project's plan from the shared database.
     * {@code tia-core} has no Maven or Gradle type to name the projects with, so naming them is
     * the caller's job; this rule only ever states the count.
     */
    @Test
    void check_multiProjectReactor_throwsNamingProjectCount() {
        // given - Tia enabled, a shared database, but a reactor of two projects
        String dbUrl = "jdbc:postgresql://localhost:5432/tiaperf";

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DistributedRunPreconditions.check(true, 2, dbUrl, null, false));

        // then
        String message = ex.getMessage();
        assertTrue(message.contains("2"), "message should state the project count, was: " + message);
        assertTrue(message.toLowerCase().contains("clear"),
                "message should mention that a later project's plan write clears the earlier "
                        + "one's, was: " + message);
    }

    /**
     * Verifies that a single-project build (the ordinary case) is unaffected by the reactor rule,
     * so it falls through to the next rule exactly as it did before the rule existed - here, the
     * embedded-H2 rule.
     */
    @Test
    void check_singleProjectReactorWithEmbeddedH2_throwsNamingSharedDatabaseOptions() {
        // given - Tia enabled, a single-project reactor, but a blank dbUrl (embedded H2)
        String dbUrl = null;

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DistributedRunPreconditions.check(true, 1, dbUrl, null, false));

        // then
        String message = ex.getMessage();
        assertTrue(message.contains("tiaDBUrl"), "message should name tiaDBUrl, was: " + message);
        assertTrue(message.toLowerCase().contains("server"), "message should mention server-mode H2, was: " + message);
        assertTrue(message.toLowerCase().contains("postgres"), "message should mention Postgres, was: " + message);
    }

    /**
     * Verifies that an embedded H2 configuration (blank {@code dbUrl}, no dialect override) is
     * rejected, and that the message names server-mode H2 and Postgres as the alternatives and
     * {@code tiaDBUrl} as the property that selects them, so a user reading the failure knows
     * exactly what to change.
     */
    @Test
    void check_embeddedH2_throwsNamingSharedDatabaseOptions() {
        // given - Tia enabled, a single-project reactor, but a blank dbUrl, which resolves to
        // embedded-mode H2
        String dbUrl = null;

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DistributedRunPreconditions.check(true, 1, dbUrl, null, false));

        // then
        String message = ex.getMessage();
        assertTrue(message.contains("tiaDBUrl"), "message should name tiaDBUrl, was: " + message);
        assertTrue(message.toLowerCase().contains("server"), "message should mention server-mode H2, was: " + message);
        assertTrue(message.toLowerCase().contains("postgres"), "message should mention Postgres, was: " + message);
    }

    /**
     * Verifies that {@code checkLocalChanges=true} is rejected even when Tia is enabled, the
     * reactor is single-project, and the database is shared, and that the message names {@code
     * tiaCheckLocalChanges}, so a user reading the failure knows exactly which setting broke the
     * every-runner-diffs-the-same-commit assumption.
     */
    @Test
    void check_localChangesEnabled_throwsNamingCheckLocalChangesProperty() {
        // given - Tia enabled, a single-project reactor, a shared (Postgres) database, but
        // checkLocalChanges is on
        String dbUrl = "jdbc:postgresql://localhost:5432/tiaperf";

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DistributedRunPreconditions.check(true, 1, dbUrl, null, true));

        // then
        String message = ex.getMessage();
        assertTrue(message.contains("tiaCheckLocalChanges"),
                "message should name tiaCheckLocalChanges, was: " + message);
    }

    /**
     * Verifies that a multi-project reactor together with an embedded H2 database - both of rules
     * 4 and 2 broken at once, with Tia enabled - fails on the reactor rule, since that is checked
     * immediately after the Tia-enabled rule, before the shared-database rule. The message a user
     * sees should not depend on which rule happens to be checked last.
     *
     * <p>Asserts on {@code "2 projects"}, a phrase unique to the rule-4 message, and separately
     * asserts {@code "tiaDBUrl"} (unique to the rule-2 message) is absent. A bare {@code
     * contains("2")} is not discriminating here: the rule-2 message names a {@code "server-mode H2
     * URL (jdbc:h2:tcp://...)"}, which itself contains the digit "2" (from "H2"), so a check that
     * only looked for "2" would pass just as well if the two rules were checked in the opposite
     * order.
     */
    @Test
    void check_multiProjectReactorAndEmbeddedH2_throwsNamingProjectCountFirst() {
        // given - Tia enabled, but a two-project reactor and embedded H2 both broken
        String dbUrl = null;

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DistributedRunPreconditions.check(true, 2, dbUrl, null, false));

        // then
        String message = ex.getMessage();
        assertTrue(message.contains("2 projects"),
                "message should state the project count since the reactor rule is checked "
                        + "immediately after the Tia-enabled rule, was: " + message);
        assertFalse(message.contains("tiaDBUrl"),
                "message should not contain the rule-2 (shared database) message, was: " + message);
    }

    /**
     * Verifies that an embedded H2 configuration with {@code checkLocalChanges=true} - both of the
     * remaining rules broken at once, with Tia enabled and a single-project reactor - fails on the
     * shared-database rule, since that is the first check {@link DistributedRunPreconditions#check}
     * performs once Tia is confirmed enabled and the reactor is confirmed single-project. The
     * message a user sees should not depend on which rule happens to be checked last.
     */
    @Test
    void check_bothRemainingRulesBroken_throwsNamingSharedDatabaseFirst() {
        // given - Tia enabled, a single-project reactor, but embedded H2 and checkLocalChanges
        // both broken
        String dbUrl = null;

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DistributedRunPreconditions.check(true, 1, dbUrl, null, true));

        // then
        assertTrue(ex.getMessage().contains("tiaDBUrl"),
                "message should name tiaDBUrl since the shared-database rule is checked first among "
                        + "the rules that apply once Tia is enabled and the reactor is single-project, "
                        + "was: " + ex.getMessage());
    }

    /**
     * Verifies that {@code tiaEnabled=false} together with a multi-project reactor, an embedded H2
     * database and {@code checkLocalChanges=true} - all four rules broken at once - fails on the
     * disabled-Tia rule, since that is the very first check {@link
     * DistributedRunPreconditions#check} performs. A user who has disabled Tia should be told they
     * are disabled rather than being told anything about their reactor shape, database, or
     * local-changes checking.
     */
    @Test
    void check_allRulesBroken_throwsNamingTiaEnabledFirst() {
        // given - Tia disabled, a two-project reactor, embedded H2, and checkLocalChanges all
        // broken at once
        boolean tiaEnabled = false;
        String dbUrl = null;

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DistributedRunPreconditions.check(tiaEnabled, 2, dbUrl, null, true));

        // then
        assertTrue(ex.getMessage().contains("tiaEnabled"),
                "message should name tiaEnabled since the disabled-Tia rule is checked first of all, "
                        + "was: " + ex.getMessage());
    }

    /**
     * Verifies that Tia enabled with a single-project reactor, a shared database (a Postgres URL)
     * and {@code checkLocalChanges=false} - all four rules satisfied - throws nothing, so a
     * correctly configured distributed run is never blocked.
     */
    @Test
    void check_tiaEnabledSharedDatabaseAndLocalChangesDisabled_throwsNothing() {
        // given - Tia enabled, a single-project reactor, a shared database, and checkLocalChanges off
        String dbUrl = "jdbc:postgresql://localhost:5432/tiaperf";

        // when / then
        assertDoesNotThrow(() -> DistributedRunPreconditions.check(true, 1, dbUrl, null, false));
    }

    /**
     * Verifies that a server-mode H2 URL also satisfies the shared-database rule alongside Tia
     * enabled, a single-project reactor, and {@code checkLocalChanges=false}, so server-mode H2
     * (not just Postgres) is a valid distributed run configuration.
     */
    @Test
    void check_h2ServerUrlAndLocalChangesDisabled_throwsNothing() {
        // given - Tia enabled, a single-project reactor, a server-mode H2 URL, and
        // checkLocalChanges off
        String dbUrl = "jdbc:h2:tcp://h2host:9092/tiadb";

        // when / then
        assertDoesNotThrow(() -> DistributedRunPreconditions.check(true, 1, dbUrl, null, false));
    }
}
