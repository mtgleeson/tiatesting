package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the catalogue write logs a one-line summary at DEBUG rather than the whole
 * multi-row {@code INSERT}, which grows with the catalogue (megabytes at tens of thousands of
 * methods). slf4j-simple, the test runtime, fixes each logger's level when the logger is created
 * and the test JVM runs at INFO, so the test raises {@link MappingWriter}'s logger to DEBUG by
 * reflection for its duration. The logger is looked up reflectively too, since slf4j-api is
 * compile-only in this module; slf4j-simple caches loggers by name, so this is the same instance
 * the writer's own {@code log} field holds. Output is captured from {@code System.err}, where
 * slf4j-simple writes.
 */
class MappingWriterSourceMethodLogTest {

    /** slf4j-simple's numeric DEBUG level ({@code LocationAwareLogger.DEBUG_INT}). */
    private static final int DEBUG_LEVEL = 10;

    private JdbcDataStore dataStore;
    private File tempDir;
    private Field levelField;
    private Object mappingWriterLogger;
    private int originalLevel;

    /**
     * Create a fresh embedded H2 database and raise {@link MappingWriter}'s logger to DEBUG.
     *
     * @throws Exception if the temp directory cannot be created or the level cannot be set
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-source-method-log-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
        dataStore.getTiaData();

        mappingWriterLogger = Class.forName("org.slf4j.LoggerFactory")
                .getMethod("getLogger", Class.class).invoke(null, MappingWriter.class);
        levelField = mappingWriterLogger.getClass().getDeclaredField("currentLogLevel");
        levelField.setAccessible(true);
        originalLevel = levelField.getInt(mappingWriterLogger);
        levelField.setInt(mappingWriterLogger, DEBUG_LEVEL);
    }

    /**
     * Restore the logger's level, close the data store and remove the temp database files.
     *
     * @throws Exception if the level cannot be restored
     */
    @AfterEach
    void tearDown() throws Exception {
        levelField.setInt(mappingWriterLogger, originalLevel);
        dataStore.close();
        File[] files = tempDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        tempDir.delete();
    }

    /**
     * Writing a three-method catalogue logs the method count and the table, and never the
     * {@code INSERT} statement or the method names inlined into it.
     *
     * @throws Exception if the captured output cannot be decoded
     */
    @Test
    void catalogueWriteLogsACountRatherThanTheInsertStatement() throws Exception {
        // given
        Map<Integer, MethodImpactTracker> catalogue = new HashMap<>();
        catalogue.put(1, new MethodImpactTracker("com/example/Car.drive.()V", 10, 12));
        catalogue.put(2, new MethodImpactTracker("com/example/Car.park.()V", 20, 22));
        catalogue.put(3, new MethodImpactTracker("com/example/Car.stop.()V", 30, 32));
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8.name()));

        // when
        try {
            dataStore.persistSourceMethods(catalogue);
        } finally {
            System.setErr(originalErr);
        }

        // then
        String log = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(log.contains("Persisting 3 indexed source method(s) to tia_source_method"),
                "expected the catalogue-write summary line, got: " + log);
        assertTrue(log.contains("Clearing indexed source methods: "),
                "the short clear-out line names the dialect's statement and stays, got: " + log);
        assertFalse(log.contains("INSERT INTO"), "the INSERT statement must not be logged, got: " + log);
        assertFalse(log.contains("Car.drive"), "method names must not be logged, got: " + log);
        assertEquals(3, dataStore.getMethodsTracked().size());
    }
}
