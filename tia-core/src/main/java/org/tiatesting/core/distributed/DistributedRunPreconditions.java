package org.tiatesting.core.distributed;

import org.tiatesting.core.persistence.DataStoreFactory;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.util.List;

/**
 * Enforces the four distributed-run validation rules {@link DistributedRunConfig} deliberately
 * does not check, because it is built from raw property values and has no way to know any of
 * them: whether Tia is enabled at all, whether the current build is a single project, the
 * resolved datastore, and whether local-changes checking is enabled are all plugin-layer
 * concerns. Four call sites call {@link #check} before doing anything else - in particular before
 * the datastore is opened or a group is claimed: the Maven and Gradle planning entry points
 * (the {@code tia-dist-plan} goal and task), which persist a plan, and the Maven and Gradle/Spock
 * claim-time entry points (the {@code prepare-agent} goal, and the forked test JVM's claim on the
 * Gradle/Spock side), which claim a group from one. So a misconfigured or disabled distributed run
 * fails fast with an actionable message instead of silently producing a broken plan, a corrupted
 * diff, or a claimable run persisted to the database while Tia is switched off.
 *
 * <p>Rule 1 - Tia must be enabled. {@code tia-dist-plan} opens the shared datastore and persists a
 * claimable run; there is no read-only or preview form of it the way {@code select-tests} has one.
 * If Tia is disabled the user has asked for a plan they are not going to get, so this is checked
 * before anything else - a disabled user should be told they are disabled, not told their database
 * is the wrong kind or that a checkbox further down the form is unticked.
 *
 * <p>Rule 4 - the build must be a single project. This rule guards two different kinds of call
 * site, not one: the planning entry points, which persist a plan, and the claim-time entry points,
 * which claim a group from one. Neither kind is bound as an aggregator/single invocation across a
 * multi-module build - each is bound per module - so in a reactor of more than one project both
 * the planning step and every runner's claim would run once per project. On the planning side,
 * persisting a plan clears the four distributed-run tables before inserting its own, so each
 * project's plan write wipes out the previous project's plan; the last project to run leaves its
 * plan behind, every project planned before it has suites assigned to groups no runner is watching
 * for, and the build reports success having silently dropped that work. On the claim-time side,
 * a claim made once per project would claim a fresh group per project instead of the single group
 * the runner process is meant to hold. This is checked immediately after "is Tia enabled", before
 * the shared-database and local-changes rules, because a build that cannot be planned or claimed
 * against at all makes both of those questions moot - a reactor of two projects is broken the same
 * way whether its database is shared or not. Multi-module support is future work, not something
 * this configuration can be adjusted to fix.
 *
 * <p>Rule 2 - the datastore must be shared. The runners coordinate entirely through it: one claims
 * a group, another sees it claimed, the last one to finish opens the barrier. An embedded
 * file-on-disk H2 gives each runner its own private copy, so no runner would ever see another's
 * rows - every runner would claim group 0, run the same tests, and the build would report success
 * having run only a fraction of the suite on each host with no barrier ever opening. {@link
 * DataStoreFactory#isSharedDatabase(String, String)} is the single source of truth for what counts
 * as shared, kept next to {@link DataStoreFactory#fromConfig} so the two cannot disagree.
 *
 * <p>Rule 3 - {@code tiaCheckLocalChanges} must be off. A distributed run is a primary build
 * diffing a committed baseline, and the whole design rests on every runner producing line numbers
 * for the same commit. Uncommitted local changes make that false per-runner: two runners with
 * different working-copy edits would compute different line numbers for the same source file
 * against the same commit, corrupting the shared plan they are meant to agree on.
 */
public final class DistributedRunPreconditions {

    private DistributedRunPreconditions() {
    }

    /**
     * Validate that a distributed run's environment can support runner coordination, checking
     * rule 1 (Tia enabled) before rule 4 (single-project build) before rule 2 (shared database)
     * before rule 3 (local-changes checking off), so a configuration that breaks more than one
     * rule fails on the most fundamental one - a disabled Tia makes every other question moot, a
     * build that cannot be planned or claimed against at all (a multi-project reactor) makes the
     * database and local-changes questions moot, and a database no runner can share makes the
     * local-changes question moot.
     *
     * @param tiaEnabled       the resolved value of {@code tiaEnabled}; must be true, since {@code
     *                         tia-dist-plan} opens the shared datastore and persists a claimable run
     *                         with no read-only preview form
     * @param projectCount     the number of projects taking part in the current build (a Maven
     *                         reactor's project count, or the equivalent for a Gradle multi-project
     *                         build); must be at most 1, since neither the planning step nor a
     *                         runner's claim is bound to run only once across a multi-project
     *                         build - each project's plan write clears the previous project's plan,
     *                         and each project's claim would claim a fresh group instead of the one
     *                         group the runner process is meant to hold. Passed as a count rather
     *                         than a list of projects so this class stays free of any Maven or
     *                         Gradle type; a caller that wants to name the projects in its own
     *                         error message can do so with the objects it already has
     * @param dbUrl            server-mode JDBC URL, or {@code null}/blank for embedded mode; passed
     *                         straight through to {@link DataStoreFactory#isSharedDatabase(String, String)}
     * @param dialectOverride  an explicit dialect id (e.g. {@code "h2"}), or {@code null}/blank to
     *                         infer the dialect from {@code dbUrl}
     * @param checkLocalChanges the resolved value of {@code tiaCheckLocalChanges}; must be false
     * @throws IllegalStateException if {@code tiaEnabled} is false, naming {@code tiaEnabled} as
     *         the property to set; or if {@code projectCount} is more than 1, stating the project
     *         count and that multi-module distributed runs (planning or claiming) are not
     *         supported; or if the resolved
     *         datastore is embedded H2, naming server-mode H2 and Postgres as the options and {@code
     *         tiaDBUrl} as the property that selects them; or if {@code checkLocalChanges} is true,
     *         naming {@code tiaCheckLocalChanges}
     * @throws IllegalArgumentException if {@link DataStoreFactory#isSharedDatabase(String, String)}
     *         cannot resolve a dialect for {@code dbUrl}/{@code dialectOverride} - see
     *         {@link DataStoreFactory#fromConfig}; this class does not catch it, so it propagates
     *         to the caller unchanged
     */
    public static void check(final boolean tiaEnabled, final int projectCount, final String dbUrl,
                              final String dialectOverride, final boolean checkLocalChanges) {
        if (!tiaEnabled) {
            throw new IllegalStateException(
                    "Distributed test runs require Tia to be enabled, but tiaEnabled is false - "
                            + "there is no plan to make. Set tiaEnabled to true to plan a distributed "
                            + "test run.");
        }
        if (projectCount > 1) {
            throw new IllegalStateException(
                    "Distributed test runs require the planning step to run exactly once for the "
                            + "whole build, and require each runner to claim exactly one group, but "
                            + "this build has " + projectCount + " projects - the Maven tia-dist-plan "
                            + "goal and the Gradle plan task are each bound per module rather than once "
                            + "across a multi-module build, and the Maven prepare-agent goal is bound "
                            + "the same way, so on a reactor of more than one project each would run "
                            + "once per project. A plan written once per project would each clear the "
                            + "previous project's plan from the shared distributed-run tables before "
                            + "inserting its own - the last project to run would leave its plan behind, "
                            + "and every project planned before it would have suites assigned to groups "
                            + "no runner is watching for. A claim made once per project would claim a "
                            + "fresh group per project instead of the single group the runner process "
                            + "is meant to hold. Multi-module support is future work, not a property "
                            + "you can set to fix this - both the planning step and every claiming "
                            + "runner must run against a single-project build until it lands.");
        }
        if (!DataStoreFactory.isSharedDatabase(dbUrl, dialectOverride)) {
            throw new IllegalStateException(
                    "Distributed test runs require a shared database every runner can reach "
                            + "concurrently, but an embedded H2 datastore was configured - each runner "
                            + "would get its own private copy and no runner would ever see another's "
                            + "group claims. Set " + H2ConnectionSettings.PROP_DB_URL + " to a "
                            + "server-mode H2 URL (jdbc:h2:tcp://...) or a Postgres URL "
                            + "(jdbc:postgresql://...).");
        }
        if (checkLocalChanges) {
            throw new IllegalStateException(
                    "Distributed test runs require every runner to diff the same committed baseline, "
                            + "but tiaCheckLocalChanges is enabled - uncommitted local changes would make "
                            + "each runner compute different line numbers for the same commit. Set "
                            + "tiaCheckLocalChanges to false for a distributed run.");
        }
    }

    /**
     * Append a build's project names to a rule-4 (multi-project) failure message from {@link
     * #check}, so a build-tool layer can tell the user exactly which projects it found - this
     * class has no Maven or Gradle type to name them with itself, so it only ever states the
     * count. Shared by every caller that wants to name projects (both the Maven and Gradle
     * planning entry points, and the Maven claim-time entry point) so the naming logic and its
     * "only when relevant" gate live in one place rather than being duplicated per build tool with
     * only the source type and getter differing.
     *
     * <p>Appends nothing when {@code tiaEnabled} is false or {@code projectNames} holds at most one
     * name, since in both cases the message passed in did not come from rule 4: a disabled Tia
     * fails on rule 1 before rule 4 is ever reached, and a single-project build never trips rule 4
     * at all. A caller does not need to inspect the message text to decide whether to call this -
     * appending is a no-op whenever it would not apply.
     *
     * @param message the failure message from {@link #check}, appended to unchanged when the
     *                 rule-4 gate below does not apply
     * @param tiaEnabled the resolved {@code tiaEnabled} value the caller ran {@link #check} with,
     *                    mirroring the condition under which rule 4 - rather than rule 1 - would
     *                    have produced {@code message}
     * @param projectNames the names of every project found in the reactor/build, in the same order
     *                      as the count the caller passed to {@link #check}
     * @return {@code message} unchanged, or with the project names appended when {@code
     *         tiaEnabled} is true and more than one project name was supplied
     */
    public static String withReactorProjectNamesIfRelevant(final String message, final boolean tiaEnabled,
                                                             final List<String> projectNames) {
        if (!tiaEnabled || projectNames.size() <= 1) {
            return message;
        }
        return message + " Projects taking part in this build: " + String.join(", ", projectNames) + ".";
    }
}
