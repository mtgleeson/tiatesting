package org.tiatesting.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;

/**
 * Serializes and deserializes a {@link LibraryImpactDrainResult} using Java object serialization,
 * in two interchangeable forms that share one encoding: to and from a file, and to and from a byte
 * array.
 *
 * <p>The file form passes the drain result from the plugin JVM (where test selection occurs) to
 * the forked test JVM (where the listener persists the result after the test run). The byte-array
 * form is what a distributed run plan stores in {@code tia_distributed_run.drain_result}, so the
 * drain the plan already performed survives the plan process exiting - the drain deletes pending
 * rows and advances sequences and cannot be repeated, so nothing later could reconstruct it. Both
 * forms go through {@link #toBytes} and {@link #fromBytes} so the two transports can never encode
 * a drain result differently.
 */
public class LibraryImpactDrainResultSerializer {

    private static final Logger log = LoggerFactory.getLogger(LibraryImpactDrainResultSerializer.class);

    /**
     * Serialize the drain result to the specified file. Failures are logged rather than thrown,
     * since this is the best-effort plugin-to-fork handoff and a failure costs a repeated drain on
     * the next run rather than a wrong result.
     *
     * @param drainResult the drain result to serialize.
     * @param file the target file.
     */
    public static void serialize(LibraryImpactDrainResult drainResult, File file) {
        file.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(toBytes(drainResult));
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

        try {
            return fromBytes(Files.readAllBytes(file.toPath()));
        } catch (IOException | ClassNotFoundException e) {
            log.warn("Failed to deserialize LibraryImpactDrainResult from {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * Serialize the drain result to a byte array, the form a distributed run plan stores in its run
     * row. Unlike {@link #serialize}, a failure is thrown rather than logged: the caller storing the
     * drain result is recording cleanup that cannot be regenerated, so silently writing nothing
     * would lose it permanently.
     *
     * @param drainResult the drain result to serialize; may be {@code null}, which encodes as a
     *                    serialized null and deserializes back to {@code null}
     * @return the serialized bytes
     * @throws IOException if the drain result cannot be serialized
     */
    public static byte[] toBytes(LibraryImpactDrainResult drainResult) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bytes)) {
            oos.writeObject(drainResult);
        }
        return bytes.toByteArray();
    }

    /**
     * Deserialize a drain result from the bytes {@link #toBytes} produced, treating an absent value
     * as "nothing was drained" so a caller reading a nullable binary database column needs no guard
     * of its own.
     *
     * @param bytes the serialized drain result; {@code null} or empty when nothing was stored
     * @return the deserialized drain result, or {@code null} if {@code bytes} is null or empty
     * @throws IOException if the bytes are not a readable object stream
     * @throws ClassNotFoundException if the serialized classes are not on the classpath
     */
    public static LibraryImpactDrainResult fromBytes(byte[] bytes) throws IOException, ClassNotFoundException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (LibraryImpactDrainResult) ois.readObject();
        }
    }
}
