package org.tiatesting.gradle.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.report.LibraryPublishesReportGenerator;
import org.tiatesting.core.vcs.VCSReader;

import java.util.function.Function;
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
    private Supplier<VCSReader> vcsReaderSupplier;
    private Function<String, H2ConnectionSettings> connectionSettingsFactory;

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
     * @param vcsReaderSupplier supplier of the active {@link VCSReader}
     */
    public void setVcsReaderSupplier(Supplier<VCSReader> vcsReaderSupplier) {
        this.vcsReaderSupplier = vcsReaderSupplier;
    }

    /**
     * Inject the connection-settings factory; called from {@code TiaBasePlugin} at task
     * registration so the settings resolve at execution time rather than apply time.
     *
     * @param connectionSettingsFactory factory mapping a branch name to {@link H2ConnectionSettings}
     */
    public void setConnectionSettingsFactory(Function<String, H2ConnectionSettings> connectionSettingsFactory) {
        this.connectionSettingsFactory = connectionSettingsFactory;
    }

    /**
     * Read the library's publish ledger from the Tia DB and print the formatted table.
     */
    @TaskAction
    public void run() {
        VCSReader vcsReader = vcsReaderSupplier.get();
        try (DataStore dataStore = new H2DataStore(connectionSettingsFactory.apply(vcsReader.getBranchName()))) {
            LibraryPublishesReportGenerator reportGenerator = new LibraryPublishesReportGenerator();
            System.out.println(reportGenerator.generateLibraryPublishesReport(dataStore, library));
        }
    }
}
