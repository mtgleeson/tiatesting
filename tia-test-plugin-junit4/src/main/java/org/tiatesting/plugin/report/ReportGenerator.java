package org.tiatesting.plugin.report;

import org.tiatesting.plugin.persistence.DataStore;

/**
 * Generate a report for a test mapping using a data store.
 */
public interface ReportGenerator {
    void generateReport(DataStore dataStore);
}
