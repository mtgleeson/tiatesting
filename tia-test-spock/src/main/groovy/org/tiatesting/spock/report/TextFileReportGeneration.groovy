package org.tiatesting.spock.report

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.tiatesting.core.coverage.ClassImpactTracker
import org.tiatesting.persistence.DataStore
import org.tiatesting.persistence.StoredMapping

import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Generate a text file report containing the test mappings.
 */
class TextFileReportGenerator implements ReportGenerator{

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
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss");
            LocalDateTime localDate = LocalDateTime.now();
            writer.write("Test Mapping Report generated at " + dtf.format(localDate) + System.lineSeparator());
            writer.write("Test mapping valid for commit number: " + storedMapping.getCommitValue() + System.lineSeparator());
            writer.write("Number of tests classes with mappings: " + storedMapping.getClassesImpacted().keySet().size());

            storedMapping.getClassesImpacted().forEach((testClass, classesImpacted) -> {
                try {
                    String fileTestEntry = System.lineSeparator() +  System.lineSeparator() + testClass + System.lineSeparator() + "\t";
                    for (ClassImpactTracker classImpacted : classesImpacted){
                        fileTestEntry = classImpacted.getMethodsImpacted()
                                .collect {it.getMethodName() + System.lineSeparator() + "\t" }
                                .inject(fileTestEntry) { result, i -> result += i }
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
