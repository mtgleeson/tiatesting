package org.tiatesting.core.diff.diffanalyze.selector;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link SelectTestsOutputFormatter} produces the correct user-facing output for
 * each scenario the select-tests task can encounter, covering both the per-test list with
 * bracketed runtimes and the total-estimate block with its optional note.
 */
class SelectTestsOutputFormatterTest {

    private static final String LINE_SEP = "\n";

    /**
     * An empty {@code testsToRun} means there's nothing to estimate - the estimate block
     * formatter must return an empty string so the plugins don't print a stray runtime line.
     */
    @Test
    void formatEstimateBlock_emptyTestsToRun_returnsEmptyString(){
        // given
        TestSelectorResult result = buildResult(setOf(), 0L, setOf(), 0L, perTestMap());

        // when
        String output = SelectTestsOutputFormatter.formatEstimateBlock(result, LINE_SEP);

        // then
        assertEquals("", output);
    }

    /**
     * Every selected test has recorded stats - the estimate block is a single runtime line
     * with no follow-up note. The {@code ms} component is omitted when the duration is
     * one second or more.
     */
    @Test
    void formatEstimateBlock_allSelectedHaveStats_printsRuntimeOnly(){
        // given
        Set<String> testsToRun = setOf("test1", "test2");
        TestSelectorResult result = buildResult(testsToRun, 1500L, setOf(), 0L,
                perTestMap("test1", 500L, "test2", 1000L));

        // when
        String output = SelectTestsOutputFormatter.formatEstimateBlock(result, LINE_SEP);

        // then
        // 1500ms is >= 1s, so the ms component is dropped
        assertEquals(LINE_SEP + "Estimated total run time: 1s", output);
    }

    /**
     * Some selected tests lack stats but a positive median is available - the note states
     * the median value explicitly and counts the new tests, but does <em>not</em> list them
     * (callers can find the names in {@link TestSelectorResult#getSelectedTestsWithoutStats}).
     */
    @Test
    void formatEstimateBlock_someMissingWithMedian_includesMedianNote(){
        // given
        Set<String> testsToRun = setOf("test1", "newTest");
        Set<String> withoutStats = setOf("newTest");
        TestSelectorResult result = buildResult(testsToRun, 700L, withoutStats, 200L,
                perTestMap("test1", 500L, "newTest", 200L));

        // when
        String output = SelectTestsOutputFormatter.formatEstimateBlock(result, LINE_SEP);

        // then
        String expected = LINE_SEP + "Estimated total run time: 700ms" + LINE_SEP + LINE_SEP
                + "Note: 1 selected test(s) have not previously been run by Tia. "
                + "A median run time of 200ms (calculated from all tracked test suites) "
                + "was used for them.";
        assertEquals(expected, output);
        // explicit assertion: the median value must appear in the output
        assertTrue(output.contains("200ms"), "Median value should be stated in the note. Output: " + output);
    }

    /**
     * Missing stats with no historical data to derive a median - the note states the new
     * tests were excluded from the estimate.
     */
    @Test
    void formatEstimateBlock_missingWithNoHistory_includesExcludedNote(){
        // given
        Set<String> testsToRun = setOf("newTest");
        Set<String> withoutStats = setOf("newTest");
        TestSelectorResult result = buildResult(testsToRun, 0L, withoutStats, 0L,
                perTestMap("newTest", 0L));

        // when
        String output = SelectTestsOutputFormatter.formatEstimateBlock(result, LINE_SEP);

        // then
        assertTrue(output.contains("Estimated total run time: "), "Output: " + output);
        assertTrue(output.contains("Note: 1 selected test(s) have not previously been run by Tia"),
                "Output: " + output);
        assertTrue(output.contains("excluded from the estimate"), "Output: " + output);
    }

    /**
     * An empty selection produces an empty list - nothing for the formatter to print.
     */
    @Test
    void formatSelectedTestsList_emptyTestsToRun_returnsEmptyString(){
        // given
        TestSelectorResult result = buildResult(setOf(), 0L, setOf(), 0L, perTestMap());

        // when
        String output = SelectTestsOutputFormatter.formatSelectedTestsList(result, LINE_SEP);

        // then
        assertEquals("", output);
    }

    /**
     * Tests with recorded stats are shown with the formatted {@code avgRunTime} in brackets
     * after the test name.
     */
    @Test
    void formatSelectedTestsList_testsWithStats_printsBracketedRuntime(){
        // given
        Set<String> testsToRun = setOf("test1", "test2");
        TestSelectorResult result = buildResult(testsToRun, 1500L, setOf(), 0L,
                perTestMap("test1", 500L, "test2", 1000L));

        // when
        String output = SelectTestsOutputFormatter.formatSelectedTestsList(result, LINE_SEP);

        // then
        assertEquals("\ttest1 (500ms)" + LINE_SEP + "\ttest2 (1s)", output);
    }

    /**
     * Tests without stats are shown with the substituted median value in brackets, just
     * like tests with recorded stats - the per-test display doesn't single them out; the
     * estimate-block note tells the user the median was used.
     */
    @Test
    void formatSelectedTestsList_missingWithMedian_showsMedianInBrackets(){
        // given
        Set<String> testsToRun = setOf("test1", "newTest");
        Set<String> withoutStats = setOf("newTest");
        TestSelectorResult result = buildResult(testsToRun, 700L, withoutStats, 200L,
                perTestMap("test1", 500L, "newTest", 200L));

        // when
        String output = SelectTestsOutputFormatter.formatSelectedTestsList(result, LINE_SEP);

        // then
        assertEquals("\ttest1 (500ms)" + LINE_SEP + "\tnewTest (200ms)", output);
    }

    /**
     * Durations of one second or more drop the {@code ms} component from the per-test
     * bracketed display - sub-second precision isn't useful at the second/minute/hour level.
     * Durations under one second keep the {@code ms} unit.
     */
    @Test
    void formatSelectedTestsList_durationAboveOneSecond_dropsMsComponent(){
        // given
        Set<String> testsToRun = setOf("fastTest", "slowTest", "verySlowTest");
        TestSelectorResult result = buildResult(testsToRun, 0L, setOf(), 0L,
                perTestMap(
                        "fastTest", 750L,       // < 1s - keep ms
                        "slowTest", 1500L,      // 1.5s - drop ms → "1s"
                        "verySlowTest", 90500L  // 1m 30.5s - drop ms → "1m 30s"
                ));

        // when
        String output = SelectTestsOutputFormatter.formatSelectedTestsList(result, LINE_SEP);

        // then
        assertEquals("\tfastTest (750ms)" + LINE_SEP
                + "\tslowTest (1s)" + LINE_SEP
                + "\tverySlowTest (1m 30s)", output);
    }

    /**
     * Tests with no recorded runtime and no available median are shown with
     * {@code (no run data)} instead of a duration.
     */
    @Test
    void formatSelectedTestsList_missingWithNoHistory_showsNoRunData(){
        // given
        Set<String> testsToRun = setOf("newTest");
        Set<String> withoutStats = setOf("newTest");
        TestSelectorResult result = buildResult(testsToRun, 0L, withoutStats, 0L,
                perTestMap("newTest", 0L));

        // when
        String output = SelectTestsOutputFormatter.formatSelectedTestsList(result, LINE_SEP);

        // then
        assertEquals("\tnewTest (no run data)", output);
    }

    /**
     * Build a {@link TestSelectorResult} with the given selection state. {@code testsToIgnore}
     * is fixed to an empty set since the formatter doesn't consult it.
     */
    private static TestSelectorResult buildResult(Set<String> testsToRun, long estimatedRunTimeMs,
                                                  Set<String> withoutStats, long median,
                                                  Map<String, Long> perTestRunTimes){
        return new TestSelectorResult(testsToRun, Collections.emptySet(), null,
                estimatedRunTimeMs, withoutStats, median, perTestRunTimes);
    }

    /**
     * Build an ordered {@link Set} preserving insertion order so per-test list output is
     * deterministic.
     */
    private static Set<String> setOf(String... values){
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    /**
     * Build a per-test runtime map from alternating name/value pairs. Inserted with a
     * {@link java.util.LinkedHashMap}-like ordering by virtue of insertion order on
     * {@link HashMap}, but the formatter iterates the test names from
     * {@link TestSelectorResult#getTestsToRun()} (which is ordered via {@link #setOf}), so
     * map iteration order does not affect the output.
     */
    private static Map<String, Long> perTestMap(Object... nameValuePairs){
        if (nameValuePairs.length % 2 != 0){
            throw new IllegalArgumentException("perTestMap requires alternating name/value pairs");
        }
        Map<String, Long> map = new HashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2){
            map.put((String) nameValuePairs[i], (Long) nameValuePairs[i + 1]);
        }
        return map;
    }
}
