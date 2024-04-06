package org.tiatesting.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.report.ReportGenerator;
import org.tiatesting.core.report.html.HtmlReportGenerator;
import org.tiatesting.core.report.plaintext.TextReportGenerator;
import org.tiatesting.core.vcs.VCSReader;

public abstract class AbstractHtmlReportMojo extends AbstractReportMojo {
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final VCSReader vcsReader = getVCSReader();
        final DataStore dataStore = new H2DataStore(getTiaDBFilePath(), vcsReader.getBranchName());
        TiaData tiaData = dataStore.getTiaData(true);
        ReportGenerator reportGenerator = new HtmlReportGenerator(vcsReader.getBranchName(), getTiaReportOutputDir());
        reportGenerator.generateReports(tiaData);
    }

}
