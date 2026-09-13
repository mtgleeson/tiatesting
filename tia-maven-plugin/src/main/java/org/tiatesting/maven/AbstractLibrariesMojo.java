package org.tiatesting.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.report.LibrariesReportGenerator;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.vcs.WorkspaceIdentity;

/**
 * Mojo used to print the tracked libraries and their state (project dir, source dirs,
 * versions, pending impacted-method batches) to the console. Mirrors
 * {@link AbstractStatusMojo} - concrete subclasses live in each {@code *-maven-plugin}
 * module and only need to supply a {@link VCSReader} via {@link #getVCSReader()}.
 *
 * <p>Invoked as {@code mvn <plugin>:libraries}. Covers the same details as the
 * {@code tia-libraries.html} report page; the status mojo intentionally no longer includes
 * library information.
 */
public abstract class AbstractLibrariesMojo extends AbstractTiaMojo {

    /**
     * Read the tracked libraries and their pending batches from the Tia DB and print the
     * formatted listing.
     */
    @Override
    public void execute() throws MojoExecutionException {
        try (WorkspaceIdentity workspaceIdentity = workspaceIdentity();
             DataStore dataStore = buildDataStore(workspaceIdentity.getBranch())) {
            LibrariesReportGenerator reportGenerator = new LibrariesReportGenerator();
            getLog().info(reportGenerator.generateLibrariesReport(dataStore));
        }
    }
}
