package org.tiatesting.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.h2.H2DataStore;
import org.tiatesting.report.ReportGenerator;
import org.tiatesting.report.TextFileReportGenerator;

public abstract class AbstractTextReportMojo extends AbstractReportMojo {
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        final VCSReader vcsReader = getVCSReader();
        final DataStore dataStore = new H2DataStore(getTiaDBFilePath(), vcsReader.getBranchName());
        ReportGenerator reportGenerator = new TextFileReportGenerator(vcsReader.getBranchName(), getTiaReportOutputDir());
        reportGenerator.generateReport(dataStore);
    }

}
