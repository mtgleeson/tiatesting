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
 * distributed build would have taken on one host (the sum of its groups), the wall clock it
 * actually took (its slowest group), and the suite counters summed across groups.
 *
 * <p>The two durations are deliberately different numbers with different jobs. The serial figure is
 * what savings and the Tia-level stats are computed from, so a distributed build stays comparable
 * with the single-host history either side of it; the wall clock is what the user waited for.
 */
class DistributedRunTotalsTest {

    private static final String RUN_ID = "run-1";

    /**
     * The serial-equivalent duration is the sum of every group's measured time - what the build
     * would have cost on one host - and never the wall clock, which would silently credit Tia with
     * the parallelism the CI system provided.
     */
    @Test
    void serialEquivalentDurationIsTheSumOfEveryGroupsTime() {
        // given
        List<DistributedRunGroup> groups = Arrays.asList(
                completed(0, 3_000L, 2, 0),
                completed(1, 5_000L, 3, 1),
                completed(2, 1_000L, 1, 0));

        // when
        DistributedRunTotals totals = DistributedRunTotals.from(groups);

        // then
        assertEquals(9_000L, totals.getSerialDurationMs(),
                "the serial-equivalent duration must be the sum of every group's time");
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
                        5000L, 6000L, 1000L, null, 4, 0, 4));

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
     * run of the same suites would have.
     */
    @Test
    void aSingleGroupBuildHasTheSameSerialAndWallClockDuration() {
        // given
        List<DistributedRunGroup> groups = Collections.singletonList(completed(0, 4_500L, 7, 2));

        // when
        DistributedRunTotals totals = DistributedRunTotals.from(groups);

        // then
        assertEquals(4_500L, totals.getSerialDurationMs(), "one group's time is the whole build's");
        assertEquals(4_500L, totals.getWallClockMs(), "one group's time is also the wall clock");
    }

    /**
     * Build a COMPLETED group carrying the measurements a runner records when it finishes.
     *
     * @param groupNumber the group's zero-based index within the run
     * @param actualDurationMs the group's measured test-execution time
     * @param suitesRan the number of suites the group's runner executed
     * @param suitesFailed the number of the group's suites with at least one failed test
     * @return the completed group
     */
    private DistributedRunGroup completed(final int groupNumber, final long actualDurationMs,
                                          final int suitesRan, final int suitesFailed) {
        return new DistributedRunGroup(RUN_ID, groupNumber, DistributedRunGroupStatus.COMPLETED,
                "runner-" + groupNumber, 5000L, 6000L, 1000L, Long.valueOf(actualDurationMs),
                suitesRan, suitesFailed, suitesRan);
    }
}
