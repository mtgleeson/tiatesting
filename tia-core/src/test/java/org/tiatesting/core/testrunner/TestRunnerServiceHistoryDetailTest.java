package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestRunTrigger;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.TiaPersistenceException;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;

import java.io.File;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that {@link TestRunnerService#persistTestRunHistory} writes the run's selection
 * breakdown from {@link TestRunResult#getSelectionDetails()} - both the five scalar counters
 * folded onto the {@link TestRunHistoryEntry} and the per-trigger rows persisted separately via
 * {@link org.tiatesting.core.persistence.DataStore#persistTestRunTriggers}. Modelled on
 * {@link TestRunnerServiceHistoryIgnoredCountTest}, which covers the same persist path for the
 * pre-existing counters.
 */
class TestRunnerServiceHistoryDetailTest {

    private JdbcDataStore dataStore;
    private TestRunnerService service;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-history-detail-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())), BranchSchema.schemaName("test", null));
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
     * When a single-host run carries a populated {@link TestRunSelectionDetails}, the persisted
     * history row records its five scalar counters and the trigger rows are readable back from
     * the datastore keyed on the entry's id - the breakdown from selection reaches both the entry
     * and the per-trigger table in one persist call.
     */
    @Test
    void persistTestRunData_writesSelectionCountersAndTriggers() {
        // given - a run with two suites, and a selection breakdown carrying two triggers plus
        // non-zero values on every scalar counter
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        trackers.put("com.example.ATest", new TestSuiteTracker("com.example.ATest"));
        trackers.put("com.example.BTest", new TestSuiteTracker("com.example.BTest"));
        Set<String> runnerTestSuites = new HashSet<>(Arrays.asList(
                "com.example.ATest", "com.example.BTest"));

        List<TestRunTrigger> triggers = Arrays.asList(
                new TestRunTrigger(TestRunTrigger.Type.SOURCE_METHOD, "com.example.Foo.save", 2),
                new TestRunTrigger(TestRunTrigger.Type.STATIC_RULE, "ForceOnDbChange", 1));
        TestRunSelectionDetails details = new TestRunSelectionDetails(triggers, 3, 1, 2, 1, 4);

        TestRunResult testRunResult = new TestRunResult(
                trackers, new HashSet<>(), runnerTestSuites, runnerTestSuites,
                new HashSet<>(), new HashMap<>(), new TestStats(), null, 0, 2, details);

        // when - persist with history enabled for a single-host run
        long runStart = System.currentTimeMillis();
        service.persistTestRunData(false, true, "detail-commit", "main", runStart, testRunResult, null);

        // then - the history row carries the five scalar counters, and the trigger table carries
        // the two triggers keyed on that row's id
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
        assertEquals(1, history.size(), "exactly one history row expected");
        TestRunHistoryEntry row = history.get(0);
        assertNotNull(row);
        assertEquals(Integer.valueOf(3), row.getNumModifiedTestFiles());
        assertEquals(Integer.valueOf(1), row.getNumNewTestFiles());
        assertEquals(Integer.valueOf(2), row.getNumPreviouslyFailed());
        assertEquals(Integer.valueOf(1), row.getNumUnsealedMapping());
        assertEquals(Integer.valueOf(4), row.getNumPendingLibrary());

        List<TestRunTrigger> persistedTriggers = dataStore.readTestRunTriggers(row.getId());
        assertEquals(2, persistedTriggers.size());
        assertEquals(triggers.get(0), persistedTriggers.get(0));
        assertEquals(triggers.get(1), persistedTriggers.get(1));
    }

    /**
     * The per-trigger breakdown is a diagnostic side write: if it fails (here simulated by a
     * datastore that always throws from {@code persistTestRunTriggers}), the run must not fail -
     * the history row and its scalar counters are already saved, so the failure is swallowed and
     * the run completes. Verifies the row (with its counters) is still persisted.
     */
    @Test
    void persistTestRunData_triggerWriteFailure_isSwallowedAndTheRowStillPersists() throws Exception {
        // given - a datastore whose trigger write always throws, over an otherwise-working H2
        File failDir = File.createTempFile("tia-runner-trigger-fail-", "");
        failDir.delete();
        failDir.mkdirs();
        JdbcDataStore throwingStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(failDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null)) {
            @Override
            public void persistTestRunTriggers(String historyId, List<TestRunTrigger> triggers) {
                throw new TiaPersistenceException(new RuntimeException("trigger write boom"));
            }
        };
        throwingStore.getTiaData(true);
        TiaData seed = throwingStore.getTiaData(true);
        seed.setCommitValue("initial");
        seed.setLastUpdated(Instant.now());
        throwingStore.persistCoreData(seed);
        TestRunnerService throwingService = new TestRunnerService(throwingStore);

        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        trackers.put("com.example.ATest", new TestSuiteTracker("com.example.ATest"));
        Set<String> runnerTestSuites = new HashSet<>(Arrays.asList("com.example.ATest"));
        List<TestRunTrigger> triggers = Arrays.asList(
                new TestRunTrigger(TestRunTrigger.Type.STATIC_RULE, "R", 1));
        TestRunSelectionDetails details = new TestRunSelectionDetails(triggers, 7, 0, 0, 0, 0);
        TestRunResult result = new TestRunResult(trackers, new HashSet<>(), runnerTestSuites,
                runnerTestSuites, new HashSet<>(), new HashMap<>(), new TestStats(), null, 0, 1, details);

        // when - the internal trigger write throws
        assertDoesNotThrow(() -> throwingService.persistTestRunData(false, true, "boom-commit",
                "main", System.currentTimeMillis(), result, null));

        // then - the history row and its counters were still written despite the trigger failure
        List<TestRunHistoryEntry> history = throwingStore.readTestRunHistory();
        assertEquals(1, history.size());
        assertEquals(Integer.valueOf(7), history.get(0).getNumModifiedTestFiles());

        for (File f : failDir.listFiles()) {
            f.delete();
        }
        failDir.delete();
    }

    /**
     * A run whose {@link TestRunResult} carries no selection details (the constructor's trailing
     * argument is null, the shape a caller not yet wired for this feature produces) still persists
     * cleanly: {@link TestRunResult#getSelectionDetails()} substitutes {@link TestRunSelectionDetails#empty()}
     * rather than null, so the history row's five counters are recorded as explicit zeroes - not
     * left null - and no trigger rows are written since the empty breakdown has none.
     */
    @Test
    void persistTestRunData_nullSelectionDetails_persistsZeroCountersAndNoTriggers() {
        // given - a run whose selection details were never supplied
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        trackers.put("com.example.ATest", new TestSuiteTracker("com.example.ATest"));
        Set<String> runnerTestSuites = new HashSet<>(Arrays.asList("com.example.ATest"));
        TestRunResult testRunResult = new TestRunResult(
                trackers, new HashSet<>(), runnerTestSuites, runnerTestSuites,
                new HashSet<>(), new HashMap<>(), new TestStats(), null, 0, 1, null);

        // when
        service.persistTestRunData(false, true, "no-detail-commit", "main",
                System.currentTimeMillis(), testRunResult, null);

        // then - counters default to zero (not null, since getSelectionDetails() never returns
        // null), and no trigger rows exist for this entry
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
        assertEquals(1, history.size());
        TestRunHistoryEntry row = history.get(0);
        assertEquals(Integer.valueOf(0), row.getNumModifiedTestFiles());
        assertEquals(0, dataStore.readTestRunTriggers(row.getId()).size());
    }
}
