package org.tiatesting.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.report.TestRunHistoryDetailConsoleFormatter;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.vcs.WorkspaceIdentity;

import java.util.List;

/**
 * Mojo used to print one recorded test run's full selection breakdown to stdout. Mirrors
 * {@link AbstractHistoryMojo} - concrete subclasses live in each {@code *-maven-plugin} module and
 * only need to supply a {@link VCSReader} via {@link #getVCSReader()}.
 *
 * <p>Invoked as {@code mvn <plugin>:history-details -DtiaHistoryId=<id>}. The id is looked up
 * against {@link DataStore#readTestRunHistory()}; a match is rendered via
 * {@link TestRunHistoryDetailConsoleFormatter#format(TestRunHistoryEntry, List, String)}, and a
 * miss via {@link TestRunHistoryDetailConsoleFormatter#notFound(String, String)}.
 */
public abstract class AbstractHistoryDetailsMojo extends AbstractTiaMojo {

    /**
     * The id of the test run to print the detail for. Required - supplied via
     * {@code mvn <plugin>:history-details -DtiaHistoryId=<id>}, typically copied from the id
     * column of {@code mvn <plugin>:history}'s output.
     */
    @Parameter(property = "tiaHistoryId")
    private String tiaHistoryId;

    /**
     * @return the configured test run id to look up, or null/blank when not supplied
     */
    public String getTiaHistoryId() {
        return tiaHistoryId;
    }

    /**
     * Looks up {@link #tiaHistoryId} in the run history and prints its full selection breakdown,
     * or a not-found message when no row matches. Fails fast with a {@link MojoExecutionException}
     * when {@code tiaHistoryId} was not supplied, since there is nothing to look up.
     *
     * @throws MojoExecutionException when {@code tiaHistoryId} is null or blank
     */
    @Override
    public void execute() throws MojoExecutionException {
        if (tiaHistoryId == null || tiaHistoryId.trim().isEmpty()) {
            throw new MojoExecutionException(
                    "tiaHistoryId is required, e.g. mvn <plugin>:history-details -DtiaHistoryId=<id>");
        }

        try (WorkspaceIdentity workspaceIdentity = workspaceIdentity();
             DataStore dataStore = buildDataStore(workspaceIdentity.getBranch())) {
            String id = tiaHistoryId.trim();
            TestRunHistoryEntry match = null;
            for (TestRunHistoryEntry entry : dataStore.readTestRunHistory()) {
                if (id.equals(entry.getId())) {
                    match = entry;
                    break;
                }
            }

            if (match != null) {
                System.out.println(TestRunHistoryDetailConsoleFormatter.format(
                        match, dataStore.readTestRunTriggers(match.getId()), System.lineSeparator()));
            } else {
                System.out.println(TestRunHistoryDetailConsoleFormatter.notFound(id, System.lineSeparator()));
            }
        }
    }
}
