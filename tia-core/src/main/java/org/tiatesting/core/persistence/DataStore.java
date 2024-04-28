package org.tiatesting.core.persistence;

import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;

import java.util.Map;
import java.util.Set;

public interface DataStore {

    /**
     * Retrieve the full persisted Tia data.
     *
     * @param readFromDisk should the Tia data be read from disk (if not, it will read from the cache if loaded)
     * @return the fully loaded Tia DB
     */
    TiaData getTiaData(boolean readFromDisk);

    /**
     * Retrieve the persisted Tia core data.
     *
     * @return the Tia core data
     */
    TiaData getTiaCore();

    /**
     * Retrieve the persisted tracked source classes.
     *
     * @return the test suites tracked by Tia
     */
    Map<String, TestSuiteTracker> getTestSuitesTracked();

    /**
     * Retrieve the persisted indexed tracked source methods.
     *
     * @return the methods tracked by Tia
     */
    Map<Integer, MethodImpactTracker> getMethodsTracked();

    /**
     * Retrieve the unique set of method ids tracked for all source classes.
     *
     * @return the unique method ids tracked by Tia
     */
    Set<Integer> getUniqueMethodIdsTracked();

    /**
     * Get the number of test suites tracked by Tia in the DB.
     *
     * @return the number of test suites tracked by Tia
     */
    int getNumTestSuites();

    /**
     * Get the number of source methods tracked;
     *
     * @return the number of source methods tracked by Tia
     */
    int getNumSourceMethods();

    /**
     * Get the list of test suites that failed the previous run and are tracked in the Tia to be executed in the next run.
     *
     * @return the list of tests that failed the previous test run
     */
    Set<String> getTestSuitesFailed();

    /**
     * Persist the core data for Tia to disk.
     *
     * @param tiaData the Tia DB object to persist.
     */
    void persistCoreData(final TiaData tiaData);

    /**
     * Persist the failed test suites data to disk.
     *
     * @param testSuitesFailed the test suites that were not successful in the test run.
     */
    void persistTestSuitesFailed(final Set<String> testSuitesFailed);

    /**
     * Persist the methods tracked data to disk.
     *
     * @param methodsTracked the list of methods that should be tracked by Tia.
     */
    void persistSourceMethods(final Map<Integer, MethodImpactTracker> methodsTracked);

    /**
     * Persist the test suites data to disk.
     *
     * @param testSuites the test suites that should be persisted to disk.
     */
    void persistTestSuites(final Map<String, TestSuiteTracker> testSuites);

    /**
     * Delete the given test suites from disk.
     *
     * @param testSuites the test suites that should be deleted from disk.
     */
    void deleteTestSuites(final Set<String> testSuites);
}
