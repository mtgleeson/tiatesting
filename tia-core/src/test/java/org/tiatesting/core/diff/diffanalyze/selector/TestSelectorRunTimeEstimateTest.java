package org.tiatesting.core.diff.diffanalyze.selector;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link TestSelector#estimateRunTime(Set, Map)} computes the correct total
 * runtime for a selected test set, applies the median {@code avgRunTime} as a fallback for
 * tests without recorded stats, and excludes zero-valued {@code avgRunTime}s from the
 * median calculation.
 */
class TestSelectorRunTimeEstimateTest {

    /**
     * All selected tests have recorded stats - the total is a straight sum of
     * {@code avgRunTime}, no fallback is needed, and the median is reported as {@code 0}.
     */
    @Test
    void estimateRunTime_allSelectedHaveStats_sumsAvgRunTime(){
        // given
        Map<String, TestSuiteTracker> tracked = buildTrackedSuites(entry("test1", 100L), entry("test2", 200L), entry("test3", 300L));
        Set<String> testsToRun = setOf("test1", "test2", "test3");

        // when
        TestSelector.RunTimeEstimate estimate = TestSelector.estimateRunTime(testsToRun, tracked, 0L);

        // then
        assertEquals(600L, estimate.getEstimatedRunTimeMs());
        assertTrue(estimate.getSelectedTestsWithoutStats().isEmpty());
        assertEquals(0L, estimate.getMedianRunTimeMsAppliedToMissing());
        assertEquals(perTestMap(entry("test1", 100L), entry("test2", 200L), entry("test3", 300L)),
                estimate.getSelectedTestRunTimesMs());
    }

    /**
     * One selected test is missing stats - the median {@code avgRunTime} across tracked
     * suites is used for that test, and the missing test name is captured for display.
     */
    @Test
    void estimateRunTime_someSelectedMissingStats_appliesMedianFallback(){
        // given
        Map<String, TestSuiteTracker> tracked = buildTrackedSuites(entry("test1", 100L), entry("test2", 200L), entry("test3", 300L));
        Set<String> testsToRun = setOf("test1", "test2", "newTest");

        // when
        TestSelector.RunTimeEstimate estimate = TestSelector.estimateRunTime(testsToRun, tracked, 0L);

        // then
        assertEquals(100L + 200L + 200L, estimate.getEstimatedRunTimeMs());
        assertEquals(setOf("newTest"), estimate.getSelectedTestsWithoutStats());
        assertEquals(200L, estimate.getMedianRunTimeMsAppliedToMissing());
        // newTest's per-test entry equals the median
        assertEquals(perTestMap(entry("test1", 100L), entry("test2", 200L), entry("newTest", 200L)),
                estimate.getSelectedTestRunTimesMs());
    }

    /**
     * Every selected test is new - the total is purely the median multiplied by the count
     * of missing tests, and all missing names are captured.
     */
    @Test
    void estimateRunTime_allSelectedMissingStats_appliesMedianForAll(){
        // given
        Map<String, TestSuiteTracker> tracked = buildTrackedSuites(entry("test1", 100L), entry("test2", 200L), entry("test3", 300L));
        Set<String> testsToRun = setOf("newTest1", "newTest2");

        // when
        TestSelector.RunTimeEstimate estimate = TestSelector.estimateRunTime(testsToRun, tracked, 0L);

        // then
        assertEquals(400L, estimate.getEstimatedRunTimeMs());
        assertEquals(setOf("newTest1", "newTest2"), estimate.getSelectedTestsWithoutStats());
        assertEquals(200L, estimate.getMedianRunTimeMsAppliedToMissing());
        assertEquals(perTestMap(entry("newTest1", 200L), entry("newTest2", 200L)),
                estimate.getSelectedTestRunTimesMs());
    }

    /**
     * No tracked test suites exist at all - the median is {@code 0}, so missing tests
     * contribute nothing to the total but are still listed for the output note.
     */
    @Test
    void estimateRunTime_noTrackedStatsAtAll_medianIsZero(){
        // given
        Map<String, TestSuiteTracker> tracked = buildTrackedSuites();
        Set<String> testsToRun = setOf("newTest");

        // when
        TestSelector.RunTimeEstimate estimate = TestSelector.estimateRunTime(testsToRun, tracked, 0L);

        // then
        assertEquals(0L, estimate.getEstimatedRunTimeMs());
        assertEquals(setOf("newTest"), estimate.getSelectedTestsWithoutStats());
        assertEquals(0L, estimate.getMedianRunTimeMsAppliedToMissing());
        // newTest carries 0 in the per-test map - the formatter renders this as "(no run data)"
        assertEquals(perTestMap(entry("newTest", 0L)), estimate.getSelectedTestRunTimesMs());
    }

    /**
     * Tracked tests with {@code avgRunTime == 0} (tracked but never recorded a real run)
     * must be excluded from the median calculation so they don't drag it down to zero.
     */
    @Test
    void estimateRunTime_zeroAvgRunTimesExcludedFromMedian(){
        // given
        Map<String, TestSuiteTracker> tracked = buildTrackedSuites(entry("zero1", 0L), entry("test1", 100L),
                entry("test2", 200L), entry("zero2", 0L));
        Set<String> testsToRun = setOf("test1", "newTest");

        // when
        TestSelector.RunTimeEstimate estimate = TestSelector.estimateRunTime(testsToRun, tracked, 0L);

        // then
        // median of [100, 200] (lower of two middles) = 100; total = 100 + 100
        assertEquals(200L, estimate.getEstimatedRunTimeMs());
        assertEquals(setOf("newTest"), estimate.getSelectedTestsWithoutStats());
        assertEquals(100L, estimate.getMedianRunTimeMsAppliedToMissing());
        assertEquals(perTestMap(entry("test1", 100L), entry("newTest", 100L)),
                estimate.getSelectedTestRunTimesMs());
    }

    /**
     * Even-sized populations return the lower of the two middle values to keep the
     * calculation integer-only and deterministic.
     */
    @Test
    void estimateRunTime_evenSizedMedianTakesLowerMiddle(){
        // given
        Map<String, TestSuiteTracker> tracked = buildTrackedSuites(entry("t1", 10L), entry("t2", 20L),
                entry("t3", 30L), entry("t4", 40L));
        Set<String> testsToRun = setOf("newTest");

        // when
        TestSelector.RunTimeEstimate estimate = TestSelector.estimateRunTime(testsToRun, tracked, 0L);

        // then
        assertEquals(20L, estimate.getMedianRunTimeMsAppliedToMissing());
        assertEquals(20L, estimate.getEstimatedRunTimeMs());
        assertEquals(setOf("newTest"), estimate.getSelectedTestsWithoutStats());
        assertEquals(perTestMap(entry("newTest", 20L)), estimate.getSelectedTestRunTimesMs());
    }

    /**
     * An empty {@code testsToRun} set yields a zero total with no missing tests recorded
     * and no median computed (no missing tests to apply it to).
     */
    @Test
    void estimateRunTime_emptyTestsToRun_returnsZero(){
        // given
        Map<String, TestSuiteTracker> tracked = buildTrackedSuites(entry("test1", 100L), entry("test2", 200L));
        Set<String> testsToRun = Collections.emptySet();

        // when
        TestSelector.RunTimeEstimate estimate = TestSelector.estimateRunTime(testsToRun, tracked, 0L);

        // then
        assertEquals(0L, estimate.getEstimatedRunTimeMs());
        assertTrue(estimate.getSelectedTestsWithoutStats().isEmpty());
        assertEquals(0L, estimate.getMedianRunTimeMsAppliedToMissing());
        assertTrue(estimate.getSelectedTestRunTimesMs().isEmpty());
    }

    /**
     * A selected test that is tracked but has {@code avgRunTime == 0} (tracked but never
     * recorded a real run) carries {@code 0} in the per-test map and is <em>not</em> treated
     * as "missing stats" - it has an entry in {@code testSuitesTracked}, just with a zero
     * value.
     */
    @Test
    void estimateRunTime_trackedTestWithZeroAvgRunTime_recordedAsZeroNotMissing(){
        // given
        Map<String, TestSuiteTracker> tracked = buildTrackedSuites(entry("zeroTest", 0L), entry("test1", 100L), entry("test2", 200L));
        Set<String> testsToRun = setOf("zeroTest", "test1");

        // when
        TestSelector.RunTimeEstimate estimate = TestSelector.estimateRunTime(testsToRun, tracked, 0L);

        // then
        assertEquals(100L, estimate.getEstimatedRunTimeMs());
        assertTrue(estimate.getSelectedTestsWithoutStats().isEmpty());
        assertEquals(0L, estimate.getMedianRunTimeMsAppliedToMissing());
        assertEquals(perTestMap(entry("zeroTest", 0L), entry("test1", 100L)),
                estimate.getSelectedTestRunTimesMs());
    }

    /**
     * The mapping overhead is reported separately from the base estimate: it's the full-run
     * baseline minus the sum of per-suite times, amortised across the tracked suites and
     * multiplied by the number of suites selected. The base estimate stays the bare per-suite sum.
     */
    @Test
    void estimateRunTime_reportsAmortisedMappingOverheadSeparately(){
        // given - 3 tracked suites summing to 600ms; full-run baseline 900ms => 300ms overhead
        // over 3 suites = 100ms/suite. Two suites selected.
        Map<String, TestSuiteTracker> tracked = buildTrackedSuites(entry("test1", 100L), entry("test2", 200L), entry("test3", 300L));
        Set<String> testsToRun = setOf("test1", "test2");

        // when
        TestSelector.RunTimeEstimate estimate = TestSelector.estimateRunTime(testsToRun, tracked, 900L);

        // then - base is the per-suite sum; overhead is 100ms/suite x 2 selected = 200ms
        assertEquals(300L, estimate.getEstimatedRunTimeMs());
        assertEquals(200L, estimate.getMappingOverheadMs());
    }

    /**
     * When the full-run baseline is below the sum of per-suite times (e.g. the build runs suites
     * in parallel), the overhead would be negative; it is clamped to zero rather than subtracting.
     */
    @Test
    void estimateRunTime_baselineBelowSum_overheadIsZero(){
        // given - sum is 600ms but baseline is only 400ms (parallel execution)
        Map<String, TestSuiteTracker> tracked = buildTrackedSuites(entry("test1", 100L), entry("test2", 200L), entry("test3", 300L));
        Set<String> testsToRun = setOf("test1", "test2");

        // when
        TestSelector.RunTimeEstimate estimate = TestSelector.estimateRunTime(testsToRun, tracked, 400L);

        // then - base only, no overhead
        assertEquals(300L, estimate.getEstimatedRunTimeMs());
        assertEquals(0L, estimate.getMappingOverheadMs());
    }

    /**
     * With no all-tests baseline recorded, there is nothing to derive overhead from, so it is zero.
     */
    @Test
    void estimateRunTime_noBaseline_overheadIsZero(){
        // given
        Map<String, TestSuiteTracker> tracked = buildTrackedSuites(entry("test1", 100L), entry("test2", 200L));
        Set<String> testsToRun = setOf("test1");

        // when
        TestSelector.RunTimeEstimate estimate = TestSelector.estimateRunTime(testsToRun, tracked, 0L);

        // then
        assertEquals(100L, estimate.getEstimatedRunTimeMs());
        assertEquals(0L, estimate.getMappingOverheadMs());
    }

    /**
     * Build a tracked-suites map pre-populated with the given test-name → {@code avgRunTime}
     * entries. Each entry produces a {@link TestSuiteTracker} carrying a {@link TestStats}
     * with the supplied {@code avgRunTime}.
     *
     * @param entries the entries to seed; may be empty
     * @return the tracked suites keyed by name, containing the entries
     */
    @SafeVarargs
    private static Map<String, TestSuiteTracker> buildTrackedSuites(Map.Entry<String, Long>... entries){
        Map<String, TestSuiteTracker> tracked = new HashMap<>();
        for (Map.Entry<String, Long> e : entries){
            TestSuiteTracker tracker = new TestSuiteTracker(e.getKey());
            TestStats stats = new TestStats();
            stats.setAvgRunTime(e.getValue());
            tracker.setTestStats(stats);
            tracked.put(e.getKey(), tracker);
        }
        return tracked;
    }

    /**
     * Build a {@link Map.Entry} pair for use with {@link #buildTrackedSuites}.
     *
     * @param name the test suite name
     * @param avgRunTime the {@code avgRunTime} (ms) to assign
     * @return a name → avgRunTime entry
     */
    private static Map.Entry<String, Long> entry(String name, long avgRunTime){
        return new AbstractMap.SimpleEntry<>(name, avgRunTime);
    }

    /**
     * Build a {@code Map<String, Long>} of expected per-test runtimes for comparison
     * against {@link TestSelector.RunTimeEstimate#getSelectedTestRunTimesMs()}.
     *
     * @param entries the entries to include in the map
     * @return a {@link HashMap} containing the entries
     */
    @SafeVarargs
    private static Map<String, Long> perTestMap(Map.Entry<String, Long>... entries){
        Map<String, Long> map = new HashMap<>();
        for (Map.Entry<String, Long> e : entries){
            map.put(e.getKey(), e.getValue());
        }
        return map;
    }

    /**
     * Build an ordered {@link Set} of the given strings, preserving insertion order so that
     * assertion failures show the expected values in a stable order.
     *
     * @param values the values to add to the set
     * @return a {@link LinkedHashSet} containing the values
     */
    private static Set<String> setOf(String... values){
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}
