package org.tiatesting.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.coverage.ClassImpactTracker;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final PersistenceStrategy persistenceStrategy;

    public MapDataStore(String dataStorePath, String mappingFilenameSuffix){
        this(dataStorePath, mappingFilenameSuffix, PersistenceStrategy.getDefaultPersistenceStrategy().name());
    }

    public MapDataStore(String dataStorePath, String mappingFilenameSuffix, String dbPersistenceStrategy){
        this.dataStorePath = dataStorePath;
        this.mappingFilenameSuffix = mappingFilenameSuffix;
        this.mappingFilename = buildMappingFilename();
        this.persistenceStrategy = getPersistenceStrategy(dbPersistenceStrategy);
    }

    @Override
    public StoredMapping getStoredMapping() {
        return readTestMappingFromDisk();
    }

    @Override
    public PersistenceStrategy getDBPersistenceStrategy(){
        return this.persistenceStrategy;
    }

    @Override
    public boolean persistTestMapping(Map<String, List<ClassImpactTracker>> testMethodsCalled, String commitValue) {
        long startTime = System.currentTimeMillis();

        // always get the latest test mapping before updating in case another process has updated the file since it was last read.
        StoredMapping storedMapping = readTestMappingFromDisk();
        log.info("Persisting commit value: " + commitValue);
        storedMapping.setCommitValue(commitValue);

        Map<String, List<ClassImpactTracker>> testMethodsCalledOnDisk = storedMapping.getClassesImpacted();
        Map<String, List<ClassImpactTracker>> mergedTestMappings = mergeTestMappingMaps(testMethodsCalledOnDisk, testMethodsCalled);
        storedMapping.setClassesImpacted(mergedTestMappings);

        //mergedTestMappings.forEach( (testClass, methodsCalled) ->
        //        log.debug(methodsCalled.stream().map(String::valueOf).collect(Collectors.joining("\n", testClass+":\n", ""))));

        boolean savedToDisk = writeTestMappingToDisk(storedMapping);
        log.debug("Time to save the mapping to disk (ms): " + (System.currentTimeMillis() - startTime));
        return savedToDisk;
    }

    /**
     * Find the persistence strategy based on the given configuration value.
     * If none is provided, or it doesn't match a valid value, use the default persistence strategy.
     *
     * @param dbPersistenceStrategy
     * @return
     */
    private PersistenceStrategy getPersistenceStrategy(String dbPersistenceStrategy){
        PersistenceStrategy persistenceStrategy = PersistenceStrategy.getDefaultPersistenceStrategy();

        if (dbPersistenceStrategy != null && dbPersistenceStrategy.length() > 0){
            try {
                persistenceStrategy = PersistenceStrategy.valueOf(dbPersistenceStrategy);
            } catch (IllegalArgumentException ex) {
                log.warn("Invalid persistence strategy provided: " + dbPersistenceStrategy + ". Defaulting to: " + persistenceStrategy);
            }
        }

        return persistenceStrategy;
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
     * Update the stored test mappings based on the results from the current test run.
     * For each test suite, set the methods impacted. If the test suite has an existing method mapping
     * then update it to use the new test mapping
     *
     *
     * @param oldTestMappings
     * @param newTestMappings
     * @return mergedTestMappings
     */
    private Map<String, List<ClassImpactTracker>> mergeTestMappingMaps(Map<String, List<ClassImpactTracker>> oldTestMappings,
                                                          Map<String, List<ClassImpactTracker>> newTestMappings){
        Map<String, List<ClassImpactTracker>> mergedTestMappings = new HashMap<>(oldTestMappings);
        newTestMappings.forEach((key, value) -> mergedTestMappings.merge(key, value, (v1, v2) -> v2));
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
    private boolean writeTestMappingToDisk(StoredMapping storedMapping){
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
