package org.tiatesting.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.LibraryPublish;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.PendingLibraryForcedSelection;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestStats;
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

    /**
     * {@inheritDoc}
     *
     * <p>The serialized store writes the whole object graph in one go, so "only the stats columns"
     * is expressed by re-reading the file and setting the incoming stats onto that copy rather than
     * writing back the caller's snapshot. Everything else on the core data - the commit value, the
     * branch, the last-updated timestamp - therefore reaches disk exactly as it was stored.
     *
     * <p>A store with no commit value has never had a mapping run, so as with the JDBC stores there
     * is nothing to attach the stats to and the file is left alone.
     *
     * @param testStats the Tia-level run stats to write onto the core data
     */
    @Override
    public void persistCoreStats(final TestStats testStats) {
        TiaData tiaDataOnDisk = getTiaData(true);

        if (tiaDataOnDisk.getCommitValue() == null){
            log.debug("No Tia core data exists yet, so the run stats were not stored. The first run "
                    + "that updates the mapping DB will create it.");
            return;
        }

        tiaDataOnDisk.setTestStats(testStats);
        long startTime = System.currentTimeMillis();
        writeTiaDataToDisk(tiaDataOnDisk);
        log.debug("Time to save the Tia core stats to disk (ms): " + (System.currentTimeMillis() - startTime));
    }

    @Override
    public void persistTestSuitesFailed(Set<String> testSuitesFailed) {
        TiaData tiaData = getTiaData(false);
        tiaData.setTestSuitesFailed(testSuitesFailed);
        long startTime = System.currentTimeMillis();
        writeTiaDataToDisk(tiaData);
        log.info("Time to save the failed test suites data to disk (ms): " + (System.currentTimeMillis() - startTime));
    }

    /**
     * Clear the unsealed flag from every tracked test suite and write the whole file back to
     * disk. The serialized store has no per-row update, so clearing the flag on the in-memory
     * suites and rewriting the file is the equivalent of the JDBC stores' targeted
     * {@code UPDATE ... WHERE unsealed = TRUE}.
     */
    @Override
    public void clearUnsealedTestSuites() {
        TiaData tiaData = getTiaData(false);
        for (TestSuiteTracker testSuiteTracker : tiaData.getTestSuitesTracked().values()) {
            testSuiteTracker.setUnsealed(false);
        }
        long startTime = System.currentTimeMillis();
        writeTiaDataToDisk(tiaData);
        log.debug("Time to clear the unsealed test suite flags on disk (ms): " + (System.currentTimeMillis() - startTime));
    }

    @Override
    public void persistSourceMethods(Map<Integer, MethodImpactTracker> methodsTracked) {
        TiaData tiaData = getTiaData(false);
        tiaData.setMethodsTracked(methodsTracked);
        long startTime = System.currentTimeMillis();
        writeTiaDataToDisk(tiaData);
        log.info("Time to save the methods tracked data to disk (ms): " + (System.currentTimeMillis() - startTime));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The serialized store has no transaction to join, so it cannot make several writes land
     * together the way the JDBC-backed stores do. Instead it sets the method catalogue directly
     * onto the seal's core data and clears the unsealed flag before its one closing
     * {@code writeTiaDataToDisk} call (via {@link #persistCoreData}) - the library drain deletes
     * and upserts below are no-ops on this store (tracked libraries are only supported in the H2
     * data store), so the catalogue, the cleared flags and the commit value are the only state
     * that actually needs to reach disk. {@link #clearUnsealedTestSuites()} and
     * {@link #persistCoreData} both write the same cached {@link TiaData} instance, so the
     * intermediate write it performs is redundant but not incorrect - the final write carries
     * every mutation regardless of which call put it there first.
     *
     * @param sealedRunData the complete seal payload
     */
    @Override
    public void persistSealedRunData(final SealedRunData sealedRunData) {
        for (LibraryImpactDrainResult.DrainedBatchKey key : sealedRunData.getDrainedMethodBatchKeys()) {
            deletePendingLibraryImpactedMethods(key.getGroupArtifact(), key.getPublishSeq());
        }
        for (LibraryImpactDrainResult.DrainedBatchKey key : sealedRunData.getDrainedForcedBatchKeys()) {
            deletePendingLibraryForcedSelections(key.getGroupArtifact(), key.getPublishSeq());
        }
        for (TrackedLibrary library : sealedRunData.getLibrariesToPersist()) {
            persistTrackedLibrary(library);
        }

        TiaData tiaData = sealedRunData.getTiaData();
        tiaData.setMethodsTracked(sealedRunData.getMethodsTracked());
        clearUnsealedTestSuites();
        persistCoreData(tiaData);
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

    /**
     * No-op: the library publish ledger, its impacted-method stamps and its forced-selection
     * batches are only supported by the JDBC-backed data stores (H2 / Postgres).
     *
     * @param publish ignored.
     * @param impactedMethodIds ignored.
     * @param forcedSelections ignored.
     * @return {@code 0} - no sequence is ever assigned by this store.
     */
    @Override
    public long persistLibraryPublish(LibraryPublish publish, Set<Integer> impactedMethodIds,
                                      List<PendingLibraryForcedSelection> forcedSelections) {
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

    /**
     * No-op: forced-selection batches are only supported by the JDBC-backed data stores.
     *
     * @return an empty list.
     */
    @Override
    public List<PendingLibraryForcedSelection> readAllPendingLibraryForcedSelections() {
        // forced-selection batches are only supported in the H2 data store
        return new ArrayList<>();
    }

    /**
     * No-op: forced-selection batches are only supported by the JDBC-backed data stores.
     *
     * @param groupArtifact ignored.
     * @return an empty list.
     */
    @Override
    public List<PendingLibraryForcedSelection> readPendingLibraryForcedSelections(String groupArtifact) {
        // forced-selection batches are only supported in the H2 data store
        return new ArrayList<>();
    }

    /**
     * No-op: forced-selection batches are only supported by the JDBC-backed data stores.
     *
     * @param groupArtifact ignored.
     * @param publishSeq ignored.
     */
    @Override
    public void deletePendingLibraryForcedSelections(String groupArtifact, long publishSeq) {
        // forced-selection batches are only supported in the H2 data store
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
     * Unsupported: distributed runs coordinate through a shared database, which the serialized
     * file-backed store is not.
     *
     * @param plan ignored
     * @throws UnsupportedOperationException always
     */
    @Override
    public void persistDistributedRunPlan(DistributedRunPlan plan) {
        throw new UnsupportedOperationException(
                "Distributed test runs require a shared database (server-mode H2 or Postgres)");
    }

    /**
     * Unsupported: distributed runs coordinate through a shared database, which the serialized
     * file-backed store is not.
     *
     * @param runId ignored
     * @return never returns
     * @throws UnsupportedOperationException always
     */
    @Override
    public DistributedRun readDistributedRun(String runId) {
        throw new UnsupportedOperationException(
                "Distributed test runs require a shared database (server-mode H2 or Postgres)");
    }

    /**
     * Unsupported: distributed runs coordinate through a shared database, which the serialized
     * file-backed store is not.
     *
     * @param runId ignored
     * @return never returns
     * @throws UnsupportedOperationException always
     */
    @Override
    public LibraryImpactDrainResult readDistributedRunDrainResult(String runId) {
        throw new UnsupportedOperationException(
                "Distributed test runs require a shared database (server-mode H2 or Postgres)");
    }

    /**
     * Unsupported: distributed runs coordinate through a shared database, which the serialized
     * file-backed store is not.
     *
     * @param runId ignored
     * @return never returns
     * @throws UnsupportedOperationException always
     */
    @Override
    public List<DistributedRunGroup> readDistributedRunGroups(String runId) {
        throw new UnsupportedOperationException(
                "Distributed test runs require a shared database (server-mode H2 or Postgres)");
    }

    /**
     * Unsupported: distributed runs coordinate through a shared database, which the serialized
     * file-backed store is not.
     *
     * @param runId ignored
     * @param groupNumber ignored
     * @return never returns
     * @throws UnsupportedOperationException always
     */
    @Override
    public List<String> readDistributedRunGroupSuites(String runId, int groupNumber) {
        throw new UnsupportedOperationException(
                "Distributed test runs require a shared database (server-mode H2 or Postgres)");
    }

    /**
     * Unsupported: distributed runs coordinate through a shared database, which the serialized
     * file-backed store is not.
     *
     * @return never returns
     * @throws UnsupportedOperationException always
     */
    @Override
    public List<DistributedRun> readAllDistributedRuns() {
        throw new UnsupportedOperationException(
                "Distributed test runs require a shared database (server-mode H2 or Postgres)");
    }

    /**
     * Unsupported: distributed runs coordinate through a shared database, which the serialized
     * file-backed store is not.
     *
     * @param runId ignored
     * @param runnerKey ignored
     * @param claimedAtMs ignored
     * @return never returns
     * @throws UnsupportedOperationException always
     */
    @Override
    public DistributedRunGroup claimNextPendingGroup(String runId, String runnerKey, long claimedAtMs) {
        throw new UnsupportedOperationException(
                "Distributed test runs require a shared database (server-mode H2 or Postgres)");
    }

    /**
     * Unsupported: distributed runs coordinate through a shared database, which the serialized
     * file-backed store is not.
     *
     * @param runId ignored
     * @param groupNumber ignored
     * @param runnerKey ignored
     * @param actualDurationMs ignored
     * @param suitesRan ignored
     * @param suitesFailed ignored
     * @param suitesObserved ignored
     * @param suitesDurationMs ignored
     * @return never returns
     * @throws UnsupportedOperationException always
     */
    @Override
    public boolean reportGroupProgress(String runId, int groupNumber, String runnerKey,
                                       long actualDurationMs, int suitesRan, int suitesFailed,
                                       int suitesObserved, long suitesDurationMs) {
        throw new UnsupportedOperationException(
                "Distributed test runs require a shared database (server-mode H2 or Postgres)");
    }

    /**
     * Unsupported: distributed runs coordinate through a shared database, which the serialized
     * file-backed store is not.
     *
     * @param runId ignored
     * @param groupNumber ignored
     * @param runnerKey ignored
     * @param completedAtMs ignored
     * @return never returns
     * @throws UnsupportedOperationException always
     */
    @Override
    public DistributedRunGroup completeGroup(String runId, int groupNumber, String runnerKey,
                                             long completedAtMs) {
        throw new UnsupportedOperationException(
                "Distributed test runs require a shared database (server-mode H2 or Postgres)");
    }

    /**
     * Unsupported: distributed runs coordinate through a shared database, which the serialized
     * file-backed store is not.
     *
     * @param runId ignored
     * @param runnerKey ignored
     * @param sealedAtMs ignored
     * @return never returns
     * @throws UnsupportedOperationException always
     */
    @Override
    public boolean electSealer(String runId, String runnerKey, long sealedAtMs) {
        throw new UnsupportedOperationException(
                "Distributed test runs require a shared database (server-mode H2 or Postgres)");
    }

    /**
     * Unsupported: distributed runs coordinate through a shared database, which the serialized
     * file-backed store is not.
     *
     * @param runId ignored
     * @throws UnsupportedOperationException always
     */
    @Override
    public void markDistributedRunSealed(String runId) {
        throw new UnsupportedOperationException(
                "Distributed test runs require a shared database (server-mode H2 or Postgres)");
    }

    /**
     * Unsupported: distributed runs coordinate through a shared database, which the serialized
     * file-backed store is not.
     *
     * @param runId ignored
     * @param methodsTracked ignored
     * @throws UnsupportedOperationException always
     */
    @Override
    public void persistStagedMethodTrackers(String runId, Map<Integer, MethodImpactTracker> methodsTracked) {
        throw new UnsupportedOperationException(
                "Distributed test runs require a shared database (server-mode H2 or Postgres)");
    }

    /**
     * Unsupported: distributed runs coordinate through a shared database, which the serialized
     * file-backed store is not.
     *
     * @param runId ignored
     * @return never returns
     * @throws UnsupportedOperationException always
     */
    @Override
    public Map<Integer, MethodImpactTracker> readStagedMethodTrackers(String runId) {
        throw new UnsupportedOperationException(
                "Distributed test runs require a shared database (server-mode H2 or Postgres)");
    }

    /**
     * Unsupported: distributed runs coordinate through a shared database, which the serialized
     * file-backed store is not.
     *
     * @param runId ignored
     * @throws UnsupportedOperationException always
     */
    @Override
    public void deleteStagedMethodTrackers(String runId) {
        throw new UnsupportedOperationException(
                "Distributed test runs require a shared database (server-mode H2 or Postgres)");
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
