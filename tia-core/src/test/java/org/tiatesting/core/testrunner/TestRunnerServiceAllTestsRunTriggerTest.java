package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;

import java.io.File;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link TestRunnerService#persistTestRunData} routes a run's duration into the Tia-level
 * all-tests-run average or the selected-run average based on
 * {@link TestRunResult#getIgnoredTestSuiteCount()}: zero ignored suites is an all-tests run, any
 * ignored suite is a selected run. Persists through an embedded H2 DB and reads the core row back.
 */
class TestRunnerServiceAllTestsRunTriggerTest {

    private H2DataStore dataStore;
    private TestRunnerService service;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-alltests-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
        dataStore.getTiaData(true);
        service = new TestRunnerService(dataStore);

        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("initial");
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);
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
     * Build a stats-only {@link TestRunResult} carrying a single run of the given duration and the
     * given selector ignore count. No suites need to have run for the Tia-level stats path.
     */
    private TestRunResult runResult(long durationMs, int ignoredTestSuiteCount){
        TestStats runStats = new TestStats();
        runStats.setNumRuns(1);
        runStats.setAvgRunTime(durationMs);
        runStats.setNumSuccessRuns(1);

        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        Set<String> empty = new HashSet<>();
        return new TestRunResult(trackers, empty, empty, empty, new HashMap<>(), runStats, null,
                ignoredTestSuiteCount, 0);
    }

    /**
     * Zero ignored suites is an all-tests run: the duration folds into allTestsRunTime and bumps
     * numAllTestsRuns, leaving the selected-run average untouched.
     */
    @Test
    void persist_zeroIgnored_updatesAllTestsRunAverage() {
        // given
        TestRunResult result = runResult(500L, 0);

        // when - stats-only persist (updateDBStats=true)
        service.persistTestRunData(false, true, false, "abc123", "main", System.currentTimeMillis(), result);
        TiaData loaded = dataStore.getTiaCore();

        // then
        assertEquals(500L, loaded.getTestStats().getAllTestsRunTime());
        assertEquals(1L, loaded.getTestStats().getNumAllTestsRuns());
        assertEquals(0L, loaded.getTestStats().getAvgRunTime());
    }

    /**
     * A non-zero ignored count is a selected run: the duration folds into avgRunTime, leaving the
     * all-tests-run stats untouched.
     */
    @Test
    void persist_someIgnored_updatesSelectedRunAverage() {
        // given
        TestRunResult result = runResult(500L, 3);

        // when
        service.persistTestRunData(false, true, false, "abc123", "main", System.currentTimeMillis(), result);
        TiaData loaded = dataStore.getTiaCore();

        // then
        assertEquals(500L, loaded.getTestStats().getAvgRunTime());
        assertEquals(0L, loaded.getTestStats().getAllTestsRunTime());
        assertEquals(0L, loaded.getTestStats().getNumAllTestsRuns());
    }
}
