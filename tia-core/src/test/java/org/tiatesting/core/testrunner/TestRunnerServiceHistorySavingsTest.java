package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.ConnectionProvider;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.dialect.SqlDialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private JdbcDataStore dataStore;
    private TestRunnerService service;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-savings-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())), BranchSchema.schemaName("test", null));
        dataStore.getTiaData();
        service = new TestRunnerService(dataStore);

        TiaData tiaData = dataStore.getTiaData();
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
     * Build a history-only {@link TestRunResult} for the real run, with the given selector ignore
     * count.
     *
     * @param ignoredTestSuiteCount the count of suites Tia ignored
     * @return the result
     */
    private TestRunResult runResult(int ignoredTestSuiteCount){
        return runResult(ignoredTestSuiteCount, RunAttempt.FIRST);
    }

    /**
     * Build a history-only {@link TestRunResult} with the given selector ignore count and attempt.
     *
     * @param ignoredTestSuiteCount the count of suites Tia ignored
     * @param runAttempt which attempt at the test task's run the result describes
     * @return the result
     */
    private TestRunResult runResult(int ignoredTestSuiteCount, RunAttempt runAttempt){
        TestStats runStats = new TestStats();
        runStats.setNumRuns(1);
        runStats.setAvgRunTime(100L);
        runStats.setNumSuccessRuns(1);
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        Set<String> empty = new HashSet<>();
        return new TestRunResult(trackers, empty, empty, empty, empty, new HashMap<>(), runStats, null,
                ignoredTestSuiteCount, 1, 0, TestRunSelectionDetails.empty(), runAttempt);
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
        service.persistTestRunData(false, true, "abc123", "main", runStart, runResult(2), null);
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
     * Regression for the savings clock-mismatch: the run duration is captured before Tia's persist
     * work, so even when that persist is slower than the full-suite baseline a partial run still
     * records a positive saving. Previously the duration was measured after the (potentially slow,
     * seed-sized) persist, so on a fast real suite it swamped the small baseline and savings clamped
     * to zero (showing "-" in the history).
     */
    @Test
    void partialRun_savingsExcludesTiaPersistOverhead() throws Exception {
        // given - a small baseline (like a fast real suite) and a datastore whose persist/read work
        // is deliberately slower than that baseline
        File slowDir = File.createTempFile("tia-runner-savings-slow-", "");
        slowDir.delete();
        slowDir.mkdirs();
        long smallBaseline = 200L; // ms; less than SlowPersistJdbcDataStore.PERSIST_DELAY_MS
        SlowPersistJdbcDataStore slowStore = new SlowPersistJdbcDataStore(
                new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(slowDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
        try {
            slowStore.getTiaData();
            TiaData tiaData = slowStore.getTiaData();
            tiaData.setCommitValue("initial");
            tiaData.setLastUpdated(Instant.now());
            tiaData.getTestStats().setAllTestsRunTime(smallBaseline);
            slowStore.persistCoreData(tiaData);
            TestRunnerService slowService = new TestRunnerService(slowStore);
            long runStart = System.currentTimeMillis();

            // when - a partial run whose persist takes longer than the baseline
            slowService.persistTestRunData(false, true, "abc123", "main", runStart, runResult(2), null);
            List<TestRunHistoryEntry> history = slowStore.readTestRunHistory();

            // then - the recorded duration excludes the persist delay, so savings stays positive
            assertEquals(1, history.size());
            TestRunHistoryEntry row = history.get(0);
            assertTrue(row.getDurationMs() < SlowPersistJdbcDataStore.PERSIST_DELAY_MS,
                    "duration must exclude Tia's persist overhead");
            assertTrue(row.getTimeSavingsMs() > 0, "partial run should record a positive saving");
            assertEquals(smallBaseline - row.getDurationMs(), row.getTimeSavingsMs());
        } finally {
            slowStore.close();
            for (File f : slowDir.listFiles()) {
                f.delete();
            }
            slowDir.delete();
        }
    }

    /**
     * An all-tests run (zero ignored) saved nothing, so its row records zero savings.
     */
    @Test
    void allTestsRun_recordsZeroSavings() {
        // given an all-tests run
        long runStart = System.currentTimeMillis();

        // when
        service.persistTestRunData(false, true, "abc123", "main", runStart, runResult(0), null);
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();

        // then
        assertEquals(1, history.size());
        assertEquals(0L, history.get(0).getTimeSavingsMs());
        assertEquals(0, history.get(0).getSavingsPercent());
    }

    /**
     * A rerun of failed tests - a Surefire rerun in the same JVM or a Gradle test-retry round in a
     * fresh one - is flagged as a rerun and credited no savings, although it ignored suites and ran
     * for a fraction of the baseline.
     */
    @Test
    void rerun_isFlaggedAndRecordsZeroSavings() {
        for (RunAttempt rerun : new RunAttempt[]{RunAttempt.RERUN_SAME_JVM, RunAttempt.RERUN_NEW_JVM}) {
            // given
            long runStart = System.currentTimeMillis() + rerun.ordinal();

            // when
            service.persistTestRunData(false, true, "abc123", "main", runStart, runResult(2, rerun), null);

            // then
            TestRunHistoryEntry row = dataStore.readTestRunHistory().get(0);
            assertTrue(row.isRerun(), rerun + " must be flagged as a rerun");
            assertEquals(0L, row.getTimeSavingsMs(), rerun + " must record no serial savings");
            assertEquals(0L, row.getWallClockSavingsMs(), rerun + " must record no wall-clock savings");
        }
    }

    /**
     * The real run of a partial selection is not flagged as a rerun.
     */
    @Test
    void firstAttempt_isNotFlaggedAsARerun() {
        // given
        long runStart = System.currentTimeMillis();

        // when
        service.persistTestRunData(false, true, "abc123", "main", runStart, runResult(2), null);

        // then
        assertFalse(dataStore.readTestRunHistory().get(0).isRerun());
    }

    /**
     * H2 datastore that sleeps inside {@link #getTiaCore()} to simulate Tia's post-test persist /
     * read overhead (e.g. a slow seed bulk persist). {@code persistTestRunData} reads the core via
     * {@code getTiaCore} after it has captured the run duration, so this delay must not appear in
     * the recorded duration or savings.
     */
    private static class SlowPersistJdbcDataStore extends JdbcDataStore {
        static final long PERSIST_DELAY_MS = 600L;

        SlowPersistJdbcDataStore(SqlDialect dialect, ConnectionProvider connectionProvider, String schema) {
            super(dialect, connectionProvider, schema);
        }

        @Override
        public TiaData getTiaCore() {
            try {
                Thread.sleep(PERSIST_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.getTiaCore();
        }
    }
}
