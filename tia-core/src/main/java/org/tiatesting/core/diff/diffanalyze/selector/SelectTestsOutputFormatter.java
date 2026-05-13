package org.tiatesting.core.diff.diffanalyze.selector;

import org.tiatesting.core.report.ReportUtils;

import java.util.stream.Collectors;

/**
 * Builds the user-facing output appended after the "Selected tests to run" block in the
 * {@code tia-select-tests} task (Maven + Gradle). Shared so both plugins produce identical
 * wording.
 */
public class SelectTestsOutputFormatter {

    private SelectTestsOutputFormatter() {}

    /**
     * Build the estimate-runtime block for a {@link TestSelectorResult}. Returns an empty
     * string when {@code testsToRun} is empty (no estimate to display).
     *
     * <p>When the result includes selected tests without recorded stats, an additional note
     * is appended listing those tests. The note explicitly states the median run time used
     * (or notes that the tests were excluded when no historical stats are available to
     * derive a median).
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
                sb.append("Note: the following ").append(n)
                  .append(" selected test(s) have no recorded run time. A median run time of ")
                  .append(ReportUtils.prettyDuration(median))
                  .append(" (calculated from all tracked test suites) was used to estimate their contribution to the total:");
            } else {
                sb.append("Note: the following ").append(n)
                  .append(" selected test(s) have no recorded run time and were excluded from the estimate (no historical stats are available to derive a median run time):");
            }
            sb.append(lineSep)
              .append(result.getSelectedTestsWithoutStats().stream()
                      .collect(Collectors.joining(lineSep + "\t", "\t", "")));
        }
        return sb.toString();
    }
}
