package org.tiatesting.core.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link ReportUtils#prettyDuration(long, boolean)}. The single-arg overload's
 * existing behaviour is exercised indirectly by other tests; this class focuses on the
 * {@code dropMsWhenAboveSecond} flag added for the select-tests output.
 */
class ReportUtilsTest {

    /**
     * With the flag enabled, durations of one second or more drop the {@code ms} component
     * so output reads as {@code "1s"} rather than {@code "1s 500ms"}.
     */
    @Test
    void prettyDuration_dropMsWhenAboveSecond_dropsMsForOneSecondAndAbove(){
        // given
        long oneAndAHalfSeconds = 1500L;
        long oneMinuteThirtySecondsHalfSecond = 90500L;

        // when
        String oneAndAHalf = ReportUtils.prettyDuration(oneAndAHalfSeconds, true);
        String oneMinuteThirty = ReportUtils.prettyDuration(oneMinuteThirtySecondsHalfSecond, true);

        // then
        assertEquals("1s", oneAndAHalf);
        assertEquals("1m 30s", oneMinuteThirty);
    }

    /**
     * With the flag enabled, durations below one second keep the {@code ms} unit (otherwise
     * sub-second values would render as the empty string).
     */
    @Test
    void prettyDuration_dropMsWhenAboveSecond_keepsMsForSubSecond(){
        // given
        long sevenHundredFiftyMs = 750L;

        // when
        String result = ReportUtils.prettyDuration(sevenHundredFiftyMs, true);

        // then
        assertEquals("750ms", result);
    }

    /**
     * Without the flag, the original behaviour is preserved — every non-zero component
     * appears in the output, including {@code ms}.
     */
    @Test
    void prettyDuration_flagFalse_preservesOriginalBehaviour(){
        // given
        long oneAndAHalfSeconds = 1500L;

        // when
        String withFlagFalse = ReportUtils.prettyDuration(oneAndAHalfSeconds, false);
        String singleArg = ReportUtils.prettyDuration(oneAndAHalfSeconds);

        // then
        assertEquals("1s 500ms", withFlagFalse);
        assertEquals("1s 500ms", singleArg);
    }
}
