package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.SelectionMode;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers when a single-host persist re-seeds: only a {@link SelectionMode#RESEED} run's first
 * attempt clears the mapping its run did not rewrite. A retry ran only the failed suites, so a
 * clear-out keyed on its writes would delete nearly everything, and a plain select-all never
 * clears. See the "Forced runs and re-seed" chapter in {@code WIKI.md}.
 *
 * <p>Driven against a real embedded-H2 {@link JdbcDataStore}, because what is under test is which
 * rows the persist leaves behind.
 */
class TestRunnerServiceReseedTest {

    private static final String SUITE_OLD = "com.example.OldTest";
    private static final String SUITE_SEEN = "com.example.SeenTest";
    private static final Set<String> DISCOVERED = new HashSet<>(Arrays.asList(SUITE_OLD, SUITE_SEEN));

    private JdbcDataStore dataStore;
    private TestRunnerService service;
    private File tempDir;

    /**
     * Create a fresh embedded H2 store with a prior commit stamp, and store a sealed mapping for
     * both suites from an ordinary first run.
     *
     * @throws Exception if the temp directory cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-reseed-", "");
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

        persist(run(RunAttempt.FIRST, SelectionMode.SELECTIVE, SUITE_OLD, SUITE_SEEN));
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
     * A re-seed run's first attempt clears the suite it did not run.
     */
    @Test
    void aFirstAttemptReseedClearsSuitesItDidNotRun() {
        // given - a re-seed run that executes only the second suite, while both are still on disk
        TestRunResult reseed = run(RunAttempt.FIRST, SelectionMode.RESEED, SUITE_SEEN);

        // when
        persist(reseed);

        // then
        assertEquals(Collections.singleton(SUITE_SEEN), dataStore.getTestSuitesTracked().keySet());
    }

    /**
     * A fresh-JVM retry of a re-seed run clears nothing - it ran only the failed suites.
     */
    @Test
    void aFreshJvmRetryOfAReseedClearsNothing() {
        // given
        TestRunResult retry = run(RunAttempt.RERUN_NEW_JVM, SelectionMode.RESEED, SUITE_SEEN);

        // when
        persist(retry);

        // then
        assertTrue(dataStore.getTestSuitesTracked().containsKey(SUITE_OLD));
    }

    /**
     * A select-all run never clears mapping data.
     */
    @Test
    void aSelectAllRunClearsNothing() {
        // given
        TestRunResult selectAll = run(RunAttempt.FIRST, SelectionMode.SELECT_ALL, SUITE_SEEN);

        // when
        persist(selectAll);

        // then
        assertTrue(dataStore.getTestSuitesTracked().containsKey(SUITE_OLD));
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
     * Build a run result for a JVM whose runner discovered both suites and executed (and was
     * selected to run) only the named ones, each covering one method of its own.
     *
     * @param attempt which attempt the run is
     * @param mode the selection mode the run carries on its selection details
     * @param executedSuites the suites that executed
     * @return the result
     */
    private static TestRunResult run(final RunAttempt attempt, final SelectionMode mode,
                                     final String... executedSuites) {
        Map<String, TestSuiteTracker> executed = new HashMap<>();
        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        for (String name : executedSuites) {
            int methodId = name.equals(SUITE_OLD) ? 1 : 2;
            TestSuiteTracker tracker = new TestSuiteTracker(name);
            tracker.getTestStats().setNumRuns(1);
            tracker.getTestStats().setAvgRunTime(100L);
            tracker.getTestStats().setNumSuccessRuns(1);
            String filename = "com/example/" + name + ".java";
            tracker.setClassesImpacted(new ArrayList<>(Collections.singletonList(
                    new ClassImpactTracker(filename, Collections.singletonList(methodId)))));
            executed.put(name, tracker);
            methods.put(methodId, new MethodImpactTracker(filename + ".m" + methodId + ".()V", 1, 2));
        }
        TestStats runStats = new TestStats();
        runStats.setNumRuns(1);
        runStats.setAvgRunTime(100L);
        runStats.setNumSuccessRuns(1);
        return new TestRunResult(executed, Collections.<String>emptySet(), DISCOVERED,
                executed.keySet(), executed.keySet(), methods, runStats, null, 0, executed.size(), 0,
                TestRunSelectionDetails.forFullRun(mode), attempt);
    }
}
