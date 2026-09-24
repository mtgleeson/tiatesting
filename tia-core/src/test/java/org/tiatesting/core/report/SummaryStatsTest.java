package org.tiatesting.core.report;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestStats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the Stats sections {@link SummaryStats} builds for the three summary reports: the
 * section layout, the wall-clock arithmetic derived from the history, the lines that only appear
 * once the history holds a distributed build, and the plain-text rendering.
 */
class SummaryStatsTest {

    private static final String LF = "\n";

    /**
     * Build Tia-level stats with a 1h serial all-tests baseline and a 10m serial partial-run
     * average across 10 runs, 2 of them all-tests runs and 1 of them failed.
     *
     * @return the stats
     */
    private static TestStats stats() {
        TestStats stats = new TestStats();
        stats.setNumRuns(10);
        stats.setNumSuccessRuns(9);
        stats.setNumFailRuns(1);
        stats.setNumAllTestsRuns(2);
        stats.setAllTestsRunTime(3_600_000L);
        stats.setAvgRunTime(600_000L);
        return stats;
    }

    /**
     * Build a distributed history row.
     *
     * @param timestampMs when the run started
     * @param ignored the suites it ignored; 0 makes it an all-tests run
     * @param wallClockMs its slowest group (ms)
     * @param wallClockSavingsMs the wall-clock savings frozen on it (ms)
     * @param groupsUsed the groups it used
     * @param groupsAvailable the groups it had available
     * @return the history row
     */
    private static TestRunHistoryEntry distributed(long timestampMs, int ignored, long wallClockMs,
                                                   long wallClockSavingsMs, int groupsUsed,
                                                   int groupsAvailable) {
        return new TestRunHistoryEntry("d" + timestampMs, timestampMs, "main", "c", 5, ignored, 0,
                wallClockMs * groupsUsed, true, 0L, 0, wallClockSavingsMs, 0, "run-" + timestampMs,
                Long.valueOf(wallClockMs), Integer.valueOf(groupsUsed), Integer.valueOf(groupsAvailable),
                RunOrigin.of(RunOrigin.SOURCE_CI, null), null, null, null, null, null);
    }

    /**
     * Build a single-host history row.
     *
     * @param timestampMs when the run started
     * @param ignored the suites it ignored; 0 makes it an all-tests run
     * @param durationMs its duration, which is its wall clock (ms)
     * @param savingsMs the savings frozen on it (ms)
     * @return the history row
     */
    private static TestRunHistoryEntry singleHost(long timestampMs, int ignored, long durationMs,
                                                  long savingsMs) {
        return TestRunHistoryEntry.create("main", "c", timestampMs, 5, ignored, 0, durationMs, true,
                savingsMs, 0, RunOrigin.of(RunOrigin.SOURCE_CI, "agent"), null);
    }

    /**
     * Find a line by label across every section.
     *
     * @param sections the sections to search
     * @param label the line's label
     * @return the line, or null when no section carries it
     */
    private static SummaryStats.Line line(List<SummaryStats.Section> sections, String label) {
        for (SummaryStats.Section section : sections) {
            for (SummaryStats.Line line : section.getLines()) {
                if (line.getLabel().equals(label)) {
                    return line;
                }
            }
        }
        return null;
    }

    /**
     * Find a line by label within one section, for labels more than one section uses.
     *
     * @param sections the sections to search
     * @param heading the heading of the section to look in
     * @param label the line's label
     * @return the line, or null when that section does not carry it
     */
    private static SummaryStats.Line lineIn(List<SummaryStats.Section> sections, String heading,
                                            String label) {
        for (SummaryStats.Section section : sections) {
            if (section.getHeading().equals(heading)) {
                return line(Collections.singletonList(section), label);
            }
        }
        return null;
    }

    /**
     * The sections come out in the order and nesting the summary page lays them out in.
     */
    @Test
    void build_laysOutTheSectionsInOrderWithTheirNesting() {
        // given / when
        List<SummaryStats.Section> sections = SummaryStats.build(4, 35, stats(),
                Collections.<TestRunHistoryEntry>emptyList());

        // then
        List<String> layout = new ArrayList<>();
        for (SummaryStats.Section section : sections) {
            layout.add(section.getDepth() + ":" + section.getHeading());
        }
        assertEquals(Arrays.asList("0:Source Code", "0:Test Run Stability", "0:Test Run Duration",
                "1:All Tests", "1:Partial Test Runs", "0:Savings"), layout);
        assertEquals("4", line(sections, "Number of test classes with mappings").getValue());
        assertEquals("35", line(sections, "Number of source methods tracked for tests").getValue());
        assertEquals("9 (90%)", line(sections, "Number of successful runs").getValue());
        assertEquals("1 (10%)", line(sections, "Number of failed runs").getValue());
        assertEquals("2", line(sections, "Number of all-tests runs").getValue());
        assertEquals("8", line(sections, "Number of partial runs").getValue());
    }

    /**
     * After a 6-group all-tests run, the wall-clock figures are measured against the 1h baseline
     * spread across 6 groups (10m): the average wall clock of 3m 30s is 35% of it, saving 65%, and
     * the builds needing 2.5 of 6 groups on average leaves 58% of the groups unused.
     */
    @Test
    void build_distributedHistory_measuresTheWallClockFiguresAgainstTheSpreadBaseline() {
        // given - a 6-group all-tests run of 10m, then partial runs of 1m, 1m and 2m on 1, 2 and 1
        // of 6 groups
        List<TestRunHistoryEntry> history = Arrays.asList(
                distributed(1L, 0, 600_000L, 0L, 6, 6),
                distributed(2L, 3, 60_000L, 540_000L, 1, 6),
                distributed(3L, 3, 60_000L, 540_000L, 2, 6),
                distributed(4L, 3, 120_000L, 480_000L, 1, 6));

        // when
        List<SummaryStats.Section> sections = SummaryStats.build(0, 0, stats(), history);

        // then - average wall clock (10m + 1m + 1m + 2m) / 4 = 3m 30s, which is 35% of 10m
        assertEquals("3m 30s (35%)",
                lineIn(sections, "Test Run Duration", "Average run time").getValue());
        // the partial runs alone average (1m + 1m + 2m) / 3 = 1m 20s, which is 13% of 10m
        assertEquals("1m 20s (13%)",
                lineIn(sections, "Partial Test Runs", "Average run time").getValue());
        assertEquals("10m (6 groups)", line(sections, "Run time (distributed)").getValue());
        assertEquals("1h (1 group)", line(sections, "Run time (not distributed)").getValue());
        assertEquals("26m", line(sections, "Total savings over all runs").getValue());
        assertEquals("65%", line(sections, "Average test run savings").getValue());
        // (6 + 1 + 2 + 1) / 4 = 2.5 used of 6 available, so 58% of the groups were not needed
        assertEquals("58%", line(sections, "Group savings").getValue());
        assertEquals("2.5 (6 available)", line(sections, "Groups used").getValue());
    }

    /**
     * A history of single-host runs gets no distributed run time and no group lines: a single
     * machine has no groups to report on.
     */
    @Test
    void build_singleHostHistory_omitsTheDistributedAndGroupLines() {
        // given
        List<TestRunHistoryEntry> history = Arrays.asList(
                singleHost(1L, 0, 3_600_000L, 0L),
                singleHost(2L, 3, 1_200_000L, 2_400_000L));

        // when
        List<SummaryStats.Section> sections = SummaryStats.build(0, 0, stats(), history);

        // then - average wall clock (60m + 20m) / 2 = 40m, 67% of the 1h baseline, saving 33%
        assertEquals("40m (67%)",
                lineIn(sections, "Test Run Duration", "Average run time").getValue());
        assertEquals("20m (33%)",
                lineIn(sections, "Partial Test Runs", "Average run time").getValue());
        assertEquals("33%", line(sections, "Average test run savings").getValue());
        assertEquals("1h (1 group)", line(sections, "Run time (not distributed)").getValue());
        assertNull(line(sections, "Run time (distributed)"));
        assertNull(line(sections, "Group savings"));
        assertNull(line(sections, "Groups used"));
    }

    /**
     * All-tests run times over a minute drop the ms component, which is noise at that scale and
     * only makes the figure harder to read: a 1h 2m 3s 456ms baseline reads as 1h 2m 3s, and spread
     * across 6 groups (620,576ms) as 10m 20s.
     */
    @Test
    void build_allTestsRunTimesOverAMinute_dropTheMilliseconds() {
        // given - a 6-group all-tests run, and a baseline with a non-zero ms component
        TestStats stats = stats();
        stats.setAllTestsRunTime(3_723_456L);
        List<TestRunHistoryEntry> history = Collections.singletonList(
                distributed(1L, 0, 620_576L, 0L, 6, 6));

        // when
        List<SummaryStats.Section> sections = SummaryStats.build(0, 0, stats, history);

        // then
        assertEquals("10m 20s (6 groups)", line(sections, "Run time (distributed)").getValue());
        assertEquals("1h 2m 3s (1 group)", line(sections, "Run time (not distributed)").getValue());
    }

    /**
     * An all-tests run time of a minute or less keeps its ms component, where it is still a
     * meaningful share of the figure.
     */
    @Test
    void build_allTestsRunTimeUnderAMinute_keepsTheMilliseconds() {
        // given
        TestStats stats = stats();
        stats.setAllTestsRunTime(45_250L);
        List<TestRunHistoryEntry> history = Collections.singletonList(
                singleHost(1L, 0, 45_250L, 0L));

        // when
        List<SummaryStats.Section> sections = SummaryStats.build(0, 0, stats, history);

        // then
        assertEquals("45s 250ms (1 group)", line(sections, "Run time (not distributed)").getValue());
    }

    /**
     * With no recorded runs there is no average and no savings to report, so both read N/A rather
     * than a misleading zero.
     */
    @Test
    void build_emptyHistory_reportsNotAvailable() {
        // given / when
        List<SummaryStats.Section> sections = SummaryStats.build(0, 0, stats(),
                Collections.<TestRunHistoryEntry>emptyList());

        // then
        assertEquals("N/A", lineIn(sections, "Test Run Duration", "Average run time").getValue());
        assertEquals("N/A", lineIn(sections, "Partial Test Runs", "Average run time").getValue());
        assertEquals("N/A", line(sections, "Average test run savings").getValue());
        assertEquals("N/A", line(sections, "Total savings over all runs").getValue());
    }

    /**
     * Runs that on average took longer than the spread baseline saved nothing, rather than a
     * negative percentage that would read as Tia costing time.
     */
    @Test
    void build_averageSlowerThanTheBaseline_clampsTheSavingsAtZero() {
        // given - a 6-group baseline of 10m, but a slow single-host run of 50m since
        List<TestRunHistoryEntry> history = Arrays.asList(
                distributed(1L, 0, 600_000L, 0L, 6, 6),
                singleHost(2L, 3, 3_000_000L, 600_000L));

        // when
        List<SummaryStats.Section> sections = SummaryStats.build(0, 0, stats(), history);

        // then
        assertEquals("0%", line(sections, "Average test run savings").getValue());
    }

    /**
     * The savings and run-time figures whose calculation is not obvious from the label carry a
     * hover hint; the plain counts do not.
     */
    @Test
    void build_explainsTheCalculatedFiguresWithAHint() {
        // given / when
        List<SummaryStats.Section> sections = SummaryStats.build(0, 0, stats(),
                Collections.singletonList(distributed(1L, 0, 600_000L, 0L, 6, 6)));

        // then
        assertNotNull(line(sections, "Average test run savings").getHint());
        assertNotNull(lineIn(sections, "Test Run Duration", "Average run time").getHint());
        assertNotNull(lineIn(sections, "Partial Test Runs", "Average run time").getHint());
        assertNotNull(line(sections, "Group savings").getHint());
        assertNull(line(sections, "Number of partial runs").getHint());
    }

    /**
     * The Partial Test Runs heading explains what a partial run is; the other headings say
     * enough on their own.
     */
    @Test
    void build_explainsWhatAPartialRunIsOnItsHeading() {
        // given / when
        List<SummaryStats.Section> sections = SummaryStats.build(0, 0, stats(),
                Collections.<TestRunHistoryEntry>emptyList());

        // then
        for (SummaryStats.Section section : sections) {
            if (section.getHeading().equals("Partial Test Runs")) {
                assertNotNull(section.getHint(), "a partial run needs explaining");
            } else {
                assertNull(section.getHint(), section.getHeading() + " should carry no hint");
            }
        }
    }

    /**
     * The text rendering puts every section under a Stats heading, indenting nested sections and
     * their lines two spaces per level, and leaves the hints out.
     */
    @Test
    void toText_indentsTheSectionsUnderAStatsHeading() {
        // given
        List<SummaryStats.Section> sections = SummaryStats.build(4, 35, stats(),
                Collections.<TestRunHistoryEntry>emptyList());

        // when
        String text = SummaryStats.toText(sections, LF);

        // then
        String[] lines = text.split(LF);
        assertEquals("Stats:", lines[0]);
        assertEquals("  Source Code", lines[1]);
        assertEquals("    Number of test classes with mappings: 4", lines[2]);
        assertEquals("    All Tests", lines[9]);
        assertEquals("      Number of all-tests runs: 2", lines[10]);
        assertFalse(text.contains("wall clock across"), "hints are HTML-only: " + text);
    }
}
