package org.tiatesting.core.report;

import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestStats;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The "Stats" block shared by the three Tia-level summary reports - the HTML summary page, the
 * {@code status} console output and the plain-text report - built once as headed sections so the
 * three cannot drift on wording, grouping or arithmetic. Each report only decides how to render a
 * heading and a line.
 *
 * <p>Two sources feed it. The run counts and the serial all-tests baseline come from {@link
 * TestStats}, which only runs that owned the mapping update. Every wall-clock figure - both
 * average run times, the savings and the group figures - is derived from the history rows, since
 * a wall clock only exists per run. The wall-clock full-suite time is the serial baseline spread across the groups
 * the most recent all-tests run used ({@link ReportUtils#lastAllTestsRunGroupCount(List)}), the
 * same division each run's wall-clock savings were frozen against. See the "Wall-clock savings"
 * material in the test-run history chapter of {@code WIKI.md}.
 */
public final class SummaryStats {

    /** Rendered where a figure cannot be computed yet, such as a percentage with no baseline. */
    static final String NOT_AVAILABLE = "N/A";

    private static final DecimalFormat ONE_DECIMAL = new DecimalFormat("###.#");

    private SummaryStats() { }

    /**
     * One headed group of lines, with an optional explanation of what the heading covers that the
     * HTML report shows on hover. Depth 0 sections sit directly under the report's Stats heading;
     * depth 1 sections sit under the depth 0 section before them.
     */
    public static final class Section {
        private final String heading;
        private final String hint;
        private final int depth;
        private final List<Line> lines;

        /**
         * @param heading the section's heading text
         * @param hint what the section covers, or null when the heading says enough
         * @param depth 0 for a top-level section, 1 for a section nested under the one before it
         * @param lines the section's lines, in display order
         */
        Section(String heading, String hint, int depth, List<Line> lines) {
            this.heading = heading;
            this.hint = hint;
            this.depth = depth;
            this.lines = Collections.unmodifiableList(lines);
        }

        /** @return the section's heading text */
        public String getHeading() { return heading; }

        /** @return what the section covers, or null when there is no hint */
        public String getHint() { return hint; }

        /** @return 0 for a top-level section, 1 for a nested one */
        public int getDepth() { return depth; }

        /** @return the section's lines, in display order */
        public List<Line> getLines() { return lines; }
    }

    /**
     * One labelled figure, with an optional explanation of how it is calculated that the HTML
     * report shows on hover.
     */
    public static final class Line {
        private final String label;
        private final String value;
        private final String hint;

        /**
         * @param label the figure's label, without the trailing colon
         * @param value the formatted figure
         * @param hint how the figure is calculated, or null when the label says enough
         */
        Line(String label, String value, String hint) {
            this.label = label;
            this.value = value;
            this.hint = hint;
        }

        /** @return the figure's label, without the trailing colon */
        public String getLabel() { return label; }

        /** @return the formatted figure */
        public String getValue() { return value; }

        /** @return how the figure is calculated, or null when there is no hint */
        public String getHint() { return hint; }

        /** @return the line as {@code "label: value"} */
        public String toText() { return label + ": " + value; }
    }

    /**
     * Build the Stats sections: Source Code, Test Run Stability, Test Run Duration (with its All
     * Tests and Partial Test Runs sub-sections) and Savings. The group lines under Savings only
     * appear once the history holds a distributed build, since a single-host run has no groups.
     *
     * @param numTestSuites the number of test classes with mappings
     * @param numSourceMethods the number of source methods tracked for tests
     * @param stats the Tia-level run statistics
     * @param history the recorded test-run history; may be null
     * @return the sections in display order
     */
    public static List<Section> build(int numTestSuites, int numSourceMethods, TestStats stats,
                                      List<TestRunHistoryEntry> history) {
        List<TestRunHistoryEntry> runs = history == null
                ? Collections.<TestRunHistoryEntry>emptyList() : history;
        int allTestsGroups = ReportUtils.lastAllTestsRunGroupCount(runs);
        long allTestsWallClockMs = ReportUtils.wallClockAllTestsRunTimeMs(
                stats.getAllTestsRunTime(), allTestsGroups);
        long avgWallClockMs = averageWallClockMs(runs);

        List<Section> sections = new ArrayList<>();
        sections.add(new Section("Source Code", null, 0, lines(
                new Line("Number of test classes with mappings", String.valueOf(numTestSuites), null),
                new Line("Number of source methods tracked for tests", String.valueOf(numSourceMethods),
                        null))));
        sections.add(new Section("Test Run Stability", null, 0, lines(
                new Line("Number of successful runs", stats.getNumSuccessRuns() + " ("
                        + percentOfRuns(stats.getNumSuccessRuns(), stats) + "%)", null),
                new Line("Number of failed runs", stats.getNumFailRuns() + " ("
                        + percentOfRuns(stats.getNumFailRuns(), stats) + "%)", null))));
        sections.add(new Section("Test Run Duration", null, 0, lines(
                new Line("Average run time", averageRunTimeValue(runs, avgWallClockMs,
                        allTestsWallClockMs),
                        "The average wall clock across every recorded test run, as a share of the "
                                + "all-tests run time spread across the groups the last all-tests "
                                + "run used."))));
        sections.add(allTestsSection(stats, allTestsGroups, allTestsWallClockMs));
        List<TestRunHistoryEntry> partialRuns = partialRuns(runs);
        sections.add(new Section("Partial Test Runs",
                "Runs where Tia selected a subset of the tests to run, rather than running all of "
                        + "them.", 1, lines(
                new Line("Number of partial runs", String.valueOf(stats.getNumPartialRuns()), null),
                new Line("Average run time", averageRunTimeValue(partialRuns,
                        averageWallClockMs(partialRuns), allTestsWallClockMs),
                        "The average wall clock across the recorded partial test runs, as a share "
                                + "of the all-tests run time spread across the groups the last "
                                + "all-tests run used."))));
        sections.add(savingsSection(runs, avgWallClockMs, allTestsWallClockMs));
        return sections;
    }

    /**
     * Render the sections as indented plain text for the console and plain-text reports: a
     * {@code Stats:} heading, then each section heading indented two spaces per level below it,
     * and its lines two spaces further in. Hints are left out; they only have somewhere to go in
     * the HTML report.
     *
     * @param sections the sections to render
     * @param lineSep the line separator to end every line with
     * @return the rendered text
     */
    public static String toText(List<Section> sections, String lineSep) {
        StringBuilder sb = new StringBuilder("Stats:").append(lineSep);
        for (Section section : sections) {
            String indent = indent(section.getDepth() + 1);
            sb.append(indent).append(section.getHeading()).append(lineSep);
            for (Line line : section.getLines()) {
                sb.append(indent).append("  ").append(line.toText()).append(lineSep);
            }
        }
        return sb.toString();
    }

    /**
     * Build the All Tests sub-section. The distributed run time only appears when the last
     * all-tests run was split across more than one group; otherwise it would repeat the
     * not-distributed figure under a label that does not describe the run. Both run times drop
     * their ms component above a minute, matching the other durations on the summary, since at
     * that scale it only makes the figure harder to read.
     *
     * @param stats the Tia-level run statistics, carrying the serial full-suite baseline
     * @param allTestsGroups the groups the most recent all-tests run used
     * @param allTestsWallClockMs the baseline spread across those groups (ms)
     * @return the All Tests section
     */
    private static Section allTestsSection(TestStats stats, int allTestsGroups,
                                           long allTestsWallClockMs) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line("Number of all-tests runs", String.valueOf(stats.getNumAllTestsRuns()), null));
        if (allTestsGroups > 1) {
            lines.add(new Line("Run time (distributed)", ReportUtils.prettyDurationDropMsAboveMinute(allTestsWallClockMs)
                    + " (" + allTestsGroups + " groups)",
                    "The average all-tests run time spread evenly across the groups the last "
                            + "all-tests run used."));
        }
        lines.add(new Line("Run time (not distributed)",
                ReportUtils.prettyDurationDropMsAboveMinute(stats.getAllTestsRunTime()) + " (1 group)",
                "The average time an all-tests run takes on one machine."));
        return new Section("All Tests", null, 1, lines);
    }

    /**
     * Build the Savings section: the total and average wall-clock savings, then - once the history
     * holds a distributed build - how many of the available groups the builds needed.
     *
     * @param runs the recorded test-run history
     * @param avgWallClockMs the average wall clock across {@code runs} (ms)
     * @param allTestsWallClockMs the all-tests run time spread across the last all-tests run's
     *                            groups (ms)
     * @return the Savings section
     */
    private static Section savingsSection(List<TestRunHistoryEntry> runs, long avgWallClockMs,
                                          long allTestsWallClockMs) {
        List<Line> lines = new ArrayList<>();
        long totalSavingsMs = ReportUtils.totalWallClockSavingsMs(runs);
        lines.add(new Line("Total savings over all runs", totalSavingsMs > 0
                ? ReportUtils.prettyDurationDropMsAboveMinute(totalSavingsMs) : NOT_AVAILABLE,
                "The wall-clock savings of every recorded test run added together."));
        lines.add(new Line("Average test run savings",
                averageSavingsValue(runs, avgWallClockMs, allTestsWallClockMs),
                "(All tests run time - Average run time) / All tests run time, using wall clock "
                        + "times: the all-tests run time spread across the groups the last "
                        + "all-tests run used, and the average wall clock across every recorded "
                        + "test run."));

        List<TestRunHistoryEntry> distributed = distributedRuns(runs);
        if (!distributed.isEmpty()) {
            double avgUsed = 0;
            double avgAvailable = 0;
            for (TestRunHistoryEntry run : distributed) {
                avgUsed += run.getGroupCount();
                avgAvailable += run.getGroupsAvailable();
            }
            avgUsed /= distributed.size();
            avgAvailable /= distributed.size();
            lines.add(new Line("Group savings",
                    Math.round((avgAvailable - avgUsed) / avgAvailable * 100) + "%",
                    "(Average groups available - Average groups used) / Average groups "
                            + "available, over the distributed test runs: the share of the "
                            + "available groups the builds did not need."));
            lines.add(new Line("Groups used", ONE_DECIMAL.format(avgUsed) + " ("
                    + ONE_DECIMAL.format(avgAvailable) + " available)",
                    "The average number of groups the distributed test runs used, and in "
                            + "brackets the average number they had available."));
        }
        return new Section("Savings", null, 0, lines);
    }

    /**
     * Format the average wall clock with its share of the wall-clock all-tests run time.
     *
     * @param runs the recorded test-run history
     * @param avgWallClockMs the average wall clock across {@code runs} (ms)
     * @param allTestsWallClockMs the wall-clock all-tests run time (ms); 0 when no baseline exists
     * @return the duration and percentage, the duration alone with no baseline, or {@code "N/A"}
     *         with no recorded runs
     */
    private static String averageRunTimeValue(List<TestRunHistoryEntry> runs, long avgWallClockMs,
                                              long allTestsWallClockMs) {
        if (runs.isEmpty()) {
            return NOT_AVAILABLE;
        }
        String duration = ReportUtils.prettyDurationDropMsAboveMinute(avgWallClockMs);
        if (allTestsWallClockMs <= 0) {
            return duration;
        }
        return duration + " (" + ReportUtils.percentOfTotal(avgWallClockMs, allTestsWallClockMs) + "%)";
    }

    /**
     * Format the average test run savings: the share of the wall-clock all-tests run time the
     * average run did not need, clamped at zero - a history of mostly slow local runs can average
     * above a distributed baseline, and a negative saving would read as Tia costing time.
     *
     * @param runs the recorded test-run history
     * @param avgWallClockMs the average wall clock across {@code runs} (ms)
     * @param allTestsWallClockMs the wall-clock all-tests run time (ms); 0 when no baseline exists
     * @return the percentage, or {@code "N/A"} with no recorded runs or no baseline
     */
    private static String averageSavingsValue(List<TestRunHistoryEntry> runs, long avgWallClockMs,
                                              long allTestsWallClockMs) {
        if (runs.isEmpty() || allTestsWallClockMs <= 0) {
            return NOT_AVAILABLE;
        }
        long savedMs = Math.max(0L, allTestsWallClockMs - avgWallClockMs);
        return ReportUtils.percentOfTotal(savedMs, allTestsWallClockMs) + "%";
    }

    /**
     * Average the wall clock across the given runs - a single-host run's duration, a distributed
     * build's slowest group.
     *
     * @param runs the runs to average
     * @return the mean wall clock in ms, or 0 when there are no runs
     */
    private static long averageWallClockMs(List<TestRunHistoryEntry> runs) {
        if (runs.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (TestRunHistoryEntry run : runs) {
            total += run.getRunWallClockMs();
        }
        return total / runs.size();
    }

    /**
     * Pick out the partial runs: those where Tia ignored at least one suite, having selected only
     * a subset of the tests to run.
     *
     * @param runs the recorded test-run history
     * @return the partial runs
     */
    private static List<TestRunHistoryEntry> partialRuns(List<TestRunHistoryEntry> runs) {
        List<TestRunHistoryEntry> partial = new ArrayList<>();
        for (TestRunHistoryEntry run : runs) {
            if (run.getNumSuitesIgnored() > 0) {
                partial.add(run);
            }
        }
        return partial;
    }

    /**
     * Pick out the distributed builds that recorded both their groups used and available.
     *
     * @param runs the recorded test-run history
     * @return the distributed runs carrying both group counts
     */
    private static List<TestRunHistoryEntry> distributedRuns(List<TestRunHistoryEntry> runs) {
        List<TestRunHistoryEntry> distributed = new ArrayList<>();
        for (TestRunHistoryEntry run : runs) {
            if (run.getGroupCount() != null && run.getGroupsAvailable() != null) {
                distributed.add(run);
            }
        }
        return distributed;
    }

    /**
     * Format a run count as a percentage of every run, to one decimal place.
     *
     * @param count the number of runs being expressed
     * @param stats the Tia-level run statistics, carrying the total run count
     * @return the percentage, or {@code "0"} when no runs are recorded
     */
    private static String percentOfRuns(long count, TestStats stats) {
        if (stats.getNumRuns() == 0) {
            return "0";
        }
        return ONE_DECIMAL.format((double) count / stats.getNumRuns() * 100);
    }

    /**
     * Collect lines into a mutable list, so a section can be built inline.
     *
     * @param lines the lines, in display order
     * @return the lines as a list
     */
    private static List<Line> lines(Line... lines) {
        List<Line> list = new ArrayList<>();
        Collections.addAll(list, lines);
        return list;
    }

    /**
     * Two spaces per level of indentation.
     *
     * @param level how many levels to indent
     * @return the indent for that level
     */
    private static String indent(int level) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }
}
