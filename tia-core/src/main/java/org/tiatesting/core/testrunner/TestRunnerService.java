package org.tiatesting.core.testrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.stats.TestStats;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class TestRunnerService {

    private static final Logger log = LoggerFactory.getLogger(TestRunnerService.class);

    /**
     * Persist the mapping of the methods called by each test class.
     *
     * @param tiaData
     * @param testSuiteTrackers the mapping of test suites to source code impacted from the current test run
     * @param testSuitesFailed the list of test suites that contained a failure or error
     * @param runnerTestSuites the lists of test suites known to the runner for the current workspace
     * @param selectedTests the tests selected to run by Tia
     * @param methodTrackersFromTestRun a map of all source code methods that were covered by any of the test suites executed in the test run
     * @param commitValue the new commit value the test run was executed against
     * @return was the test mapping data saved successfully
     */
    public void updateTestMapping(final TiaData tiaData, final Map<String, TestSuiteTracker> testSuiteTrackers, final Set<String> testSuitesFailed,
                                  final Set<String> runnerTestSuites, final Set<String> selectedTests,
                                  final Map<Integer, MethodImpactTracker> methodTrackersFromTestRun, final String commitValue) {
        log.info("Persisting commit value: " + commitValue);
        tiaData.setCommitValue(commitValue);

        // update the test mapping
        Map<String, TestSuiteTracker> testSuiteTrackersOnDisk = tiaData.getTestSuitesTracked();
        Map<String, TestSuiteTracker> mergedTestSuiteTrackers = mergeTestMappingMaps(testSuiteTrackersOnDisk, testSuiteTrackers);
        tiaData.setTestSuitesTracked(mergedTestSuiteTrackers);

        // update the tracked methods
        Map<Integer, MethodImpactTracker> methodTrackersOnDisk = tiaData.getMethodsTracked();
        Map<Integer, MethodImpactTracker> updatedMethodTrackers = updateMethodTracker(methodTrackersOnDisk, methodTrackersFromTestRun, mergedTestSuiteTrackers);
        tiaData.setMethodsTracked(updatedMethodTrackers);

        // remove any test suites that have been deleted
        removeDeletedTestSuites(tiaData, runnerTestSuites);

        // The list of failed tests is updated on each test run (not rebuilt from scratch). This accounts for
        // scenarios where the test suite is split across multiple hosts which can be updating the stored TIA DB.
        // First, remove all the existing test suites that were selected for this run, and then add back any that failed.
        tiaData.getTestSuitesFailed().removeAll(selectedTests);
        tiaData.getTestSuitesFailed().addAll(testSuitesFailed);

        tiaData.setLastUpdated(Instant.now());
        //mergedTestMappings.forEach( (testClass, methodsCalled) ->
        //        log.debug(methodsCalled.stream().map(String::valueOf).collect(Collectors.joining("\n", testClass+":\n", ""))));
    }

    /**
     * Update the stats stored in the stored mapping. Note, this doesn't persist the changes.
     *
     * @param tiaData
     * @param testSuiteTrackers the test suites with updated stats from the current test run
     * @param testRunStats the stats for the test run
     */
    public void updateStats(final TiaData tiaData, final Map<String, TestSuiteTracker> testSuiteTrackers, final TestStats testRunStats){
        tiaData.incrementStats(testRunStats);

        // update the test mapping
        Map<String, TestSuiteTracker> testSuiteTrackersOnDisk = tiaData.getTestSuitesTracked();
        Map<String, TestSuiteTracker> mergedTestSuiteTrackers = mergeTestMappingStats(testSuiteTrackersOnDisk, testSuiteTrackers);
        tiaData.setTestSuitesTracked(mergedTestSuiteTrackers);
    }

    /**
     * Remove all deleted test suites from the test trackers that will be updated in the DB.
     * A test suite is determined to be deleted if it was not in the list of test suites executed by the test runner,
     * but it was previously tracked by Tia and stored in the DB.
     *
     * @param tiaData
     * @param runnerTestSuites
     */
    private void removeDeletedTestSuites(final TiaData tiaData, final Set<String> runnerTestSuites){
        Set<String> deletedTestSuites = new HashSet<>();
        for (String testSuiteTracked : tiaData.getTestSuitesTracked().keySet()){
            if (!runnerTestSuites.contains(testSuiteTracked)){
                deletedTestSuites.add(testSuiteTracked);
            }
        }

        if (!deletedTestSuites.isEmpty()) {
            log.info("Removing the following deleted test suites from the persisted mapping: {}", deletedTestSuites);
            tiaData.getTestSuitesTracked().keySet().removeAll(deletedTestSuites);
        }
    }

    /**
     * Update the method tracker which is stored on disk.
     *
     * @param methodTrackerOnDisk current method tracker persisted on disk
     * @param methodTrackerFromTestRun methods called from the current test run
     * @param testSuiteTrackers updated test suite tracker to be persisted
     */
    private Map<Integer, MethodImpactTracker> updateMethodTracker(final Map<Integer, MethodImpactTracker> methodTrackerOnDisk,
                                                                  final Map<Integer, MethodImpactTracker> methodTrackerFromTestRun,
                                                                  final Map<String, TestSuiteTracker> testSuiteTrackers){

        // Set containing the combined method ids using the updated test mapping after the test run
        Set<Integer> methodsImpactedAfterTestRun = new HashSet<>();

        // collect a set of all methods called from the updated test mapping
        for (TestSuiteTracker testSuiteTracker : testSuiteTrackers.values()){
            for (ClassImpactTracker classImpactTracker : testSuiteTracker.getClassesImpacted()){
                methodsImpactedAfterTestRun.addAll(classImpactTracker.getMethodsImpacted());
            }
        }

        // add all methods called from the test mapping to the method tracker index. The method tracker will now
        // have the correct list of methods. But the methods will have the details associated before the test run
        // which will potentially have incorrect line numbers. This happens when a method(s) exist in a source file that
        // had its line numbers changes due to a source file change, but the methods weren't executed in the test run.
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
                newTestSuiteTrackerToAdd.setSourceFilename(newTestSuiteTracker.getSourceFilename());
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
