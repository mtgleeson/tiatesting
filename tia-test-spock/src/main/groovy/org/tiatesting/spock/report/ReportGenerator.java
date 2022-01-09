package org.tiatesting.spock.report;

import org.tiatesting.persistence.DataStore;

/**
 * Generate a report for a test mapping using a data store.
 */
public interface ReportGenerator {
    void generateReport(DataStore dataStore);
}
