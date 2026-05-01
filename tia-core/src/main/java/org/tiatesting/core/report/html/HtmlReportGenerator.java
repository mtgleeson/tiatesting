package org.tiatesting.core.report.html;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.report.ReportGenerator;

import java.io.File;

public class HtmlReportGenerator implements ReportGenerator {
    private static final Logger log = LoggerFactory.getLogger(HtmlReportGenerator.class);
    private final String filenameExt;
    private final File reportOutputDir;

    public HtmlReportGenerator(String filenameExt, File reportOutputDir){
        this.filenameExt = filenameExt;
        this.reportOutputDir = reportOutputDir;
    }

    @Override
    public void generateReports(TiaData tiaData) {
        copyStaticAssets();
        generateSummaryReport(tiaData);
        generateTestSuiteReport(tiaData);
        generateSourceMethodReport(tiaData);
        generateSourceCodeLandingReport(tiaData);
        generateLibraryReport(tiaData);
    }

    @Override
    public String generateSummaryReport(TiaData tiaData) {
        new HtmlSummaryReport(filenameExt, reportOutputDir).generateSummaryReport(tiaData);
        return null;
    }

    @Override
    public String generateTestSuiteReport(TiaData tiaData) {
        new HtmlTestSuiteReport(filenameExt, reportOutputDir).generateTestSuiteReport(tiaData);
        return null;
    }

    @Override
    public String generateSourceMethodReport(TiaData tiaData) {
        new HtmlSourceMethodReport(filenameExt, reportOutputDir).generateSourceMethodReport(tiaData);
        return null;
    }

    private void generateSourceCodeLandingReport(TiaData tiaData) {
        new HtmlSourceCodeLandingReport(filenameExt, reportOutputDir).generateReport(tiaData);
    }

    private void generateLibraryReport(TiaData tiaData) {
        new HtmlLibraryReport(filenameExt, reportOutputDir).generateReport(tiaData);
    }

    /**
     * Extract bundled CSS / JS / images from the {@code tia-core} JAR into
     * {@code <reportOutputDir>/html/<branch>/assets/} so every page can reference them
     * via relative URLs.
     */
    private void copyStaticAssets() {
        File branchDir = new File(reportOutputDir.getAbsoluteFile()
                + File.separator + "html" + File.separator + filenameExt);
        if (!branchDir.exists() && !branchDir.mkdirs()) {
            log.warn("Failed to create per-branch report dir, asset copy may fail: {}",
                    branchDir.getAbsolutePath());
        }
        HtmlAssetCopier.copyAssetsTo(branchDir);
    }
}
