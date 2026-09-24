package org.tiatesting.core.report;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunHistoryEntry;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link ReportUtils}'s duration formatting - {@link ReportUtils#prettyDuration(long,
 * boolean)} and its {@code dropMsWhenAboveSecond} flag, and {@link
 * ReportUtils#prettyDurationDropMsAboveMinute(long)} - along with the savings helpers.
 */
class ReportUtilsTest {

    /**
     * Build a history entry carrying the persisted {@code timeSavingsMs} the aggregation reads;
     * the rest are filler.
     */
    private static TestRunHistoryEntry historyEntry(long timeSavingsMs){
        return new TestRunHistoryEntry("id", 0L, "main", "commit", 1, 1, 0, 0L, false, timeSavingsMs, 0, timeSavingsMs, 0,
                null, null, null, null, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null, null);
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
     * The wall-clock baseline is the serial full-suite baseline spread evenly across the groups
     * available; one group leaves it unchanged.
     */
    @Test
    void wallClockAllTestsRunTimeMs_spreadsTheBaselineAcrossTheGroupsAvailable(){
        // given
        long allTestsRunTimeMs = 3_600_000L;

        // when
        long acrossSix = ReportUtils.wallClockAllTestsRunTimeMs(allTestsRunTimeMs, 6);
        long acrossOne = ReportUtils.wallClockAllTestsRunTimeMs(allTestsRunTimeMs, 1);

        // then
        assertEquals(600_000L, acrossSix);
        assertEquals(3_600_000L, acrossOne);
    }

    /**
     * Savings text is the duration followed by its percentage, or a dash when nothing was saved.
     */
    @Test
    void savingsText_rendersDurationAndPercentOrADash(){
        // given / when / then
        assertEquals("8s (80%)", ReportUtils.savingsText(8_000L, 80));
        assertEquals("-", ReportUtils.savingsText(0L, 0));
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
        long savings = ReportUtils.totalWallClockSavingsMs(history);

        // then
        assertEquals(5500L, savings);
    }

    /**
     * The total sums the wall-clock savings, not the serial savings, when the two differ.
     */
    @Test
    void totalWallClockSavingsMs_sumsTheWallClockNotTheSerialSavings(){
        // given - a distributed row that saved 58s serially but 8s of wall clock
        List<TestRunHistoryEntry> history = Arrays.asList(historyEntry(4000L),
                allTestsRunEntry(1L, true, Integer.valueOf(6)),
                new TestRunHistoryEntry("d", 2L, "main", "commit", 1, 7, 0, 2_000L, true, 58_000L,
                        97, 8_000L, 80, "run-1", Long.valueOf(2_000L), Integer.valueOf(1),
                        Integer.valueOf(6), RunOrigin.of(RunOrigin.SOURCE_CI, null),
                        null, null, null, null, null));

        // when
        long savings = ReportUtils.totalWallClockSavingsMs(history);

        // then
        assertEquals(12_000L, savings);
    }

    /**
     * Build a history row for a run that ignored nothing - an all-tests run - at the given time.
     *
     * @param timestampMs when the run started
     * @param updatedDbMapping whether the run owned the mapping, and so moved the baseline
     * @param groupCount the groups it was split across, or null for a single-host run
     * @return the history row
     */
    private static TestRunHistoryEntry allTestsRunEntry(long timestampMs, boolean updatedDbMapping,
                                                        Integer groupCount){
        return new TestRunHistoryEntry("all-" + timestampMs, timestampMs, "main", "commit", 10, 0, 0,
                60_000L, updatedDbMapping, 0L, 0, 0L, 0, groupCount == null ? null : "run-" + timestampMs,
                groupCount == null ? null : Long.valueOf(10_000L), groupCount, groupCount,
                RunOrigin.of(RunOrigin.SOURCE_CI, null), null, null, null, null, null);
    }

    /**
     * The group count comes from the most recent all-tests run that owned the mapping. A later
     * local all-tests run did not move the baseline, so it is ignored, as is any partial run.
     */
    @Test
    void lastAllTestsRunGroupCount_readsTheLatestMappingOwningAllTestsRun(){
        // given - an older 3-group run, a newer 6-group run, then a newer local single-host run
        List<TestRunHistoryEntry> history = Arrays.asList(
                allTestsRunEntry(1_000L, true, Integer.valueOf(3)),
                allTestsRunEntry(3_000L, false, null),
                historyEntry(4000L),
                allTestsRunEntry(2_000L, true, Integer.valueOf(6)));

        // when
        int groups = ReportUtils.lastAllTestsRunGroupCount(history);

        // then
        assertEquals(6, groups);
    }

    /**
     * A single-host all-tests run, or no all-tests run at all, counts as one group.
     */
    @Test
    void lastAllTestsRunGroupCount_isOneForASingleHostRunOrNone(){
        // given / when / then
        assertEquals(1, ReportUtils.lastAllTestsRunGroupCount(
                Collections.singletonList(allTestsRunEntry(1_000L, true, null))));
        assertEquals(1, ReportUtils.lastAllTestsRunGroupCount(Collections.emptyList()));
        assertEquals(1, ReportUtils.lastAllTestsRunGroupCount(null));
    }

    /**
     * A null or empty history yields zero total savings.
     */
    @Test
    void totalSavingsMs_nullOrEmpty_isZero(){
        // given / when / then
        assertEquals(0L, ReportUtils.totalWallClockSavingsMs(null));
        assertEquals(0L, ReportUtils.totalWallClockSavingsMs(Collections.emptyList()));
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

        // then
        assertEquals("1s 500ms", withFlagFalse);
    }

    /**
     * A zero duration renders as {@code 0ms} rather than an empty string, which would read as a
     * missing value - a distributed build with no groups records exactly zero.
     */
    @Test
    void prettyDuration_zero_rendersAsZeroMs(){
        // given
        long zero = 0L;

        // when
        String plain = ReportUtils.prettyDuration(zero, false);
        String droppingMs = ReportUtils.prettyDuration(zero, true);
        String droppingMsAboveMinute = ReportUtils.prettyDurationDropMsAboveMinute(zero);

        // then
        assertEquals("0ms", plain);
        assertEquals("0ms", droppingMs);
        assertEquals("0ms", droppingMsAboveMinute);
    }
}
