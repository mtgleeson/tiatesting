package org.tiatesting.gradle.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.tiatesting.core.distributed.DistributedRunStatusReport;
import org.tiatesting.core.persistence.DataStore;

/**
 * Gradle task that prints the state of a distributed test run: the run itself, every group in its
 * plan, and the runner that claimed each one. The Gradle equivalent of the Maven {@code dist-status}
 * goal ({@code AbstractTiaDistStatusMojo}), sharing its whole report with it through {@link
 * DistributedRunStatusReport} so the two build tools cannot drift on what a run's state is called.
 *
 * <p>Read-only: it claims, completes, seals and clears nothing, so it is safe to run against a build
 * whose runners are still going, from a second terminal or from a CI step watching the fan-out.
 *
 * <p>Invoked as {@code ./gradlew tia-dist-status}. The run reported on is {@code --runId} when
 * given, otherwise the extension's {@code tia { runId = ... } }, otherwise the most recently planned
 * run - which is normally the only one, since each plan write clears the previous run's rows. The
 * command-line flag comes first so a developer can inspect any run from a workspace whose build file
 * is configured for a different one. Suite names are omitted unless {@code --suites} is passed, since
 * a group's assigned suite list is unbounded in size.
 *
 * <p>Unlike {@link TiaDistCompleteTask}, this task reads no claim from the build's {@link
 * DistributedClaimRegistry} and is registered unconditionally rather than only for a distributed
 * build: it reports on the shared database's view of a run rather than on anything this build did,
 * which is what lets it be run from a workspace that took no part in the run at all. Implemented as
 * a {@link DefaultTask} subclass like {@link TiaDistPlanTask}, with the owning plugin injected at
 * registration time, so its {@link Option @Option} flags can be wired in through Gradle's
 * task-options machinery.
 */
public class TiaDistStatusTask extends DefaultTask {

    private TiaBasePlugin plugin;
    private String runId;
    private boolean suites;

    /**
     * Inject the owning plugin; called from {@link TiaBasePlugin#createDistStatusTask()} at task
     * registration so the datastore, VCS reader and configured run id are resolved lazily at
     * execution time rather than at plugin-apply time.
     *
     * @param plugin the {@link TiaBasePlugin} instance that registered this task
     */
    public void setPlugin(TiaBasePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Setter used by Gradle when the user passes {@code --runId=<id>} on the command line.
     *
     * @param runId the run to report on, overriding any configured {@code tia { runId } }
     */
    @Option(option = "runId", description = "Distributed run id to report on (default: the "
            + "configured tia.runId, or the most recently planned run).")
    public void setRunId(String runId) {
        this.runId = runId;
    }

    /**
     * @return the current {@code --runId} value, or null when the flag was not supplied
     */
    @Input
    @Optional
    public String getRunId() {
        return runId;
    }

    /**
     * Setter used by Gradle when the user passes {@code --suites} on the command line.
     *
     * @param suites whether to list each group's assigned suite names
     */
    @Option(option = "suites", description = "Also list the test suite names assigned to each group.")
    public void setSuites(boolean suites) {
        this.suites = suites;
    }

    /**
     * @return whether each group's assigned suite names are printed (default false)
     */
    @Input
    public boolean getSuites() {
        return suites;
    }

    /**
     * Read the distributed run's state from the shared datastore and print the report to stdout.
     *
     * <p>Opens the datastore against the workspace's current branch, since Tia isolates each branch
     * in its own schema and a run is only visible from the branch it was planned on. Every "no such
     * run" case - an unplanned branch, a superseded id, a private database - is reported as an
     * explanatory message rather than a failure: this task answers a question, and a pipeline step
     * that prints a run's state must not be the thing that fails the build.
     */
    @TaskAction
    public void run() {
        String requestedRunId = runId != null && !runId.trim().isEmpty() ? runId : plugin.getRunId();
        try (DataStore dataStore = plugin.buildDistributedDataStore(plugin.getVCSReader().getBranchName())) {
            System.out.println(DistributedRunStatusReport.format(dataStore, requestedRunId, suites,
                    System.currentTimeMillis(), System.lineSeparator()));
        }
    }
}
