package org.tiatesting.core.report;

import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.DataStore;

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
     * sealed commit), an unsealed-suite count when any suite's mapping row was written by a run
     * that never completed its seal, mapping size counts and aggregate run statistics. Library
     * information is intentionally not included - that's the libraries task's job
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
        sb.append("Test mapping valid for commit: " + tiaData.getCommitValue() + lineSep);

        long unsealedCount = dataStore.getTestSuitesTracked().values().stream()
                .filter(TestSuiteTracker::isUnsealed).count();
        if (unsealedCount > 0) {
            sb.append("Unsealed test suites: " + unsealedCount
                    + " (a previous run wrote their mapping but did not complete - they will be re-run)" + lineSep);
        }
        sb.append(lineSep);

        sb.append(SummaryStats.toText(SummaryStats.build(dataStore.getNumTestSuites(),
                dataStore.getNumSourceMethods(), tiaData.getTestStats(),
                dataStore.readTestRunHistory()), lineSep));

        return sb.toString();
    }
}
