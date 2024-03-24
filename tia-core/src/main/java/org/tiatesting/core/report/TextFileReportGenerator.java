package org.tiatesting.core.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.model.TiaData;

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
public class TextFileReportGenerator implements ReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(TextFileReportGenerator.class);
    private final String filenameExt;
    private final File reportOutputDir;

    public TextFileReportGenerator(String filenameExt, File reportOutputDir){
        this.filenameExt = filenameExt;
        this.reportOutputDir = reportOutputDir;
    }

    @Override
    public String generateReport(DataStore dataStore) {
        createOutputDir();

        long startTime = System.currentTimeMillis();
        TiaData tiaData = dataStore.getTiaData(true);
        log.info("Data retrieved. Writing the report...");

        StringBuilder reportBuilder = new StringBuilder();
        try {
            writeCoreTiaReportData(reportBuilder, tiaData);
            writeFailedTests(reportBuilder, tiaData);
            writeMethodIndex(reportBuilder, tiaData);
            writeTestSuiteMapping(reportBuilder, tiaData);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String file = reportOutputDir + File.separator + "tia-test-mapping-" + filenameExt + ".txt";
        try (RandomAccessFile writer = new RandomAccessFile(file, "rw");
             FileChannel channel = writer.getChannel()){
            writer.setLength(0); // remove contents if the file already exists
            ByteBuffer buff = ByteBuffer.wrap(reportBuilder.toString().getBytes(StandardCharsets.UTF_8));
            channel.write(buff);
        } catch (IOException  e) {
            throw new RuntimeException(e);
        }

        log.info("Time to write the text report (ms): " + (System.currentTimeMillis() - startTime));
        return null;
    }

    private void createOutputDir() {
        reportOutputDir.mkdirs();
    }

    private void writeTestSuiteMapping(StringBuilder reportBuilder, TiaData tiaData) throws IOException {
        String lineSep = System.lineSeparator();
        Map<Integer, MethodImpactTracker> methodImpactTrackers = tiaData.getMethodsTracked();
        reportBuilder.append(lineSep + lineSep + "Test class mapping:");
        Map<String, StringBuilder> stringBuilders = new TreeMap<>();

        tiaData.getTestSuitesTracked().entrySet().parallelStream().forEach(entry -> {
            String testSuite = entry.getKey();
            TestSuiteTracker testSuiteTracker = entry.getValue();
            StringBuilder builder = new StringBuilder();
            String fileTestEntry = lineSep + lineSep + testSuite;
            Collections.sort(testSuiteTracker.getClassesImpacted(), Comparator.comparing(ClassImpactTracker::getSourceFilename));

            for (ClassImpactTracker classImpacted : testSuiteTracker.getClassesImpacted()){
                for (Integer methodId : classImpacted.getMethodsImpacted()){
                    MethodImpactTracker methodImpactTracker = methodImpactTrackers.get(methodId);
                    fileTestEntry += lineSep + "\t" + methodImpactTracker.getMethodName() +
                            " " + methodImpactTracker.getLineNumberStart() + " -> " + methodImpactTracker.getLineNumberEnd();
                }
            }

            builder.append(fileTestEntry);
            stringBuilders.put(testSuite, builder);
        });

        stringBuilders.values().forEach(builder  -> reportBuilder.append(builder.toString()));
    }

    private void writeMethodIndex(StringBuilder reportBuilder, TiaData tiaData) throws IOException {
        String lineSep = System.lineSeparator();
        Map<Integer, MethodImpactTracker> methodImpactTrackers = tiaData.getMethodsTracked();
        reportBuilder.append(lineSep + lineSep + "Methods index:");
        methodImpactTrackers.forEach((methodId, methodImpactTracker) -> {
            reportBuilder.append((lineSep + "\t" + methodId + ": " + methodImpactTracker.getMethodName() +
                    " " + methodImpactTracker.getLineNumberStart() + " -> " + methodImpactTracker.getLineNumberEnd()));
        });
    }

    private void writeFailedTests(StringBuilder reportBuilder, TiaData tiaData) throws IOException {
        String lineSep = System.lineSeparator();
        reportBuilder.append("Failed tests:");
        if (tiaData.getTestSuitesFailed().size() == 0){
            reportBuilder.append(" none");
        } else {
            for (String failedTestClass: tiaData.getTestSuitesFailed()){
                reportBuilder.append(lineSep + "\t" + failedTestClass);
            }
        }
    }

    private void writeCoreTiaReportData(StringBuilder reportBuilder, TiaData tiaData) throws IOException {
        Locale locale = Locale.getDefault();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss zzz", locale).withZone(ZoneId.systemDefault());
        LocalDateTime localDate = LocalDateTime.now();

        String lineSep = System.lineSeparator();
        reportBuilder.append("Test Mapping Report generated at " + dtf.format(localDate) + lineSep);
        String lastCommit = tiaData.getCommitValue() != null ? tiaData.getCommitValue() : "N/A";
        reportBuilder.append("Test mapping valid for commit number: " + lastCommit + lineSep);
        reportBuilder.append("Number of tests classes with mappings: " + tiaData.getTestSuitesTracked().keySet().size() + lineSep);
        String dbLastUpdated = tiaData.getLastUpdated()!= null ? dtf.format(tiaData.getLastUpdated()) : "N/A";
        reportBuilder.append("Tia DB last updated: " + (dbLastUpdated) + lineSep);

        TestStats stats = tiaData.getTestStats();
        double percSuccess = ((double)stats.getNumSuccessRuns()) / (double)(stats.getNumRuns()) * 100;
        double percFail = ((double)stats.getNumFailRuns()) / (double)(stats.getNumRuns()) * 100;
        DecimalFormat avgFormat = new DecimalFormat("###.#");

        reportBuilder.append("Number of runs: " + stats.getNumRuns() + lineSep);
        reportBuilder.append("Average run time: " + ReportUtils.prettyDuration(stats.getAvgRunTime()) + lineSep);
        reportBuilder.append("Number of successful runs: " + stats.getNumSuccessRuns() + " (" + avgFormat.format(percSuccess) + "%)"  + lineSep);
        reportBuilder.append("Number of failed runs: " + stats.getNumFailRuns() + " (" + avgFormat.format(percFail) + "%)" + lineSep + lineSep);
    }
}
