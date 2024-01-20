package org.tiatesting.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.MapDataStore;
import org.tiatesting.report.ReportGenerator;
import org.tiatesting.report.TextFileReportGenerator;

public abstract class AbstractTextReportMojo extends AbstractTiaMojo {
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        final VCSReader vcsReader = getVCSReader();
        final DataStore dataStore = new MapDataStore(getTiaDBFilePath(), vcsReader.getBranchName());

        ReportGenerator reportGenerator = new TextFileReportGenerator(vcsReader.getBranchName());
        reportGenerator.generateReport(dataStore);
    }

}
