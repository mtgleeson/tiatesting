package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.SelectionMode;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestRunTrigger;
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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that a single-host mapping-update run counts its per-method run stats at the seal: one
 * executed run for each method its suites covered, and one triggered run for each changed method
 * its selection was triggered by, with a retry of failed tests adding nothing. See the "Method run
 * stats" chapter in {@code WIKI.md}.
 */
class TestRunnerServiceMethodRunStatsTest {

    private static final String SUITE = "com.example.FooTest";
    private static final String SOURCE_FILE = "com/example/Foo.java";
    private static final String METHOD_NAME = "com/example/Foo.save.()V";
    private static final int METHOD_ID = 7;
    private static final String OTHER_SUITE = "com.example.FooOtherTest";
    private static final String OTHER_METHOD_NAME = "com/example/Foo.load.()V";
    private static final int OTHER_METHOD_ID = 8;

    private JdbcDataStore dataStore;
    private TestRunnerService service;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database with a sealed commit and build the service over it.
     *
     * @throws Exception if the temp directory cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-method-run-stats-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
        service = new TestRunnerService(dataStore);

        TiaData tiaData = dataStore.getTiaData();
        tiaData.setCommitValue("initial");
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);
    }

    /**
     * Close the data store and remove the temp database files.
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
     * A first-attempt mapping-update run that executed the method and was triggered by a change to
     * it counts one executed run and one triggered run against the method.
     */
    @Test
    void mappingRunCountsExecutedAndTriggeredRun() {
        // given
        TestRunResult result = runResult(RunAttempt.FIRST);

        // when
        service.persistTestRunData(true, false, "commit-1", "main", System.currentTimeMillis(), result, null);

        // then
        MethodImpactTracker method = dataStore.getMethodsTracked().get(METHOD_ID);
        assertEquals(1L, method.getExecutedRunCount());
        assertEquals(1L, method.getTriggeredRunCount());
    }

    /**
     * A catalogued method that a run's suites loaded but didn't execute - another suite covers it,
     * and the coverage client reports every method of a covered class so the seal can refresh its
     * line numbers - gains no executed run, while the method the run did execute does.
     */
    @Test
    void methodLoadedButNotExecutedByTheRunIsNotCounted() {
        // given - a seed where OTHER_SUITE covers both methods, so both are catalogued
        Set<String> bothSuites = new HashSet<>(Arrays.asList(SUITE, OTHER_SUITE));
        service.persistTestRunData(true, false, "commit-1", "main", System.currentTimeMillis(),
                runResult(RunAttempt.FIRST, OTHER_SUITE, new HashSet<>(Arrays.asList(METHOD_ID, OTHER_METHOD_ID)),
                        bothSuites), null);
        TestRunResult result = runResult(RunAttempt.FIRST, SUITE, Collections.singleton(METHOD_ID), bothSuites);

        // when - SUITE executes only METHOD_ID, though its class's other method is observed too
        service.persistTestRunData(true, false, "commit-2", "main", System.currentTimeMillis(), result, null);

        // then
        assertEquals(Collections.singleton(METHOD_ID), result.getCoveredMethodIds());
        assertEquals(2L, dataStore.getMethodsTracked().get(METHOD_ID).getExecutedRunCount());
        assertEquals(1L, dataStore.getMethodsTracked().get(OTHER_METHOD_ID).getExecutedRunCount());
    }

    /**
     * A retry of failed tests carries the same coverage and selection as the first attempt, which
     * already counted the run, so it leaves both counts as they were.
     */
    @Test
    void retryDoesNotCountTheRunAgain() {
        // given
        service.persistTestRunData(true, false, "commit-1", "main", System.currentTimeMillis(),
                runResult(RunAttempt.FIRST), null);
        TestRunResult retry = runResult(RunAttempt.RERUN_SAME_JVM);

        // when
        service.persistTestRunData(true, false, "commit-1", "main", System.currentTimeMillis(), retry, null);

        // then
        MethodImpactTracker method = dataStore.getMethodsTracked().get(METHOD_ID);
        assertEquals(1L, method.getExecutedRunCount());
        assertEquals(1L, method.getTriggeredRunCount());
    }

    /**
     * Build the result of a run whose one suite covered the method, selected because the method
     * changed.
     *
     * @param runAttempt which attempt at the test task's run this is
     * @return the run result
     */
    private TestRunResult runResult(RunAttempt runAttempt) {
        return runResult(runAttempt, SUITE, Collections.singleton(METHOD_ID), Collections.singleton(SUITE));
    }

    /**
     * Build the result of a run in which one suite executed the given methods of the Foo class,
     * selected because METHOD_ID changed. The observed trackers hold both of Foo's methods whatever
     * the suite executed, as the coverage client reports every method of a covered class.
     *
     * @param runAttempt which attempt at the test task's run this is
     * @param suiteName the suite the run executed
     * @param coveredMethodIds the Foo methods the suite executed
     * @param runnerSuites every suite the runner knows of, so suites the run skipped aren't deleted
     * @return the run result
     */
    private TestRunResult runResult(RunAttempt runAttempt, String suiteName, Set<Integer> coveredMethodIds,
                                    Set<String> runnerSuites) {
        TestSuiteTracker suite = new TestSuiteTracker(suiteName);
        suite.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker(SOURCE_FILE, new HashSet<>(coveredMethodIds))));
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        trackers.put(suiteName, suite);
        Set<String> suites = Collections.singleton(suiteName);

        Map<Integer, MethodImpactTracker> observed = new HashMap<>();
        observed.put(METHOD_ID, new MethodImpactTracker(METHOD_NAME, 3, 6));
        observed.put(OTHER_METHOD_ID, new MethodImpactTracker(OTHER_METHOD_NAME, 8, 10));

        TestRunSelectionDetails details = new TestRunSelectionDetails(Collections.singletonList(
                new TestRunTrigger(TestRunTrigger.Type.SOURCE_METHOD, METHOD_NAME, METHOD_ID, 1)),
                0, 0, 0, 0, 0, SelectionMode.SELECTIVE);

        return new TestRunResult(trackers, new HashSet<>(), runnerSuites, suites, suites, observed,
                new TestStats(), null, runnerSuites.size() - 1, 1, 0, details, runAttempt);
    }
}
