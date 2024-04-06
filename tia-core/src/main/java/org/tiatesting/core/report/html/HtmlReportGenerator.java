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
        generateSummaryReport(tiaData);
        generateTestSuiteReport(tiaData);
        generateSourceClassReport(tiaData);
        generateSourceMethodReport(tiaData);
    }

    @Override
    public String generateSummaryReport(TiaData tiaData) {
        // TODO
        return null;
    }

    @Override
    public String generateTestSuiteReport(TiaData tiaData) {
        HtmlTestSuiteReport htmlTestSuiteReport = new HtmlTestSuiteReport(filenameExt, reportOutputDir);
        htmlTestSuiteReport.generateTestSuiteReport(tiaData);
        return null;
    }

    @Override
    public String generateSourceClassReport(TiaData tiaData) {
        HtmlSourceClassReport htmlSourceClassReport = new HtmlSourceClassReport(filenameExt, reportOutputDir);
        htmlSourceClassReport.generateSourceClassReport(tiaData);
        return null;
    }

    @Override
    public String generateSourceMethodReport(TiaData tiaData) {
        HtmlSourceMethodReport htmlSourceMethodReport = new HtmlSourceMethodReport(filenameExt, reportOutputDir);
        htmlSourceMethodReport.generateSourceMethodReport(tiaData);
        return null;
    }
}
