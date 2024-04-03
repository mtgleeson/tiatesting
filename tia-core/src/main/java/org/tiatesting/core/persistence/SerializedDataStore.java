package org.tiatesting.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
    public void deleteTestSuites(Set<String> testSuites) {
        // do nothing, the deleted test suites will be serialized in persistTestSuites(testSuites);
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
