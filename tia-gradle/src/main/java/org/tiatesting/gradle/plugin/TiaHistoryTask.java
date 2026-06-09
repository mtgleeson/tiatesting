package org.tiatesting.gradle.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.report.TestRunHistoryConsoleFormatter;
import org.tiatesting.core.vcs.VCSReader;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Gradle task that prints the most recent rows from the {@code tia_test_run_history} table to stdout.
 * Invoked as {@code ./gradlew tia-history}; the row cap is configurable via {@code --last N}
 * (default {@code 20}).
 *
 * <p>Implemented as a {@link DefaultTask} subclass (rather than the inline {@code doLast}
 * closure pattern used by the other Tia tasks) so the {@link Option @Option}-annotated
 * {@code --last} CLI flag can be wired in through Gradle's task-options machinery.
 */
public class TiaHistoryTask extends DefaultTask {

    private String last = "20";
    private Supplier<VCSReader> vcsReaderSupplier;
    private Function<String, H2ConnectionSettings> connectionSettingsFactory;

    /**
     * Setter used by Gradle when the user passes {@code --last=N} on the command line.
     *
     * @param last the user-supplied row cap (parsed to int at execution time)
     */
    @Option(option = "last", description = "Number of recent test runs to display (default 20).")
    public void setLast(String last) {
        this.last = last;
    }

    /**
     * @return the current {@code --last} value (default {@code "20"} when the flag is not supplied)
     */
    @Input
    public String getLast() {
        return last;
    }

    /**
     * Inject the VCS reader factory; called from {@code TiaBasePlugin.createHistoryTask} at
     * task registration so the reader is resolved lazily at execution time.
     *
     * @param vcsReaderSupplier supplier of the active {@link VCSReader}
     */
    public void setVcsReaderSupplier(Supplier<VCSReader> vcsReaderSupplier) {
        this.vcsReaderSupplier = vcsReaderSupplier;
    }

    /**
     * Inject the connection-settings factory; called from {@code TiaBasePlugin.createHistoryTask}
     * at task registration so the settings (which depend on the consumer's {@code tia { ... }}
     * extension and the run's branch) resolve at execution time rather than apply time. The
     * factory takes the branch name and returns embedded- or server-mode connection settings.
     *
     * @param connectionSettingsFactory factory mapping a branch name to {@link H2ConnectionSettings}
     */
    public void setConnectionSettingsFactory(Function<String, H2ConnectionSettings> connectionSettingsFactory) {
        this.connectionSettingsFactory = connectionSettingsFactory;
    }

    /**
     * Read the history table and print the most-recent rows. Parses {@link #last} to an int,
     * failing fast with a {@link GradleException} on non-numeric or non-positive values.
     */
    @TaskAction
    public void run() {
        int limit;
        try {
            limit = Integer.parseInt(last.trim());
        } catch (NumberFormatException e) {
            throw new GradleException("--last must be a positive integer; received '" + last + "'");
        }
        if (limit <= 0) {
            throw new GradleException("--last must be a positive integer; received " + limit);
        }
        VCSReader vcsReader = vcsReaderSupplier.get();
        try (DataStore dataStore = new H2DataStore(connectionSettingsFactory.apply(vcsReader.getBranchName()))) {
            List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
            System.out.println(TestRunHistoryConsoleFormatter.formatHistory(
                    history, limit, System.lineSeparator()));
        }
    }
}
