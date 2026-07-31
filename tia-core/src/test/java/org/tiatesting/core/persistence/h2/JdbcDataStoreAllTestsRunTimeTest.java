package org.tiatesting.core.persistence.h2;

import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;

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
 * {@code num_all_tests_runs}) on the {@code tia_core} table in {@link JdbcDataStore}: a round-trip
 * through {@code persistCoreData} / {@code getTiaCore}, and the migration that adds the columns to
 * a DB created without them (old rows read back as 0). Uses a temp-directory embedded H2 database
 * per test.
 */
class JdbcDataStoreAllTestsRunTimeTest {

    private JdbcDataStore dataStore;
    private H2ConnectionSettings settings;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-test-", "");
        tempDir.delete();
        tempDir.mkdirs();
        settings = H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test");
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(settings), BranchSchema.schemaName("test"));
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

        try (Connection connection = DriverManager.getConnection(new H2ConnectionProvider(settings).jdbcUrl(),
                settings.getUsername(), settings.getPassword());
             Statement statement = connection.createStatement()) {
            // a raw JDBC connection defaults to the vendor's default schema, not the branch schema
            // JdbcDataStore selects on its own connections - select it explicitly before altering
            // the unqualified table name.
            statement.execute(new H2Dialect().selectSchemaSql(BranchSchema.schemaName("test")));
            statement.executeUpdate("ALTER TABLE tia_core DROP COLUMN all_tests_run_time");
            statement.executeUpdate("ALTER TABLE tia_core DROP COLUMN num_all_tests_runs");
        }

        // when - a fresh datastore re-runs ensureSchema (via getTiaCore), which must re-add them
        JdbcDataStore migrated = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(settings), BranchSchema.schemaName("test"));
        TiaData loaded = migrated.getTiaCore();
        migrated.close();

        // then
        assertEquals(0L, loaded.getTestStats().getAllTestsRunTime());
        assertEquals(0L, loaded.getTestStats().getNumAllTestsRuns());
    }
}
