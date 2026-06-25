package org.tiatesting.core.diff.diffanalyze.selector;

import org.tiatesting.core.report.ReportUtils;

import java.util.Map;

/**
 * Builds the user-facing output for the {@code tia-select-tests} task (Maven + Gradle).
 * Shared so both plugins produce identical wording.
 *
 * <p>Provides two related blocks:
 * <ul>
 *   <li>{@link #formatSelectedTestsList} — the tab-indented list of selected tests with each
 *       test's estimated runtime in brackets after its name.</li>
 *   <li>{@link #formatEstimateBlock} — the total estimated runtime, plus a single-line note
 *       when some selected tests have no recorded run-time data.</li>
 * </ul>
 *
 * <p>All durations are formatted with the {@code dropMsWhenAboveSecond} flag on
 * {@link ReportUtils#prettyDuration(long, boolean)} so the {@code ms} component is dropped
 * when the value is one second or more — sub-second precision is rarely meaningful at the
 * minute or hour level.
 */
public class SelectTestsOutputFormatter {

    private SelectTestsOutputFormatter() {}

    /**
     * Build the tab-indented list of selected tests with each test's estimated runtime in
     * brackets after the name (e.g. {@code "\tcom.example.FooSpec (1m 30s)"}). Tests with no
     * recorded run-time data and no available median are shown with {@code (no run data)}.
     *
     * <p>Returns an empty string when no tests are selected.
     *
     * @param result the test-selection result
     * @param lineSep the line separator to use between rows
     * @return the formatted test list, or an empty string when {@code testsToRun} is empty
     */
    public static String formatSelectedTestsList(final TestSelectorResult result, final String lineSep){
        if (result.getTestsToRun().isEmpty()){
            return "";
        }

        Map<String, Long> perTestTimes = result.getSelectedTestRunTimesMs();
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String testName : result.getTestsToRun()){
            if (!first){
                sb.append(lineSep);
            }
            first = false;
            sb.append('\t').append(testName).append(' ').append(formatBracketedTime(perTestTimes, testName));
        }
        return sb.toString();
    }

    /**
     * Build the estimate-runtime block for a {@link TestSelectorResult}. Returns an empty
     * string when {@code testsToRun} is empty.
     *
     * <p>When the result includes selected tests without recorded stats, a single-line note
     * is appended stating the count of new tests and the median run time substituted for
     * them. When no historical stats are available to derive a median, the note instead
     * states the new tests were excluded from the estimate.
     *
     * @param result the test-selection result
     * @param lineSep the line separator to use between lines (e.g. {@code "\n"} or
     *                {@link System#lineSeparator()})
     * @param includeMappingOverhead whether the run being estimated will collect coverage (update
     *                               the mapping). When {@code true} the per-suite estimate gains
     *                               {@link TestSelectorResult#getMappingOverheadMs()} - coverage
     *                               capture is not part of the stored per-suite times - so the
     *                               displayed total, percentage and savings reflect a coverage run.
     * @return the formatted estimate block, or an empty string when no estimate applies
     */
    public static String formatEstimateBlock(final TestSelectorResult result, final String lineSep,
                                             final boolean includeMappingOverhead){
        if (result.getTestsToRun().isEmpty()){
            return "";
        }

        // A mapping-update run also pays per-suite coverage capture, which is not in the stored
        // per-suite times; fold it in only when the previewed run collects coverage.
        long selectedMs = result.getEstimatedRunTimeMs()
                + (includeMappingOverhead ? result.getMappingOverheadMs() : 0L);

        StringBuilder sb = new StringBuilder();
        sb.append(lineSep);
        sb.append("Estimated total run time: ")
          .append(ReportUtils.prettyDuration(selectedMs, true));

        // When an all-tests-run baseline exists, show what fraction of the full-suite time the
        // selected run is expected to take, and the savings (time and percent) versus running all
        // tests. With no baseline recorded yet we can't compute these, so the line stays bare.
        long allTestsRunTimeMs = result.getAllTestsRunTimeMs();
        if (allTestsRunTimeMs > 0){
            long savingsMs = Math.max(0L, allTestsRunTimeMs - selectedMs);
            long selectedPercent = Math.round((double) selectedMs / allTestsRunTimeMs * 100);
            long savingsPercent = Math.round((double) savingsMs / allTestsRunTimeMs * 100);
            sb.append(" (").append(selectedPercent).append("%)");
            sb.append(lineSep).append("Estimated savings: ")
              .append(formatSavingsDuration(savingsMs)).append(" (").append(savingsPercent).append("%)");
        }

        if (!result.getSelectedTestsWithoutStats().isEmpty()){
            int n = result.getSelectedTestsWithoutStats().size();
            long median = result.getMedianRunTimeMsAppliedToMissing();
            sb.append(lineSep).append(lineSep);
            if (median > 0){
                sb.append("Note: ").append(n)
                  .append(" selected test(s) have not previously been run by Tia. A median run time of ")
                  .append(ReportUtils.prettyDuration(median, true))
                  .append(" (calculated from all tracked test suites) was used for them.");
            } else {
                sb.append("Note: ").append(n)
                  .append(" selected test(s) have not previously been run by Tia. No historical stats are available to derive a median run time, so they were excluded from the estimate.");
            }
        }
        return sb.toString();
    }

    /**
     * Format the estimated-savings duration. Delegates to
     * {@link ReportUtils#prettyDuration(long, boolean)} but renders zero (or clamped-to-zero)
     * savings as {@code "0s"} rather than the empty string that {@code prettyDuration} returns
     * for a zero duration.
     *
     * @param savingsMs the savings in milliseconds (never negative)
     * @return the formatted savings duration, or {@code "0s"} when there is no saving
     */
    private static String formatSavingsDuration(final long savingsMs){
        if (savingsMs <= 0){
            return "0s";
        }
        return ReportUtils.prettyDuration(savingsMs, true);
    }

    /**
     * Format the per-test runtime in brackets. Returns {@code "(no run data)"} when the test
     * has no entry in the map or the recorded value is {@code 0}.
     *
     * @param perTestTimes per-test runtime map (ms)
     * @param testName the test suite name to look up
     * @return the bracketed runtime string
     */
    private static String formatBracketedTime(final Map<String, Long> perTestTimes, final String testName){
        Long ms = perTestTimes.get(testName);
        if (ms == null || ms <= 0){
            return "(no run data)";
        }
        return "(" + ReportUtils.prettyDuration(ms, true) + ")";
    }
}
