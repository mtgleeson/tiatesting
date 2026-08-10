package org.tiatesting.core.distributed;

import org.tiatesting.core.persistence.DataStoreFactory;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

/**
 * Enforces the two distributed-run validation rules {@link DistributedRunConfig} deliberately does
 * not check, because it is built from raw property values and has no way to know either fact: the
 * resolved datastore and whether local-changes checking is enabled are both plugin-layer concerns.
 * Task 2 (the Maven goal) and Task 3 (the Gradle task) both call {@link #check} before doing
 * anything else, so a misconfigured distributed run fails fast with an actionable message instead
 * of silently producing a broken plan or a corrupted diff.
 *
 * <p>Rule 1 - the datastore must be shared. The runners coordinate entirely through it: one claims
 * a group, another sees it claimed, the last one to finish opens the barrier. An embedded
 * file-on-disk H2 gives each runner its own private copy, so no runner would ever see another's
 * rows - every runner would claim group 0, run the same tests, and the build would report success
 * having run only a fraction of the suite on each host with no barrier ever opening. {@link
 * DataStoreFactory#isSharedDatabase(String, String)} is the single source of truth for what counts
 * as shared, kept next to {@link DataStoreFactory#fromConfig} so the two cannot disagree.
 *
 * <p>Rule 2 - {@code tiaCheckLocalChanges} must be off. A distributed run is a primary build
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
     * rule 1 (shared database) before rule 2 (local-changes checking off) so a configuration that
     * breaks both rules fails on the more fundamental one - a database no runner can share makes
     * the local-changes question moot.
     *
     * @param dbUrl            server-mode JDBC URL, or {@code null}/blank for embedded mode; passed
     *                         straight through to {@link DataStoreFactory#isSharedDatabase(String, String)}
     * @param dialectOverride  an explicit dialect id (e.g. {@code "h2"}), or {@code null}/blank to
     *                         infer the dialect from {@code dbUrl}
     * @param checkLocalChanges the resolved value of {@code tiaCheckLocalChanges}; must be false
     * @throws IllegalStateException if the resolved datastore is embedded H2, naming server-mode H2
     *         and Postgres as the options and {@code tiaDBUrl} as the property that selects them;
     *         or if {@code checkLocalChanges} is true, naming {@code tiaCheckLocalChanges}
     * @throws IllegalArgumentException if {@link DataStoreFactory#isSharedDatabase(String, String)}
     *         cannot resolve a dialect for {@code dbUrl}/{@code dialectOverride} - see
     *         {@link DataStoreFactory#fromConfig}; this class does not catch it, so it propagates
     *         to the caller unchanged
     */
    public static void check(final String dbUrl, final String dialectOverride, final boolean checkLocalChanges) {
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
