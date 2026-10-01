package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Verifies {@link MethodImpactTracker#getMatchedLineRanges()}, the single definition of the lines a
 * source change must touch to impact a method.
 */
class MethodImpactTrackerMatchedLinesTest {

    /**
     * An ordinary method is matched on its first to last code line widened by one line either
     * side, for its signature and closing brace.
     */
    @Test
    void ordinaryMethodIsMatchedOnItsCodeLinesPlusOneEitherSide() {
        // given
        MethodImpactTracker method = new MethodImpactTracker("com/example/Car.checkBrakes.()V", 36, 38);

        // when
        int[] matched = method.getMatchedLineRanges();

        // then
        assertArrayEquals(new int[]{35, 39}, matched);
    }

    /**
     * A split constructor is matched on its stored line ranges as-is, since they already include
     * the allowance.
     */
    @Test
    void splitConstructorIsMatchedOnItsStoredLineRanges() {
        // given
        int[] lineRanges = {7, 16, 25, 25, 74, 75};
        MethodImpactTracker constructor = new MethodImpactTracker("com/example/Car.<init>.()V", 8, 74, lineRanges);

        // when
        int[] matched = constructor.getMatchedLineRanges();

        // then
        assertArrayEquals(lineRanges, matched);
    }
}
