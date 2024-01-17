package org.tiatesting.persistence;

import org.tiatesting.core.coverage.MethodImpactTracker;
import org.tiatesting.core.coverage.TestSuiteTracker;

import java.util.Map;
import java.util.Set;

public interface DataStore {

    /**
     * Retrieve the stored test mapping details.
     *
     * @return
     */
    StoredMapping getStoredMapping(boolean readLatestDBFromDisk);

    /**
     * Persist the mapping of the methods called by each test class.
     *
     * @param testSuiteTrackers the mapping of test suites to source code impacted from the current test run
     * @param testSuitesFailed the list of test suites that contained a failure or error
     * @param runnerTestSuites the lists of test suites known to the runner for the current workspace
     * @param selectedTests the tests selected to run by Tia
     * @param methodTrackers a map of all source code methods that were covered by any of the test suites executed in the test run
     * @param commitValue the new commit value the test run was executed against
     * @param getLatestStoredDB should we read the data store from the persisted DB or a potentially locally cache copy
     * @return was the test mapping data saved successfully
     */
    void updateTestMapping(final Map<String, TestSuiteTracker> testSuiteTrackers, final Set<String> testSuitesFailed,
                              final Set<String> runnerTestSuites, final Set<String> selectedTests,
                              final Map<Integer, MethodImpactTracker> methodTrackers, final String commitValue, boolean getLatestStoredDB);

    /**
     * Update the stats stored in the stored mapping. Note, this doesn't persist the changes.
     *
     * @param testSuiteTrackers the test suites with updated stats from the current test run
     * @param totalRunTime the total amount of time taken run the test suites
     * @param getLatestStoredDB should we read the data store from the persisted DB or a potentially locally cache copy
     */
    void updateStats(final Map<String, TestSuiteTracker> testSuiteTrackers, final long totalRunTime, boolean getLatestStoredDB);

    boolean persistStoreMapping();
}
