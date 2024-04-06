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
     * @param tiaData
     * @return
     */
    void generateReports(TiaData tiaData);

    /**
     * Generate the Tia summary report.
     *
     * @param tiaData
     * @return
     */
    String generateSummaryReport(TiaData tiaData);

    /**
     * Generate the report for the test suites tracked by Tia.
     * @param tiaData
     */
    String generateTestSuiteReport(TiaData tiaData);

    /**
     * Generate the report for the source classes tracked by Tia.
     * @param tiaData
     * @return
     */
    String generateSourceClassReport(TiaData tiaData);

    /**
     *  Generate the report for the source methods tracked by Tia.
     * @param tiaData
     * @return
     */
    String generateSourceMethodReport(TiaData tiaData);
}
