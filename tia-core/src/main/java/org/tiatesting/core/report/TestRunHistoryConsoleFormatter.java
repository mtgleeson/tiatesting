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
 * for the {@code history} / {@code tia-history} CLI task.
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
            "Duration", "Mapping", "Id"
    };

    // Right-align numeric columns (Ran, Ignored, Failed). Everything else is left-aligned.
    private static final boolean[] RIGHT_ALIGN = {
            false, false, false, true, true, true, false, false, false
    };

    private TestRunHistoryConsoleFormatter() { }

    /**
     * Render a history list to a single plain-text string suitable for printing to stdout.
     *
     * @param entries  full history list, most recent first (as returned by {@code DataStore.readTestRunHistory()})
     * @param limit    maximum number of rows to render (the total count in the header always reflects
     *                 {@code entries.size()}); values {@code <= 0} are treated as zero rows
     * @param lineSep  line separator (typically {@code System.lineSeparator()}) so callers control EOL style
     * @return the formatted output — empty-history sentence, or a header line + blank line + table
     */
    public static String formatHistory(List<TestRunHistoryEntry> entries, int limit, String lineSep) {
        if (entries == null || entries.isEmpty()) {
            return EMPTY_HISTORY_MESSAGE;
        }

        int total = entries.size();
        int effectiveLimit = Math.max(0, limit);
        int rowCount = Math.min(effectiveLimit, total);
        List<TestRunHistoryEntry> visible = entries.subList(0, rowCount);

        List<String[]> rows = new ArrayList<>(visible.size());
        ZoneId zone = ZoneId.systemDefault();
        for (TestRunHistoryEntry e : visible) {
            rows.add(toRow(e, zone));
        }

        int[] widths = computeColumnWidths(rows);

        StringBuilder sb = new StringBuilder();
        sb.append("Displaying the latest ").append(effectiveLimit)
                .append(" test runs from a total of ").append(total).append(lineSep);
        sb.append(lineSep);

        appendRow(sb, HEADERS, widths, lineSep);
        appendSeparator(sb, widths, lineSep);
        for (String[] row : rows) {
            appendRow(sb, row, widths, lineSep);
        }

        return sb.toString();
    }

    /**
     * Build the array of column values for a single entry, in the same order as {@link #HEADERS}.
     *
     * @param e    the history entry
     * @param zone the time zone used to render {@code runTimestampMs}
     * @return a 9-element array of strings ready to be width-padded and emitted
     */
    private static String[] toRow(TestRunHistoryEntry e, ZoneId zone) {
        String dateTime = Instant.ofEpochMilli(e.getRunTimestampMs()).atZone(zone)
                .format(LOCAL_DATE_TIME);
        return new String[] {
                dateTime,
                nullSafe(e.getBranch()),
                truncate(nullSafe(e.getCommit()), TRUNCATE_LEN),
                Integer.toString(e.getNumSuitesRan()),
                Integer.toString(e.getNumSuitesIgnored()),
                Integer.toString(e.getNumSuitesFailed()),
                ReportUtils.prettyDuration(e.getDurationMs(), true),
                e.isUpdatedDbMapping() ? "yes" : "no",
                truncate(nullSafe(e.getId()), TRUNCATE_LEN)
        };
    }

    /**
     * Compute per-column max widths across the header labels and all data rows.
     *
     * @param rows the populated data rows (each the same length as {@link #HEADERS})
     * @return an array of column widths, one per header column
     */
    private static int[] computeColumnWidths(List<String[]> rows) {
        int[] widths = new int[HEADERS.length];
        for (int i = 0; i < HEADERS.length; i++) {
            widths[i] = HEADERS[i].length();
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
     * @param lineSep line separator to terminate the row
     */
    private static void appendRow(StringBuilder sb, String[] cells, int[] widths, String lineSep) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append("  ");
            }
            sb.append(pad(cells[i], widths[i], RIGHT_ALIGN[i]));
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