package org.tiatesting.plugin.report;

import org.tiatesting.plugin.persistence.DataStore;
import org.tiatesting.plugin.persistence.StoredMapping;
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
public class TextFileReportGenerator implements ReportGenerator {

    private static final Log log = LogFactory.getLog(TextFileReportGenerator.class);

    @Override
    public void generateReport(DataStore dataStore) {
        long startTime = System.currentTimeMillis();
        StoredMapping storedMapping = dataStore.getTestMapping();

        if (storedMapping == null){
            storedMapping = dataStore.getTestMapping();
        }

        try(Writer writer = Files.newBufferedWriter(Paths.get("tia-test-mapping.txt"))) {
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss");
            LocalDateTime localDate = LocalDateTime.now();
            writer.write("Test Mapping Report generated at " + dtf.format(localDate) + System.lineSeparator());
            writer.write("Test mapping valid for commit number: " + storedMapping.getCommitValue() + System.lineSeparator());
            writer.write("Number of tests classes with mappings: " + storedMapping.getTestMethodsCalled().keySet().size());

            storedMapping.getTestMethodsCalled().forEach((testClass, methodsCalled) -> {
                try {
                    String fileTestEntry = methodsCalled.stream().map(String::valueOf).collect(
                            Collectors.joining(System.lineSeparator() + "\t", System.lineSeparator() +  System.lineSeparator() + testClass + System.lineSeparator() + "\t", ""));
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
