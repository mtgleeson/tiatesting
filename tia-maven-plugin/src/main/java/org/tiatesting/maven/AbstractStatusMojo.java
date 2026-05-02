package org.tiatesting.maven;

import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.report.StatusReportGenerator;
import org.tiatesting.core.vcs.VCSReader;

public abstract class AbstractStatusMojo extends AbstractTiaMojo {
    @Override
    public void execute() {
        final VCSReader vcsReader = getVCSReader();
        final DataStore dataStore = new H2DataStore(getTiaDBFilePath(), vcsReader.getBranchName());
        StatusReportGenerator reportGenerator = new StatusReportGenerator();
        getLog().info(reportGenerator.generateSummaryReport(dataStore));
    }
}
