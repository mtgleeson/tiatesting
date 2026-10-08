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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the persistence of the per-method run stats (the {@code executed_run_count} and
 * {@code triggered_run_count} columns on {@code tia_source_method}) in {@link JdbcDataStore}:
 * round trips through the full and by-id catalogue reads, and the migration that adds the columns
 * to a DB created without them. Uses a temp-directory embedded H2 database per test.
 */
class JdbcDataStoreMethodRunStatsTest {

    private static final int METHOD_ID = 101;

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
        tempDir = File.createTempFile("tia-method-run-stats-", "");
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
     * A method's executed and triggered run counts survive a catalogue write and full read.
     */
    @Test
    void catalogueRoundTripsRunCounts() {
        // given
        Map<Integer, MethodImpactTracker> methods = catalogue(12, 3);

        // when
        dataStore.persistSourceMethods(methods);
        MethodImpactTracker read = dataStore.getMethodsTracked().get(METHOD_ID);

        // then
        assertEquals(12, read.getExecutedRunCount());
        assertEquals(3, read.getTriggeredRunCount());
    }

    /**
     * The by-id catalogue read also carries the run counts.
     */
    @Test
    void methodsByIdReadCarriesRunCounts() {
        // given
        dataStore.persistSourceMethods(catalogue(12, 3));

        // when
        MethodImpactTracker read = dataStore.getMethodsTrackedForIds(
                Collections.singleton(METHOD_ID)).get(METHOD_ID);

        // then
        assertEquals(12, read.getExecutedRunCount());
        assertEquals(3, read.getTriggeredRunCount());
    }

    /**
     * A DB whose catalogue predates the run-count columns gains them on next contact: the
     * pre-existing row reads back with zero counts, and new counts can be written.
     *
     * @throws Exception if the raw JDBC column drop fails
     */
    @Test
    void migrationAddsRunCountColumns() throws Exception {
        // given - seed a method, then drop the columns to simulate a pre-migration DB. The engine
        // is kept alive for the JVM (DB_CLOSE_DELAY=-1), so the drop is visible to a fresh
        // datastore opened against the same file.
        dataStore.persistSourceMethods(catalogue(5, 5));
        try (Connection connection = DriverManager.getConnection(new H2ConnectionProvider(settings).jdbcUrl(),
                settings.getUsername(), settings.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(new H2Dialect().selectSchemaSql(BranchSchema.schemaName("test", null)));
            statement.executeUpdate("ALTER TABLE tia_source_method DROP COLUMN executed_run_count");
            statement.executeUpdate("ALTER TABLE tia_source_method DROP COLUMN triggered_run_count");
        }

        // when - a fresh datastore re-runs ensureSchema, which must re-add both columns
        JdbcDataStore migrated = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(settings),
                BranchSchema.schemaName("test", null));
        MethodImpactTracker legacyRead = migrated.getTiaData().getMethodsTracked().get(METHOD_ID);
        migrated.persistSourceMethods(catalogue(7, 2));
        MethodImpactTracker rewrittenRead = migrated.getMethodsTracked().get(METHOD_ID);
        migrated.close();

        // then
        assertEquals(0, legacyRead.getExecutedRunCount());
        assertEquals(0, legacyRead.getTriggeredRunCount());
        assertEquals(7, rewrittenRead.getExecutedRunCount());
        assertEquals(2, rewrittenRead.getTriggeredRunCount());
    }

    /**
     * Builds a single-method catalogue carrying the given run counts.
     *
     * @param executedRunCount the executed-run count to set on the method
     * @param triggeredRunCount the triggered-run count to set on the method
     * @return the catalogue keyed by method id
     */
    private Map<Integer, MethodImpactTracker> catalogue(long executedRunCount, long triggeredRunCount) {
        MethodImpactTracker tracker = new MethodImpactTracker("com/example/Car.drive.()V", 30, 32);
        tracker.setExecutedRunCount(executedRunCount);
        tracker.setTriggeredRunCount(triggeredRunCount);
        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        methods.put(METHOD_ID, tracker);
        return methods;
    }
}
