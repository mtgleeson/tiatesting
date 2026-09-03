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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Lock the Tia-level run stats against lost updates.
 *
 * <p>A run reads the core row at the start of its persist and writes at the end, with the whole
 * mapping persist in between - seconds to minutes. Merging the new figures onto that snapshot and
 * writing back absolutes made the update a read-modify-write across that window, so a second run
 * committing inside it had its increment silently overwritten. The stats now go to the seal as a
 * delta the store accumulates in SQL, against the row's value at write time.
 *
 * <p>These tests interleave two seals deterministically rather than racing threads: the second run
 * commits inside the first run's read-to-write window, which is exactly the shape of the lost
 * update.
 */
class TestRunnerServiceConcurrentStatsTest {

    private InterleavingDataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-concurrent-stats-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new InterleavingDataStore(tempDir);
        dataStore.getTiaData(true);

        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("commit-0");
        tiaData.setBranch("main");
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
     * The regression this change exists for: a build commits its stats inside another build's
     * read-to-write window, and both increments survive.
     */
    @Test
    void anIncrementLandingMidPersistIsNotLost() {
        // given - build B seals while build A is between its core read and its core write
        TestRunnerService buildA = new TestRunnerService(dataStore);
        dataStore.afterNextCoreRead = () -> sealAsAnotherBuildWould("commit-b", 400L);

        // when
        buildA.persistTestRunData(true, false, "commit-a", "main",
                System.currentTimeMillis(), partialRun(200L), null);

        // then - two runs recorded, not one
        TestStats stats = dataStore.getTiaCore().getTestStats();
        assertEquals(2L, stats.getNumRuns(),
                "both builds' runs must be counted; a lost update would record only one");
        assertEquals(2L, stats.getNumSuccessRuns());
    }

    /**
     * The averages must survive the interleave too, not just the counters: the mean of the two
     * durations, computed against whatever the row held at each write.
     */
    @Test
    void theAverageFoldsInBothInterleavedRuns() {
        // given
        TestRunnerService buildA = new TestRunnerService(dataStore);
        dataStore.afterNextCoreRead = () -> sealAsAnotherBuildWould("commit-b", 400L);

        // when
        buildA.persistTestRunData(true, false, "commit-a", "main",
                System.currentTimeMillis(), partialRun(200L), null);

        // then - (400 + 200) / 2
        assertEquals(300L, dataStore.getTiaCore().getTestStats().getAvgRunTime());
    }

    /**
     * The SQL accumulation must produce exactly what the in-memory rolling average produced, or the
     * stored figures would shift the day this changed. Three partial runs, checked against the same
     * arithmetic {@code TiaData.incrementStats} performs.
     */
    @Test
    void theAccumulatedAverageMatchesTheInMemoryArithmetic() {
        // given
        TestRunnerService service = new TestRunnerService(dataStore);
        long[] durations = {100L, 250L, 900L};

        TiaData expected = new TiaData();
        for (long duration : durations) {
            TestStats runStats = new TestStats();
            runStats.setNumRuns(1);
            runStats.setAvgRunTime(duration);
            runStats.setNumSuccessRuns(1);
            expected.incrementStats(runStats, false);
        }

        // when
        for (long duration : durations) {
            service.persistTestRunData(true, false, "commit-" + duration, "main",
                    System.currentTimeMillis(), partialRun(duration), null);
        }

        // then
        TestStats stored = dataStore.getTiaCore().getTestStats();
        assertEquals(expected.getTestStats().getAvgRunTime(), stored.getAvgRunTime());
        assertEquals(expected.getTestStats().getNumRuns(), stored.getNumRuns());
    }

    /**
     * An all-tests run folds into the all-tests baseline and leaves the selected-run average alone,
     * exactly as the in-memory routing did - the two averages must stay independent through the
     * SQL rewrite.
     */
    @Test
    void anAllTestsRunFeedsOnlyTheAllTestsBaseline() {
        // given
        TestRunnerService service = new TestRunnerService(dataStore);

        // when - one selected run, then one all-tests run
        service.persistTestRunData(true, false, "commit-1", "main",
                System.currentTimeMillis(), partialRun(200L), null);
        service.persistTestRunData(true, false, "commit-2", "main",
                System.currentTimeMillis(), allTestsRun(1000L), null);

        // then
        TestStats stats = dataStore.getTiaCore().getTestStats();
        assertEquals(200L, stats.getAvgRunTime(), "the all-tests run must not move the selected average");
        assertEquals(1000L, stats.getAllTestsRunTime());
        assertEquals(1L, stats.getNumAllTestsRuns());
        assertEquals(2L, stats.getNumRuns());
    }

    /**
     * A Surefire retry contributes a zero-run increment, which must leave every stats column exactly
     * as it was rather than dividing by a bumped denominator.
     */
    @Test
    void aRetryContributesNothing() {
        // given - one recorded run
        TestRunnerService service = new TestRunnerService(dataStore);
        service.persistTestRunData(true, false, "commit-1", "main",
                System.currentTimeMillis(), partialRun(200L), null);

        // when - a retry, whose stats carry no run
        service.persistTestRunData(true, false, "commit-1", "main",
                System.currentTimeMillis(), retry(), null);

        // then
        TestStats stats = dataStore.getTiaCore().getTestStats();
        assertEquals(1L, stats.getNumRuns(), "a retry must not be counted as another run");
        assertEquals(200L, stats.getAvgRunTime(), "and must not move the average");
    }

    /**
     * The first seal on a database with no core row has nothing to accumulate against, so the
     * increment becomes the absolute value. That path is the INSERT rather than the UPDATE.
     */
    @Test
    void theFirstSealOnAnEmptyStoreRecordsTheIncrementAsTheAbsoluteValue() throws Exception {
        // given - a store with no core row at all
        File emptyDir = File.createTempFile("tia-concurrent-stats-empty-", "");
        emptyDir.delete();
        emptyDir.mkdirs();
        try (JdbcDataStore emptyStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(emptyDir.getAbsolutePath())),
                BranchSchema.schemaName("test"))) {
            emptyStore.getTiaData(true);
            TestRunnerService service = new TestRunnerService(emptyStore);

            // when
            service.persistTestRunData(true, false, "commit-1", "main",
                    System.currentTimeMillis(), partialRun(750L), null);

            // then
            TestStats stats = emptyStore.getTiaCore().getTestStats();
            assertEquals(1L, stats.getNumRuns());
            assertEquals(750L, stats.getAvgRunTime());
        } finally {
            for (File f : emptyDir.listFiles()) {
                f.delete();
            }
            emptyDir.delete();
        }
    }

    /**
     * Stand in for another build sealing its own run while the build under test is mid-persist.
     * Uses a separate service over the same store, reading the core row directly so the interleave
     * hook does not re-enter itself.
     *
     * @param commitValue the commit the other build seals
     * @param durationMs the other build's run duration
     */
    private void sealAsAnotherBuildWould(final String commitValue, final long durationMs) {
        new TestRunnerService(dataStore.withoutHook())
                .persistTestRunData(true, false, commitValue, "main",
                        System.currentTimeMillis(), partialRun(durationMs), null);
    }

    /**
     * Build a result for a partial (Tia-selected) run of the given duration.
     *
     * @param durationMs the run's duration in ms
     * @return the populated result
     */
    private TestRunResult partialRun(final long durationMs) {
        return result(durationMs, 1, 3);
    }

    /**
     * Build a result for an all-tests run (zero suites ignored) of the given duration.
     *
     * @param durationMs the run's duration in ms
     * @return the populated result
     */
    private TestRunResult allTestsRun(final long durationMs) {
        return result(durationMs, 1, 0);
    }

    /**
     * Build a result whose stats carry no run, as a Surefire retry's do.
     *
     * @return the populated result
     */
    private TestRunResult retry() {
        return result(0L, 0, 3);
    }

    /**
     * Build a {@link TestRunResult} with one tracked suite and the given Tia-level stats.
     *
     * @param durationMs the run duration to report
     * @param numRuns the run count to report; {@code 0} for a retry
     * @param ignoredTestSuiteCount the selector's ignore count, which decides all-tests vs selected
     * @return the populated result
     */
    private TestRunResult result(final long durationMs, final long numRuns,
                                 final int ignoredTestSuiteCount) {
        TestStats runStats = new TestStats();
        runStats.setNumRuns(numRuns);
        runStats.setAvgRunTime(durationMs);
        runStats.setNumSuccessRuns(numRuns);

        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        trackers.put("com.example.SomeTest", new TestSuiteTracker("com.example.SomeTest"));
        return new TestRunResult(trackers, new HashSet<>(), new HashSet<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>(), runStats, null, ignoredTestSuiteCount, 1);
    }

    /**
     * Real embedded H2 store that can fire a one-shot hook immediately after a core read, letting a
     * test place a competing write inside the read-to-write window.
     */
    private static final class InterleavingDataStore extends JdbcDataStore {

        /** Runs once after the next {@link #getTiaCore()}, then clears itself. */
        private Runnable afterNextCoreRead;

        /** When true this instance reports reads without firing the hook. */
        private boolean hookSuppressed;

        /**
         * @param databaseDir the directory to hold the embedded database files
         */
        InterleavingDataStore(final File databaseDir) {
            super(new H2Dialect(),
                    new H2ConnectionProvider(H2ConnectionSettings.embedded(databaseDir.getAbsolutePath())),
                    BranchSchema.schemaName("test"));
        }

        /**
         * Read the core row, then fire the interleave hook if a test armed one.
         *
         * @return the stored core data
         */
        @Override
        public TiaData getTiaCore() {
            TiaData tiaData = super.getTiaCore();

            if (!hookSuppressed && afterNextCoreRead != null) {
                Runnable hook = afterNextCoreRead;
                afterNextCoreRead = null;
                hookSuppressed = true;
                try {
                    hook.run();
                } finally {
                    hookSuppressed = false;
                }
            }

            return tiaData;
        }

        /**
         * @return this store, with the interleave hook suppressed for the duration of the competing
         *         build's own persist so it does not re-enter the hook
         */
        InterleavingDataStore withoutHook() {
            return this;
        }
    }
}
