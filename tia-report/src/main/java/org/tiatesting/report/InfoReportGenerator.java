package org.tiatesting.report;

import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.StoredMapping;

import java.text.DecimalFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Generate core information about the stored DB and output it to the user.
 * This is intended as a quick snapshot as to the state of the stored DB for Tia.
 */
public class InfoReportGenerator implements ReportGenerator {
    @Override
    public String generateReport(DataStore dataStore) {
        StoredMapping storedMapping = dataStore.getStoredMapping(true);

        Locale locale = Locale.getDefault();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss zzz", locale).withZone(ZoneId.systemDefault());

        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator());
        sb.append("Tia Info:" + System.lineSeparator());
        sb.append("DB last updated: " + (storedMapping.getLastUpdated()!= null ? dtf.format(storedMapping.getLastUpdated()) : "N/A") + System.lineSeparator());
        sb.append("Test mapping valid for commit number: " + storedMapping.getCommitValue() + System.lineSeparator());
        sb.append("Number of tests classes with mappings: " + storedMapping.getTestSuitesTracked().keySet().size()
                + System.lineSeparator());
        sb.append("Number of source methods tracked for tests: " + storedMapping.getMethodsTracked().keySet().size()
                + System.lineSeparator());
        sb.append("Number of runs: " + storedMapping.getNumRuns() + System.lineSeparator());
        double avgRunTime = storedMapping.getNumRuns() > 0 ? (storedMapping.getTotalRunTime() / storedMapping.getNumRuns()) : 0;
        sb.append("Average run time (sec): " + (new DecimalFormat("#,###").format(avgRunTime) + System.lineSeparator()));
        sb.append("Pending failed tests: " + System.lineSeparator() + storedMapping.getTestSuitesFailed().stream().map(test ->
                "\t" + test).collect(Collectors.joining(System.lineSeparator())));

        return sb.toString();
    }
}
