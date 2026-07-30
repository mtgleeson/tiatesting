package org.tiatesting.maven;

import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.report.StatusReportGenerator;
import org.tiatesting.core.vcs.VCSReader;

public abstract class AbstractStatusMojo extends AbstractTiaMojo {
    @Override
    public void execute() {
        final VCSReader vcsReader = getVCSReader();
        try (DataStore dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(buildH2ConnectionSettings(vcsReader.getBranchName())))) {
            StatusReportGenerator reportGenerator = new StatusReportGenerator();
            getLog().info(reportGenerator.generateSummaryReport(dataStore));
        }
    }
}
