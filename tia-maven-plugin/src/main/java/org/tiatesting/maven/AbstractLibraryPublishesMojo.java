package org.tiatesting.maven;

import org.apache.maven.plugins.annotations.Parameter;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.report.LibraryPublishesReportGenerator;
import org.tiatesting.core.vcs.VCSReader;

/**
 * Mojo that prints a tracked library's publish ledger as a table - one row per published build
 * with its sequence, version, jar hash, commit, publish time and pending method count. The
 * library is selected with the {@code tiaLibrary} parameter as {@code groupId:artifactId}
 * (e.g. {@code mvn <plugin>:library-publishes -DtiaLibrary=com.example:lib}). Concrete
 * subclasses live in each {@code *-maven-plugin} module and only need to supply a
 * {@link VCSReader} via {@link #getVCSReader()}.
 */
public abstract class AbstractLibraryPublishesMojo extends AbstractTiaMojo {

    /** The {@code groupId:artifactId} of the tracked library to report on. */
    @Parameter(property = "tiaLibrary")
    String tiaLibrary;

    /**
     * Read the library's publish ledger from the Tia DB and print the formatted table.
     */
    @Override
    public void execute() {
        final VCSReader vcsReader = getVCSReader();
        try (DataStore dataStore = new H2DataStore(buildH2ConnectionSettings(vcsReader.getBranchName()))) {
            LibraryPublishesReportGenerator reportGenerator = new LibraryPublishesReportGenerator();
            getLog().info(reportGenerator.generateLibraryPublishesReport(dataStore, tiaLibrary));
        }
    }
}
