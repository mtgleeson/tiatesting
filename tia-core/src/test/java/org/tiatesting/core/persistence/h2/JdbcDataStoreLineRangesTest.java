package org.tiatesting.core.persistence.h2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests the persistence of a method's exact line ranges (the nullable {@code line_ranges} column)
 * in {@link JdbcDataStore}: round trips through the method catalogue and the distributed-run
 * staging table, and the migration that adds the column to a DB created without it. Uses a
 * temp-directory embedded H2 database per test.
 */
class JdbcDataStoreLineRangesTest {

    private static final int CONSTRUCTOR_ID = 101;
    private static final int PLAIN_METHOD_ID = 102;
    private static final int[] CONSTRUCTOR_RANGES = {7, 16, 20, 21, 25, 25, 74, 75};

    private JdbcDataStore dataStore;
    private H2ConnectionSettings settings;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database in a new temp directory and bootstrap its schema.
     *
     * @throws Exception if the temp directory cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-line-ranges-", "");
        tempDir.delete();
        tempDir.mkdirs();
        settings = H2ConnectionSettings.embedded(tempDir.getAbsolutePath());
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(settings),
                BranchSchema.schemaName("test", null));
        dataStore.getTiaData();
    }

    /**
     * Close the data store and remove the temp database files.
     */
    @AfterEach
    void tearDown() {
        dataStore.close();
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * A constructor's line ranges survive a catalogue write and full read, while a plain method's
     * absent ranges read back as null.
     */
    @Test
    void catalogueRoundTripsLineRanges() {
        // given
        Map<Integer, MethodImpactTracker> methods = catalogue();

        // when
        dataStore.persistSourceMethods(methods);
        Map<Integer, MethodImpactTracker> read = dataStore.getMethodsTracked();

        // then
        assertArrayEquals(CONSTRUCTOR_RANGES, read.get(CONSTRUCTOR_ID).getLineRanges());
        assertNull(read.get(PLAIN_METHOD_ID).getLineRanges());
    }

    /**
     * The by-id catalogue read also carries the line ranges.
     */
    @Test
    void methodsByIdReadCarriesLineRanges() {
        // given
        dataStore.persistSourceMethods(catalogue());

        // when
        Map<Integer, MethodImpactTracker> read = dataStore.getMethodsTrackedForIds(
                new HashSet<>(Arrays.asList(CONSTRUCTOR_ID, PLAIN_METHOD_ID)));

        // then
        assertArrayEquals(CONSTRUCTOR_RANGES, read.get(CONSTRUCTOR_ID).getLineRanges());
        assertNull(read.get(PLAIN_METHOD_ID).getLineRanges());
    }

    /**
     * A distributed runner's staged constructor keeps its line ranges when the sealer reads the
     * stage back.
     */
    @Test
    void stagingTableRoundTripsLineRanges() {
        // given
        Map<Integer, MethodImpactTracker> staged = catalogue();

        // when
        dataStore.persistStagedMethodTrackers("run-1", staged, Collections.emptySet());
        Map<Integer, MethodImpactTracker> read = dataStore.readStagedMethodTrackers("run-1");

        // then
        assertArrayEquals(CONSTRUCTOR_RANGES, read.get(CONSTRUCTOR_ID).getLineRanges());
        assertNull(read.get(PLAIN_METHOD_ID).getLineRanges());
    }

    /**
     * A DB whose catalogue and staging tables predate the {@code line_ranges} column gains it on
     * next contact: the pre-existing row reads back with no ranges, and new ranges can be written.
     *
     * @throws Exception if the raw JDBC column drop fails
     */
    @Test
    void migrationAddsLineRangesColumns() throws Exception {
        // given - seed a method, then drop the columns to simulate a pre-migration DB. The engine
        // is kept alive for the JVM (DB_CLOSE_DELAY=-1), so the drop is visible to a fresh
        // datastore opened against the same file.
        Map<Integer, MethodImpactTracker> legacy = new HashMap<>();
        legacy.put(PLAIN_METHOD_ID, new MethodImpactTracker("com/example/Car.drive.()V", 30, 32));
        dataStore.persistSourceMethods(legacy);
        try (Connection connection = DriverManager.getConnection(new H2ConnectionProvider(settings).jdbcUrl(),
                settings.getUsername(), settings.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(new H2Dialect().selectSchemaSql(BranchSchema.schemaName("test", null)));
            statement.executeUpdate("ALTER TABLE tia_source_method DROP COLUMN line_ranges");
            statement.executeUpdate("ALTER TABLE tia_distributed_run_method_stage DROP COLUMN line_ranges");
        }

        // when - a fresh datastore re-runs ensureSchema, which must re-add both columns
        JdbcDataStore migrated = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(settings),
                BranchSchema.schemaName("test", null));
        Map<Integer, MethodImpactTracker> legacyRead = migrated.getTiaData().getMethodsTracked();
        migrated.persistStagedMethodTrackers("run-1", catalogue(), Collections.emptySet());
        Map<Integer, MethodImpactTracker> stagedRead = migrated.readStagedMethodTrackers("run-1");
        migrated.close();

        // then
        assertEquals(30, legacyRead.get(PLAIN_METHOD_ID).getLineNumberStart());
        assertNull(legacyRead.get(PLAIN_METHOD_ID).getLineRanges());
        assertArrayEquals(CONSTRUCTOR_RANGES, stagedRead.get(CONSTRUCTOR_ID).getLineRanges());
    }

    /**
     * Builds a small method catalogue: a constructor split by a late field (with line ranges) and a
     * plain method (without).
     *
     * @return the catalogue keyed by method id
     */
    private Map<Integer, MethodImpactTracker> catalogue() {
        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        methods.put(CONSTRUCTOR_ID, new MethodImpactTracker("com/example/Car.<init>.()V", 8, 74, CONSTRUCTOR_RANGES));
        methods.put(PLAIN_METHOD_ID, new MethodImpactTracker("com/example/Car.drive.()V", 30, 32));
        return methods;
    }
}
