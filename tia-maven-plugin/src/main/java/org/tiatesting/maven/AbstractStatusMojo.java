package org.tiatesting.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.report.StatusReportGenerator;
import org.tiatesting.core.vcs.WorkspaceIdentity;

public abstract class AbstractStatusMojo extends AbstractTiaMojo {
    @Override
    public void execute() throws MojoExecutionException {
        try (WorkspaceIdentity workspaceIdentity = workspaceIdentity();
             DataStore dataStore = buildDataStore(workspaceIdentity.getBranch())) {
            StatusReportGenerator reportGenerator = new StatusReportGenerator();
            getLog().info(reportGenerator.generateSummaryReport(dataStore));
        }
    }
}
