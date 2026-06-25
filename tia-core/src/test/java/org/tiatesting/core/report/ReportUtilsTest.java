package org.tiatesting.core.report;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestRunHistoryEntry;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link ReportUtils#prettyDuration(long, boolean)}. The single-arg overload's
 * existing behaviour is exercised indirectly by other tests; this class focuses on the
 * {@code dropMsWhenAboveSecond} flag added for the select-tests output.
 */
class ReportUtilsTest {

    /**
     * Build a history entry carrying only the fields the savings calculation reads
     * ({@code numSuitesIgnored}, {@code durationMs}); the rest are filler.
     */
    private static TestRunHistoryEntry historyEntry(int numSuitesIgnored, long durationMs){
        return new TestRunHistoryEntry("id", 0L, "main", "commit", 1, numSuitesIgnored, 0, durationMs, false);
    }

    /**
     * Above one minute the {@code ms} component is dropped so a cumulative figure reads as
     * {@code "1m 59s"} rather than {@code "1m 59s 500ms"}.
     */
    @Test
    void prettyDurationDropMsAboveMinute_dropsMsAboveAMinute(){
        // given
        long oneMinuteFiftyNineAndAHalf = 119500L;

        // when
        String result = ReportUtils.prettyDurationDropMsAboveMinute(oneMinuteFiftyNineAndAHalf);

        // then
        assertEquals("1m 59s", result);
    }

    /**
     * At or below one minute the {@code ms} component is kept.
     */
    @Test
    void prettyDurationDropMsAboveMinute_keepsMsAtOrBelowAMinute(){
        // given
        long justUnderAMinute = 59999L;

        // when
        String result = ReportUtils.prettyDurationDropMsAboveMinute(justUnderAMinute);

        // then
        assertEquals("59s 999ms", result);
    }

    /**
     * The percentage of a part against a total is rounded to the nearest whole percent, and a
     * zero total yields {@code 0} rather than dividing by zero.
     */
    @Test
    void percentOfTotal_roundsAndGuardsZeroTotal(){
        // given / when / then
        assertEquals(75L, ReportUtils.percentOfTotal(75L, 100L));
        assertEquals(33L, ReportUtils.percentOfTotal(1L, 3L));
        assertEquals(67L, ReportUtils.percentOfTotal(2L, 3L));
        assertEquals(0L, ReportUtils.percentOfTotal(5L, 0L));
    }

    /**
     * Total savings sums, over history rows where Tia ignored at least one suite, the
     * full-suite baseline minus that run's actual duration (never negative). All-tests runs
     * (zero ignored) and runs slower than the baseline contribute nothing.
     */
    @Test
    void totalSavingsMs_sumsPartialRunSavingsAgainstBaseline(){
        // given - baseline 5000ms; a partial run that saved 4000ms, an all-tests run (excluded),
        // and a partial run slower than the baseline (clamped to 0)
        List<TestRunHistoryEntry> history = Arrays.asList(
                historyEntry(2, 1000L),
                historyEntry(0, 4800L),
                historyEntry(1, 6000L));

        // when
        long savings = ReportUtils.totalSavingsMs(5000L, history);

        // then
        assertEquals(4000L, savings);
    }

    /**
     * With no all-tests baseline recorded the savings cannot be computed, so the total is 0.
     */
    @Test
    void totalSavingsMs_noBaseline_isZero(){
        // given
        List<TestRunHistoryEntry> history = Collections.singletonList(historyEntry(2, 1000L));

        // when
        long savings = ReportUtils.totalSavingsMs(0L, history);

        // then
        assertEquals(0L, savings);
    }

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
     * Without the flag, the original behaviour is preserved - every non-zero component
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
