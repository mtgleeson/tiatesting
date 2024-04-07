package org.tiatesting.core.report.plaintext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.report.ReportGenerator;
import org.tiatesting.core.report.ReportUtils;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Generate a text file report containing the test mappings.
 */
public class TextReportGenerator implements ReportGenerator {
    private static final Logger log = LoggerFactory.getLogger(TextReportGenerator.class);
    private final String filenameExt;
    private final File reportOutputDir;

    public TextReportGenerator(String filenameExt, File reportOutputDir){
        this.filenameExt = filenameExt;
        this.reportOutputDir = reportOutputDir;
    }

    @Override
    public void generateReports(TiaData tiaData) {
        generateSummaryReport(tiaData);
        generateTestSuiteReport(tiaData);
        generateSourceMethodReport(tiaData);
    }

    @Override
    public String generateSummaryReport(TiaData tiaData) {
        TextSummaryReport textSummaryReport = new TextSummaryReport(filenameExt, reportOutputDir);
        return textSummaryReport.generateSummaryReport(tiaData);
    }

    @Override
    public String generateTestSuiteReport(TiaData tiaData) {
        // TODO
        return null;
    }

    @Override
    public String generateSourceMethodReport(TiaData tiaData) {
        // TODO
        return null;
    }

}
