package org.tiatesting.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

/**
 * Serializes and deserializes a {@link LibraryImpactDrainResult} to/from a file using
 * Java object serialization. Used to pass the drain result from the plugin JVM (where
 * test selection occurs) to the forked test JVM (where the listener persists the result
 * after the test run).
 */
public class LibraryImpactDrainResultSerializer {

    private static final Logger log = LoggerFactory.getLogger(LibraryImpactDrainResultSerializer.class);

    /**
     * Serialize the drain result to the specified file.
     *
     * @param drainResult the drain result to serialize.
     * @param file the target file.
     */
    public static void serialize(LibraryImpactDrainResult drainResult, File file) {
        file.getParentFile().mkdirs();
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
            oos.writeObject(drainResult);
        } catch (IOException e) {
            log.warn("Failed to serialize LibraryImpactDrainResult to {}: {}", file.getAbsolutePath(), e.getMessage());
        }
    }

    /**
     * Deserialize the drain result from the specified file path.
     *
     * @param filePath the path to the serialized drain result file.
     * @return the deserialized drain result, or {@code null} if the file cannot be read.
     */
    public static LibraryImpactDrainResult deserialize(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return null;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            return null;
        }

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            return (LibraryImpactDrainResult) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            log.warn("Failed to deserialize LibraryImpactDrainResult from {}: {}", filePath, e.getMessage());
            return null;
        }
    }
}
