package org.tiatesting.core.distributed;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
     * the project count and the reason a build with more than one project cannot be planned or
     * claimed against - neither the planning goal nor the claim-time goal is bound as an
     * aggregator, so each would run once per project: on the planning side, each project's plan
     * write would clear the previous project's plan from the shared database; on the claim-time
     * side, each project's claim would claim a fresh group instead of the single group the runner
     * process is meant to hold. {@code tia-core} has no Maven or Gradle type to name the projects
     * with, so naming them is the caller's job; this rule only ever states the count.
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

    /**
     * Verifies that {@link DistributedRunPreconditions#withReactorProjectNamesIfRelevant} leaves
     * the message unchanged when {@code tiaEnabled} is false, even with more than one project
     * name supplied - a disabled Tia fails on rule 1 before rule 4 (the reactor rule) is ever
     * reached, so a message produced under {@code tiaEnabled=false} never came from rule 4 and has
     * nothing to append project names to.
     */
    @Test
    void withReactorProjectNamesIfRelevant_tiaDisabled_returnsMessageUnchanged() {
        // given - Tia disabled and a message that did not come from the reactor rule, alongside
        // more than one project name
        String message = "Distributed test runs require Tia to be enabled.";
        List<String> projectNames = Arrays.asList("module-a", "module-b");

        // when
        String result = DistributedRunPreconditions.withReactorProjectNamesIfRelevant(message, false, projectNames);

        // then
        assertEquals(message, result);
    }

    /**
     * Verifies that {@link DistributedRunPreconditions#withReactorProjectNamesIfRelevant} leaves
     * the message unchanged when the project list is empty, so a caller that has not resolved any
     * reactor projects (the {@code size == 0} branch) never appends a dangling "Projects taking
     * part in this build: ." suffix.
     */
    @Test
    void withReactorProjectNamesIfRelevant_zeroProjects_returnsMessageUnchanged() {
        // given - Tia enabled, but no project names resolved at all
        String message = "Distributed test runs require the planning step to run exactly once.";
        List<String> projectNames = Collections.emptyList();

        // when
        String result = DistributedRunPreconditions.withReactorProjectNamesIfRelevant(message, true, projectNames);

        // then
        assertEquals(message, result);
    }

    /**
     * Verifies that {@link DistributedRunPreconditions#withReactorProjectNamesIfRelevant} leaves
     * the message unchanged for a single-project build, so a build that never trips rule 4 never
     * has a one-project list appended to an unrelated message.
     */
    @Test
    void withReactorProjectNamesIfRelevant_singleProject_returnsMessageUnchanged() {
        // given - Tia enabled and exactly one project name, the ordinary single-module case
        String message = "Distributed test runs require the planning step to run exactly once.";
        List<String> projectNames = Collections.singletonList("module-a");

        // when
        String result = DistributedRunPreconditions.withReactorProjectNamesIfRelevant(message, true, projectNames);

        // then
        assertEquals(message, result);
    }

    /**
     * Verifies that {@link DistributedRunPreconditions#withReactorProjectNamesIfRelevant} appends
     * every project name, comma-joined and in order, when Tia is enabled and more than one project
     * name is supplied - the case rule 4's rejection message is meant to be enriched for.
     */
    @Test
    void withReactorProjectNamesIfRelevant_multipleProjectsAndTiaEnabled_appendsJoinedNames() {
        // given - Tia enabled and three project names
        String message = "Distributed test runs require the planning step to run exactly once.";
        List<String> projectNames = Arrays.asList("module-a", "module-b", "module-c");

        // when
        String result = DistributedRunPreconditions.withReactorProjectNamesIfRelevant(message, true, projectNames);

        // then
        assertEquals(message + " Projects taking part in this build: module-a, module-b, module-c.", result);
    }
}
