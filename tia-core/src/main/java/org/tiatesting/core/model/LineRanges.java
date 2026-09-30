package org.tiatesting.core.model;

/**
 * Converts a method's exact line ranges (see {@link MethodImpactTracker#getLineRanges()}) to and
 * from the text form stored in the datastore's {@code line_ranges} column, e.g.
 * {@code "7-16,20-21,44-44,74-75"}. Each comma-separated entry is an inclusive {@code start-end}
 * pair, in ascending order.
 * <p>
 * See the "Constructor and static initializer line ranges" chapter in {@code WIKI.md}.
 */
public final class LineRanges {

    private static final char RANGE_SEPARATOR = ',';
    private static final char BOUND_SEPARATOR = '-';

    private LineRanges() {
    }

    /**
     * Format flat inclusive start/end pairs as the stored text form.
     *
     * @param lineRanges flat inclusive {@code [start, end, ...]} pairs, or {@code null}
     * @return the stored text form, or {@code null} when there are no ranges (so the column stays
     *         null for the common case of a method matched by its start-end range)
     */
    public static String format(int[] lineRanges) {
        if (lineRanges == null || lineRanges.length == 0) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lineRanges.length; i += 2) {
            if (i > 0) {
                text.append(RANGE_SEPARATOR);
            }
            text.append(lineRanges[i]).append(BOUND_SEPARATOR).append(lineRanges[i + 1]);
        }
        return text.toString();
    }

    /**
     * Parse the stored text form back into flat inclusive start/end pairs.
     *
     * @param text the stored text form, or {@code null}
     * @return flat inclusive {@code [start, end, ...]} pairs, or {@code null} when the text is null
     *         or empty
     */
    public static int[] parse(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String[] ranges = text.split(String.valueOf(RANGE_SEPARATOR));
        int[] lineRanges = new int[ranges.length * 2];
        for (int i = 0; i < ranges.length; i++) {
            int separator = ranges[i].indexOf(BOUND_SEPARATOR);
            lineRanges[i * 2] = Integer.parseInt(ranges[i].substring(0, separator));
            lineRanges[i * 2 + 1] = Integer.parseInt(ranges[i].substring(separator + 1));
        }
        return lineRanges;
    }
}
