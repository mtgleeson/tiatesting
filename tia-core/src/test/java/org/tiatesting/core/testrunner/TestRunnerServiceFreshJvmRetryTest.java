package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
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
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how a single-host persist treats a retry of failed tests that ran in a fresh JVM - a
 * Gradle test-retry round - compared with the test task's first attempt. The fresh JVM ran only the
 * failed tests and knows nothing of the first attempt, so its persist must add to what the first
 * attempt stored rather than overwrite it: coverage is unioned, a suite's run is counted with its
 * own outcome but leaves the stored average run time alone, the developer-disabled flags are left as
 * stored, and no Tia-level stats are contributed. Each behaviour is checked against the first
 * attempt's, which still replaces.
 *
 * <p>Driven against a real embedded-H2 {@link JdbcDataStore}, because what is under test is which
 * rows the persist leaves behind.
 */
class TestRunnerServiceFreshJvmRetryTest {

    private static final String SUITE_A = "com.example.ATest";
    private static final String SUITE_B = "com.example.BTest";
    private static final String CLASS_X = "com/example/X.java";
    private static final String CLASS_Y = "com/example/Y.java";
    private static final Set<String> ALL_SUITES = new HashSet<>(Arrays.asList(SUITE_A, SUITE_B));

    private JdbcDataStore dataStore;
    private TestRunnerService service;
    private File tempDir;

    /**
     * Create a fresh embedded H2 store with a prior commit stamp.
     *
     * @throws Exception if the temp directory cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-fresh-jvm-retry-", "");
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
     * Verifies a fresh-JVM retry adds the coverage it captured to the suite's stored coverage: a
     * class it did not reach keeps its stored methods, and a class it did reach gains its methods.
     */
    @Test
    void freshJvmRetry_unionsItsCoverageWithTheStoredCoverage() {
        // given
        persist(run(RunAttempt.FIRST, tracker(SUITE_A, 1_000L, false, cls(CLASS_X, 1, 2), cls(CLASS_Y, 3))));

        // when
        persist(run(RunAttempt.RERUN_NEW_JVM, tracker(SUITE_A, 50L, false, cls(CLASS_X, 2, 4))));

        // then
        Map<String, Set<Integer>> expected = new TreeMap<>();
        expected.put(CLASS_X, new TreeSet<>(Arrays.asList(1, 2, 4)));
        expected.put(CLASS_Y, new TreeSet<>(Collections.singletonList(3)));
        assertEquals(expected, storedCoverage(SUITE_A));
    }

    /**
     * Verifies the first attempt still replaces a suite's coverage, which is how coverage a suite no
     * longer reaches is dropped.
     */
    @Test
    void firstAttempt_replacesTheStoredCoverage() {
        // given
        persist(run(RunAttempt.FIRST, tracker(SUITE_A, 1_000L, false, cls(CLASS_X, 1, 2), cls(CLASS_Y, 3))));

        // when
        persist(run(RunAttempt.FIRST, tracker(SUITE_A, 1_000L, false, cls(CLASS_X, 2, 4))));

        // then
        Map<String, Set<Integer>> expected = new TreeMap<>();
        expected.put(CLASS_X, new TreeSet<>(Arrays.asList(2, 4)));
        assertEquals(expected, storedCoverage(SUITE_A));
    }

    /**
     * Verifies a flaky suite that fails its first attempt and passes a fresh-JVM retry records a
     * fail then a pass, as a same-JVM retry does, while the retry's short run time leaves the
     * stored average untouched.
     */
    @Test
    void freshJvmRetry_countsTheRunButKeepsTheStoredAverage() {
        // given
        persist(run(RunAttempt.FIRST, tracker(SUITE_A, 1_000L, true, cls(CLASS_X, 1))));

        // when
        persist(run(RunAttempt.RERUN_NEW_JVM, tracker(SUITE_A, 50L, false, cls(CLASS_X, 1))));

        // then
        TestStats stats = dataStore.getTestSuitesTracked().get(SUITE_A).getTestStats();
        assertEquals(2, stats.getNumRuns());
        assertEquals(1, stats.getNumFailRuns());
        assertEquals(1, stats.getNumSuccessRuns());
        assertEquals(1_000L, stats.getAvgRunTime());
    }

    /**
     * Verifies a fresh-JVM retry, told the whole selection but running only the failures, does not
     * flag the selected suites it did not run as developer-disabled - while a first attempt with
     * the same shape does.
     */
    @Test
    void freshJvmRetry_leavesTheDeveloperDisabledFlagsAlone() {
        // given
        persist(run(RunAttempt.FIRST, tracker(SUITE_A, 1_000L, true, cls(CLASS_X, 1)),
                tracker(SUITE_B, 1_000L, false, cls(CLASS_Y, 3))));

        // when
        persist(run(RunAttempt.RERUN_NEW_JVM, tracker(SUITE_A, 50L, false, cls(CLASS_X, 1))));

        // then
        assertFalse(dataStore.getTestSuitesTracked().get(SUITE_B).isDeveloperDisabled());

        // and when the same shape is a first attempt, the flag is derived
        persist(run(RunAttempt.FIRST, tracker(SUITE_A, 1_000L, false, cls(CLASS_X, 1))));
        assertTrue(dataStore.getTestSuitesTracked().get(SUITE_B).isDeveloperDisabled());
    }

    /**
     * Verifies neither kind of retry contributes Tia-level stats: the first attempt already counted
     * the run.
     */
    @Test
    void retries_contributeNoTiaLevelStats() {
        // given
        persist(run(RunAttempt.FIRST, tracker(SUITE_A, 1_000L, true, cls(CLASS_X, 1))));
        long runsAfterFirstAttempt = dataStore.getTiaCore().getTestStats().getNumRuns();

        // when
        persist(run(RunAttempt.RERUN_SAME_JVM, tracker(SUITE_A, 50L, false, cls(CLASS_X, 1))));
        persist(run(RunAttempt.RERUN_NEW_JVM, tracker(SUITE_A, 50L, false, cls(CLASS_X, 1))));

        // then
        assertEquals(1L, runsAfterFirstAttempt);
        assertEquals(runsAfterFirstAttempt, dataStore.getTiaCore().getTestStats().getNumRuns());
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
     * Build a run result for a JVM whose runner discovered both suites, Tia selected both, and the
     * given trackers executed. The run's Tia-level stats record one run.
     *
     * @param attempt which attempt the run is
     * @param trackers the suites that executed, with their coverage and stats
     * @return the result
     */
    private static TestRunResult run(final RunAttempt attempt, final TestSuiteTracker... trackers) {
        Map<String, TestSuiteTracker> executed = new HashMap<>();
        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        Set<String> failed = new HashSet<>();
        for (TestSuiteTracker tracker : trackers) {
            executed.put(tracker.getName(), tracker);
            if (tracker.getTestStats().getNumFailRuns() > 0) {
                failed.add(tracker.getName());
            }
            for (ClassImpactTracker cls : tracker.getClassesImpacted()) {
                for (Integer id : cls.getMethodsImpacted()) {
                    methods.put(id, new MethodImpactTracker(cls.getSourceFilename() + ".m" + id + ".()V", id, id + 1));
                }
            }
        }
        TestStats runStats = new TestStats();
        runStats.setNumRuns(1);
        runStats.setAvgRunTime(1_000L);
        runStats.setNumSuccessRuns(failed.isEmpty() ? 1 : 0);
        runStats.setNumFailRuns(failed.isEmpty() ? 0 : 1);
        return new TestRunResult(executed, failed, ALL_SUITES, executed.keySet(), ALL_SUITES, methods,
                runStats, null, 0, executed.size(), failed.size(), TestRunSelectionDetails.empty(), attempt);
    }

    /**
     * Build the tracker a listener leaves for a suite that executed once.
     *
     * @param name the suite name
     * @param runTimeMs the suite's measured run time
     * @param failed whether the suite failed
     * @param classes the coverage it captured
     * @return the tracker
     */
    private static TestSuiteTracker tracker(final String name, final long runTimeMs, final boolean failed,
                                            final ClassImpactTracker... classes) {
        TestSuiteTracker tracker = new TestSuiteTracker(name);
        tracker.getTestStats().setNumRuns(1);
        tracker.getTestStats().setAvgRunTime(runTimeMs);
        tracker.getTestStats().setNumSuccessRuns(failed ? 0 : 1);
        tracker.getTestStats().setNumFailRuns(failed ? 1 : 0);
        tracker.setClassesImpacted(new java.util.ArrayList<>(Arrays.asList(classes)));
        return tracker;
    }

    /**
     * Build one covered class with the given method ids.
     *
     * @param filename the class's source filename
     * @param methodIds the covered method ids
     * @return the class tracker
     */
    private static ClassImpactTracker cls(final String filename, final Integer... methodIds) {
        return new ClassImpactTracker(filename, Arrays.asList(methodIds));
    }

    /**
     * Read a suite's stored coverage as a sorted class-to-method-ids map, for comparison.
     *
     * @param suite the suite name
     * @return the stored coverage
     */
    private Map<String, Set<Integer>> storedCoverage(final String suite) {
        Map<String, Set<Integer>> coverage = new TreeMap<>();
        List<ClassImpactTracker> classes = dataStore.readTestSuiteCoverage(Collections.singleton(suite)).get(suite);
        for (ClassImpactTracker cls : classes) {
            coverage.put(cls.getSourceFilename(), new TreeSet<>(cls.getMethodsImpacted()));
        }
        return coverage;
    }
}
