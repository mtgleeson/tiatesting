package org.tiatesting.core.persistence.h2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests persistence of the Tia-level all-tests-run stats ({@code all_tests_run_time} /
 * {@code num_all_tests_runs}) on the {@code tia_core} table in {@link H2DataStore}: a round-trip
 * through {@code persistCoreData} / {@code getTiaCore}, and the migration that adds the columns to
 * a DB created without them (old rows read back as 0). Uses a temp-directory embedded H2 database
 * per test.
 */
class H2DataStoreAllTestsRunTimeTest {

    private H2DataStore dataStore;
    private H2ConnectionSettings settings;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-test-", "");
        tempDir.delete();
        tempDir.mkdirs();
        settings = H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test");
        dataStore = new H2DataStore(settings);
    }

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
     * Build a core {@link TiaData} row with the given all-tests stats and the minimum fields the
     * insert needs (commit value, branch, last-updated).
     */
    private TiaData coreData(long allTestsRunTime, long numAllTestsRuns){
        TiaData tiaData = new TiaData();
        tiaData.setCommitValue("commit-1");
        tiaData.setBranch("main");
        tiaData.setLastUpdated(Instant.now());
        tiaData.getTestStats().setNumRuns(3);
        tiaData.getTestStats().setAllTestsRunTime(allTestsRunTime);
        tiaData.getTestStats().setNumAllTestsRuns(numAllTestsRuns);
        return tiaData;
    }

    /**
     * The all-tests-run stats persisted on the core row read back unchanged.
     */
    @Test
    void persistAndLoad_allTestsRunStats_roundTrips() {
        // given
        dataStore.getTiaData(true); // bootstrap schema

        // when
        dataStore.persistCoreData(coreData(1234L, 2L));
        TiaData loaded = dataStore.getTiaCore();

        // then
        assertEquals(1234L, loaded.getTestStats().getAllTestsRunTime());
        assertEquals(2L, loaded.getTestStats().getNumAllTestsRuns());
    }

    /**
     * A {@code tia_core} table predating the all-tests columns gains them via migration on next
     * contact, and the pre-existing row reads back with both values 0.
     */
    @Test
    void migration_addsAllTestsColumns_oldRowReadsZero() throws Exception {
        // given - seed a core row, then drop the columns to simulate a pre-migration DB. The
        // engine stays alive for the JVM (DB_CLOSE_DELAY=-1), so the drop is visible to a fresh
        // datastore against the same file.
        dataStore.getTiaData(true);
        dataStore.persistCoreData(coreData(999L, 5L));

        try (Connection connection = DriverManager.getConnection(dataStore.getJdbcUrl(),
                settings.getUsername(), settings.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE tia_core DROP COLUMN all_tests_run_time");
            statement.executeUpdate("ALTER TABLE tia_core DROP COLUMN num_all_tests_runs");
        }

        // when - a fresh datastore re-runs ensureSchema (via getTiaCore), which must re-add them
        H2DataStore migrated = new H2DataStore(settings);
        TiaData loaded = migrated.getTiaCore();
        migrated.close();

        // then
        assertEquals(0L, loaded.getTestStats().getAllTestsRunTime());
        assertEquals(0L, loaded.getTestStats().getNumAllTestsRuns());
    }
}
