package org.tiatesting.core.distributed;

import org.tiatesting.core.persistence.DataStoreFactory;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

/**
 * Enforces the four distributed-run validation rules {@link DistributedRunConfig} deliberately
 * does not check, because it is built from raw property values and has no way to know any of
 * them: whether Tia is enabled at all, whether the current build is a single project, the
 * resolved datastore, and whether local-changes checking is enabled are all plugin-layer
 * concerns. Task 2 (the Maven goal) and Task 3 (the Gradle task) both call {@link #check} before
 * doing anything else - in particular before the datastore is opened - so a misconfigured or
 * disabled distributed run fails fast with an actionable message instead of silently producing a
 * broken plan, a corrupted diff, or a claimable run persisted to the database while Tia is
 * switched off.
 *
 * <p>Rule 1 - Tia must be enabled. {@code tia-dist-plan} opens the shared datastore and persists a
 * claimable run; there is no read-only or preview form of it the way {@code select-tests} has one.
 * If Tia is disabled the user has asked for a plan they are not going to get, so this is checked
 * before anything else - a disabled user should be told they are disabled, not told their database
 * is the wrong kind or that a checkbox further down the form is unticked.
 *
 * <p>Rule 4 - the build must be a single project. Neither the Maven {@code tia-dist-plan} goal nor
 * the Gradle plan task is bound as an aggregator/single invocation across a multi-module build, so
 * in a reactor of more than one project the planning step runs once per project - and persisting a
 * plan clears the four distributed-run tables before inserting its own, so each project's plan
 * write wipes out the previous project's plan. The last project to run leaves its plan behind;
 * every project planned before it has suites assigned to groups no runner is watching for, and the
 * build reports success having silently dropped that work. This is checked immediately after
 * "is Tia enabled", before the shared-database and local-changes rules, because a build that
 * cannot be planned at all makes both of those questions moot - a reactor of two projects is
 * broken the same way whether its database is shared or not. Multi-module support is future work,
 * not something this configuration can be adjusted to fix.
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
     * build that cannot be planned at all (a multi-project reactor) makes the database and
     * local-changes questions moot, and a database no runner can share makes the local-changes
     * question moot.
     *
     * @param tiaEnabled       the resolved value of {@code tiaEnabled}; must be true, since {@code
     *                         tia-dist-plan} opens the shared datastore and persists a claimable run
     *                         with no read-only preview form
     * @param projectCount     the number of projects taking part in the current build (a Maven
     *                         reactor's project count, or the equivalent for a Gradle multi-project
     *                         build); must be at most 1, since planning is not bound to run once
     *                         across a multi-project build and each project's plan write clears the
     *                         previous project's plan. Passed as a count rather than a list of
     *                         projects so this class stays free of any Maven or Gradle type; a
     *                         caller that wants to name the projects in its own error message can do
     *                         so with the objects it already has
     * @param dbUrl            server-mode JDBC URL, or {@code null}/blank for embedded mode; passed
     *                         straight through to {@link DataStoreFactory#isSharedDatabase(String, String)}
     * @param dialectOverride  an explicit dialect id (e.g. {@code "h2"}), or {@code null}/blank to
     *                         infer the dialect from {@code dbUrl}
     * @param checkLocalChanges the resolved value of {@code tiaCheckLocalChanges}; must be false
     * @throws IllegalStateException if {@code tiaEnabled} is false, naming {@code tiaEnabled} as
     *         the property to set; or if {@code projectCount} is more than 1, stating the project
     *         count and that multi-module distributed planning is not supported; or if the resolved
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
                            + "whole build, but this build has " + projectCount + " projects - neither "
                            + "the Maven tia-dist-plan goal nor the Gradle plan task is bound to run "
                            + "only once across a multi-module build, so it would run once per project, "
                            + "and persisting a plan clears the previous plan's rows from the shared "
                            + "distributed-run tables before inserting its own. The last project to run "
                            + "would leave its plan behind; every project planned before it would have "
                            + "suites assigned to groups no runner is watching for, and the build would "
                            + "report success having silently dropped that work. Multi-module support is "
                            + "future work, not a property you can set to fix this - run the planning "
                            + "step against a single-project build until it lands.");
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
}
