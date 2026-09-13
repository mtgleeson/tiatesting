package org.tiatesting.gradle.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.report.LibraryPublishesReportGenerator;
import org.tiatesting.core.vcs.WorkspaceIdentity;

import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Gradle task that prints a tracked library's publish ledger as a table - one row per published
 * build with its sequence, version, jar hash, commit, publish time and pending method count.
 * Invoked as {@code ./gradlew tia-library-publishes --library=groupId:artifactId}.
 *
 * <p>Implemented as a {@link DefaultTask} subclass (like {@link TiaHistoryTask}) so the
 * {@link Option @Option}-annotated {@code --library} CLI flag can be wired in through Gradle's
 * task-options machinery.
 */
public class TiaLibraryPublishesTask extends DefaultTask {

    private String library;
    private Supplier<WorkspaceIdentity> workspaceIdentitySupplier;
    private BiFunction<String, String, DataStore> dataStoreFactory;
    private Supplier<Set<String>> schemaSuffixes;

    /**
     * Setter used by Gradle when the user passes {@code --library=groupId:artifactId} on the
     * command line.
     *
     * @param library the {@code groupId:artifactId} of the tracked library to report on
     */
    @Option(option = "library", description = "The groupId:artifactId of the tracked library to report on.")
    public void setLibrary(String library) {
        this.library = library;
    }

    /**
     * @return the {@code --library} value, or null when the flag was not supplied
     */
    @Input
    @Optional
    public String getLibrary() {
        return library;
    }

    /**
     * Inject the VCS reader factory; called from {@code TiaBasePlugin} at task registration so
     * the reader is resolved lazily at execution time.
     *
     * @param workspaceIdentitySupplier supplier of this build's {@link WorkspaceIdentity}, which
     *                                  resolves the branch from configuration where it is set and
     *                                  from the version control system where it is not
     */
    public void setWorkspaceIdentitySupplier(Supplier<WorkspaceIdentity> workspaceIdentitySupplier) {
        this.workspaceIdentitySupplier = workspaceIdentitySupplier;
    }

    /**
     * Inject the datastore factory; called from {@code TiaBasePlugin} at task registration so the
     * datastore is built at execution time rather than apply time.
     *
     * @param dataStoreFactory factory mapping a branch name to a constructed {@link DataStore}
     */
    public void setDataStoreFactory(BiFunction<String, String, DataStore> dataStoreFactory) {
        this.dataStoreFactory = dataStoreFactory;
    }

    /**
     * Supply the schema suffixes this report iterates - one per distinct suffix declared across the
     * project's Tia-enabled test tasks, so a project that isolates its test tasks into their own
     * schemas gets a section per schema. A single-schema project supplies one entry and the output
     * is unchanged.
     *
     * @param schemaSuffixes supplier of the suffixes to report over, which may contain null
     */
    public void setSchemaSuffixes(Supplier<Set<String>> schemaSuffixes) {
        this.schemaSuffixes = schemaSuffixes;
    }

    /**
     * Read the library's publish ledger from the Tia DB and print the formatted table.
     */
    @TaskAction
    public void run() {
        // Resolved and released before the loop: every schema below belongs to the one branch,
        // and holding a repository handle open across the reads would serve nothing.
        final String branch;
        try (WorkspaceIdentity workspaceIdentity = workspaceIdentitySupplier.get()) {
            branch = workspaceIdentity.getBranch();
        }
        Set<String> suffixes = schemaSuffixes.get();
        for (String suffix : suffixes) {
            TiaSchemaResolver.printSchemaHeadingIfNeeded(suffix, suffixes.size());
            try (DataStore dataStore = dataStoreFactory.apply(branch, suffix)) {
                LibraryPublishesReportGenerator reportGenerator = new LibraryPublishesReportGenerator();
                System.out.println(reportGenerator.generateLibraryPublishesReport(dataStore, library));
            }
        }
    }
}
