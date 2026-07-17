package org.tiatesting.core.report;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal column-aligned ASCII table renderer for console reports: a header row, a separator
 * line, and data rows, with every column padded to the width of its widest cell. Used by the
 * library reporting tasks so per-row detail stays scannable in build logs.
 */
public class TextTable {

    private final String[] headers;
    private final List<String[]> rows = new ArrayList<>();

    /**
     * Create a table with the given column headers. Every added row must supply one cell per
     * header.
     *
     * @param headers the column header labels.
     */
    public TextTable(String... headers) {
        this.headers = headers;
    }

    /**
     * Add a data row. Null cells render as {@code -}.
     *
     * @param cells one cell per column, in header order.
     * @throws IllegalArgumentException when the cell count does not match the header count.
     */
    public void addRow(String... cells) {
        if (cells.length != headers.length) {
            throw new IllegalArgumentException("Expected " + headers.length + " cells but got " + cells.length);
        }
        String[] row = new String[cells.length];
        for (int i = 0; i < cells.length; i++) {
            row[i] = cells[i] != null ? cells[i] : "-";
        }
        rows.add(row);
    }

    /**
     * Render the table: header, dashed separator, then each row, columns separated by
     * {@code " | "} and padded to the widest cell in their column.
     *
     * @param lineSep the line separator to use between rows.
     * @return the rendered table, ending without a trailing separator.
     */
    public String render(String lineSep) {
        int[] widths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            widths[i] = headers[i].length();
        }
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i].length());
            }
        }

        StringBuilder sb = new StringBuilder();
        appendRow(sb, headers, widths);
        sb.append(lineSep);
        for (int i = 0; i < headers.length; i++) {
            if (i > 0) {
                sb.append("-+-");
            }
            for (int j = 0; j < widths[i]; j++) {
                sb.append('-');
            }
        }
        for (String[] row : rows) {
            sb.append(lineSep);
            appendRow(sb, row, widths);
        }
        return sb.toString();
    }

    /**
     * Append one row's cells, left-padded to the column widths and joined with {@code " | "}.
     *
     * @param sb the builder to append to.
     * @param cells the row cells.
     * @param widths the resolved column widths.
     */
    private void appendRow(StringBuilder sb, String[] cells, int[] widths) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(cells[i]);
            for (int j = cells[i].length(); j < widths[i]; j++) {
                sb.append(' ');
            }
        }
    }
}
