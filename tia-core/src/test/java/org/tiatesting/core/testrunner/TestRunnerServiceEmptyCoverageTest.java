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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the persist of a suite that executed but covered no tracked code: its stored edges are
 * replaced by the empty capture and it is flagged as observed, so it is no longer selected for
 * code it used to cover and a re-seed keeps it with its stats. See the "Persist flow and crash
 * safety" and "Forced runs and re-seed" chapters in {@code WIKI.md}.
 *
 * <p>Driven against a real embedded-H2 {@link JdbcDataStore}, because what is under test is which
 * rows the persist leaves behind.
 */
class TestRunnerServiceEmptyCoverageTest {

    private static final String SUITE_A = "com.example.ATest";
    private static final String SUITE_B = "com.example.BTest";
    private static final Set<String> DISCOVERED = new HashSet<>(Arrays.asList(SUITE_A, SUITE_B));

    private JdbcDataStore dataStore;
    private TestRunnerService service;
    private File tempDir;

    /**
     * Create a fresh embedded H2 store with a prior commit stamp, and store a sealed mapping for
     * both suites from an ordinary first run, each covering one method.
     *
     * @throws Exception if the temp directory cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-empty-coverage-", "");
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

        persist(run(SelectionMode.SELECTIVE, Collections.<String>emptySet(), SUITE_A, SUITE_B));
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
     * A suite that executed but covered no tracked code has its stored edges replaced by the empty
     * capture, so it is no longer selected for code it used to cover.
     */
    @Test
    void executedSuiteWithNoCoverage_losesItsOldEdges() {
        // given - A ran again, covering nothing this time
        TestRunResult rerun = run(SelectionMode.SELECTIVE, setOf(SUITE_A), SUITE_A);

        // when
        persist(rerun);

        // then
        assertTrue(dataStore.readTestSuiteCoverage(setOf(SUITE_A))
                .getOrDefault(SUITE_A, Collections.<ClassImpactTracker>emptyList()).isEmpty());
        assertTrue(dataStore.getTestSuitesTracked().containsKey(SUITE_A));
    }

    /**
     * A suite that didn't run keeps its edges - only executed suites are rewritten.
     */
    @Test
    void suiteThatDidNotRun_keepsItsEdges() {
        // given - only B ran
        TestRunResult rerun = run(SelectionMode.SELECTIVE, Collections.<String>emptySet(), SUITE_B);

        // when
        persist(rerun);

        // then
        assertEquals(1, dataStore.readTestSuiteCoverage(setOf(SUITE_A)).get(SUITE_A).size());
    }

    /**
     * A re-seed keeps a suite it executed even when the suite covered nothing, with its stats.
     */
    @Test
    void reseed_keepsAnExecutedSuiteWithNoCoverage() {
        // given - a re-seed that runs both suites; A covers nothing
        TestRunResult reseed = run(SelectionMode.RESEED, setOf(SUITE_A), SUITE_A, SUITE_B);

        // when
        persist(reseed);

        // then
        TestSuiteTracker a = dataStore.getTestSuitesTracked().get(SUITE_A);
        assertNotNull(a, "an executed suite must survive the re-seed");
        assertEquals(2, a.getTestStats().getNumRuns());
    }

    /**
     * A fresh-JVM retry that captured nothing for a suite keeps the suite's stored coverage: the
     * retry ran only its failed tests, and its capture is unioned with what is stored.
     */
    @Test
    void freshJvmRetryWithNoCoverage_keepsTheStoredEdges() {
        // given
        // a build whose first attempt ran both suites and failed A
        TestRunIdentity build = TestRunResultFixture.runIdentity();
        persist(run(new TestJvmContribution(), build, SelectionMode.SELECTIVE, setOf(SUITE_A),
                Collections.<String>emptySet(), SUITE_A, SUITE_B));
        TestRunResult retry = run(new TestJvmContribution(), build, SelectionMode.SELECTIVE,
                Collections.<String>emptySet(), setOf(SUITE_A), SUITE_A);

        // when A is retried in a fresh JVM and captures nothing
        persist(retry);

        // then
        assertEquals(1, dataStore.readTestSuiteCoverage(setOf(SUITE_A)).get(SUITE_A).size());
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
     * Build the first attempt of a new build, in a new JVM, with no failures.
     *
     * @param mode the selection mode the run carries on its selection details
     * @param noCoverageSuites the executed suites that captured no coverage
     * @param executedSuites the suites that executed
     * @return the result
     */
    private static TestRunResult run(final SelectionMode mode, final Set<String> noCoverageSuites,
                                     final String... executedSuites) {
        return run(new TestJvmContribution(), TestRunResultFixture.runIdentity(), mode,
                Collections.<String>emptySet(), noCoverageSuites, executedSuites);
    }

    /**
     * Build a run result for one plan of a given build, in a given JVM, whose runner discovered
     * both suites and executed the named ones. Each executed suite covers one method of its own,
     * unless it is named in {@code noCoverageSuites}, when its capture is empty.
     *
     * @param jvm the test JVM the plan ran in
     * @param build the run the plan belongs to
     * @param mode the selection mode the run carries on its selection details
     * @param failedSuites the executed suites that failed
     * @param noCoverageSuites the executed suites that captured no coverage
     * @param executedSuites the suites that executed
     * @return the result
     */
    private static TestRunResult run(final TestJvmContribution jvm, final TestRunIdentity build,
                                     final SelectionMode mode, final Set<String> failedSuites,
                                     final Set<String> noCoverageSuites, final String... executedSuites) {
        Map<String, TestSuiteTracker> executed = new HashMap<>();
        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        for (String name : executedSuites) {
            TestSuiteTracker tracker = new TestSuiteTracker(name);
            tracker.getTestStats().setNumRuns(1);
            tracker.getTestStats().setAvgRunTime(100L);
            tracker.getTestStats().setNumSuccessRuns(1);
            List<ClassImpactTracker> classes = new ArrayList<>();
            if (!noCoverageSuites.contains(name)) {
                int methodId = name.equals(SUITE_A) ? 1 : 2;
                String filename = "com/example/" + name + ".java";
                classes.add(new ClassImpactTracker(filename, Collections.singletonList(methodId)));
                methods.put(methodId, new MethodImpactTracker(filename + ".m" + methodId + ".()V", 1, 2));
            }
            tracker.setClassesImpacted(classes);
            executed.put(name, tracker);
        }
        TestStats runStats = new TestStats();
        runStats.setNumRuns(1);
        runStats.setAvgRunTime(100L);
        runStats.setNumSuccessRuns(1);
        return new TestRunResult(executed, failedSuites, DISCOVERED,
                executed.keySet(), executed.keySet(), methods, runStats, null, 0,
                TestRunResultFixture.planOutcome(executed.keySet(), failedSuites),
                TestRunSelectionDetails.forFullRun(mode), jvm, build);
    }

    /**
     * @param names the names to collect
     * @return a mutable set of the names
     */
    private static Set<String> setOf(final String... names) {
        return new HashSet<>(Arrays.asList(names));
    }
}
