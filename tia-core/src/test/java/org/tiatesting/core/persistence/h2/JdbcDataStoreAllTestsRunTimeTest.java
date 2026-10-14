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
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests persistence of the Tia-level all-tests-run stats ({@code all_tests_run_time} /
 * {@code num_all_tests_runs}) on the {@code tia_core} table in {@link JdbcDataStore}: a round-trip
 * through {@code persistCoreData} / {@code getTiaCore}. Uses a temp-directory embedded H2
 * database per test.
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
        settings = H2ConnectionSettings.embedded(tempDir.getAbsolutePath());
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(settings), BranchSchema.schemaName("test", null));
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
        dataStore.getTiaData(); // bootstrap schema

        // when
        dataStore.persistCoreData(coreData(1234L, 2L));
        TiaData loaded = dataStore.getTiaCore();

        // then
        assertEquals(1234L, loaded.getTestStats().getAllTestsRunTime());
        assertEquals(2L, loaded.getTestStats().getNumAllTestsRuns());
    }
}
