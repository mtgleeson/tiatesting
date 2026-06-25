package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;

import java.io.File;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link TestRunnerService#persistTestRunData} freezes each run's savings onto its
 * history row at persist time: a partial run records {@code timeSavingsMs = baseline - duration}
 * (and a matching percent) against the all-tests baseline then current, while an all-tests run
 * records zero. Freezing is required because the baseline is a rolling average that changes later.
 */
class TestRunnerServiceHistorySavingsTest {

    // Large baseline so any real wall-clock duration leaves a positive saving.
    private static final long BASELINE_MS = 10_000_000L;

    private H2DataStore dataStore;
    private TestRunnerService service;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-savings-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
        dataStore.getTiaData(true);
        service = new TestRunnerService(dataStore);

        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("initial");
        tiaData.setLastUpdated(Instant.now());
        tiaData.getTestStats().setAllTestsRunTime(BASELINE_MS); // established full-suite baseline
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
     * Build a history-only {@link TestRunResult} with the given selector ignore count.
     */
    private TestRunResult runResult(int ignoredTestSuiteCount){
        TestStats runStats = new TestStats();
        runStats.setNumRuns(1);
        runStats.setAvgRunTime(100L);
        runStats.setNumSuccessRuns(1);
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        Set<String> empty = new HashSet<>();
        return new TestRunResult(trackers, empty, empty, empty, new HashMap<>(), runStats, null,
                ignoredTestSuiteCount, 1);
    }

    /**
     * A partial run (some suites ignored) freezes savings = baseline - this run's duration, with a
     * matching percent.
     */
    @Test
    void partialRun_freezesSavingsAgainstBaseline() {
        // given a partial run started just now
        long runStart = System.currentTimeMillis();

        // when
        service.persistTestRunData(false, true, true, "abc123", "main", runStart, runResult(2));
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();

        // then
        assertEquals(1, history.size());
        TestRunHistoryEntry row = history.get(0);
        long expectedSavings = BASELINE_MS - row.getDurationMs();
        assertEquals(expectedSavings, row.getTimeSavingsMs());
        assertTrue(row.getTimeSavingsMs() > 0, "partial run should record a positive saving");
        assertEquals((int) Math.round((double) expectedSavings / BASELINE_MS * 100), row.getSavingsPercent());
    }

    /**
     * An all-tests run (zero ignored) saved nothing, so its row records zero savings.
     */
    @Test
    void allTestsRun_recordsZeroSavings() {
        // given an all-tests run
        long runStart = System.currentTimeMillis();

        // when
        service.persistTestRunData(false, true, true, "abc123", "main", runStart, runResult(0));
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();

        // then
        assertEquals(1, history.size());
        assertEquals(0L, history.get(0).getTimeSavingsMs());
        assertEquals(0, history.get(0).getSavingsPercent());
    }
}
