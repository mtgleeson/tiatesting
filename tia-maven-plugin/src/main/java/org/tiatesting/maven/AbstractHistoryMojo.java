package org.tiatesting.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.report.TestRunHistoryConsoleFormatter;
import org.tiatesting.core.vcs.VCSReader;

import java.util.List;

/**
 * Mojo used to print the most recent rows from the {@code tia_test_run_history} table to stdout.
 * Mirrors {@link AbstractSelectTestsMojo} - concrete subclasses live in each {@code *-maven-plugin}
 * module and only need to supply a {@link VCSReader} via {@link #getVCSReader()}.
 *
 * <p>Invoked as {@code mvn <plugin>:history}. The number of rows printed is configurable via
 * {@code -DtiaHistoryLast=N} (default {@code 20}).
 */
public abstract class AbstractHistoryMojo extends AbstractTiaMojo {

    /**
     * Maximum number of recent test runs to display. Defaults to {@code 20}; users widen or
     * narrow via {@code mvn <plugin>:history -DtiaHistoryLast=N}.
     */
    @Parameter(property = "tiaHistoryLast", defaultValue = "20")
    private int tiaHistoryLast;

    /**
     * @return the configured row cap (default 20 when unset)
     */
    public int getTiaHistoryLast() {
        return tiaHistoryLast;
    }

    @Override
    public void execute() throws MojoExecutionException {
        if (tiaHistoryLast <= 0) {
            throw new MojoExecutionException(
                    "tiaHistoryLast must be a positive integer; received " + tiaHistoryLast);
        }
        final VCSReader vcsReader = getVCSReader();
        try (DataStore dataStore = new H2DataStore(buildH2ConnectionSettings(vcsReader.getBranchName()))) {
            List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
            System.out.println(TestRunHistoryConsoleFormatter.formatHistory(
                    history, tiaHistoryLast, System.lineSeparator()));
        }
    }
}
