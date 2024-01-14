package org.tiatesting.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.coverage.ClassImpactTracker;
import org.tiatesting.core.coverage.MethodImpactTracker;
import org.tiatesting.core.coverage.TestSuiteTracker;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * DataStore implementation based on a plain Java Map and being persisted to a file on disk.
 */
public class MapDataStore implements DataStore {

    private static final Logger log = LoggerFactory.getLogger(MapDataStore.class);

    private final String mappingFilenamePrefix = "tia-testmapping";
    private final String mappingFilenameSuffix;
    private final String mappingFilenameExt = "ser";
    private final String mappingFilename;
    private final String dataStorePath;

    public MapDataStore(String dataStorePath, String mappingFilenameSuffix){
        this.dataStorePath = dataStorePath;
        this.mappingFilenameSuffix = mappingFilenameSuffix;
        this.mappingFilename = buildMappingFilename();
    }

    @Override
    public StoredMapping getStoredMapping() {
        return readTestMappingFromDisk();
    }

    @Override
    public boolean persistTestMapping(final Map<String, TestSuiteTracker> testSuiteTrackers, final Set<String> testSuitesFailed,
                                      final Set<String> runnerTestSuites, final Set<String> selectedTests,
                                      final Map<Integer, MethodImpactTracker> methodTrackersFromTestRun,
                                      final String commitValue, long totalRunTimeMs) {
        long startTime = System.currentTimeMillis();

        // always get the latest test mapping before updating in case another process has updated the file since it was last read.
        StoredMapping storedMapping = readTestMappingFromDisk();
        log.info("Persisting commit value: " + commitValue);
        storedMapping.setCommitValue(commitValue);
        long totalRunTimeSec = totalRunTimeMs > 1000 ? (totalRunTimeMs / 1000) : 1;
        storedMapping.incrementTotalRunTime(totalRunTimeSec);
        storedMapping.incrementNumRuns();

        // update the test mapping
        Map<String, TestSuiteTracker> testSuiteTrackersOnDisk = storedMapping.getTestSuitesTracked();
        Map<String, TestSuiteTracker> mergedTestSuiteTrackers = mergeTestMappingMaps(testSuiteTrackersOnDisk, testSuiteTrackers);
        storedMapping.setTestSuitesTracked(mergedTestSuiteTrackers);

        // update the tracked methods
        Map<Integer, MethodImpactTracker> methodTrackersOnDisk = storedMapping.getMethodsTracked();
        Map<Integer, MethodImpactTracker> updatedMethodTrackers = updateMethodTracker(methodTrackersOnDisk, methodTrackersFromTestRun, mergedTestSuiteTrackers);
        storedMapping.setMethodsTracked(updatedMethodTrackers);

        // remove any test suites that have been deleted
        removeDeletedTestSuites(storedMapping, runnerTestSuites);

        // The list of failed tests is updated on each test run (not rebuilt from scratch). This accounts for
        // scenarios where the test suite is split across multiple hosts which can be updating the stored TIA DB.
        // First, remove all the existing test suites that were selected for this run, and then add back any that failed.
        storedMapping.getTestSuitesFailed().removeAll(selectedTests);
        storedMapping.getTestSuitesFailed().addAll(testSuitesFailed);

        storedMapping.setLastUpdated(Instant.now());
        //mergedTestMappings.forEach( (testClass, methodsCalled) ->
        //        log.debug(methodsCalled.stream().map(String::valueOf).collect(Collectors.joining("\n", testClass+":\n", ""))));

        boolean savedToDisk = writeTestMappingToDisk(storedMapping);
        log.debug("Time to save the mapping to disk (ms): " + (System.currentTimeMillis() - startTime));
        return savedToDisk;
    }

    /**
     * Remove all deleted test suites from the test trackers that will be updated in the DB.
     * A test suite is determined to be deleted if it was not in the list of test suites executed by the test runner,
     * but it was previously tracked by Tia and stored in the DB.
     *
     * @param storedMapping
     * @param runnerTestSuites
     */
    private void removeDeletedTestSuites(final StoredMapping storedMapping, final Set<String> runnerTestSuites){
        Set<String> deletedTestSuites = new HashSet<>();
        for (String testSuiteTracked : storedMapping.getTestSuitesTracked().keySet()){
            if (!runnerTestSuites.contains(testSuiteTracked)){
                deletedTestSuites.add(testSuiteTracked);
            }
        }

        if (!deletedTestSuites.isEmpty()) {
            log.info("Removing the following deleted test suites from the persisted mapping: {}", deletedTestSuites);
            storedMapping.getTestSuitesTracked().keySet().removeAll(deletedTestSuites);
        }
    }

    /**
     * Read the serialized test mapping file from disk.
     * If the file on disk doesn't exist then create a new {@link StoredMapping} object
     *
     * @return
     */
    private StoredMapping readTestMappingFromDisk(){
        StoredMapping storedMapping;

        try {
            FileInputStream fis = new FileInputStream(dataStorePath + "/" + mappingFilename);
            ObjectInputStream ois = new ObjectInputStream(fis);
            storedMapping = (StoredMapping) ois.readObject();
            ois.close();
        } catch (FileNotFoundException e){
            log.debug(dataStorePath + "/" + mappingFilename + " doesn't currently exist.");
            storedMapping = new StoredMapping();
        } catch (ClassNotFoundException | IOException e) {
            log.error("An error occurred", e);
            throw new RuntimeException(e);
        }

        return storedMapping;
    }

    private String buildMappingFilename(){
        return mappingFilenamePrefix + "-" + mappingFilenameSuffix + "." + mappingFilenameExt;
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

        // update the method details for all methods stored in the DB with the latest method line numbers in case they
        // have changed due to source code changes (even if the method wasn't executed from the test run).
        //for (Integer methodId : methodTrackerFromTestRun.keySet()){
        //    if (newMethodTracker.containsKey(methodId)){
        //        newMethodTracker.put(methodId, methodTrackerFromTestRun.get(methodId));
        //    }
       // }

        return newMethodTracker;
    }

    /**
     * Update the stored test suite trackers based on the results from the current test run.
     * For each test suite, set the new tracker including the new test to source code mappings.
     * If the test suite has an existing tracker then update it to use the new tracker.
     *
     * @param oldTestSuiteTrackers
     * @param newTestSuiteTrackers
     * @return mergedTestMappings
     */
    private Map<String, TestSuiteTracker> mergeTestMappingMaps(final Map<String, TestSuiteTracker> oldTestSuiteTrackers,
                                                               final Map<String, TestSuiteTracker> newTestSuiteTrackers){
        Map<String, TestSuiteTracker> mergedTestMappings = new HashMap<>(oldTestSuiteTrackers);

        newTestSuiteTrackers.forEach((key, value) ->
                mergedTestMappings.merge(key, value, (oldTestSuiteTracker, newTestSuiteTracker) ->  {
                    newTestSuiteTracker.incrementStats(oldTestSuiteTracker.getNumRuns(), oldTestSuiteTracker.getTotalRunTime(),
                            oldTestSuiteTracker.getNumSuccessRuns(), oldTestSuiteTracker.getNumFailRuns());
                    return newTestSuiteTracker;
                }));
        return mergedTestMappings;
    }

    /**
     * Serialize the contents of the store mapping object to a file on disk.
     * Lock the file on disk to avoid concurrent writes from other JVMs.
     * The lock strategy channel.lock() will wait for if another process already has a
     * lock in place.
     *
     * @param storedMapping
     * @return
     */
    private boolean writeTestMappingToDisk(final StoredMapping storedMapping){
        boolean savedToDisk = true;
        final String fullMappingFilename = dataStorePath + "/" + mappingFilename;

        try (FileOutputStream fileOutputStream = new FileOutputStream(fullMappingFilename);
             FileChannel channel = fileOutputStream.getChannel();
             FileLock lock = channel.lock()) {
            ObjectOutputStream out = new ObjectOutputStream(fileOutputStream);
            out.writeObject(storedMapping);
            log.info("Serialized data is saved in " + fullMappingFilename);
        } catch (IOException e) {
            savedToDisk = false;
            log.error("Serialized data failed to saved to disk for " + fullMappingFilename);
            e.printStackTrace();
        }

        return savedToDisk;
    }
}
