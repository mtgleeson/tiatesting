package org.tiatesting.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.LibraryPublish;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.*;

/**
 * DataStore implementation based on a plain Java Object and being persisted to a file on disk.
 */
public class SerializedDataStore implements DataStore {

    private static final Logger log = LoggerFactory.getLogger(SerializedDataStore.class);

    private final String dataFilenamePrefix = "tia-data";
    private final String dataFilenameSuffix;
    private final String dataFilenameExt = "ser";
    private final String dataFilename;
    private final String dataStorePath;

    // local cached copy of the DB
    private TiaData tiaData;

    public SerializedDataStore(String dataStorePath, String dataFilenameSuffix){
        this.dataStorePath = dataStorePath;
        this.dataFilenameSuffix = dataFilenameSuffix;
        this.dataFilename = buildTiaDataFilename();
    }

    @Override
    public TiaData getTiaData(boolean readFromDisk) {
        if (this.tiaData == null || readFromDisk){
            this.tiaData = readTiaDataFromDisk();
        }
        return this.tiaData;
    }

    @Override
    public TiaData getTiaCore() {
        return getTiaData(true);
    }

    @Override
    public Map<String, TestSuiteTracker> getTestSuitesTracked() {
        return getTiaData(false).getTestSuitesTracked();
    }

    @Override
    public Map<Integer, MethodImpactTracker> getMethodsTracked() {
        return getTiaData(false).getMethodsTracked();
    }

    @Override
    public Set<Integer> getUniqueMethodIdsTracked() {
        Set<Integer> methodsImpactedAfterTestRun = new HashSet<>();

        for (TestSuiteTracker testSuiteTracker : getTiaData(false).getTestSuitesTracked().values()){
            for (ClassImpactTracker classImpactTracker : testSuiteTracker.getClassesImpacted()){
                methodsImpactedAfterTestRun.addAll(classImpactTracker.getMethodsImpacted());
            }
        }

        return methodsImpactedAfterTestRun;
    }

    /**
     * Targeted changed-files-to-tracked-methods read over the in-memory data: filter the tracked suite-class-method
     * graph down to the requested source files. The serialized store holds the whole DB in
     * memory after one read ({@code getTiaData(false)} uses the cached copy), so a filtered
     * walk is the natural equivalent of the H2 store's indexed query.
     *
     * @param sourceFilenames the mapping keys of the source files to look up
     * @return map of source filename to (method id to method tracker); empty when the input
     *         is null or empty
     */
    @Override
    public Map<String, Map<Integer, MethodImpactTracker>> getMethodsTrackedForFiles(final Set<String> sourceFilenames) {
        Map<String, Map<Integer, MethodImpactTracker>> methodsByFile = new HashMap<>();
        if (sourceFilenames == null || sourceFilenames.isEmpty()) {
            return methodsByFile;
        }

        TiaData tiaData = getTiaData(false);
        Map<Integer, MethodImpactTracker> methodsTracked = tiaData.getMethodsTracked();

        for (TestSuiteTracker testSuiteTracker : tiaData.getTestSuitesTracked().values()) {
            for (ClassImpactTracker classImpacted : testSuiteTracker.getClassesImpacted()) {
                String sourceFilename = classImpacted.getSourceFilename();
                if (!sourceFilenames.contains(sourceFilename)) {
                    continue;
                }
                for (Integer methodId : classImpacted.getMethodsImpacted()) {
                    MethodImpactTracker methodTracker = methodsTracked.get(methodId);
                    if (methodTracker != null) {
                        methodsByFile.computeIfAbsent(sourceFilename, key -> new HashMap<>()).put(methodId, methodTracker);
                    }
                }
            }
        }

        return methodsByFile;
    }

    /**
     * Targeted methods-to-covering-suites read over the in-memory data: collect the names of the test suites
     * whose coverage includes any of the given method ids, keyed per method id.
     *
     * @param methodIds the tracked method ids to find covering test suites for
     * @return map of method id to covering test-suite names; empty when the input is null
     *         or empty
     */
    @Override
    public Map<Integer, Set<String>> getTestSuitesForMethods(final Set<Integer> methodIds) {
        Map<Integer, Set<String>> suitesByMethodId = new HashMap<>();
        if (methodIds == null || methodIds.isEmpty()) {
            return suitesByMethodId;
        }

        for (TestSuiteTracker testSuiteTracker : getTiaData(false).getTestSuitesTracked().values()) {
            for (ClassImpactTracker classImpacted : testSuiteTracker.getClassesImpacted()) {
                for (Integer methodId : classImpacted.getMethodsImpacted()) {
                    if (methodIds.contains(methodId)) {
                        suitesByMethodId.computeIfAbsent(methodId, key -> new HashSet<>()).add(testSuiteTracker.getName());
                    }
                }
            }
        }

        return suitesByMethodId;
    }

    @Override
    public int getNumTestSuites() {
        return getTiaData(false).getTestSuitesTracked().size();
    }

    @Override
    public int getNumSourceMethods() {
        return getTiaData(false).getMethodsTracked().size();
    }

    @Override
    public Set<String> getTestSuitesFailed() {
        return getTiaData(false).getTestSuitesFailed();
    }

    @Override
    public void persistCoreData(TiaData tiaData) {
        long startTime = System.currentTimeMillis();
        writeTiaDataToDisk(tiaData);
        log.debug("Time to save the Tia core data to disk (ms): " + (System.currentTimeMillis() - startTime));
    }

    @Override
    public void persistTestSuitesFailed(Set<String> testSuitesFailed) {
        TiaData tiaData = getTiaData(false);
        tiaData.setTestSuitesFailed(testSuitesFailed);
        long startTime = System.currentTimeMillis();
        writeTiaDataToDisk(tiaData);
        log.info("Time to save the failed test suites data to disk (ms): " + (System.currentTimeMillis() - startTime));
    }

    @Override
    public void persistSourceMethods(Map<Integer, MethodImpactTracker> methodsTracked) {
        TiaData tiaData = getTiaData(false);
        tiaData.setMethodsTracked(methodsTracked);
        long startTime = System.currentTimeMillis();
        writeTiaDataToDisk(tiaData);
        log.info("Time to save the methods tracked data to disk (ms): " + (System.currentTimeMillis() - startTime));
    }

    @Override
    public void persistTestSuites(Map<String, TestSuiteTracker> testSuites) {
        TiaData tiaData = getTiaData(false);
        tiaData.setTestSuitesTracked(testSuites);
        long startTime = System.currentTimeMillis();
        writeTiaDataToDisk(tiaData);
        log.info("Time to save the test suites tracked data to disk (ms): " + (System.currentTimeMillis() - startTime));
    }

    @Override
    public void persistTestSuiteStatsOnly(Map<String, TestSuiteTracker> testSuites) {
        // Serialized data store has no notion of separate stats vs mapping tables - the file
        // is the whole DB. The caller (TestRunnerService.updateTestSuiteMapping) only merges
        // stats fields into the in-memory map for stats-only runs, so writing the whole map
        // produces an on-disk file with unchanged mapping and updated stats. Delegating to
        // persistTestSuites is correct here.
        persistTestSuites(testSuites);
    }

    @Override
    public void deleteTestSuites(Set<String> testSuites) {
        // do nothing, the deleted test suites will be serialized in persistTestSuites(testSuites);
    }

    @Override
    public Map<String, TrackedLibrary> readTrackedLibraries() {
        return new HashMap<>();
    }

    @Override
    public void persistTrackedLibrary(TrackedLibrary trackedLibrary) {
        // tracked libraries are only supported in the H2 data store
    }

    @Override
    public void deleteTrackedLibrary(String groupArtifact) {
        // tracked libraries are only supported in the H2 data store
    }

    @Override
    public List<LibraryPublish> readLibraryPublishes(String groupArtifact) {
        return new ArrayList<>();
    }

    @Override
    public List<LibraryPublish> readAllLibraryPublishes() {
        return new ArrayList<>();
    }

    @Override
    public Map<Integer, MethodImpactTracker> getMethodsTrackedForIds(Set<Integer> methodIds) {
        // targeted method-id reads are only supported in the H2 data store
        return new HashMap<>();
    }

    @Override
    public long persistLibraryPublish(LibraryPublish publish, Set<Integer> impactedMethodIds) {
        // the library publish ledger is only supported in the H2 data store
        return 0;
    }

    @Override
    public LibraryPublish lookupLibraryPublish(String groupArtifact, String jarHash, String version) {
        // the library publish ledger is only supported in the H2 data store
        return null;
    }

    @Override
    public List<PendingLibraryImpactedMethod> readPendingLibraryImpactedMethods(String groupArtifact) {
        return new ArrayList<>();
    }

    @Override
    public List<PendingLibraryImpactedMethod> readAllPendingLibraryImpactedMethods() {
        return new ArrayList<>();
    }

    @Override
    public void persistPendingLibraryImpactedMethods(PendingLibraryImpactedMethod pending) {
        // pending library methods are only supported in the H2 data store
    }

    @Override
    public void deletePendingLibraryImpactedMethods(String groupArtifact, long publishSeq) {
        // pending library methods are only supported in the H2 data store
    }

    @Override
    public void persistTestRunHistoryEntry(TestRunHistoryEntry entry) {
        // test run history is only supported in the H2 data store
    }

    @Override
    public List<TestRunHistoryEntry> readTestRunHistory() {
        return new ArrayList<>();
    }

    /**
     * Read the serialized Tia data file from disk.
     * If the file on disk doesn't exist then create a new {@link TiaData} object
     *
     * @return
     */
    private TiaData readTiaDataFromDisk(){
        TiaData tiaData;

        try {
            FileInputStream fis = new FileInputStream(dataStorePath + "/" + dataFilename);
            ObjectInputStream ois = new ObjectInputStream(fis);
            tiaData = (TiaData) ois.readObject();
            ois.close();
        } catch (FileNotFoundException e){
            log.debug(dataStorePath + "/" + dataFilename + " doesn't currently exist.");
            tiaData = new TiaData();
        } catch (ClassNotFoundException | IOException e) {
            log.error("An error occurred", e);
            throw new TiaPersistenceException(e);
        }

        return tiaData;
    }

    private String buildTiaDataFilename(){
        return dataFilenamePrefix + "-" + dataFilenameSuffix + "." + dataFilenameExt;
    }

    /**
     * Serialize the contents of the Tia data object to a file on disk.
     * Lock the file on disk to avoid concurrent writes from other JVMs.
     * The lock strategy channel.lock() will wait for if another process already has a
     * lock in place.
     *
     * @param tiaData
     * @return
     */
    private boolean writeTiaDataToDisk(final TiaData tiaData){
        boolean savedToDisk = true;
        final String fullTiaDataFilename = dataStorePath + "/" + dataFilename;

        try (FileOutputStream fileOutputStream = new FileOutputStream(fullTiaDataFilename);
             FileChannel channel = fileOutputStream.getChannel();
             FileLock lock = channel.lock()) {
            ObjectOutputStream out = new ObjectOutputStream(fileOutputStream);
            out.writeObject(tiaData);
            log.info("Serialized data is saved in " + fullTiaDataFilename);
        } catch (IOException e) {
            savedToDisk = false;
            log.error("Serialized data failed to saved to disk for " + fullTiaDataFilename);
            throw new TiaPersistenceException(e);
        }

        return savedToDisk;
    }
}
