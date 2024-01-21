package org.tiatesting.maven;

import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

public abstract class AbstractReportMojo extends AbstractTiaMojo {
    /**
     * Output directory for the generated reports.
     */
    @Parameter(property = "tiaReportOutputDir", defaultValue = "${project.reporting.outputDirectory}/tia")
    private File tiaReportOutputDir;

    public File getTiaReportOutputDir() {
        return tiaReportOutputDir;
    }
}
