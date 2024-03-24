package org.tiatesting.maven;

import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.report.InfoReportGenerator;
import org.tiatesting.core.report.ReportGenerator;

public abstract class AbstractInfoMojo extends AbstractTiaMojo {
    @Override
    public void execute() {
        final VCSReader vcsReader = getVCSReader();
        final DataStore dataStore = new H2DataStore(getTiaDBFilePath(), vcsReader.getBranchName());
        ReportGenerator reportGenerator = new InfoReportGenerator();
        getLog().info(reportGenerator.generateReport(dataStore));
    }
}
