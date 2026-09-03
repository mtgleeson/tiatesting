package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock the scope of the per-suite write: a run persists the suites it actually touched, not every
 * suite in the project.
 *
 * <p>The persist used to read every tracked suite, merge in memory, and write the whole map back.
 * That made it a read-modify-write over the entire table, so a build committing inside the window
 * had its increments overwritten on suites the writing build had never even run - the two builds
 * did not need to overlap on a single suite, only in time. It also meant a project running twelve
 * selected suites out of five thousand issued five thousand row writes.
 *
 * <p>What a run touches is: the suites it executed, plus any suite whose {@code developerDisabled}
 * flag its observations changed. Everything else it merely read, and has nothing to say about.
 *
 * <p><b>Not covered here:</b> two builds that both ran the <em>same</em> suite still lose one of the
 * two increments, because the surviving write is still absolute rather than accumulating. That is a
 * separate change; this one removes the far larger blast radius around it.
 */
class TestRunnerServiceSuiteWriteScopeTest {

    private static final String SUITE_A = "com.example.ATest";
    private static final String SUITE_B = "com.example.BTest";
    private static final String SUITE_C = "com.example.CTest";

    private RecordingDataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-suite-write-scope-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new RecordingDataStore(tempDir);
        dataStore.getTiaData(true);

        TiaData core = dataStore.getTiaData(true);
        core.setCommitValue("commit-0");
        core.setBranch("main");
        core.setLastUpdated(Instant.now());
        dataStore.persistCoreData(core);

        Map<String, TestSuiteTracker> seed = new HashMap<>();
        seed.put(SUITE_A, seeded(SUITE_A));
        seed.put(SUITE_B, seeded(SUITE_B));
        seed.put(SUITE_C, seeded(SUITE_C));
        dataStore.persistTestSuites(seed);
        dataStore.writtenSuites.clear();
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
     * The regression this change exists for: a build increments suite B's stats while another build
     * - which ran only suite A - is mid-persist. B's increment must survive.
     */
    @Test
    void aConcurrentIncrementOnAnUntouchedSuiteSurvives() {
        // given - the other build runs suite B inside this build's read-to-write window
        dataStore.afterNextSuiteRead = () -> runBuild(SUITE_B, 300L);

        // when - this build runs only suite A
        runBuild(SUITE_A, 500L);

        // then
        TestSuiteTracker suiteB = dataStore.getTestSuitesTracked().get(SUITE_B);
        assertEquals(5L, suiteB.getTestStats().getNumRuns(),
                "the other build's increment on a suite this build never ran must not be clobbered");
    }

    /**
     * A run that executed one suite writes one suite row, not the whole table.
     */
    @Test
    void onlyTheExecutedSuiteIsWritten() {
        // given / when
        runBuild(SUITE_A, 500L);

        // then
        assertEquals(1, dataStore.writtenSuites.size(), "exactly one persist call expected");
        assertEquals(new HashSet<>(Arrays.asList(SUITE_A)), dataStore.writtenSuites.get(0),
                "a run that executed one suite must write only that suite's row");
    }

    /**
     * A suite the run did not execute still gets written when the run's observations changed its
     * developer-disabled flag - Tia selected it and the runner discovered it, but it never ran, so
     * the developer disabled it. That is a fact only this run learned, so it must reach the DB.
     */
    @Test
    void aSuiteWhoseDeveloperDisabledFlagChangedIsAlsoWritten() {
        // given - Tia selected A and B and the runner discovered both, but only A executed
        Set<String> selected = new HashSet<>(Arrays.asList(SUITE_A, SUITE_B));
        Set<String> discovered = new HashSet<>(Arrays.asList(SUITE_A, SUITE_B, SUITE_C));

        // when
        runBuild(executedSuites(SUITE_A), selected, discovered, 500L);

        // then - B is written because its flag flipped; C is not, it was never in play
        assertTrue(dataStore.writtenSuites.get(0).contains(SUITE_A), "the executed suite is written");
        assertTrue(dataStore.writtenSuites.get(0).contains(SUITE_B),
                "a suite whose developer-disabled flag changed must be written");
        assertFalse(dataStore.writtenSuites.get(0).contains(SUITE_C),
                "a suite the run neither ran nor learned anything about must not be written");
        assertTrue(dataStore.getTestSuitesTracked().get(SUITE_B).isDeveloperDisabled());
    }

    /**
     * A flag that did not change is not a reason to write the row. Re-running the same suite twice
     * must not start writing its peers.
     */
    @Test
    void anUnchangedFlagDoesNotWidenTheWrite() {
        // given - a first run that leaves every flag as it found it
        runBuild(SUITE_A, 500L);
        dataStore.writtenSuites.clear();

        // when - the same shape of run again
        runBuild(SUITE_A, 500L);

        // then
        assertEquals(new HashSet<>(Arrays.asList(SUITE_A)), dataStore.writtenSuites.get(0),
                "an unchanged flag must not pull other suites into the write");
    }

    /**
     * Narrowing the write must not break deletion: a suite the runner no longer knows about is
     * still removed, which is a separate call rather than an absence from the written map.
     */
    @Test
    void aDeletedSuiteIsStillRemoved() {
        // given - the runner no longer discovers suite C
        Set<String> discovered = new HashSet<>(Arrays.asList(SUITE_A, SUITE_B));

        // when
        runBuild(executedSuites(SUITE_A), new HashSet<>(Arrays.asList(SUITE_A)), discovered, 500L);

        // then
        assertFalse(dataStore.getTestSuitesTracked().containsKey(SUITE_C),
                "a suite the runner no longer knows about must still be deleted");
        assertTrue(dataStore.getTestSuitesTracked().containsKey(SUITE_B),
                "a suite the runner still knows about must survive");
    }

    /**
     * Run a build that executed one suite, with Tia having selected only that suite and the runner
     * discovering all three.
     *
     * @param suiteName the suite the build executed
     * @param durationMs the suite's measured duration
     */
    private void runBuild(final String suiteName, final long durationMs) {
        runBuild(executedSuites(suiteName), new HashSet<>(Arrays.asList(suiteName)),
                new HashSet<>(Arrays.asList(SUITE_A, SUITE_B, SUITE_C)), durationMs);
    }

    /**
     * Drive a persist for a build with the given executed suites, selection and discovery sets.
     *
     * @param executed the suites that executed, with their measured stats
     * @param selectedTests the suites Tia selected to run
     * @param runnerTestSuites the suites the runner discovered
     * @param durationMs the run duration to report at the Tia level
     */
    private void runBuild(final Map<String, TestSuiteTracker> executed, final Set<String> selectedTests,
                          final Set<String> runnerTestSuites, final long durationMs) {
        TestStats runStats = new TestStats();
        runStats.setNumRuns(1);
        runStats.setAvgRunTime(durationMs);
        runStats.setNumSuccessRuns(1);

        TestRunResult result = new TestRunResult(executed, new HashSet<>(), runnerTestSuites,
                runnerTestSuites, selectedTests, new HashMap<>(), runStats, null, 1, executed.size());
        new TestRunnerService(dataStore).persistTestRunData(true, false,
                "commit-" + durationMs, "main", System.currentTimeMillis(), result, null);
    }

    /**
     * Build the executed-suite map for one suite that ran and measured a time.
     *
     * @param suiteName the suite that executed
     * @return the tracker map a listener would hand the persist
     */
    private Map<String, TestSuiteTracker> executedSuites(final String suiteName) {
        TestSuiteTracker ran = new TestSuiteTracker(suiteName);
        ran.getTestStats().setNumRuns(1);
        ran.getTestStats().setAvgRunTime(500L);
        ran.getTestStats().setNumSuccessRuns(1);
        Map<String, TestSuiteTracker> executed = new HashMap<>();
        executed.put(suiteName, ran);
        return executed;
    }

    /**
     * A suite as a prior build left it: four recorded runs averaging 100ms.
     *
     * @param name the suite name
     * @return the seeded tracker
     */
    private static TestSuiteTracker seeded(final String name) {
        TestSuiteTracker tracker = new TestSuiteTracker(name);
        tracker.getTestStats().setNumRuns(4);
        tracker.getTestStats().setAvgRunTime(100);
        tracker.getTestStats().setNumSuccessRuns(4);
        return tracker;
    }

    /**
     * Real embedded H2 store that records which suites each persist wrote, and can fire a one-shot
     * hook after a suite read so a test can place a competing build inside the read-to-write window.
     */
    private static final class RecordingDataStore extends JdbcDataStore {

        final List<Set<String>> writtenSuites = new ArrayList<>();
        private Runnable afterNextSuiteRead;
        private boolean suppressed;

        /**
         * @param databaseDir the directory to hold the embedded database files
         */
        RecordingDataStore(final File databaseDir) {
            super(new H2Dialect(),
                    new H2ConnectionProvider(H2ConnectionSettings.embedded(databaseDir.getAbsolutePath())),
                    BranchSchema.schemaName("test"));
        }

        /**
         * Read the tracked suites, then fire the interleave hook if a test armed one.
         *
         * @return the stored suites
         */
        @Override
        public Map<String, TestSuiteTracker> getTestSuitesTracked() {
            Map<String, TestSuiteTracker> tracked = super.getTestSuitesTracked();

            if (!suppressed && afterNextSuiteRead != null) {
                Runnable hook = afterNextSuiteRead;
                afterNextSuiteRead = null;
                suppressed = true;
                try {
                    hook.run();
                } finally {
                    suppressed = false;
                }
            }

            return tracked;
        }

        /**
         * Record the names written by this persist, then delegate.
         *
         * @param testSuites the suites to write
         */
        @Override
        public void persistTestSuites(final Map<String, TestSuiteTracker> testSuites) {
            writtenSuites.add(new HashSet<>(testSuites.keySet()));
            super.persistTestSuites(testSuites);
        }
    }
}
