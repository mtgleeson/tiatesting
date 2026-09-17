package org.tiatesting.gradle.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.report.TestRunHistoryDetailConsoleFormatter;
import org.tiatesting.core.vcs.WorkspaceIdentity;

import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Gradle task that prints one recorded test run's full selection breakdown to stdout. Invoked as
 * {@code ./gradlew tia-history-details --id=<historyId>}; the id is a value from the
 * {@code tia_test_run_history} table, typically copied from the {@code tia-history} task's output.
 *
 * <p>Implemented as a {@link DefaultTask} subclass, mirroring {@link TiaHistoryTask}, so the
 * {@link Option @Option}-annotated {@code --id} CLI flag can be wired in through Gradle's
 * task-options machinery.
 */
public class TiaHistoryDetailsTask extends DefaultTask {

    private String id;
    private Supplier<WorkspaceIdentity> workspaceIdentitySupplier;
    private BiFunction<String, String, DataStore> dataStoreFactory;
    private Supplier<Set<String>> schemaSuffixes;

    /**
     * Setter used by Gradle when the user passes {@code --id=<historyId>} on the command line.
     *
     * @param id the test run history id to show details for
     */
    @Option(option = "id", description = "The test run history id to show details for.")
    public void setId(String id) {
        this.id = id;
    }

    /**
     * @return the current {@code --id} value, or null when the flag was not supplied
     */
    @Input
    public String getId() {
        return id;
    }

    /**
     * Inject the VCS reader factory; called from {@code TiaBasePlugin.createHistoryDetailsTask} at
     * task registration so the reader is resolved lazily at execution time.
     *
     * @param workspaceIdentitySupplier supplier of this build's {@link WorkspaceIdentity}, which
     *                                  resolves the branch from configuration where it is set and
     *                                  from the version control system where it is not
     */
    public void setWorkspaceIdentitySupplier(Supplier<WorkspaceIdentity> workspaceIdentitySupplier) {
        this.workspaceIdentitySupplier = workspaceIdentitySupplier;
    }

    /**
     * Inject the datastore factory; called from {@code TiaBasePlugin.createHistoryDetailsTask} at
     * task registration so the datastore (which depends on the consumer's {@code tia { ... }}
     * extension and the run's branch) is built at execution time rather than apply time. The
     * factory takes the branch name and returns a constructed {@link DataStore}.
     *
     * @param dataStoreFactory factory mapping a branch name to a constructed {@link DataStore}
     */
    public void setDataStoreFactory(BiFunction<String, String, DataStore> dataStoreFactory) {
        this.dataStoreFactory = dataStoreFactory;
    }

    /**
     * Supply the schema suffixes this report iterates - one per distinct suffix declared across the
     * project's Tia-enabled test tasks, so a project that isolates its test tasks into their own
     * schemas is searched across every one of them for the requested id. A single-schema project
     * supplies one entry and the search is unchanged.
     *
     * @param schemaSuffixes supplier of the suffixes to search over, which may contain null
     */
    public void setSchemaSuffixes(Supplier<Set<String>> schemaSuffixes) {
        this.schemaSuffixes = schemaSuffixes;
    }

    /**
     * Look up the requested run by id across every configured schema suffix and print its full
     * selection breakdown, stopping at the first schema that holds it. Fails fast with a
     * {@link GradleException} when {@code --id} is missing or blank. Prints a not-found message,
     * rather than failing the build, when no schema holds a matching row - the id may simply be
     * wrong, which is a usage mistake rather than a build failure.
     */
    @TaskAction
    public void run() {
        if (id == null || id.trim().isEmpty()) {
            throw new GradleException("--id is required, e.g. ./gradlew tia-history-details --id=<historyId>");
        }
        String trimmedId = id.trim();
        String lineSep = System.lineSeparator();

        // Resolved and released before the loop: every schema below belongs to the one branch,
        // and holding a repository handle open across the reads would serve nothing.
        final String branch;
        try (WorkspaceIdentity workspaceIdentity = workspaceIdentitySupplier.get()) {
            branch = workspaceIdentity.getBranch();
        }

        for (String suffix : schemaSuffixes.get()) {
            try (DataStore dataStore = dataStoreFactory.apply(branch, suffix)) {
                for (TestRunHistoryEntry entry : dataStore.readTestRunHistory()) {
                    if (trimmedId.equals(entry.getId())) {
                        System.out.println(TestRunHistoryDetailConsoleFormatter.format(
                                entry, dataStore.readTestRunTriggers(entry.getId()), lineSep));
                        return;
                    }
                }
            }
        }

        System.out.println(TestRunHistoryDetailConsoleFormatter.notFound(trimmedId, lineSep));
    }
}
