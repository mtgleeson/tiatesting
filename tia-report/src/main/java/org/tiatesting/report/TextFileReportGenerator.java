package org.tiatesting.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.coverage.ClassImpactTracker;
import org.tiatesting.core.coverage.MethodImpactTracker;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.StoredMapping;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Generate a text file report containing the test mappings.
 */
public class TextFileReportGenerator implements ReportGenerator{

    private static final Logger log = LoggerFactory.getLogger(TextFileReportGenerator.class);
    private final String filenameExt;

    public TextFileReportGenerator(String filenameExt){
        this.filenameExt = filenameExt;
    }

    @Override
    public void generateReport(DataStore dataStore) {
        long startTime = System.currentTimeMillis();
        StoredMapping storedMapping = dataStore.getStoredMapping();

        if (storedMapping == null){
            storedMapping = dataStore.getStoredMapping();
        }

        try(Writer writer = Files.newBufferedWriter(Paths.get("tia-test-mapping-" + filenameExt + ".txt"))) {
            Locale locale = Locale.getDefault();
            NumberFormat nf = NumberFormat.getPercentInstance(locale);
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss zzz", locale).withZone(ZoneId.systemDefault());
            LocalDateTime localDate = LocalDateTime.now();
            writer.write("Test Mapping Report generated at " + dtf.format(localDate) + System.lineSeparator());
            writer.write("Test mapping valid for commit number: " + storedMapping.getCommitValue() + System.lineSeparator());
            writer.write("Number of tests classes with mappings: " + storedMapping.getTestSuitesTracked().keySet().size()
                    + System.lineSeparator());
            writer.write("Tia DB last updated: " + dtf.format(storedMapping.getLastUpdated()) + System.lineSeparator());
            writer.write("Number of runs: " + storedMapping.getNumRuns() + System.lineSeparator());
            writer.write("Average run time (sec): " + (new DecimalFormat("#,###").format((storedMapping.getTotalRunTime() / storedMapping.getNumRuns()))
                    + System.lineSeparator() + System.lineSeparator()));

            writer.write("Failed tests:");
            if (storedMapping.getTestSuitesFailed().size() == 0){
                writer.write(" none");
            } else {
                for (String failedTestClass: storedMapping.getTestSuitesFailed()){
                    writer.write(System.lineSeparator() + "\t" + failedTestClass);
                }
            }

            Map<Integer, MethodImpactTracker> methodImpactTrackers = storedMapping.getMethodsTracked();
            writer.write(System.lineSeparator() + System.lineSeparator() + "Methods index:");
            methodImpactTrackers.forEach((methodId, methodImpactTracker) -> {
                try {
                    writer.write((System.lineSeparator() + "\t" + methodId + ": " + methodImpactTracker.getMethodName() +
                            " " + methodImpactTracker.getLineNumberStart() + " -> " + methodImpactTracker.getLineNumberEnd()));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            writer.write(System.lineSeparator() + System.lineSeparator() + "Test class mapping:");

            storedMapping.getTestSuitesTracked().forEach((testClass, testSuiteTracker) -> {
                try {
                    String fileTestEntry = System.lineSeparator() +  System.lineSeparator() + testClass;
                    for (ClassImpactTracker classImpacted : testSuiteTracker.getClassesImpacted()){
                        for (Integer methodId : classImpacted.getMethodsImpacted()){
                            MethodImpactTracker methodImpactTracker = methodImpactTrackers.get(methodId);
                            fileTestEntry += System.lineSeparator() + "\t" + methodImpactTracker.getMethodName() +
                                    " " + methodImpactTracker.getLineNumberStart() + " -> " + methodImpactTracker.getLineNumberEnd();
                        }
                    }
                    writer.write(fileTestEntry);
                }
                catch (IOException ex) {
                    log.error("An error occurred", ex);
                }
            });
        } catch(UncheckedIOException | IOException ex) {
            log.error("An error occurred", ex);
        }

        log.debug("Time to write the text report (ms): " + (System.currentTimeMillis() - startTime));
    }
}
