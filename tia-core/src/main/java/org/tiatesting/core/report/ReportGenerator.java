package org.tiatesting.core.report;

import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.DataStore;

/**
 * Generate a report for a test mapping using a data store.
 */
public interface ReportGenerator {

    /**
     * Generate all the Tia reports.
     *
     * @param tiaData the Tia data from the DB
     */
    void generateReports(TiaData tiaData);

    /**
     * Generate the Tia summary report.
     *
     * @param tiaData the Tia data from the DB
     * @return the report as a String
     */
    String generateSummaryReport(TiaData tiaData);

    /**
     * Generate the report for the test suites tracked by Tia.
     *
     * @param tiaData the Tia data from the DB
     * @return the report as a String
     */
    String generateTestSuiteReport(TiaData tiaData);

    /**
     * Generate the report for the source methods tracked by Tia.
     *
     * @param tiaData the Tia data from the DB
     * @return the report as a String
     */
    String generateSourceMethodReport(TiaData tiaData);
}
