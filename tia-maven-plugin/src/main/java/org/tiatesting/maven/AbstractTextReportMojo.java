package org.tiatesting.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.report.ReportGenerator;
import org.tiatesting.core.report.plaintext.TextReportGenerator;
import org.tiatesting.core.vcs.WorkspaceIdentity;

public abstract class AbstractTextReportMojo extends AbstractReportMojo {
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try (WorkspaceIdentity workspaceIdentity = workspaceIdentity();
             DataStore dataStore = buildDataStore(workspaceIdentity.getBranch())) {
            TiaData tiaData = dataStore.getTiaData(true);
            ReportGenerator reportGenerator = new TextReportGenerator(workspaceIdentity.getBranch(), getTiaReportOutputDir());
            reportGenerator.generateReports(tiaData);
        }
    }

}
