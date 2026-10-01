package org.tiatesting.core.report.plaintext;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.MethodImpactTracker;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies how {@link TextSummaryReport} describes a tracked method's lines.
 */
class TextSummaryReportLineSpanTest {

    /**
     * An ordinary method is described by its start and end line only.
     */
    @Test
    void ordinaryMethodShowsStartAndEnd() {
        // given
        MethodImpactTracker method = new MethodImpactTracker("com/example/Car.checkBrakes.()V", 36, 38);

        // when
        String span = TextSummaryReport.lineSpan(method);

        // then
        assertEquals("36 -> 38", span);
    }

    /**
     * A constructor whose range is split by a late field also shows its matched lines.
     */
    @Test
    void splitConstructorAppendsMatchedLines() {
        // given
        MethodImpactTracker constructor = new MethodImpactTracker("com/example/Car.<init>.()V", 8, 74,
                new int[]{7, 16, 25, 25, 74, 75});

        // when
        String span = TextSummaryReport.lineSpan(constructor);

        // then
        assertEquals("8 -> 74 (matched lines 7-16,25,74-75)", span);
    }
}
