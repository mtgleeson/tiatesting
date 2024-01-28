package org.tiatesting.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.TiaData;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

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
    public boolean persistTiaData(final TiaData tiaData){
        long startTime = System.currentTimeMillis();
        boolean savedToDisk = writeTiaDataToDisk(tiaData);
        log.debug("Time to save the Tia data to disk (ms): " + (System.currentTimeMillis() - startTime));
        return savedToDisk;
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
            throw new RuntimeException(e);
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
            e.printStackTrace();
        }

        return savedToDisk;
    }
}
