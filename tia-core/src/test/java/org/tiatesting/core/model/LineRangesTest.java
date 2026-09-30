package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the stored text form of a method's exact line ranges produced and read by
 * {@link LineRanges}.
 */
class LineRangesTest {

    /**
     * Flat start/end pairs format as comma-separated {@code start-end} entries, including
     * single-line ranges.
     */
    @Test
    void formatWritesCommaSeparatedStartEndPairs() {
        // given
        int[] lineRanges = {7, 16, 20, 21, 44, 44, 74, 75};

        // when
        String text = LineRanges.format(lineRanges);

        // then
        assertEquals("7-16,20-21,44-44,74-75", text);
    }

    /**
     * No ranges format as null, so the column stays null for plain methods.
     */
    @Test
    void formatReturnsNullForNoRanges() {
        // given
        int[] noRanges = null;
        int[] emptyRanges = new int[0];

        // when
        String noRangesText = LineRanges.format(noRanges);
        String emptyRangesText = LineRanges.format(emptyRanges);

        // then
        assertNull(noRangesText);
        assertNull(emptyRangesText);
    }

    /**
     * The stored text parses back into the same flat start/end pairs it was formatted from.
     */
    @Test
    void parseReversesFormat() {
        // given
        int[] lineRanges = {2, 8, 14, 31};
        String text = LineRanges.format(lineRanges);

        // when
        int[] parsed = LineRanges.parse(text);

        // then
        assertArrayEquals(lineRanges, parsed);
    }

    /**
     * A null or empty column value parses as no ranges.
     */
    @Test
    void parseReturnsNullForNullOrEmptyText() {
        // given
        String nullText = null;
        String emptyText = "";

        // when
        int[] fromNull = LineRanges.parse(nullText);
        int[] fromEmpty = LineRanges.parse(emptyText);

        // then
        assertNull(fromNull);
        assertNull(fromEmpty);
    }
}
