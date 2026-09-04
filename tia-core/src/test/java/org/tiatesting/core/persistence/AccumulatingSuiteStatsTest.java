package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-suite stats accumulate onto the stored row rather than replacing it.
 *
 * <p>Writing them as absolutes made the persist a read-modify-write: a caller merged this run's
 * figures onto a snapshot it had read minutes earlier, so any increment that landed in between was
 * overwritten. The store now adds at write time, which is the only point at which "what the row
 * currently holds" is knowable.
 *
 * <p>Exercised at the datastore rather than through {@code TestRunnerService}, because what is under
 * test is the SQL: the arithmetic, the guard for a write that contributes no run, and the vendor
 * forms - H2 cannot express this at all in the {@code MERGE ... KEY ... VALUES} shape the ordinary
 * upsert uses. {@code PostgresAccumulatingSuiteStatsTest} runs the same assertions against Postgres.
 */
class AccumulatingSuiteStatsTest {

    private static final String SUITE = "com.example.ATest";

    private DataStore dataStore;
    private File tempDir;

    /**
     * Open the store under test. Overridden by the Postgres mirror so the same assertions run
     * against the other vendor's SQL.
     *
     * @return an open datastore the fixture owns and closes
     * @throws Exception if the store cannot be opened
     */
    DataStore openStore() throws Exception {
        tempDir = File.createTempFile("tia-accumulating-suite-", "");
        tempDir.delete();
        tempDir.mkdirs();
        return new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
    }

    @BeforeEach
    void setUp() throws Exception {
        dataStore = openStore();
        dataStore.getTiaData(true);
    }

    @AfterEach
    void tearDown() {
        if (dataStore != null) {
            dataStore.close();
        }
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * A suite the store has never seen takes the insert branch, where the contribution is the
     * absolute value.
     */
    @Test
    void aFirstWriteRecordsTheContributionAsIs() {
        // given / when
        persist(suite(1, 200, 1, 0, false));

        // then
        TestSuiteTracker stored = read();
        assertEquals(1L, stored.getTestStats().getNumRuns());
        assertEquals(200L, stored.getTestStats().getAvgRunTime());
        assertEquals(1L, stored.getTestStats().getNumSuccessRuns());
    }

    /**
     * The regression this change exists for: two writes of one run each leave two runs recorded,
     * with the average folded across both.
     */
    @Test
    void twoContributionsBothCount() {
        // given
        persist(suite(1, 200, 1, 0, false));

        // when
        persist(suite(1, 400, 1, 0, false));

        // then
        TestSuiteTracker stored = read();
        assertEquals(2L, stored.getTestStats().getNumRuns(),
                "both contributions must count; an absolute write would record only the last");
        assertEquals(300L, stored.getTestStats().getAvgRunTime(), "(1*200 + 1*400) / 2");
        assertEquals(2L, stored.getTestStats().getNumSuccessRuns());
    }

    /**
     * The accumulated average must match the arithmetic {@code TestStats.incrementStats} performs in
     * memory, or every stored figure shifts the day this changed.
     */
    @Test
    void theAccumulatedAverageMatchesTheInMemoryArithmetic() {
        // given
        long[] durations = {100L, 250L, 900L, 40L};
        TestSuiteTracker expected = new TestSuiteTracker(SUITE);

        // when
        for (long duration : durations) {
            persist(suite(1, duration, 1, 0, false));
            expected.incrementStats(suite(1, duration, 1, 0, false).getTestStats());
        }

        // then
        assertEquals(expected.getTestStats().getAvgRunTime(), read().getTestStats().getAvgRunTime());
        assertEquals(expected.getTestStats().getNumRuns(), read().getTestStats().getNumRuns());
    }

    /**
     * A write contributing no run - a retry, or a row written only because its developer-disabled
     * flag changed - must leave the stored stats exactly as they were rather than dividing by a
     * denominator it did not move.
     */
    @Test
    void aContributionOfNoRunsLeavesTheStatsAlone() {
        // given
        persist(suite(1, 200, 1, 0, false));

        // when
        persist(suite(0, 0, 0, 0, false));

        // then
        TestSuiteTracker stored = read();
        assertEquals(1L, stored.getTestStats().getNumRuns());
        assertEquals(200L, stored.getTestStats().getAvgRunTime(),
                "a write with no run behind it must not move the average");
    }

    /**
     * The same, on a row that has never recorded a run: the guard has to hold when both sides of
     * the denominator are zero, which is the division-by-zero case.
     */
    @Test
    void aContributionOfNoRunsOnAnEmptyRowIsSafe() {
        // given / when
        persist(suite(0, 0, 0, 0, false));

        // then
        TestSuiteTracker stored = read();
        assertEquals(0L, stored.getTestStats().getNumRuns());
        assertEquals(0L, stored.getTestStats().getAvgRunTime());
    }

    /**
     * The developer-disabled flag is replaced, not accumulated - it is what the run observed about
     * the suite, so the most recent observation wins.
     */
    @Test
    void theDeveloperDisabledFlagIsReplacedNotAccumulated() {
        // given
        persist(suite(1, 200, 1, 0, true));
        assertTrue(read().isDeveloperDisabled());

        // when
        persist(suite(1, 200, 1, 0, false));

        // then
        assertFalse(read().isDeveloperDisabled(), "the latest observation of the flag must win");
    }

    /**
     * A failed run accumulates onto the failure counter, and leaves the success counter alone.
     */
    @Test
    void theSuccessAndFailureCountersAccumulateIndependently() {
        // given
        persist(suite(1, 200, 1, 0, false));

        // when
        persist(suite(1, 200, 0, 1, false));

        // then
        TestSuiteTracker stored = read();
        assertEquals(1L, stored.getTestStats().getNumSuccessRuns());
        assertEquals(1L, stored.getTestStats().getNumFailRuns());
        assertEquals(2L, stored.getTestStats().getNumRuns());
    }

    /**
     * Persist one suite write.
     *
     * @param tracker the suite write to persist
     */
    private void persist(final TestSuiteTracker tracker) {
        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put(tracker.getName(), tracker);
        dataStore.persistTestSuites(suites);
    }

    /**
     * @return the stored tracker for the suite under test
     */
    private TestSuiteTracker read() {
        return dataStore.getTestSuitesTracked().get(SUITE);
    }

    /**
     * Build one run's contribution for the suite under test, carrying a single coverage edge so the
     * write exercises the edge path alongside the stats.
     *
     * @param numRuns the run count this write contributes
     * @param runTimeMs the run time this write contributes
     * @param numSuccessRuns the successful-run count this write contributes
     * @param numFailRuns the failed-run count this write contributes
     * @param developerDisabled the flag value this write observed
     * @return the populated tracker
     */
    private TestSuiteTracker suite(final long numRuns, final long runTimeMs, final long numSuccessRuns,
                                   final long numFailRuns, final boolean developerDisabled) {
        TestSuiteTracker tracker = new TestSuiteTracker(SUITE);
        tracker.getTestStats().setNumRuns(numRuns);
        tracker.getTestStats().setAvgRunTime(runTimeMs);
        tracker.getTestStats().setNumSuccessRuns(numSuccessRuns);
        tracker.getTestStats().setNumFailRuns(numFailRuns);
        tracker.setDeveloperDisabled(developerDisabled);
        tracker.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker("com/example/A.java", new HashSet<>(Collections.singletonList(1)))));
        return tracker;
    }
}
