package org.tiatesting.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.report.StatusReportGenerator;
import org.tiatesting.core.vcs.WorkspaceIdentity;

@Mojo(name = "status", defaultPhase = LifecyclePhase.NONE)
public class StatusMojo extends AbstractTiaMojo {
    @Override
    public void execute() throws MojoExecutionException {
        try (WorkspaceIdentity workspaceIdentity = workspaceIdentity();
             DataStore dataStore = buildDataStore(workspaceIdentity.getBranch())) {
            StatusReportGenerator reportGenerator = new StatusReportGenerator();
            getLog().info(reportGenerator.generateSummaryReport(dataStore));
        }
    }
}
