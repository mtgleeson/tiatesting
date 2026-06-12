package org.tiatesting.maven;

import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.report.LibrariesReportGenerator;
import org.tiatesting.core.vcs.VCSReader;

/**
 * Mojo used to print the tracked libraries and their state (project dir, source dirs,
 * versions, pending impacted-method batches) to the console. Mirrors
 * {@link AbstractStatusMojo} - concrete subclasses live in each {@code *-maven-plugin}
 * module and only need to supply a {@link VCSReader} via {@link #getVCSReader()}.
 *
 * <p>Invoked as {@code mvn <plugin>:libraries}. Covers the same details as the
 * {@code tia-libraries.html} report page; the status mojo intentionally no longer includes
 * library information.
 */
public abstract class AbstractLibrariesMojo extends AbstractTiaMojo {

    /**
     * Read the tracked libraries and their pending batches from the Tia DB and print the
     * formatted listing.
     */
    @Override
    public void execute() {
        final VCSReader vcsReader = getVCSReader();
        try (DataStore dataStore = new H2DataStore(buildH2ConnectionSettings(vcsReader.getBranchName()))) {
            LibrariesReportGenerator reportGenerator = new LibrariesReportGenerator();
            getLog().info(reportGenerator.generateLibrariesReport(dataStore));
        }
    }
}
