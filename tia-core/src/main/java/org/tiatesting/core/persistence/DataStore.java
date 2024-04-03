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
     * @return
     */
    TiaData getTiaData(boolean readFromDisk);

    /**
     * Retrieve the persisted Tia core data.
     *
     * @return
     */
    TiaData getTiaCore();

    /**
     * Retrieve the persisted tracked source classes.
     *
     * @return
     */
    Map<String, TestSuiteTracker> getTestSuitesTracked();

    /**
     * Retrieve the persisted indexed tracked source methods.
     *
     * @return
     */
    Map<Integer, MethodImpactTracker> getMethodsTracked();

    /**
     * Retrieve the unique set of method ids tracked for all source classes.
     *
     * @return
     */
    Set<Integer> getUniqueMethodIdsTracked();

    /**
     * Get the number of test suites tracked by Tia in the DB.
     * @return
     */
    int getNumTestSuites();

    /**
     * Get the number of source methods tracked;
     * @return
     */
    int getNumSourceMethods();

    /**
     * Get the list of test suites that failed the previous run and are tracked in the Tia to be executed in the next run.
     * @return
     */
    Set<String> getTestSuitesFailed();

    /**
     * Persist the core data for Tia to disk.
     * @param tiaData
     */
    void persistCoreData(final TiaData tiaData);

    /**
     * Persist the failed test suites data to disk.
     * @param testSuitesFailed
     */
    void persistTestSuitesFailed(final Set<String> testSuitesFailed);

    /**
     * Persist the methods tracked data to disk.
     *
     * @param methodsTracked
     */
    void persistSourceMethods(final Map<Integer, MethodImpactTracker> methodsTracked);

    /**
     * Persist the test suites data to disk.
     *
     * @param testSuites
     */
    void persistTestSuites(final Map<String, TestSuiteTracker> testSuites);

    /**
     * Delete the given test suites from disk.
     *
     * @param testSuites
     */
    void deleteTestSuites(final Set<String> testSuites);
}
