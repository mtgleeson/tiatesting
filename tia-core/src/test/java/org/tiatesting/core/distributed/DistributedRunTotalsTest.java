package org.tiatesting.core.distributed;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cover the arithmetic the build's one history row is built from: the serial-equivalent duration a
 * distributed build would have taken on one host, the wall clock it actually took (its slowest
 * group), and the suite counters summed across groups.
 *
 * <p>The two durations are deliberately different numbers with different jobs. The serial figure is
 * what savings and the Tia-level stats are computed from, so a distributed build stays comparable
 * with the single-host history either side of it; the wall clock is what the user waited for.
 *
 * <p>The serial figure is <b>not</b> a plain sum of the groups: every runner pays a fixed per-JVM
 * cost inside its measured window that a single-host run of the same suites would pay once, so
 * summing charges it once per group. The tests below pin both halves of that - the correction when
 * the groups reported the suite-time split it is measured from, and the fall back to the plain sum
 * when they did not.
 */
class DistributedRunTotalsTest {

    private static final String RUN_ID = "run-1";

    /**
     * The serial-equivalent duration falls back to the plain sum of every group's measured time when
     * no group reported how much of its time went on named suites - and never to the wall clock,
     * which would silently credit Tia with the parallelism the CI system provided.
     */
    @Test
    void serialEquivalentDurationFallsBackToTheSumWhenNoGroupTimedItsSuites() {
        // given
        List<DistributedRunGroup> groups = Arrays.asList(
                completed(0, 3_000L, 2, 0),
                completed(1, 5_000L, 3, 1),
                completed(2, 1_000L, 1, 0));

        // when
        DistributedRunTotals totals = DistributedRunTotals.from(groups);

        // then
        assertEquals(9_000L, totals.getSerialDurationMs(),
                "with no suite-time split to work from, the serial duration must be the plain sum "
                        + "of every group's time");
        assertEquals(0L, totals.getFixedOverheadMs(),
                "a build whose groups ran suites without timing them must measure no overhead, so "
                        + "the correction is a no-op rather than a guess");
    }

    /**
     * The fixed per-JVM overhead every runner pays is charged <b>once</b> for the build, not once per
     * group. Three groups each paying 5s of JVM start-up on top of their suites total 300s summed,
     * but the same selection on one host would have paid the 5s once - 290s - and that is the figure
     * savings and the full-suite baseline have to be computed from.
     */
    @Test
    void theFixedOverheadIsChargedOnceForTheBuildNotOncePerGroup() {
        // given - each group's duration is its suite time plus the same 5s of fixed JVM overhead
        List<DistributedRunGroup> groups = Arrays.asList(
                completed(0, 105_000L, 100_000L, 2, 0),
                completed(1, 100_000L, 95_000L, 3, 0),
                completed(2, 95_000L, 90_000L, 1, 0));

        // when
        DistributedRunTotals totals = DistributedRunTotals.from(groups);

        // then
        assertEquals(5_000L, totals.getFixedOverheadMs(),
                "the fixed overhead is the gap each group left between its duration and its suite "
                        + "time");
        assertEquals(290_000L, totals.getSerialDurationMs(),
                "the serial-equivalent duration must be the 285s of suite time plus one 5s "
                        + "overhead, not the 300s a plain sum would charge");
    }

    /**
     * The overhead charged once is the <b>smallest</b> gap any group left, not the mean and not the
     * largest, because that is the largest amount every runner demonstrably paid. It also matters
     * that the rest survives: a group that retried its failed tests spent real wall-clock time that
     * no fresh suite run accounts for, and that time belongs in the build's total rather than being
     * mistaken for a duplicated fixed cost.
     */
    @Test
    void onlyTheOverheadEveryGroupPaidIsRemovedSoARetrysTimeSurvives() {
        // given - group 1 spent an extra 20s retrying its failed tests, which times no fresh suite
        List<DistributedRunGroup> groups = Arrays.asList(
                completed(0, 105_000L, 100_000L, 2, 0),
                completed(1, 120_000L, 95_000L, 3, 1),
                completed(2, 95_000L, 90_000L, 1, 0));

        // when
        DistributedRunTotals totals = DistributedRunTotals.from(groups);

        // then
        assertEquals(5_000L, totals.getFixedOverheadMs(),
                "the retrying group's 25s gap must not raise the overhead every group is credited "
                        + "with paying");
        assertEquals(310_000L, totals.getSerialDurationMs(),
                "285s of suite time, plus one 5s overhead, plus the 20s the retry really cost - "
                        + "the retry must not be subtracted away as duplicated overhead");
    }

    /**
     * A group that ran suites but timed none of them disqualifies the whole build from the
     * correction. Its entire duration would otherwise look like fixed overhead, making the smallest
     * measured gap the whole of that group's time and gutting the total. Over-stating the duration
     * (the fall back) under-states savings; under-stating it inflates the baseline every later
     * savings figure is measured against, and only one of those is safe to be wrong about.
     */
    @Test
    void aGroupThatRanSuitesWithoutTimingThemDisablesTheCorrectionForTheWholeBuild() {
        // given - group 1 ran suites with stats collection off, so it reports no suite time
        List<DistributedRunGroup> groups = Arrays.asList(
                completed(0, 105_000L, 100_000L, 2, 0),
                completed(1, 100_000L, 0L, 3, 0),
                completed(2, 95_000L, 90_000L, 1, 0));

        // when
        DistributedRunTotals totals = DistributedRunTotals.from(groups);

        // then
        assertEquals(0L, totals.getFixedOverheadMs(),
                "one group without a suite-time split makes the whole build un-decomposable");
        assertEquals(300_000L, totals.getSerialDurationMs(),
                "the serial duration must fall back to the plain sum rather than treat group 1's "
                        + "whole duration as overhead");
    }

    /**
     * A group that was assigned no suites still started a JVM, so the time it spent is pure fixed
     * overhead and is a legitimate measurement of it. This is the shape a nothing-impacted build
     * takes, where every group is empty.
     */
    @Test
    void anEmptyGroupsTimeIsCountedAsAMeasurementOfTheFixedOverhead() {
        // given - group 2 was assigned nothing, so its 2s is all start-up
        List<DistributedRunGroup> groups = Arrays.asList(
                completed(0, 105_000L, 100_000L, 2, 0),
                completed(1, 100_000L, 95_000L, 3, 0),
                completed(2, 2_000L, 0L, 0, 0));

        // when
        DistributedRunTotals totals = DistributedRunTotals.from(groups);

        // then
        assertEquals(2_000L, totals.getFixedOverheadMs(),
                "a group that ran no suites has no suite time to report, and that is a measurement "
                        + "of pure overhead rather than a missing split");
        assertEquals(203_000L, totals.getSerialDurationMs(),
                "the 207s summed less two of the three 2s overhead copies");
    }

    /**
     * The overhead is subtracted once per group that actually contributed a duration, not once per
     * group in the plan. A group that recorded no duration added nothing to the sum, so subtracting
     * a copy on its behalf would take out time no group ever put in.
     */
    @Test
    void noOverheadCopyIsSubtractedForAGroupThatContributedNoDuration() {
        // given - two measured groups and one that recorded nothing
        List<DistributedRunGroup> groups = Arrays.asList(
                completed(0, 105_000L, 100_000L, 2, 0),
                completed(1, 100_000L, 95_000L, 3, 0),
                new DistributedRunGroup(RUN_ID, 2, DistributedRunGroupStatus.COMPLETED, "runner-2",
                        5000L, 6000L, 1000L, null, 0, 0, 0, 0L));

        // when
        DistributedRunTotals totals = DistributedRunTotals.from(groups);

        // then
        assertEquals(200_000L, totals.getSerialDurationMs(),
                "205s summed less one 5s overhead copy - two measured groups keep one copy between "
                        + "them, and the unmeasured group is not charged for one it never added");
    }

    /**
     * The serial-equivalent duration is clamped at zero. Nothing in the arithmetic should be able to
     * drive it negative, but a negative duration on the history row would render as a nonsense
     * saving, so the floor is explicit rather than assumed.
     */
    @Test
    void theSerialDurationIsNeverNegative() {
        // given - degenerate measurements where every group is pure overhead
        List<DistributedRunGroup> groups = Arrays.asList(
                completed(0, 1_000L, 0L, 0, 0),
                completed(1, 1_000L, 0L, 0, 0),
                completed(2, 1_000L, 0L, 0, 0));

        // when
        DistributedRunTotals totals = DistributedRunTotals.from(groups);

        // then
        assertEquals(1_000L, totals.getSerialDurationMs(),
                "three idle JVMs of 1s each cost the build one 1s of fixed overhead, never a "
                        + "negative duration");
    }

    /**
     * The wall clock a distributed build took is its slowest group: every other group finished
     * inside that window, so the build was not done until that one was.
     */
    @Test
    void wallClockDurationIsTheSlowestGroup() {
        // given
        List<DistributedRunGroup> groups = Arrays.asList(
                completed(0, 3_000L, 2, 0),
                completed(1, 5_000L, 3, 1),
                completed(2, 1_000L, 1, 0));

        // when
        DistributedRunTotals totals = DistributedRunTotals.from(groups);

        // then
        assertEquals(5_000L, totals.getWallClockMs(),
                "the wall clock must be the slowest group, not the sum and not the average");
    }

    /**
     * The suite counters are summed across groups, since the groups partition the build's selection
     * and no suite is assigned to two of them.
     */
    @Test
    void suiteCountersAreSummedAcrossEveryGroup() {
        // given
        List<DistributedRunGroup> groups = Arrays.asList(
                completed(0, 3_000L, 2, 0),
                completed(1, 5_000L, 3, 1));

        // when
        DistributedRunTotals totals = DistributedRunTotals.from(groups);

        // then
        assertEquals(5, totals.getSuitesRan(), "the suites ran must be summed across groups");
        assertEquals(1, totals.getSuitesFailed(), "the failed suites must be summed across groups");
        assertEquals(2, totals.getGroupCount(), "the group count must be the number of groups");
    }

    /**
     * A group with no recorded duration contributes nothing rather than throwing. The sealer is
     * only reached once every group is COMPLETED and a completion always records a duration, so
     * this is defensive - but a null-triggered failure at the seal would abandon a build that had
     * already run all its tests.
     */
    @Test
    void aGroupWithNoRecordedDurationContributesNothing() {
        // given
        List<DistributedRunGroup> groups = Arrays.asList(
                completed(0, 3_000L, 2, 0),
                new DistributedRunGroup(RUN_ID, 1, DistributedRunGroupStatus.COMPLETED, "runner-b",
                        5000L, 6000L, 1000L, null, 4, 0, 4, 0L));

        // when
        DistributedRunTotals totals = DistributedRunTotals.from(groups);

        // then
        assertEquals(3_000L, totals.getSerialDurationMs(),
                "an unmeasured group must contribute no time");
        assertEquals(3_000L, totals.getWallClockMs(),
                "an unmeasured group must not be the slowest group");
        assertEquals(6, totals.getSuitesRan(), "its suite counters are still known and still count");
    }

    /**
     * An empty group list totals to zero on every figure rather than throwing, so a run planned
     * with no groups at all cannot break the seal that retires it.
     */
    @Test
    void noGroupsTotalsToZeroOnEveryFigure() {
        // given
        List<DistributedRunGroup> groups = new ArrayList<>();

        // when
        DistributedRunTotals totals = DistributedRunTotals.from(groups);

        // then
        assertEquals(0L, totals.getSerialDurationMs(), "no groups means no time");
        assertEquals(0L, totals.getWallClockMs(), "no groups means no wall clock");
        assertEquals(0, totals.getSuitesRan(), "no groups means no suites ran");
        assertEquals(0, totals.getSuitesFailed(), "no groups means no suites failed");
        assertEquals(0, totals.getGroupCount(), "no groups means a group count of zero");
    }

    /**
     * A single-group build's serial duration and wall clock are the same number, which is the
     * degenerate case that keeps a one-group distributed build reporting exactly what a single-host
     * run of the same suites would have. Its one copy of the fixed overhead is kept: there is
     * nothing duplicated to remove, and a single-host run would have paid it too. This is the shape
     * a seed run takes, so getting it wrong would move the full-suite baseline.
     */
    @Test
    void aSingleGroupBuildKeepsItsWholeDurationIncludingItsOverhead() {
        // given
        List<DistributedRunGroup> groups =
                Collections.singletonList(completed(0, 4_500L, 4_000L, 7, 2));

        // when
        DistributedRunTotals totals = DistributedRunTotals.from(groups);

        // then
        assertEquals(4_500L, totals.getSerialDurationMs(), "one group's time is the whole build's");
        assertEquals(4_500L, totals.getWallClockMs(), "one group's time is also the wall clock");
    }

    /**
     * Build a COMPLETED group that reported no suite-time split, as a runner with stats collection
     * off does. A group in this shape that ran suites disables the overhead correction, so these are
     * the fall-back cases.
     *
     * @param groupNumber the group's zero-based index within the run
     * @param actualDurationMs the group's measured test-execution time
     * @param suitesRan the number of suites the group's runner executed
     * @param suitesFailed the number of the group's suites with at least one failed test
     * @return the completed group
     */
    private DistributedRunGroup completed(final int groupNumber, final long actualDurationMs,
                                          final int suitesRan, final int suitesFailed) {
        return completed(groupNumber, actualDurationMs, 0L, suitesRan, suitesFailed);
    }

    /**
     * Build a COMPLETED group carrying the measurements a runner records when it finishes, including
     * how much of its duration went on named suites. The remainder is the runner's fixed per-JVM
     * overhead, which is what the build charges once.
     *
     * @param groupNumber the group's zero-based index within the run
     * @param actualDurationMs the group's measured test-execution time
     * @param suitesDurationMs the part of that time attributable to named suites
     * @param suitesRan the number of suites the group's runner executed
     * @param suitesFailed the number of the group's suites with at least one failed test
     * @return the completed group
     */
    private DistributedRunGroup completed(final int groupNumber, final long actualDurationMs,
                                          final long suitesDurationMs, final int suitesRan,
                                          final int suitesFailed) {
        return new DistributedRunGroup(RUN_ID, groupNumber, DistributedRunGroupStatus.COMPLETED,
                "runner-" + groupNumber, 5000L, 6000L, 1000L, Long.valueOf(actualDurationMs),
                suitesRan, suitesFailed, suitesRan, suitesDurationMs);
    }
}
