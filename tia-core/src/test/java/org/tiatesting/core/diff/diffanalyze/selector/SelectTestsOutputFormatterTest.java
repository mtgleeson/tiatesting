package org.tiatesting.core.diff.diffanalyze.selector;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link SelectTestsOutputFormatter#formatEstimateBlock} produces the correct
 * user-facing output for each of the four cases the select-tests task can encounter:
 * empty selection, all selected tests have stats, some/all missing stats with a usable
 * median, and missing stats with no historical data to derive a median.
 */
class SelectTestsOutputFormatterTest {

    private static final String LINE_SEP = "\n";

    /**
     * An empty {@code testsToRun} means there's nothing to estimate — the formatter must
     * return an empty string so the plugins don't print a stray runtime line.
     */
    @Test
    void formatEstimateBlock_emptyTestsToRun_returnsEmptyString(){
        // given
        TestSelectorResult result = new TestSelectorResult(Collections.emptySet(),
                Collections.emptySet(), null, 0L, Collections.emptySet(), 0L);

        // when
        String output = SelectTestsOutputFormatter.formatEstimateBlock(result, LINE_SEP);

        // then
        assertEquals("", output);
    }

    /**
     * Every selected test has recorded stats — the output is a single runtime line with
     * no follow-up note.
     */
    @Test
    void formatEstimateBlock_allSelectedHaveStats_printsRuntimeOnly(){
        // given
        Set<String> testsToRun = new LinkedHashSet<>(java.util.Arrays.asList("test1", "test2"));
        TestSelectorResult result = new TestSelectorResult(testsToRun, Collections.emptySet(),
                null, 1500L, Collections.emptySet(), 0L);

        // when
        String output = SelectTestsOutputFormatter.formatEstimateBlock(result, LINE_SEP);

        // then
        assertEquals("Estimated total run time: 1s 500ms", output);
    }

    /**
     * Some selected tests lack stats but a positive median is available — the output
     * states the median value explicitly and lists each missing test by name.
     */
    @Test
    void formatEstimateBlock_someMissingWithMedian_includesMedianNote(){
        // given
        Set<String> testsToRun = new LinkedHashSet<>(java.util.Arrays.asList("test1", "newTest"));
        Set<String> withoutStats = new LinkedHashSet<>(Collections.singletonList("newTest"));
        TestSelectorResult result = new TestSelectorResult(testsToRun, Collections.emptySet(),
                null, 700L, withoutStats, 200L);

        // when
        String output = SelectTestsOutputFormatter.formatEstimateBlock(result, LINE_SEP);

        // then
        String expected = "Estimated total run time: 700ms" + LINE_SEP
                + "Note: the following 1 selected test(s) have no recorded run time. "
                + "A median run time of 200ms (calculated from all tracked test suites) "
                + "was used to estimate their contribution to the total:" + LINE_SEP
                + "\tnewTest";
        assertEquals(expected, output);
    }

    /**
     * Missing stats with no historical data to derive a median — the output states the
     * tests were excluded and lists each one by name.
     */
    @Test
    void formatEstimateBlock_missingWithNoHistory_includesExcludedNote(){
        // given
        Set<String> testsToRun = new LinkedHashSet<>(Collections.singletonList("newTest"));
        Set<String> withoutStats = new LinkedHashSet<>(Collections.singletonList("newTest"));
        TestSelectorResult result = new TestSelectorResult(testsToRun, Collections.emptySet(),
                null, 0L, withoutStats, 0L);

        // when
        String output = SelectTestsOutputFormatter.formatEstimateBlock(result, LINE_SEP);

        // then
        assertTrue(output.startsWith("Estimated total run time: "), "Output: " + output);
        assertTrue(output.contains("Note: the following 1 selected test(s) have no recorded run time "
                + "and were excluded from the estimate"), "Output: " + output);
        assertTrue(output.contains("no historical stats are available to derive a median run time"),
                "Output: " + output);
        assertTrue(output.endsWith(LINE_SEP + "\tnewTest"), "Output: " + output);
    }
}
