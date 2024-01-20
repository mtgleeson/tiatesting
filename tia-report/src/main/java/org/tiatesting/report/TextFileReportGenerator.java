package org.tiatesting.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.coverage.ClassImpactTracker;
import org.tiatesting.core.coverage.MethodImpactTracker;
import org.tiatesting.core.stats.TestStats;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.StoredMapping;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Generate a text file report containing the test mappings.
 */
public class TextFileReportGenerator implements ReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(TextFileReportGenerator.class);
    private final String filenameExt;

    public TextFileReportGenerator(String filenameExt){
        this.filenameExt = filenameExt;
    }

    @Override
    public String generateReport(DataStore dataStore) {
        long startTime = System.currentTimeMillis();
        StoredMapping storedMapping = dataStore.getStoredMapping(true);

        try (Writer writer = Files.newBufferedWriter(Paths.get("tia-test-mapping-" + filenameExt + ".txt"))) {
            writeCoreTiaReportData(writer, storedMapping);
            writeFailedTests(writer, storedMapping);
            writeMethodIndex(writer, storedMapping);
            writeTestSuiteMapping(writer, storedMapping);
        }catch (UncheckedIOException | IOException ex) {
            log.error("An error occurred generating the text report", ex);
        }

        log.debug("Time to write the text report (ms): " + (System.currentTimeMillis() - startTime));
        return null;
    }

    private void writeTestSuiteMapping(Writer writer, StoredMapping storedMapping) throws IOException {
        String lineSep = System.lineSeparator();
        Map<Integer, MethodImpactTracker> methodImpactTrackers = storedMapping.getMethodsTracked();
        writer.write(lineSep + lineSep + "Test class mapping:");

        storedMapping.getTestSuitesTracked().forEach((testClass, testSuiteTracker) -> {
            try {
                String fileTestEntry = lineSep + lineSep + testClass;
                for (ClassImpactTracker classImpacted : testSuiteTracker.getClassesImpacted()){
                    for (Integer methodId : classImpacted.getMethodsImpacted()){
                        MethodImpactTracker methodImpactTracker = methodImpactTrackers.get(methodId);
                        fileTestEntry += lineSep + "\t" + methodImpactTracker.getMethodName() +
                                " " + methodImpactTracker.getLineNumberStart() + " -> " + methodImpactTracker.getLineNumberEnd();
                    }
                }
                writer.write(fileTestEntry);
            }
            catch (IOException ex) {
                log.error("An error occurred", ex);
            }
        });
    }

    private void writeMethodIndex(Writer writer, StoredMapping storedMapping) throws IOException {
        String lineSep = System.lineSeparator();
        Map<Integer, MethodImpactTracker> methodImpactTrackers = storedMapping.getMethodsTracked();
        writer.write(lineSep + lineSep + "Methods index:");
        methodImpactTrackers.forEach((methodId, methodImpactTracker) -> {
            try {
                writer.write((lineSep + "\t" + methodId + ": " + methodImpactTracker.getMethodName() +
                        " " + methodImpactTracker.getLineNumberStart() + " -> " + methodImpactTracker.getLineNumberEnd()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void writeFailedTests(Writer writer, StoredMapping storedMapping) throws IOException {
        String lineSep = System.lineSeparator();
        writer.write("Failed tests:");
        if (storedMapping.getTestSuitesFailed().size() == 0){
            writer.write(" none");
        } else {
            for (String failedTestClass: storedMapping.getTestSuitesFailed()){
                writer.write(lineSep + "\t" + failedTestClass);
            }
        }
    }

    private void writeCoreTiaReportData(Writer writer, StoredMapping storedMapping) throws IOException {
        Locale locale = Locale.getDefault();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss zzz", locale).withZone(ZoneId.systemDefault());
        LocalDateTime localDate = LocalDateTime.now();

        String lineSep = System.lineSeparator();
        writer.write("Test Mapping Report generated at " + dtf.format(localDate) + lineSep);
        String lastCommit = storedMapping.getCommitValue() != null ? storedMapping.getCommitValue() : "N/A";
        writer.write("Test mapping valid for commit number: " + lastCommit + lineSep);
        writer.write("Number of tests classes with mappings: " + storedMapping.getTestSuitesTracked().keySet().size() + lineSep);
        String dbLastUpdated = storedMapping.getLastUpdated()!= null ? dtf.format(storedMapping.getLastUpdated()) : "N/A";
        writer.write("Tia DB last updated: " + (dbLastUpdated) + lineSep);

        TestStats stats = storedMapping.getTestStats();
        double percSuccess = ((double)stats.getNumSuccessRuns()) / (double)(stats.getNumRuns()) * 100;
        double percFail = ((double)stats.getNumFailRuns()) / (double)(stats.getNumRuns()) * 100;
        DecimalFormat avgFormat = new DecimalFormat("###.#");

        writer.write("Number of runs: " + stats.getNumRuns() + lineSep);
        writer.write("Average run time: " + ReportUtils.prettyDuration(stats.getAvgRunTime()) + lineSep);
        writer.write("Number of successful runs: " + stats.getNumSuccessRuns() + " (" + avgFormat.format(percSuccess) + "%)"  + lineSep);
        writer.write("Number of failed runs: " + stats.getNumFailRuns() + " (" + avgFormat.format(percFail) + "%)" + lineSep + lineSep);
    }
}
