package org.tiatesting.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.report.ReportGenerator;
import org.tiatesting.core.report.plaintext.TextReportGenerator;
import org.tiatesting.core.vcs.VCSReader;

public abstract class AbstractTextReportMojo extends AbstractReportMojo {
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final VCSReader vcsReader = getVCSReader();
        try (DataStore dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(buildH2ConnectionSettings(vcsReader.getBranchName())))) {
            TiaData tiaData = dataStore.getTiaData(true);
            ReportGenerator reportGenerator = new TextReportGenerator(vcsReader.getBranchName(), getTiaReportOutputDir());
            reportGenerator.generateReports(tiaData);
        }
    }

}
