package org.tiatesting.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.coverage.ClassImpactTracker;
import org.tiatesting.core.coverage.MethodImpactTracker;
import org.tiatesting.core.coverage.TestSuiteTracker;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.*;

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
                                      final Set<String> runnerTestSuites, final Map<Integer, MethodImpactTracker> methodTrackers,
                                      final String commitValue) {
        long startTime = System.currentTimeMillis();

        // always get the latest test mapping before updating in case another process has updated the file since it was last read.
        StoredMapping storedMapping = readTestMappingFromDisk();
        log.info("Persisting commit value: " + commitValue);
        storedMapping.setCommitValue(commitValue);

        // update the tracked methods
        Map<Integer, MethodImpactTracker> methodTrackersOnDisk = storedMapping.getMethodsTracked();
        updateMethodTracker(methodTrackersOnDisk, methodTrackers, testSuiteTrackers);

        // update the test mapping
        Map<String, TestSuiteTracker> testSuiteTrackersOnDisk = storedMapping.getTestSuitesTracked();
        Map<String, TestSuiteTracker> mergedTestSuiteTrackers = mergeTestMappingMaps(testSuiteTrackersOnDisk, testSuiteTrackers);
        storedMapping.setTestSuitesTracked(mergedTestSuiteTrackers);

        // remove any test suites that have been deleted
        removeDeletedTestSuites(storedMapping, runnerTestSuites);

        // The list of failed tests is updated on each test run (not rebuilt from scratch). This accounts for
        // scenarios where the test suite is split into multiple processes which can be updating the stored TIA DB.
        // First, remove all the test suites that were executed in this run, and then add back any that failed.
        storedMapping.getTestSuitesFailed().removeAll(testSuiteTrackers.keySet());
        storedMapping.getTestSuitesFailed().addAll(testSuitesFailed);

        storedMapping.setLastUpdated(new Date());
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
     * @param methodTrackerOnDisk
     * @param newMethodTrackers
     * @param testSuiteTrackers
     */
    private void updateMethodTracker(final Map<Integer, MethodImpactTracker> methodTrackerOnDisk,
                                     final Map<Integer, MethodImpactTracker> newMethodTrackers,
                                     final Map<String, TestSuiteTracker> testSuiteTrackers){

        Set<Integer> allMethodsImpactedFromTestRun = new HashSet<>();

        for (TestSuiteTracker testSuiteTracker : testSuiteTrackers.values()){
            for (ClassImpactTracker classImpactTracker : testSuiteTracker.getClassesImpacted()){
                allMethodsImpactedFromTestRun.addAll(classImpactTracker.getMethodsImpacted());
            }
        }

        for (Integer methodImpactedId : allMethodsImpactedFromTestRun){
            methodTrackerOnDisk.put(methodImpactedId, newMethodTrackers.get(methodImpactedId));
        }

        // update the method details for all methods stored in the DB with the latest method line numbers in case they
        // have changed due to source code changes (even if the method wasn't executed from the test run).
        for(Integer methodId : methodTrackerOnDisk.keySet()){
            if (newMethodTrackers.containsKey(methodId)){
                methodTrackerOnDisk.put(methodId, newMethodTrackers.get(methodId));
            }
        }
    }

    /**
     * Update the stored test suite trackers absed on the results from the current test run.
     * For each test suite, set the new tracker including the new test to source code mappings.
     * If the test suite has an existing tracker then update it to use the new traker.
     *
     * @param oldTestSuiteTrackers
     * @param newTestSuiteTrackers
     * @return mergedTestMappings
     */
    private Map<String, TestSuiteTracker> mergeTestMappingMaps(final Map<String, TestSuiteTracker> oldTestSuiteTrackers,
                                                                       final Map<String, TestSuiteTracker> newTestSuiteTrackers){
        Map<String, TestSuiteTracker> mergedTestMappings = new HashMap<>(oldTestSuiteTrackers);
        newTestSuiteTrackers.forEach((key, value) -> mergedTestMappings.merge(key, value, (v1, v2) -> v2));
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
