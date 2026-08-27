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
     * Build a history entry carrying the persisted {@code timeSavingsMs} the aggregation reads;
     * the rest are filler.
     */
    private static TestRunHistoryEntry historyEntry(long timeSavingsMs){
        return new TestRunHistoryEntry("id", 0L, "main", "commit", 1, 1, 0, 0L, false, timeSavingsMs, 0,
                null, null, null);
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
     * A single run's savings is the baseline minus its duration, clamped at zero. An all-tests
     * run and a run with no baseline save nothing; a run slower than the baseline clamps to zero.
     */
    @Test
    void runSavingsMs_partialRunAgainstBaseline(){
        // given / when / then
        assertEquals(4000L, ReportUtils.runSavingsMs(5000L, 1000L, false));   // partial run
        assertEquals(0L, ReportUtils.runSavingsMs(5000L, 1000L, true));       // all-tests run
        assertEquals(0L, ReportUtils.runSavingsMs(0L, 1000L, false));         // no baseline
        assertEquals(0L, ReportUtils.runSavingsMs(5000L, 6000L, false));      // slower than baseline
    }

    /**
     * Total savings sums the per-run {@code timeSavingsMs} frozen on the history rows.
     */
    @Test
    void totalSavingsMs_sumsPersistedPerRunSavings(){
        // given - rows carrying frozen savings of 4000, 0 and 1500
        List<TestRunHistoryEntry> history = Arrays.asList(
                historyEntry(4000L), historyEntry(0L), historyEntry(1500L));

        // when
        long savings = ReportUtils.totalSavingsMs(history);

        // then
        assertEquals(5500L, savings);
    }

    /**
     * Build a history entry for a distributed build, carrying the wall clock and group count only a
     * distributed run records; a single-host row leaves both null.
     *
     * @param wallClockMs the build's wall clock - its slowest group
     * @return a history entry the distributed aggregations count
     */
    private static TestRunHistoryEntry distributedEntry(long wallClockMs){
        return new TestRunHistoryEntry("id", 0L, "main", "commit", 1, 1, 0, 0L, false, 0L, 0,
                "run-1", Long.valueOf(wallClockMs), Integer.valueOf(3));
    }

    /**
     * The distributed average covers only the rows that carry a wall clock, and ignores single-host
     * rows entirely rather than treating their absent wall clock as a zero - which would drag the
     * average towards zero in proportion to how much of the project's history predates distributed
     * mode.
     */
    @Test
    void averageDistributedWallClockMs_averagesOnlyTheDistributedRows(){
        // given - two distributed rows either side of a single-host one
        List<TestRunHistoryEntry> history = Arrays.asList(
                distributedEntry(40_000L), historyEntry(9999L), distributedEntry(60_000L));

        // when
        long average = ReportUtils.averageDistributedWallClockMs(history);

        // then - the mean of 40s and 60s, with the single-host row excluded
        assertEquals(50_000L, average);
        assertEquals(2, ReportUtils.distributedRunCount(history));
    }

    /**
     * A history with no distributed build yields a zero average and a zero count, which is what the
     * summary reports key their second line off - so a project that has never distributed a build
     * sees no change at all.
     */
    @Test
    void averageDistributedWallClockMs_noDistributedRows_isZero(){
        // given / when / then
        assertEquals(0L, ReportUtils.averageDistributedWallClockMs(null));
        assertEquals(0L, ReportUtils.averageDistributedWallClockMs(Collections.emptyList()));
        assertEquals(0L, ReportUtils.averageDistributedWallClockMs(
                Arrays.asList(historyEntry(1000L), historyEntry(2000L))));
        assertEquals(0, ReportUtils.distributedRunCount(null));
        assertEquals(0, ReportUtils.distributedRunCount(
                Arrays.asList(historyEntry(1000L), historyEntry(2000L))));
    }

    /**
     * With no distributed build in the history the reports print the one line they always have,
     * unqualified - a project that never distributes must see no change.
     */
    @Test
    void averageRunTimeLines_noDistributedRows_isTheSingleUnqualifiedLine(){
        // given
        List<TestRunHistoryEntry> history = Arrays.asList(historyEntry(0L));

        // when
        List<String> lines = ReportUtils.averageRunTimeLines(100_000L, 200_000L, history);

        // then
        assertEquals(1, lines.size());
        assertEquals("Average run time: 1m 40s (50%)", lines.get(0));
    }

    /**
     * With distributed builds present, the existing line is qualified as the serial equivalent and a
     * second reports the average wall clock. Both changes belong together: the qualifier alone
     * explains nothing, and the second line alone leaves two unlabelled averages of different things
     * beside each other. The run count is stated because the two lines average different
     * populations - every run against distributed runs only.
     */
    @Test
    void averageRunTimeLines_withDistributedRows_qualifiesTheTotalAndAddsTheWallClock(){
        // given - two distributed runs averaging 50s, against a 200s all-tests baseline
        List<TestRunHistoryEntry> history = Arrays.asList(
                distributedEntry(40_000L), historyEntry(0L), distributedEntry(60_000L));

        // when
        List<String> lines = ReportUtils.averageRunTimeLines(100_000L, 200_000L, history);

        // then
        assertEquals(2, lines.size());
        assertEquals("Average run time (serial equivalent): 1m 40s (50%)", lines.get(0));
        assertEquals("Average distributed run time: 50s (25%) over 2 distributed run(s)", lines.get(1));
    }

    /**
     * A null or empty history yields zero total savings.
     */
    @Test
    void totalSavingsMs_nullOrEmpty_isZero(){
        // given / when / then
        assertEquals(0L, ReportUtils.totalSavingsMs(null));
        assertEquals(0L, ReportUtils.totalSavingsMs(Collections.emptyList()));
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
