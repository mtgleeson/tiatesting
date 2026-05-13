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
     * @return the formatted estimate block, or an empty string when no estimate applies
     */
    public static String formatEstimateBlock(final TestSelectorResult result, final String lineSep){
        if (result.getTestsToRun().isEmpty()){
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(lineSep);
        sb.append("Estimated total run time: ")
          .append(ReportUtils.prettyDuration(result.getEstimatedRunTimeMs()));

        if (!result.getSelectedTestsWithoutStats().isEmpty()){
            int n = result.getSelectedTestsWithoutStats().size();
            long median = result.getMedianRunTimeMsAppliedToMissing();
            sb.append(lineSep).append(lineSep);
            if (median > 0){
                sb.append("Note: ").append(n)
                  .append(" selected test(s) have not previously been run by Tia. A median run time of ")
                  .append(ReportUtils.prettyDuration(median))
                  .append(" (calculated from all tracked test suites) was used for them.");
            } else {
                sb.append("Note: ").append(n)
                  .append(" selected test(s) have not previously been run by Tia. No historical stats are available to derive a median run time, so they were excluded from the estimate.");
            }
        }
        return sb.toString();
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
        return "(" + ReportUtils.prettyDuration(ms) + ")";
    }
}
