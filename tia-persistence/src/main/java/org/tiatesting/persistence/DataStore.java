package org.tiatesting.persistence;

import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.StoredMapping;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.stats.TestStats;

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
     * @param storedMapping
     * @param testSuiteTrackers the mapping of test suites to source code impacted from the current test run
     * @param testSuitesFailed the list of test suites that contained a failure or error
     * @param runnerTestSuites the lists of test suites known to the runner for the current workspace
     * @param selectedTests the tests selected to run by Tia
     * @param methodTrackers a map of all source code methods that were covered by any of the test suites executed in the test run
     * @param commitValue the new commit value the test run was executed against
     * @return was the test mapping data saved successfully
     */
    void updateTestMapping(final StoredMapping storedMapping, final Map<String, TestSuiteTracker> testSuiteTrackers, final Set<String> testSuitesFailed,
                           final Set<String> runnerTestSuites, final Set<String> selectedTests,
                           final Map<Integer, MethodImpactTracker> methodTrackers, final String commitValue);

    /**
     * Update the stats stored in the stored mapping. Note, this doesn't persist the changes.
     *
     * @param storedMapping
     * @param testSuiteTrackers the test suites with updated stats from the current test run
     * @param testRunStats the stats for the test run
     */
    void updateStats(final StoredMapping storedMapping, final Map<String, TestSuiteTracker> testSuiteTrackers, final TestStats testRunStats);

    boolean persistStoreMapping(final StoredMapping storedMapping);
}
