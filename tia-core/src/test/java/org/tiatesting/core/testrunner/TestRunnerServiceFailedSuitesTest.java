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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers how a single-host persist maintains the failed-suite set: a suite's stored failed state is
 * the outcome of its latest execution, and the persist touches only the suites this JVM executed,
 * plus selected suites it can show will not run (flagged developer-disabled). Everything else in
 * the stored set is left alone.
 *
 * <p>Driven against a real embedded-H2 {@link JdbcDataStore}, because what is under test is which
 * rows the persist leaves in {@code tia_test_suites_failed}.
 */
class TestRunnerServiceFailedSuitesTest {

    private static final String SUITE_A = "com.example.ATest";
    private static final String SUITE_B = "com.example.BTest";
    private static final String SUITE_C = "com.example.CTest";
    private static final Set<String> ALL_SUITES = new HashSet<>(Arrays.asList(SUITE_A, SUITE_B, SUITE_C));

    private JdbcDataStore dataStore;
    private TestRunnerService service;
    private File tempDir;

    /**
     * Create a fresh embedded H2 store tracking three suites, with a prior commit stamp.
     *
     * @throws Exception if the temp directory cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-failed-suites-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
        service = new TestRunnerService(dataStore);

        TiaData tiaData = dataStore.getTiaData();
        tiaData.setCommitValue("commit-0");
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);

        Map<String, TestSuiteTracker> tracked = new HashMap<>();
        for (String suite : ALL_SUITES) {
            tracked.put(suite, new TestSuiteTracker(suite));
        }
        dataStore.persistTestSuites(tracked);
    }

    /**
     * Close the store and remove the temp directory.
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
     * Verifies a stored-failed suite that executed and passed leaves the failed set.
     */
    @Test
    void persist_previouslyFailedSuiteExecutesAndPasses_isRemoved() {
        // given
        seedFailed(SUITE_A);
        TestRunResult result = result(set(SUITE_A), set(SUITE_A), set(), 0);

        // when
        persist(result);

        // then
        assertEquals(set(), dataStore.getTestSuitesFailed());
    }

    /**
     * Verifies a suite whose latest execution failed is stored as failed.
     */
    @Test
    void persist_executedSuiteFails_isStored() {
        // given
        TestRunResult result = result(set(SUITE_A), set(SUITE_A), set(SUITE_A), 1);

        // when
        persist(result);

        // then
        assertEquals(set(SUITE_A), dataStore.getTestSuitesFailed());
    }

    /**
     * Verifies a stored-failed suite this JVM neither selected nor executed - another runner's
     * suite in a distributed build, say - is left in the failed set.
     */
    @Test
    void persist_storedFailedSuiteNotExecutedHere_isLeftAlone() {
        // given
        seedFailed(SUITE_C);
        TestRunResult result = result(set(SUITE_A), set(SUITE_A), set(), 0);

        // when
        persist(result);

        // then
        assertEquals(set(SUITE_C), dataStore.getTestSuitesFailed());
    }

    /**
     * Verifies a stored-failed suite that was selected and discovered but did not execute - which
     * flags it developer-disabled - is dropped, since Tia can show it will not run.
     */
    @Test
    void persist_selectedFailedSuiteDidNotExecute_isDroppedAsDeveloperDisabled() {
        // given
        seedFailed(SUITE_B);
        TestRunResult result = result(set(SUITE_A), set(SUITE_A, SUITE_B), set(), 0);

        // when
        persist(result);

        // then
        assertEquals(set(), dataStore.getTestSuitesFailed());
    }

    /**
     * Verifies a Surefire retry in the same JVM keeps a failure from an earlier attempt that it did
     * not re-run. The JVM's tracker map and failed set carry every attempt, so the retry's persist
     * still sees suite A as executed and still failing, while suite B passed on its retry.
     */
    @Test
    void persist_sameJvmRetryReRunsOnlySomeFailures_earlierUnretriedFailureStays() {
        // given
        persist(result(set(SUITE_A, SUITE_B), set(SUITE_A, SUITE_B), set(SUITE_A, SUITE_B), 2));

        // when
        persist(result(set(SUITE_A, SUITE_B), set(SUITE_A, SUITE_B), set(SUITE_A), 0));

        // then
        assertEquals(set(SUITE_A), dataStore.getTestSuitesFailed());
    }

    /**
     * Verifies a Gradle test-retry round - a fresh JVM that executes only the previous round's
     * failures - updates just those suites: the retried suite that now passes leaves the failed set,
     * and the round-1 suite that failed again stays.
     */
    @Test
    void persist_freshJvmRetryRoundOfEveryFailure_latestOutcomeWins() {
        // given
        persist(result(set(SUITE_A, SUITE_B, SUITE_C), ALL_SUITES, set(SUITE_A, SUITE_B), 2));

        // when
        persist(result(set(SUITE_A, SUITE_B), ALL_SUITES, set(SUITE_B), 1));

        // then
        assertEquals(set(SUITE_B), dataStore.getTestSuitesFailed());
    }

    /**
     * Verifies the history row's failed count is the attempt's own count, not the size of the
     * JVM-wide failed set, so it describes the same attempt as the ran count.
     */
    @Test
    void persist_historyRowFailedCount_isPerAttempt() {
        // given
        TestRunResult retry = result(set(SUITE_A, SUITE_B), set(SUITE_A, SUITE_B), set(SUITE_A), 0);

        // when
        service.persistTestRunData(true, true, "commit-1", "main", System.currentTimeMillis(), retry, null);

        // then
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
        assertEquals(1, history.size());
        assertEquals(0, history.get(0).getNumSuitesFailed());
    }

    /**
     * Store the given suites as failed, as an earlier run would have left them.
     *
     * @param suites the suites to store
     */
    private void seedFailed(final String... suites) {
        dataStore.persistTestSuitesFailed(Collections.emptySet(), set(suites));
    }

    /**
     * Persist a result as a mapping-owning single-host run with no history row.
     *
     * @param result the result to persist
     */
    private void persist(final TestRunResult result) {
        service.persistTestRunData(true, false, "commit-1", "main", System.currentTimeMillis(), result, null);
    }

    /**
     * Build a run result for a JVM whose runner discovered every tracked suite.
     *
     * @param executed the suites that executed in this JVM
     * @param selected the suites Tia selected
     * @param failed the suites whose latest execution failed
     * @param failedThisAttempt the count of suites that failed in the attempt being persisted
     * @return the result
     */
    private static TestRunResult result(final Set<String> executed, final Set<String> selected,
                                        final Set<String> failed, final int failedThisAttempt) {
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        for (String suite : executed) {
            trackers.put(suite, new TestSuiteTracker(suite));
        }
        return new TestRunResult(trackers, failed, ALL_SUITES, executed, selected, new HashMap<>(),
                new TestStats(), null, ALL_SUITES.size() - selected.size(), executed.size(),
                failedThisAttempt, TestRunSelectionDetails.empty(), RunAttempt.FIRST);
    }

    /**
     * Build a mutable set of suite names.
     *
     * @param suites the names
     * @return the set
     */
    private static Set<String> set(final String... suites) {
        return new HashSet<>(Arrays.asList(suites));
    }
}
