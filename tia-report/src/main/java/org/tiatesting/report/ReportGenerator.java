package org.tiatesting.report;

import org.tiatesting.core.persistence.DataStore;

/**
 * Generate a report for a test mapping using a data store.
 */
public interface ReportGenerator {
    String generateReport(DataStore dataStore);
}
