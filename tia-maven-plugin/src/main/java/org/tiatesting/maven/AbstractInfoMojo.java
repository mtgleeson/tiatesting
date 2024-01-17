package org.tiatesting.maven;

import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.MapDataStore;
import org.tiatesting.report.InfoReportGenerator;
import org.tiatesting.report.ReportGenerator;

public abstract class AbstractInfoMojo extends AbstractTiaMojo {
    @Override
    public void execute() {
        final VCSReader vcsReader = getVCSReader();
        final DataStore dataStore = new MapDataStore(getTiaDBFilePath(), vcsReader.getBranchName());
        ReportGenerator reportGenerator = new InfoReportGenerator();
        reportGenerator.generateReport(dataStore);
    }

    public abstract VCSReader getVCSReader();
}
