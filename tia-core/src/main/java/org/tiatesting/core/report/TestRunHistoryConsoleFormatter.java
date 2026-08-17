package org.tiatesting.core.report;

import org.tiatesting.core.model.TestRunHistoryEntry;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Renders a list of {@link TestRunHistoryEntry} rows as a fixed-width plain-text table
 * for the {@code history} / {@code tia-history} CLI task. The {@code Ignored} column is the
 * count of test suites Tia chose to ignore for that run, taken from
 * {@link TestRunHistoryEntry#getNumSuitesIgnored()}; engine-level skips Tia did not cause
 * are excluded.
 *
 * <p>The output shape is:
 * <pre>
 * Displaying the latest N test runs from a total of X
 *
 * Date/time            Branch        Commit    Ran  Ignored  Failed  Duration  Mapping  Id
 * -------------------  ------------  --------  ---  -------  ------  --------  -------  --------
 * 2026-05-15 09:30:42  main          abc123de   42        3       1  1m 23s    yes      550e8400
 * ...
 * </pre>
 *
 * <p>When any row in view describes a distributed build, two further columns appear after
 * {@code Duration}: {@code Wall clock} (what the build actually took - its slowest group) and
 * {@code Groups}. {@code Duration} keeps the serial-equivalent time in both modes, so it stays the
 * figure savings are computed from and stays comparable across the two. A history with no
 * distributed runs in it renders neither column, and single-host rows in a mixed history dash them.
 *
 * <p>When the input list is empty, the formatter returns the single sentence
 * {@code "No Tia test run history recorded yet."} (no header, no table).
 *
 * <p>Column widths are computed dynamically (max of header width and longest cell value),
 * commit and id are truncated to 8 characters, and timestamps are rendered in the JVM's
 * local time zone with format {@code yyyy-MM-dd HH:mm:ss}.
 */
public final class TestRunHistoryConsoleFormatter {

    private static final String EMPTY_HISTORY_MESSAGE = "No Tia test run history recorded yet.";
    private static final int TRUNCATE_LEN = 8;
    private static final DateTimeFormatter LOCAL_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String[] HEADERS = {
            "Date/time", "Branch", "Commit", "Ran", "Ignored", "Failed",
            "Duration", "Savings", "Savings %", "Mapping", "Id"
    };

    // Right-align numeric columns (Ran, Ignored, Failed, Savings %). Everything else is left-aligned.
    private static final boolean[] RIGHT_ALIGN = {
            false, false, false, true, true, true, false, false, true, false, false
    };

    /**
     * The same columns plus the two a distributed build adds: the wall clock it actually took
     * (its slowest group) and how many groups it was split across. Duration stays the
     * serial-equivalent time in both modes, so the two columns read as "what it cost" and "what you
     * waited for" rather than competing for the same meaning.
     */
    private static final String[] DISTRIBUTED_HEADERS = {
            "Date/time", "Branch", "Commit", "Ran", "Ignored", "Failed",
            "Duration", "Wall clock", "Groups", "Savings", "Savings %", "Mapping", "Id"
    };

    private static final boolean[] DISTRIBUTED_RIGHT_ALIGN = {
            false, false, false, true, true, true, false, false, true, false, true, false, false
    };

    /** Rendered in a distributed column of a single-host row, which has no such value. */
    private static final String NOT_APPLICABLE = "-";

    private TestRunHistoryConsoleFormatter() { }

    /**
     * Render a history list to a single plain-text string suitable for printing to stdout.
     *
     * @param entries  full history list, most recent first (as returned by {@code DataStore.readTestRunHistory()})
     * @param limit    maximum number of rows to render (the total count in the header always reflects
     *                 {@code entries.size()}); values {@code <= 0} are treated as zero rows
     * @param lineSep  line separator (typically {@code System.lineSeparator()}) so callers control EOL style
     * @return the formatted output - empty-history sentence, or a header line + blank line + table
     */
    public static String formatHistory(List<TestRunHistoryEntry> entries, int limit, String lineSep) {
        if (entries == null || entries.isEmpty()) {
            return EMPTY_HISTORY_MESSAGE;
        }

        int total = entries.size();
        int effectiveLimit = Math.max(0, limit);
        int rowCount = Math.min(effectiveLimit, total);
        List<TestRunHistoryEntry> visible = entries.subList(0, rowCount);

        // The two distributed columns are only worth the width when something in view is a
        // distributed build; on a project that does not distribute its tests they would be a dash
        // on every row, so the table stays exactly as it was.
        boolean showDistributed = anyDistributed(visible);
        String[] headers = showDistributed ? DISTRIBUTED_HEADERS : HEADERS;
        boolean[] rightAlign = showDistributed ? DISTRIBUTED_RIGHT_ALIGN : RIGHT_ALIGN;

        List<String[]> rows = new ArrayList<>(visible.size());
        ZoneId zone = ZoneId.systemDefault();
        for (TestRunHistoryEntry e : visible) {
            rows.add(toRow(e, zone, showDistributed));
        }

        int[] widths = computeColumnWidths(headers, rows);

        StringBuilder sb = new StringBuilder();
        sb.append("Displaying the latest ").append(rowCount)
                .append(" test runs from a total of ").append(total).append(".").append(lineSep);
        sb.append(lineSep);

        appendRow(sb, headers, widths, rightAlign, lineSep);
        appendSeparator(sb, widths, lineSep);
        for (String[] row : rows) {
            appendRow(sb, row, widths, rightAlign, lineSep);
        }

        return sb.toString();
    }

    /**
     * Report whether any visible row describes a distributed build, which is what decides between
     * the two column layouts.
     *
     * @param entries the rows about to be rendered
     * @return true when at least one row carries a distributed run's group count
     */
    private static boolean anyDistributed(List<TestRunHistoryEntry> entries) {
        for (TestRunHistoryEntry entry : entries) {
            if (entry.getGroupCount() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Build the array of column values for a single entry, in the same order as the header layout
     * in use. A single-host row rendered in the distributed layout dashes the two extra columns
     * rather than showing zeros, which would read as a build that took no time and used no groups.
     *
     * @param e    the history entry
     * @param zone the time zone used to render {@code runTimestampMs}
     * @param showDistributed whether the wall clock and group columns are being rendered
     * @return the row's cells, ready to be width-padded and emitted
     */
    private static String[] toRow(TestRunHistoryEntry e, ZoneId zone, boolean showDistributed) {
        String dateTime = Instant.ofEpochMilli(e.getRunTimestampMs()).atZone(zone)
                .format(LOCAL_DATE_TIME);
        boolean hasSavings = e.getTimeSavingsMs() > 0;
        List<String> cells = new ArrayList<>(DISTRIBUTED_HEADERS.length);
        cells.add(dateTime);
        cells.add(nullSafe(e.getBranch()));
        cells.add(truncate(nullSafe(e.getCommit()), TRUNCATE_LEN));
        cells.add(Integer.toString(e.getNumSuitesRan()));
        cells.add(Integer.toString(e.getNumSuitesIgnored()));
        cells.add(Integer.toString(e.getNumSuitesFailed()));
        cells.add(ReportUtils.prettyDuration(e.getDurationMs(), true));
        if (showDistributed) {
            cells.add(e.getWallClockMs() != null
                    ? ReportUtils.prettyDuration(e.getWallClockMs().longValue(), true)
                    : NOT_APPLICABLE);
            cells.add(e.getGroupCount() != null
                    ? e.getGroupCount().toString() : NOT_APPLICABLE);
        }
        cells.add(hasSavings ? ReportUtils.prettyDuration(e.getTimeSavingsMs(), true) : NOT_APPLICABLE);
        cells.add(hasSavings ? e.getSavingsPercent() + "%" : NOT_APPLICABLE);
        cells.add(e.isUpdatedDbMapping() ? "yes" : "no");
        cells.add(truncate(nullSafe(e.getId()), TRUNCATE_LEN));
        return cells.toArray(new String[0]);
    }

    /**
     * Compute per-column max widths across the header labels and all data rows.
     *
     * @param headers the header labels of the layout in use
     * @param rows the populated data rows (each the same length as {@code headers})
     * @return an array of column widths, one per header column
     */
    private static int[] computeColumnWidths(String[] headers, List<String[]> rows) {
        int[] widths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            widths[i] = headers[i].length();
        }
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (row[i].length() > widths[i]) {
                    widths[i] = row[i].length();
                }
            }
        }
        return widths;
    }

    /**
     * Append a single row (header or data) to {@code sb}, padding each cell to its column width
     * and separating columns with two spaces.
     *
     * @param sb      output buffer
     * @param cells   row contents in column order
     * @param widths  per-column widths
     * @param rightAlign per-column alignment of the layout in use
     * @param lineSep line separator to terminate the row
     */
    private static void appendRow(StringBuilder sb, String[] cells, int[] widths,
                                  boolean[] rightAlign, String lineSep) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append("  ");
            }
            sb.append(pad(cells[i], widths[i], rightAlign[i]));
        }
        sb.append(lineSep);
    }

    /**
     * Append the dashed separator that sits between the header row and the data rows.
     *
     * @param sb      output buffer
     * @param widths  per-column widths
     * @param lineSep line separator to terminate the row
     */
    private static void appendSeparator(StringBuilder sb, int[] widths, String lineSep) {
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) {
                sb.append("  ");
            }
            char[] dashes = new char[widths[i]];
            Arrays.fill(dashes, '-');
            sb.append(dashes);
        }
        sb.append(lineSep);
    }

    /**
     * Pad {@code value} to {@code width} with spaces. When {@code rightAlign} is {@code true}
     * the padding is added on the left; otherwise on the right.
     *
     * @param value      the cell value
     * @param width      the target column width
     * @param rightAlign whether to right-align (numeric columns) or left-align (text columns)
     * @return the padded cell value
     */
    private static String pad(String value, int width, boolean rightAlign) {
        if (value.length() >= width) {
            return value;
        }
        char[] padding = new char[width - value.length()];
        Arrays.fill(padding, ' ');
        return rightAlign ? new String(padding) + value : value + new String(padding);
    }

    /**
     * Truncate {@code value} to at most {@code maxLen} characters.
     *
     * @param value  the source string
     * @param maxLen the maximum length to keep
     * @return the truncated string ({@code value} unchanged when shorter than {@code maxLen})
     */
    private static String truncate(String value, int maxLen) {
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }

    /**
     * Coalesce null to the empty string for display purposes.
     *
     * @param value the possibly-null value
     * @return {@code value}, or {@code ""} when {@code value} is null
     */
    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}