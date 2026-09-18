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
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the empty-run guard on {@link TestRunnerService#persistTestRunData}: a run that executed
 * none of the test suites Tia expected it to run - the shape a misconfigured build takes, finishing
 * in milliseconds with a missing or mismatched test dependency - records no Tia-level stats, does not
 * establish the all-tests-run baseline, is counted as neither a run nor a success, and is credited no
 * savings on its history row.
 *
 * <p>The run that legitimately executes no suite - Tia ignored every suite because nothing was
 * impacted - is deliberately not caught by the guard and keeps contributing its stats and savings; the
 * tests below pin both sides of that line. Persists through an embedded H2 DB and reads the core row
 * and history rows back.
 */
class TestRunnerServiceEmptyRunStatsGuardTest {

    // Large baseline so any real wall-clock duration leaves a positive saving to be credited or not.
    private static final long BASELINE_MS = 10_000_000L;

    private JdbcDataStore dataStore;
    private TestRunnerService service;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-emptyrun-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
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
     * Give the DB an established full-suite baseline, so a run's savings are a figure that can be
     * credited or withheld.
     */
    private void establishBaseline() {
        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.getTestStats().setAllTestsRunTime(BASELINE_MS);
        dataStore.persistCoreData(tiaData);
    }

    /**
     * Build a {@link TestRunResult} for a run reporting one successful run of the given duration.
     *
     * @param durationMs the duration the run measured for itself
     * @param ignoredTestSuiteCount the suites Tia's selector chose to ignore
     * @param selectedTests the suites Tia selected to run
     * @param suitesRanThisAttempt the suites that actually executed in this attempt
     * @return the run result to persist
     */
    private TestRunResult runResult(long durationMs, int ignoredTestSuiteCount,
                                    Set<String> selectedTests, int suitesRanThisAttempt) {
        TestStats runStats = new TestStats();
        runStats.setNumRuns(1);
        runStats.setAvgRunTime(durationMs);
        runStats.setNumSuccessRuns(1);

        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        Set<String> empty = new HashSet<>();
        return new TestRunResult(trackers, empty, empty, empty, selectedTests, new HashMap<>(),
                runStats, null, ignoredTestSuiteCount, suitesRanThisAttempt,
                TestRunSelectionDetails.empty());
    }

    private Set<String> suiteNames(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    /**
     * The reported case: a misconfigured all-tests run (nothing ignored, so every suite was expected)
     * finishes having executed no suite. Its duration must not become the full-suite baseline - doing
     * so halves or worse every later savings figure - and it must not be counted as a run or a
     * success.
     */
    @Test
    void allTestsRunThatRanNoSuites_recordsNoStats() {
        // given - nothing ignored (every suite expected) but no suite executed
        TestRunResult result = runResult(152L, 0, new HashSet<>(), 0);

        // when - a mapping-owning persist, which is the only kind that records stats
        service.persistTestRunData(true, false, "abc123", "main", System.currentTimeMillis(), result, null);
        TestStats stored = dataStore.getTiaCore().getTestStats();

        // then
        assertEquals(0L, stored.getAllTestsRunTime(), "the empty run must not establish the baseline");
        assertEquals(0L, stored.getNumAllTestsRuns());
        assertEquals(0L, stored.getAvgRunTime(), "nor fold into the selected-run average");
        assertEquals(0L, stored.getNumRuns(), "the empty run is not a run");
        assertEquals(0L, stored.getNumSuccessRuns(), "nor a success");
        assertEquals(0L, stored.getNumFailRuns());
    }

    /**
     * An established baseline is left where it is by a later misconfigured all-tests run, rather than
     * being averaged down towards the empty run's duration.
     */
    @Test
    void allTestsRunThatRanNoSuites_leavesEstablishedBaselineUntouched() {
        // given - a baseline from earlier healthy runs
        establishBaseline();
        TestRunResult result = runResult(152L, 0, new HashSet<>(), 0);

        // when
        service.persistTestRunData(true, false, "abc123", "main", System.currentTimeMillis(), result, null);
        TestStats stored = dataStore.getTiaCore().getTestStats();

        // then
        assertEquals(BASELINE_MS, stored.getAllTestsRunTime());
        assertEquals(0L, stored.getNumAllTestsRuns());
    }

    /**
     * A partial run where Tia selected suites but none of them executed is the same misconfiguration
     * seen after the first mapping run: no stats, and no savings credited on the history row - the
     * build finished early because it ran nothing, not because Tia deselected anything.
     */
    @Test
    void selectedSuitesButRanNone_recordsNoStatsAndNoSavings() {
        // given - Tia selected two suites and ignored three, and neither selected suite executed
        establishBaseline();
        TestRunResult result = runResult(152L, 3, suiteNames("com.example.ATest", "com.example.BTest"), 0);

        // when - persisting both the mapping/stats and the history row
        service.persistTestRunData(true, true, "abc123", "main", System.currentTimeMillis(), result, null);
        TestStats stored = dataStore.getTiaCore().getTestStats();
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();

        // then
        assertEquals(0L, stored.getNumRuns());
        assertEquals(0L, stored.getNumSuccessRuns());
        assertEquals(0L, stored.getAvgRunTime());
        assertEquals(1, history.size(), "the empty run still gets a history row so it stays visible");
        assertEquals(0, history.get(0).getNumSuitesRan());
        assertEquals(0L, history.get(0).getTimeSavingsMs(), "an empty run saved nothing");
        assertEquals(0, history.get(0).getSavingsPercent());
    }

    /**
     * The legitimate empty run - Tia ignored every suite because nothing was impacted, so it selected
     * none - is not a misconfiguration and is left alone: it still counts as a successful run and
     * still earns the full savings, which is Tia's largest win.
     */
    @Test
    void nothingImpactedRun_stillRecordsStatsAndSavings() {
        // given - suites ignored, nothing selected, nothing run
        establishBaseline();
        TestRunResult result = runResult(152L, 5, new HashSet<>(), 0);

        // when
        service.persistTestRunData(true, true, "abc123", "main", System.currentTimeMillis(), result, null);
        TestStats stored = dataStore.getTiaCore().getTestStats();
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();

        // then
        assertEquals(1L, stored.getNumRuns());
        assertEquals(1L, stored.getNumSuccessRuns());
        assertEquals(152L, stored.getAvgRunTime(), "a selected run, so it folds into the selected average");
        assertEquals(BASELINE_MS, stored.getAllTestsRunTime(), "and leaves the baseline alone");
        assertEquals(1, history.size());
        assertTrue(history.get(0).getTimeSavingsMs() > 0, "deselecting every suite is a real saving");
    }

    /**
     * Regression guard for the healthy path: an all-tests run that did execute suites still
     * establishes the baseline and counts as a successful run.
     */
    @Test
    void allTestsRunThatRanSuites_stillEstablishesBaseline() {
        // given - nothing ignored and suites executed
        TestRunResult result = runResult(500L, 0, new HashSet<>(), 4);

        // when
        service.persistTestRunData(true, false, "abc123", "main", System.currentTimeMillis(), result, null);
        TestStats stored = dataStore.getTiaCore().getTestStats();

        // then
        assertEquals(500L, stored.getAllTestsRunTime());
        assertEquals(1L, stored.getNumAllTestsRuns());
        assertEquals(1L, stored.getNumRuns());
        assertEquals(1L, stored.getNumSuccessRuns());
    }
}
