package org.tiatesting.persistence;

import org.tiatesting.core.coverage.ClassImpactTracker;
import org.tiatesting.core.coverage.MethodImpactTracker;
import org.tiatesting.core.coverage.TestSuiteTracker;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface DataStore {

    /**
     * Retrieve the stored test mapping details.
     *
     * @return
     */
    StoredMapping getStoredMapping();

    /**
     * Persist the mapping of the methods called by each test class.
     *
     * @param testSuiteTrackers the mapping of test suites to source code impacted from the current test run
     * @param testSuitesFailed the list of test suites that contained a failure or error
     * @param runnerTestSuites the lists of test suites known to the runner for the current workspace
     * @param selectedTests the tests selected to run by Tia
     * @param methodTrackers a map of all source code methods that were covered by any of the test suites executed in the test run
     * @param commitValue the new commit value the test run was executed against
     * @param totalRunTime the total amount of time taken run the test suites
     * @return was the test mapping data saved successfully
     */
    boolean persistTestMapping(final Map<String, TestSuiteTracker> testSuiteTrackers, final Set<String> testSuitesFailed,
                               final Set<String> runnerTestSuites, final Set<String> selectedTests,
                               final Map<Integer, MethodImpactTracker> methodTrackers, final String commitValue, final long totalRunTime);

}
