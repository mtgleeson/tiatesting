package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.h2.H2DataStore;

import java.io.File;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the persist-history math in {@link TestRunnerService#persistTestRunData} for the
 * {@code num_suites_ignored} column. The persisted value must come from
 * {@link TestRunResult#getIgnoredTestSuiteCount()} (the selector's authoritative ignore count)
 * - NOT from {@code runnerTestSuites.size() - testSuiteTrackers.size()}, which would mix in
 * engine-level skips Tia did not cause.
 */
class TestRunnerServiceHistoryIgnoredCountTest {

    private H2DataStore dataStore;
    private TestRunnerService service;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-history-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(tempDir.getAbsolutePath(), "test");
        dataStore.getTiaData(true);
        service = new TestRunnerService(dataStore);

        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("initial");
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);
    }

    @AfterEach
    void tearDown() {
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * When the selector reports 7 suites ignored, the history row records 7 - independent of
     * what {@code runnerTestSuites - testSuiteTrackers} happens to be. This is the primary
     * behaviour change: the count comes from the selector, not from the runner's not-executed
     * set.
     */
    @Test
    void persistedIgnoredCount_comesFromSelectorNotRunnerDiff() {
        // given - 4 suites ran, runner saw the same 4 (no engine-level skips), but the
        // selector chose to ignore 7 other suites in the selection step
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        trackers.put("com.example.ATest", new TestSuiteTracker("com.example.ATest"));
        trackers.put("com.example.BTest", new TestSuiteTracker("com.example.BTest"));
        trackers.put("com.example.CTest", new TestSuiteTracker("com.example.CTest"));
        trackers.put("com.example.DTest", new TestSuiteTracker("com.example.DTest"));
        Set<String> runnerTestSuites = new HashSet<>(Arrays.asList(
                "com.example.ATest", "com.example.BTest", "com.example.CTest", "com.example.DTest"));
        TestRunResult testRunResult = new TestRunResult(
                trackers, new HashSet<>(), runnerTestSuites,
                new HashSet<>(), new HashMap<>(), new TestStats(), null, 7);

        // when - persist with history enabled
        long runStart = System.currentTimeMillis();
        service.persistTestRunData(false, false, true, "abc123", "main", runStart, testRunResult);

        // then - the row records the selector's ignore count, not the runner diff
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
        assertEquals(1, history.size(), "exactly one history row expected");
        TestRunHistoryEntry row = history.get(0);
        assertNotNull(row);
        assertEquals(4, row.getNumSuitesRan(), "ran count comes from trackers");
        assertEquals(7, row.getNumSuitesIgnored(), "ignored count comes from selector, not runner diff");
        assertEquals(0, row.getNumSuitesFailed(), "no failures in this run");
    }

    /**
     * Engine-level skips that Tia did not cause (e.g. user {@code @Disabled}, surefire
     * {@code groups} filters) must not contribute to the ignored count. Modelled here by a
     * test run where the runner saw 10 suites but only ran 4 - the persisted ignored value
     * still tracks the selector's 0, not the runner's 6 not-executed suites.
     */
    @Test
    void engineLevelSkips_doNotInflateIgnoredCount() {
        // given - runner saw 10 suites, ran 4. The other 6 were skipped by something other
        // than Tia (e.g. user @Disabled). Selector reports 0 ignored.
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        trackers.put("com.example.RanA", new TestSuiteTracker("com.example.RanA"));
        trackers.put("com.example.RanB", new TestSuiteTracker("com.example.RanB"));
        trackers.put("com.example.RanC", new TestSuiteTracker("com.example.RanC"));
        trackers.put("com.example.RanD", new TestSuiteTracker("com.example.RanD"));
        Set<String> runnerTestSuites = new HashSet<>(Arrays.asList(
                "com.example.RanA", "com.example.RanB", "com.example.RanC", "com.example.RanD",
                "com.example.UserDisabledA", "com.example.UserDisabledB", "com.example.UserDisabledC",
                "com.example.UserDisabledD", "com.example.UserDisabledE", "com.example.UserDisabledF"));
        TestRunResult testRunResult = new TestRunResult(
                trackers, new HashSet<>(), runnerTestSuites,
                new HashSet<>(), new HashMap<>(), new TestStats(), null, 0);

        // when
        service.persistTestRunData(false, false, true, "abc123", "main", System.currentTimeMillis(), testRunResult);

        // then - ignored is 0 even though runner-saw-but-didn't-run is 6
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
        assertEquals(1, history.size());
        assertEquals(0, history.get(0).getNumSuitesIgnored(),
                "engine-level skips Tia did not cause must not inflate the ignored count");
    }

    /**
     * First-run / no-mapping flow ({@link
     * org.tiatesting.core.diff.diffanalyze.selector.TestSelector#selectTestsToIgnore} returns
     * an empty ignore set in this branch). The history row records 0 ignored - consistent with
     * "Tia ignored nothing this run".
     */
    @Test
    void firstRun_noStoredMapping_persistsZeroIgnored() {
        // given - no trackers, no runner suites, selector ignored nothing
        TestRunResult testRunResult = new TestRunResult(
                new HashMap<>(), new HashSet<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>(), new TestStats(), null, 0);

        // when
        service.persistTestRunData(false, false, true, "first-run-commit", "main",
                System.currentTimeMillis(), testRunResult);

        // then
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
        assertEquals(1, history.size());
        assertEquals(0, history.get(0).getNumSuitesRan());
        assertEquals(0, history.get(0).getNumSuitesIgnored());
        assertEquals(0, history.get(0).getNumSuitesFailed());
    }
}
