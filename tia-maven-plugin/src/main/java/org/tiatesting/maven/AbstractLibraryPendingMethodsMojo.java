package org.tiatesting.maven;

import org.apache.maven.plugins.annotations.Parameter;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.report.LibraryPendingMethodsReportGenerator;
import org.tiatesting.core.vcs.VCSReader;

/**
 * Mojo that prints a tracked library's pending impacted methods as a table - one row per pending
 * method with the publish it shipped in (sequence + version) and the method's tracked name and
 * line range. The library is selected with the {@code tiaLibrary} parameter as
 * {@code groupId:artifactId} (e.g.
 * {@code mvn <plugin>:library-pending-methods -DtiaLibrary=com.example:lib}). Concrete
 * subclasses live in each {@code *-maven-plugin} module and only need to supply a
 * {@link VCSReader} via {@link #getVCSReader()}.
 */
public abstract class AbstractLibraryPendingMethodsMojo extends AbstractTiaMojo {

    /** The {@code groupId:artifactId} of the tracked library to report on. */
    @Parameter(property = "tiaLibrary")
    String tiaLibrary;

    /**
     * Read the library's pending stamps and tracked method details from the Tia DB and print
     * the formatted table.
     */
    @Override
    public void execute() {
        final VCSReader vcsReader = getVCSReader();
        try (DataStore dataStore = new H2DataStore(buildH2ConnectionSettings(vcsReader.getBranchName()))) {
            LibraryPendingMethodsReportGenerator reportGenerator = new LibraryPendingMethodsReportGenerator();
            getLog().info(reportGenerator.generateLibraryPendingMethodsReport(dataStore, tiaLibrary));
        }
    }
}
