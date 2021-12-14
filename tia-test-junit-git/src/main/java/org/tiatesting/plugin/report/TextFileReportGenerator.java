package org.tiatesting.plugin.report;

import org.tiatesting.core.coverage.ClassImpactTracker;
import org.tiatesting.core.coverage.MethodImpactTracker;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.StoredMapping;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/**
 * Generate a text file report containing the test mappings.
 */
public class TextFileReportGenerator implements ReportGenerator{

    private static final Log log = LogFactory.getLog(TextFileReportGenerator.class);

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
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss");
            LocalDateTime localDate = LocalDateTime.now();
            writer.write("Test Mapping Report generated at " + dtf.format(localDate) + System.lineSeparator());
            writer.write("Test mapping valid for commit number: " + storedMapping.getCommitValue() + System.lineSeparator());
            writer.write("Number of tests classes with mappings: " + storedMapping.getClassesImpacted().keySet().size());

            storedMapping.getClassesImpacted().forEach((testClass, classesImpacted) -> {
                try {
                    String fileTestEntry = System.lineSeparator() +  System.lineSeparator() + testClass + System.lineSeparator() + "\t";
                    for (ClassImpactTracker classImpacted : classesImpacted){
                        fileTestEntry += classImpacted.getMethodsImpacted().stream().map(MethodImpactTracker::getMethodName).collect(
                                Collectors.joining(System.lineSeparator() + "\t", "", ""));
                    }
                    writer.write(fileTestEntry);
                }
                catch (IOException ex) {
                    log.error(ex);
                }
            });
        } catch(UncheckedIOException | IOException ex) {
            log.error(ex);
        }

        log.debug("Time to write the text report (ms): " + (System.currentTimeMillis() - startTime));
    }
}
