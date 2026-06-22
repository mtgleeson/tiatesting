package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link TestStats#incrementStats(TestStats, boolean)} keeps the selected-run average
 * ({@code avgRunTime}) and the all-tests-run average ({@code allTestsRunTime} / {@code
 * numAllTestsRuns}) independent: each regime folds the incoming duration into only its own
 * rolling average, while the shared totals ({@code numRuns}, success/fail counts) advance for
 * both. A retry carrying {@code numRuns == 0} is a no-op.
 */
class TestStatsAllTestsRunTimeTest {

    /**
     * A single run carrying a duration, with {@code numRuns == 1} and the given success/fail split.
     */
    private static TestStats run(long durationMs, long successRuns, long failRuns){
        TestStats stats = new TestStats();
        stats.setNumRuns(1);
        stats.setAvgRunTime(durationMs);
        stats.setNumSuccessRuns(successRuns);
        stats.setNumFailRuns(failRuns);
        return stats;
    }

    /**
     * Selected runs (allTestsRun=false) fold into avgRunTime only; the all-tests stats stay zero.
     */
    @Test
    void incrementStats_selectedRunsOnly_updateAvgRunTimeOnly(){
        // given
        TestStats stats = new TestStats();

        // when
        stats.incrementStats(run(100, 1, 0), false);
        stats.incrementStats(run(300, 1, 0), false);

        // then
        assertEquals(200, stats.getAvgRunTime());
        assertEquals(2, stats.getNumRuns());
        assertEquals(0, stats.getAllTestsRunTime());
        assertEquals(0, stats.getNumAllTestsRuns());
    }

    /**
     * All-tests runs (allTestsRun=true) fold into allTestsRunTime and bump numAllTestsRuns; the
     * selected average stays zero.
     */
    @Test
    void incrementStats_allTestsRunsOnly_updateAllTestsRunTimeOnly(){
        // given
        TestStats stats = new TestStats();

        // when
        stats.incrementStats(run(400, 1, 0), true);
        stats.incrementStats(run(600, 1, 0), true);

        // then
        assertEquals(500, stats.getAllTestsRunTime());
        assertEquals(2, stats.getNumAllTestsRuns());
        assertEquals(2, stats.getNumRuns());
        assertEquals(0, stats.getAvgRunTime());
    }

    /**
     * Interleaving the two regimes keeps both averages and both counters independent.
     */
    @Test
    void incrementStats_interleaved_keepsAveragesIndependent(){
        // given
        TestStats stats = new TestStats();

        // when - seed all-tests run, then two selected runs, then another all-tests run
        stats.incrementStats(run(1000, 1, 0), true);   // allTests: avg 1000, count 1
        stats.incrementStats(run(100, 1, 0), false);   // selected: avg 100, count 1
        stats.incrementStats(run(300, 1, 0), false);   // selected: avg 200, count 2
        stats.incrementStats(run(2000, 1, 0), true);   // allTests: avg 1500, count 2

        // then
        assertEquals(200, stats.getAvgRunTime());
        assertEquals(1500, stats.getAllTestsRunTime());
        assertEquals(2, stats.getNumAllTestsRuns());
        assertEquals(4, stats.getNumRuns());
    }

    /**
     * Both regimes advance the shared totals: numRuns and the success/fail counters.
     */
    @Test
    void incrementStats_bothRegimes_advanceSharedTotals(){
        // given
        TestStats stats = new TestStats();

        // when
        stats.incrementStats(run(100, 1, 0), true);
        stats.incrementStats(run(200, 0, 1), false);

        // then
        assertEquals(2, stats.getNumRuns());
        assertEquals(1, stats.getNumSuccessRuns());
        assertEquals(1, stats.getNumFailRuns());
    }

    /**
     * A Surefire retry carrying numRuns == 0 is a no-op in both regimes.
     */
    @Test
    void incrementStats_retryWithZeroRuns_isNoOp(){
        // given
        TestStats stats = new TestStats();
        stats.incrementStats(run(100, 1, 0), false);
        TestStats retry = new TestStats(); // numRuns defaults to 0

        // when
        stats.incrementStats(retry, true);
        stats.incrementStats(retry, false);

        // then
        assertEquals(100, stats.getAvgRunTime());
        assertEquals(1, stats.getNumRuns());
        assertEquals(0, stats.getNumAllTestsRuns());
        assertEquals(0, stats.getAllTestsRunTime());
    }
}
