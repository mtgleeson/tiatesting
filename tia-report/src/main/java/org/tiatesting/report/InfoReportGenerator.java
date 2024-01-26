package org.tiatesting.report;

import org.tiatesting.core.stats.TestStats;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.core.model.StoredMapping;

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
        String lineSep = System.lineSeparator();

        StringBuilder sb = new StringBuilder(lineSep);
        sb.append("Tia Info:" + lineSep);
        sb.append("DB last updated: " + (storedMapping.getLastUpdated()!= null ? dtf.format(storedMapping.getLastUpdated()) : "N/A") + lineSep);
        sb.append("Test mapping valid for commit number: " + storedMapping.getCommitValue() + lineSep + lineSep);

        sb.append("Number of tests classes with mappings: " + storedMapping.getTestSuitesTracked().keySet().size() + lineSep);
        sb.append("Number of source methods tracked for tests: " + storedMapping.getMethodsTracked().keySet().size() + lineSep);

        TestStats stats = storedMapping.getTestStats();
        double percSuccess = ((double)stats.getNumSuccessRuns()) / (double)(stats.getNumRuns()) * 100;
        double percFail = ((double)stats.getNumFailRuns()) / (double)(stats.getNumRuns()) * 100;
        DecimalFormat avgFormat = new DecimalFormat("###.#");

        sb.append("Number of runs: " + stats.getNumRuns() + lineSep);
        sb.append("Average run time: " + ReportUtils.prettyDuration(stats.getAvgRunTime()) + lineSep);
        sb.append("Number of successful runs: " + stats.getNumSuccessRuns() + " (" + avgFormat.format(percSuccess) + "%)" + lineSep);
        sb.append("Number of failed runs: " + stats.getNumFailRuns() + " (" + avgFormat.format(percFail) + "%)" + lineSep + lineSep);

        String failedTests = storedMapping.getTestSuitesFailed().stream().map(test ->
                "\t" + test).collect(Collectors.joining(lineSep));
        failedTests = (failedTests != null && !failedTests.isEmpty()) ? failedTests : "none";
        sb.append("Pending failed tests: " + failedTests);

        return sb.toString();
    }
}
