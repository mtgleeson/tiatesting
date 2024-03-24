package org.tiatesting.core.testrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.model.TestStats;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class TestRunnerService {

    private static final Logger log = LoggerFactory.getLogger(TestRunnerService.class);

    private final DataStore dataStore;

    public TestRunnerService(final DataStore dataStore){
        this.dataStore = dataStore;
    }

    public void persistTestRunData(final boolean updateDBMapping, final boolean updateDBStats,
                                   final String commitValue, final TestRunResult testRunResult){
        log.info("Persisting core data with commit value: " + commitValue);
        TiaData tiaData = dataStore.getTiaCore();
        updateTiaCoreData(tiaData, commitValue, updateDBStats, testRunResult.getTestStats());
        updateTestSuiteMapping(tiaData, testRunResult.getTestSuiteTrackers(), testRunResult.getRunnerTestSuites(), updateDBMapping, updateDBStats);

        if (updateDBMapping){
            updateMethodsTracked(tiaData, testRunResult.getMethodTrackersFromTestRun());
            updateTestSuitesFailed(tiaData, testRunResult.getSelectedTests(), testRunResult.getTestSuitesFailed());
        }
    }

    /**
     *
     * @param tiaData
     * @param commitValue the new commit value the test run was executed against
     * @param updateDBStats should the test stats be updated for the test run
     * @param testStats the stats for the test run
     */
    private void updateTiaCoreData(final TiaData tiaData, final String commitValue, final boolean updateDBStats,
                                   final TestStats testStats){
        tiaData.setCommitValue(commitValue);
        tiaData.setLastUpdated(Instant.now());

        if (updateDBStats){
            tiaData.incrementStats(testStats);
        }

        dataStore.persistCoreData(tiaData);
    }

    /**
     * Update the test suite mapping to source code in the DB.
     * Remove any deleted test suites from the DB.
     *
     * Also update the stats for the test suites if configured for the test run.
     *
     * @param tiaData
     * @param testSuiteTrackers the mapping of test suites to source code impacted from the current test run
     * @param runnerTestSuites the lists of test suites known to the runner for the current workspace
     * @param updateDBMapping should the test suite to source code mapping be updated for the test run
     * @param updateDBStats should the test stats be updated for the test run
     */
    private void updateTestSuiteMapping(final TiaData tiaData, final Map<String, TestSuiteTracker> testSuiteTrackers,
                                        final Set<String> runnerTestSuites, final boolean updateDBMapping, final boolean updateDBStats){

        Map<String, TestSuiteTracker> testSuiteTrackersOnDisk = dataStore.getTestSuitesTracked();
        tiaData.setTestSuitesTracked(testSuiteTrackersOnDisk);

        if (updateDBMapping){
            Map<String, TestSuiteTracker> mergedTestSuiteTrackers = mergeTestMappingMaps(testSuiteTrackersOnDisk, testSuiteTrackers);
            tiaData.setTestSuitesTracked(mergedTestSuiteTrackers);

            //mergedTestMappings.forEach( (testClass, methodsCalled) ->
            //        log.debug(methodsCalled.stream().map(String::valueOf).collect(Collectors.joining("\n", testClass+":\n", ""))));

            // remove any test suites that have been deleted
            removeDeletedTestSuites(tiaData.getTestSuitesTracked(), runnerTestSuites);
        }

        if (updateDBStats){
            Map<String, TestSuiteTracker> mergedTestSuiteTrackers = mergeTestMappingStats(tiaData.getTestSuitesTracked(), testSuiteTrackers);
            tiaData.setTestSuitesTracked(mergedTestSuiteTrackers);
        }

        dataStore.persistTestSuites(tiaData.getTestSuitesTracked());
    }

    /**
     * Note, make sure this method is called after updateTestSuiteMapping. It relies on querying the DB for the list of
     * updated source class method ids.
     *
     * @param tiaData
     * @param methodTrackersFromTestRun a map of all source code methods that were covered by any of the test suites executed in the test run
     */
    private void updateMethodsTracked(final TiaData tiaData, final Map<Integer, MethodImpactTracker> methodTrackersFromTestRun){
        Map<Integer, MethodImpactTracker> methodTrackersOnDisk = dataStore.getMethodsTracked();
        Map<Integer, MethodImpactTracker> updatedMethodTrackers = updateMethodTracker(methodTrackersOnDisk, methodTrackersFromTestRun);
        tiaData.setMethodsTracked(updatedMethodTrackers);
        dataStore.persistSourceMethods(updatedMethodTrackers);
    }

    /**
     *  The list of failed tests is updated on each test run (not rebuilt from scratch). This accounts for
     *  scenarios where the test suite is split across multiple hosts which can be updating the stored TIA DB.
     *  First, remove all the existing test suites that were selected for this run, and then add back any that failed.
     *
     * @param tiaData
     * @param selectedTests the tests selected to run by Tia
     * @param testSuitesFailed the list of test suites that contained a failure or error
     */
    private void updateTestSuitesFailed(final TiaData tiaData, final Set<String> selectedTests, final Set<String> testSuitesFailed){
        tiaData.setTestSuitesFailed(dataStore.getTestSuitesFailed());
        tiaData.getTestSuitesFailed().removeAll(selectedTests);
        tiaData.getTestSuitesFailed().addAll(testSuitesFailed);
        dataStore.persistTestSuitesFailed(tiaData.getTestSuitesFailed());
    }

    /**
     * Remove all deleted test suites from the test trackers that will be updated in the DB.
     * A test suite is determined to be deleted if it was not in the list of test suites executed by the test runner,
     * but it was previously tracked by Tia and stored in the DB.
     *
     * @param testSuitesInDB
     * @param runnerTestSuites
     */
    private void removeDeletedTestSuites(final Map<String, TestSuiteTracker> testSuitesInDB, final Set<String> runnerTestSuites){
        Set<String> deletedTestSuites = new HashSet<>();
        for (String testSuiteTracked : testSuitesInDB.keySet()){
            if (!runnerTestSuites.contains(testSuiteTracked)){
                deletedTestSuites.add(testSuiteTracked);
            }
        }

        if (!deletedTestSuites.isEmpty()) {
            log.info("Removing the following deleted test suites from the persisted mapping: {}", deletedTestSuites);
            testSuitesInDB.keySet().removeAll(deletedTestSuites);
        }
    }

    /**
     * Update the method tracker which is stored on disk.
     *
     * @param methodTrackerOnDisk current method tracker persisted on disk
     * @param methodTrackerFromTestRun methods called from the current test run
     */
    private Map<Integer, MethodImpactTracker> updateMethodTracker(final Map<Integer, MethodImpactTracker> methodTrackerOnDisk,
                                                                  final Map<Integer, MethodImpactTracker> methodTrackerFromTestRun){

        // Set containing the combined method ids using the updated test mapping after the test run
        Set<Integer> methodsImpactedAfterTestRun = dataStore.getUniqueMethodIdsTracked();


        // We have the updated list of method ids. But the stored method data will have the details associated before the test run
        // which will potentially have incorrect line numbers. This happens for 2 reasons.
        // 1. This happens when a method(s) exist in a source file that had its line numbers changes due to a source file change.
        // 2. We also need to account for other methods that had their lines numbers updated but weren't executed in the test,
        // i.e. new lines of code were added to a method, this causes that method to be executed in this test run. But, the methods in
        // the file below this will all be pushed down and have updated line numbers. So we need to update those indexed
        // methods in the DB as well.
        Map<Integer, MethodImpactTracker> newMethodTracker = new HashMap<>();

        for (Integer methodImpactedId : methodsImpactedAfterTestRun){
            if (methodTrackerFromTestRun.containsKey(methodImpactedId)){
                newMethodTracker.put(methodImpactedId, methodTrackerFromTestRun.get(methodImpactedId));
            } else {
                newMethodTracker.put(methodImpactedId, methodTrackerOnDisk.get(methodImpactedId));
            }
        }

        return newMethodTracker;
    }

    /**
     * Update the stored test suite trackers based on the results from the current test run.
     * For each test suite, set the new tracker including the new test to source code mappings.
     * If the test suite has an existing tracker then update it to use the new tracker.
     *
     * @param storedTestSuiteTrackers
     * @param newTestSuiteTrackers
     * @return mergedTestMappings
     */
    private Map<String, TestSuiteTracker> mergeTestMappingMaps(final Map<String, TestSuiteTracker> storedTestSuiteTrackers,
                                                               final Map<String, TestSuiteTracker> newTestSuiteTrackers){
        Map<String, TestSuiteTracker> mergedTestMappings = new HashMap<>(storedTestSuiteTrackers);

        newTestSuiteTrackers.forEach((testSuiteName, newTestSuiteTracker) -> {
            TestSuiteTracker storedTestSuiteTracker = storedTestSuiteTrackers.get(testSuiteName);

            if (storedTestSuiteTracker != null){
                storedTestSuiteTracker.setClassesImpacted(newTestSuiteTracker.getClassesImpacted());
            } else {
                TestSuiteTracker newTestSuiteTrackerToAdd = new TestSuiteTracker();
                // Add a new test suite tracker but don't add the stats, this gets updated separately
                newTestSuiteTrackerToAdd.setName(newTestSuiteTracker.getName());
                newTestSuiteTrackerToAdd.setClassesImpacted(newTestSuiteTracker.getClassesImpacted());
                mergedTestMappings.put(testSuiteName, newTestSuiteTrackerToAdd);
            }
        });

        return mergedTestMappings;
    }

    /**
     * Update the stored test suite trackers based on the results from the current test run.
     * For each test suite, set the new tracker including the new test to source code mappings.
     * If the test suite has an existing tracker then update it to use the new tracker.
     *
     * @param storedTestSuiteTrackers
     * @param newTestSuiteTrackers
     * @return mergedTestMappings
     */
    private Map<String, TestSuiteTracker> mergeTestMappingStats(final Map<String, TestSuiteTracker> storedTestSuiteTrackers,
                                                                final Map<String, TestSuiteTracker> newTestSuiteTrackers){
        Map<String, TestSuiteTracker> mergedTestMappings = new HashMap<>(storedTestSuiteTrackers);

        newTestSuiteTrackers.forEach((testSuiteName, newTestSuiteTracker) -> {
            TestSuiteTracker storedTestSuiteTracker = storedTestSuiteTrackers.get(testSuiteName);

            if (storedTestSuiteTracker != null){
                storedTestSuiteTracker.incrementStats(newTestSuiteTracker.getTestStats());
            } else {
                mergedTestMappings.put(testSuiteName, newTestSuiteTracker);
            }
        });

        return mergedTestMappings;
    }
}
