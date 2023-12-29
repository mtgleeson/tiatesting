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
     * @param testSuiteTrackers
     * @param testSuitesFailed
     * @param runnerTestSuites
     * @param methodTrackers
     * @param commitValue
     * @return was the test mapping data saved successfully
     */
    boolean persistTestMapping(Map<String, TestSuiteTracker> testSuiteTrackers, Set<String> testSuitesFailed,
                               Set<String> runnerTestSuites, Map<Integer, MethodImpactTracker> methodTrackers,
                               String commitValue);

}
