package org.tiatesting.maven;

import org.apache.maven.plugins.annotations.Parameter;
import org.tiatesting.core.distributed.DistributedRunStatusReport;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.vcs.VCSReader;

/**
 * Mojo that prints the state of a distributed test run: the run itself, every group in its plan,
 * and the runner that claimed each one. Read-only - it claims, completes, seals and clears nothing -
 * so it is safe to run against a build whose runners are still going, from a second terminal or from
 * a CI step watching the fan-out.
 *
 * <p>Invoked as {@code mvn <plugin>:dist-status}. With no {@code -DtiaRunId} it reports the most
 * recently planned run, which is normally the only one, since each plan write clears the previous
 * run's rows; pass {@code -DtiaRunId} to name a specific one. Suite names are omitted by default and
 * printed with {@code -DtiaDistStatusSuites=true}, since a group's assigned suite list is unbounded
 * in size.
 *
 * <p>Unlike {@link AbstractTiaDistCompleteMojo}, this goal reads nothing from {@code fork.properties}
 * and needs no claim of its own: it reports on the shared database's view of the run rather than on
 * whatever this workspace happens to have done, which is what lets it be run from a machine that
 * took no part in the build at all. It does need the same {@code tiaDBUrl} the run's runners
 * coordinate through - pointed at a private embedded database it would simply find no run planned,
 * and say so.
 *
 * <p>See the distributed test runs chapter in {@code WIKI.md} for the lifecycle this goal reports on.
 */
public abstract class AbstractTiaDistStatusMojo extends AbstractTiaMojo {

    /**
     * Whether to list each group's assigned test suite names under the group table. Off by default
     * because a group's suite list is unbounded - a large project's plan can assign thousands of
     * names to one group, which would bury the run and group state the report exists to show.
     *
     * <p>Package-visible, like the distributed-run parameters on {@link AbstractTiaMojo}, so a test
     * can drive the goal without a Maven parameter-injection harness.
     */
    @Parameter(property = "tiaDistStatusSuites", defaultValue = "false")
    boolean tiaDistStatusSuites;

    /**
     * @return whether each group's assigned suite names are printed (default false)
     */
    public boolean isTiaDistStatusSuites() {
        return tiaDistStatusSuites;
    }

    /**
     * Read the distributed run's state from the shared datastore and print the report to stdout.
     *
     * <p>Opens the datastore against the workspace's current branch, since Tia isolates each branch
     * in its own schema and a run is only visible from the branch it was planned on. Every "no such
     * run" case - an unplanned branch, a superseded id, a private database - is reported as an
     * explanatory message rather than a failure: this goal answers a question, and a pipeline step
     * that prints the run's state must not be the thing that fails the build.
     */
    @Override
    public void execute() {
        final VCSReader vcsReader = getVCSReader();
        try (DataStore dataStore = buildDataStore(vcsReader.getBranchName())) {
            System.out.println(DistributedRunStatusReport.format(dataStore, getTiaRunId(),
                    tiaDistStatusSuites, System.currentTimeMillis(), System.lineSeparator()));
        }
    }
}
