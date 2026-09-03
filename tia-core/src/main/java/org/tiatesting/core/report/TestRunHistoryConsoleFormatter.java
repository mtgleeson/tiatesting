package org.tiatesting.core.report;

import org.tiatesting.core.model.RunOrigin;
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
 * <p><b>Two groups of columns appear only when they have something to say.</b> The table is already
 * wide, and a column that is a dash on every row costs width while telling the reader nothing:
 * <ul>
 *   <li>{@code Wall clock} and {@code Groups} appear when any row in view describes a distributed
 *       build. {@code Duration} keeps the serial-equivalent time in both modes, so it stays the
 *       figure savings are computed from and stays comparable across the two; single-host rows in a
 *       mixed history dash the two extra columns.</li>
 *   <li>{@code Source} and {@code Host} appear when any row in view carries a known run origin.
 *       A history recorded entirely before those columns existed renders neither.</li>
 * </ul>
 *
 * <p>When the input list is empty, the formatter returns the single sentence
 * {@code "No Tia test run history recorded yet."} (no header, no table).
 *
 * <p>Column widths are computed dynamically (max of header width and longest cell value),
 * commit and id are truncated to 8 characters, and timestamps are rendered in the JVM's
 * local time zone with format {@code yyyy-MM-dd HH:mm:ss}. The host is deliberately not
 * truncated: unlike a commit hash it is read to tell machines apart, and a fixed-width prefix of
 * several agents in the same naming scheme would collapse them into one.
 */
public final class TestRunHistoryConsoleFormatter {

    private static final String EMPTY_HISTORY_MESSAGE = "No Tia test run history recorded yet.";
    private static final int TRUNCATE_LEN = 8;
    private static final DateTimeFormatter LOCAL_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Rendered where a row has no value for a column the layout is showing. */
    private static final String NOT_APPLICABLE = "-";

    private TestRunHistoryConsoleFormatter() { }

    /**
     * Produces one row's value for one column.
     */
    private interface CellValue {
        /**
         * @param entry the history row being rendered
         * @param zone the time zone to render timestamps in
         * @return the cell's text, never null
         */
        String of(TestRunHistoryEntry entry, ZoneId zone);
    }

    /**
     * One column of the table: its header, its alignment and how to read its value from a row.
     *
     * <p>Bundling the three together is what lets the optional column groups be assembled by
     * filtering one list. Held as three parallel arrays instead, each optional group would double
     * the number of layouts that must be kept in step - and a header, an alignment flag and a cell
     * that drift out of alignment produce a table that is wrong rather than one that fails.
     */
    private static final class Column {
        private final String header;
        private final boolean rightAlign;
        private final CellValue value;

        /**
         * @param header the column's header label
         * @param rightAlign true to right-align the column (numeric), false to left-align (text)
         * @param value how to read this column's value from a row
         */
        Column(final String header, final boolean rightAlign, final CellValue value) {
            this.header = header;
            this.rightAlign = rightAlign;
            this.value = value;
        }
    }

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

        List<Column> columns = layout(anyDistributed(visible), anyKnownOrigin(visible));

        String[] headers = new String[columns.size()];
        boolean[] rightAlign = new boolean[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            headers[i] = columns.get(i).header;
            rightAlign[i] = columns.get(i).rightAlign;
        }

        List<String[]> rows = new ArrayList<>(visible.size());
        ZoneId zone = ZoneId.systemDefault();
        for (TestRunHistoryEntry e : visible) {
            String[] cells = new String[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                cells[i] = columns.get(i).value.of(e, zone);
            }
            rows.add(cells);
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
     * Assemble the columns for this render, including each optional group only when the rows in
     * view have something to put in it.
     *
     * @param showDistributed whether to include the wall-clock and group-count columns
     * @param showOrigin whether to include the run-source and host columns
     * @return the columns in display order
     */
    private static List<Column> layout(final boolean showDistributed, final boolean showOrigin) {
        List<Column> columns = new ArrayList<>();
        columns.add(new Column("Date/time", false, (e, zone) ->
                Instant.ofEpochMilli(e.getRunTimestampMs()).atZone(zone).format(LOCAL_DATE_TIME)));
        columns.add(new Column("Branch", false, (e, zone) -> nullSafe(e.getBranch())));
        columns.add(new Column("Commit", false, (e, zone) ->
                truncate(nullSafe(e.getCommit()), TRUNCATE_LEN)));
        columns.add(new Column("Ran", true, (e, zone) -> Integer.toString(e.getNumSuitesRan())));
        columns.add(new Column("Ignored", true, (e, zone) -> Integer.toString(e.getNumSuitesIgnored())));
        columns.add(new Column("Failed", true, (e, zone) -> Integer.toString(e.getNumSuitesFailed())));
        columns.add(new Column("Duration", false, (e, zone) ->
                ReportUtils.prettyDuration(e.getDurationMs(), true)));

        if (showDistributed) {
            // Dashed rather than zeroed on a single-host row, which would read as a build that took
            // no time and used no groups.
            columns.add(new Column("Wall clock", false, (e, zone) -> e.getWallClockMs() != null
                    ? ReportUtils.prettyDuration(e.getWallClockMs().longValue(), true)
                    : NOT_APPLICABLE));
            columns.add(new Column("Groups", true, (e, zone) -> e.getGroupCount() != null
                    ? e.getGroupCount().toString() : NOT_APPLICABLE));
        }

        columns.add(new Column("Savings", false, (e, zone) -> e.getTimeSavingsMs() > 0
                ? ReportUtils.prettyDuration(e.getTimeSavingsMs(), true) : NOT_APPLICABLE));
        columns.add(new Column("Savings %", true, (e, zone) -> e.getTimeSavingsMs() > 0
                ? e.getSavingsPercent() + "%" : NOT_APPLICABLE));

        if (showOrigin) {
            columns.add(new Column("Source", false, (e, zone) ->
                    orNotApplicable(e.getRunOrigin().getRunSource())));
            // Dashed for a distributed build as well as for a row that predates the column: no
            // single machine ran it, so there is no host to name.
            columns.add(new Column("Host", false, (e, zone) ->
                    orNotApplicable(e.getRunOrigin().getHostName())));
        }

        columns.add(new Column("Mapping", false, (e, zone) -> e.isUpdatedDbMapping() ? "yes" : "no"));
        columns.add(new Column("Id", false, (e, zone) -> truncate(nullSafe(e.getId()), TRUNCATE_LEN)));
        return columns;
    }

    /**
     * Report whether any visible row describes a distributed build.
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
     * Report whether any visible row knows where it came from. Either half counts: a distributed
     * build records a source with no host, so requiring both would hide the source on a history
     * made up entirely of distributed builds.
     *
     * @param entries the rows about to be rendered
     * @return true when at least one row carries a run source or a host
     */
    private static boolean anyKnownOrigin(List<TestRunHistoryEntry> entries) {
        for (TestRunHistoryEntry entry : entries) {
            RunOrigin origin = entry.getRunOrigin();
            if (origin.getRunSource() != null || origin.getHostName() != null) {
                return true;
            }
        }
        return false;
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

    /**
     * Coalesce null to the not-applicable dash, for a column whose absence is meaningful rather
     * than merely empty.
     *
     * @param value the possibly-null value
     * @return {@code value}, or the dash placeholder when {@code value} is null
     */
    private static String orNotApplicable(String value) {
        return value == null ? NOT_APPLICABLE : value;
    }
}
