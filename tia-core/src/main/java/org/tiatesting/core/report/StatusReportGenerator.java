package org.tiatesting.core.report;

import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.DataStore;

import java.text.DecimalFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Generate core information about the stored DB and output it to the user.
 * This is intended as a quick snapshot as to the state of the stored DB for Tia.
 * Tracked-library details live in their own report - see {@link LibrariesReportGenerator}.
 */
public class StatusReportGenerator {

    /**
     * Build the status snapshot from the data store: mapping identity (last updated, branch,
     * sealed commit), mapping size counts and aggregate run statistics. Library information
     * is intentionally not included - that's the libraries task's job
     * ({@link LibrariesReportGenerator}). Pending failed tests are also not included - they
     * surface in the select-tests output ("Running previously failed tests"), where they are
     * acted on.
     *
     * @param dataStore the Tia data store to read the snapshot from
     * @return the formatted status report text
     */
    public String generateSummaryReport(DataStore dataStore) {
        TiaData tiaData = dataStore.getTiaCore();
        Locale locale = Locale.getDefault();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss zzz", locale).withZone(ZoneId.systemDefault());
        String lineSep = System.lineSeparator();

        StringBuilder sb = new StringBuilder(lineSep);
        sb.append("Tia Status:" + lineSep);
        sb.append("DB last updated: " + (tiaData.getLastUpdated()!= null ? dtf.format(tiaData.getLastUpdated()) : "N/A") + lineSep);
        sb.append("Branch: " + (tiaData.getBranch() != null ? tiaData.getBranch() : "N/A") + lineSep);
        sb.append("Test mapping valid for commit: " + tiaData.getCommitValue() + lineSep + lineSep);

        int numTestSuites = dataStore.getNumTestSuites();
        sb.append("Number of tests classes with mappings: " + numTestSuites + lineSep);
        int numSourceMethods = dataStore.getNumSourceMethods();
        sb.append("Number of source methods tracked for tests: " + numSourceMethods + lineSep);

        TestStats stats = tiaData.getTestStats();
        double percSuccess = ((double)stats.getNumSuccessRuns()) / (double)(stats.getNumRuns()) * 100;
        double percFail = ((double)stats.getNumFailRuns()) / (double)(stats.getNumRuns()) * 100;
        DecimalFormat avgFormat = new DecimalFormat("###.#");

        sb.append("Number of runs: " + stats.getNumRuns() + lineSep);
        sb.append("Average run time: " + ReportUtils.prettyDuration(stats.getAvgRunTime()) + lineSep);
        sb.append("Number of all-tests runs: " + stats.getNumAllTestsRuns() + lineSep);
        sb.append("All tests run time: " + ReportUtils.prettyDuration(stats.getAllTestsRunTime()) + lineSep);
        sb.append("Number of successful runs: " + stats.getNumSuccessRuns() + " (" + avgFormat.format(percSuccess) + "%)" + lineSep);
        sb.append("Number of failed runs: " + stats.getNumFailRuns() + " (" + avgFormat.format(percFail) + "%)");

        return sb.toString();
    }
}
