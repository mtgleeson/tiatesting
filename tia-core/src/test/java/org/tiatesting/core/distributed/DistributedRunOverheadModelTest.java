package org.tiatesting.core.distributed;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the algebra that splits a run's overhead into the part a JVM pays once and the part every
 * suite pays, and every input that makes the split unavailable.
 *
 * <p>The point of the split is that the two behave differently the moment a build is fanned out.
 * The fixed part is <b>duplicated</b> per runner - each is its own JVM - while Tia's original
 * single-number model divided the whole overhead by the suite count, which amortises the fixed part
 * away and leaves a distributed estimate short by roughly one copy of it per group.
 *
 * <p>Every case that cannot be solved must skip rather than approximate, because an unsolved model
 * leaves the previous rolling averages alone while a bad one overwrites them.
 */
class DistributedRunOverheadModelTest {

    private static final String RUN_ID = "run-1";

    /**
     * The pair of equations recovers a known fixed/capture pair. Three tracked suites at 100ms each
     * against a 900ms baseline leaves 600ms of whole-run overhead; two groups of one suite that took
     * 500ms and 300ms for 100ms of suite time each leave 400ms and 200ms of per-group overhead. That
     * is capture = (600 - 300) / (3 - 1) = 150ms per suite, and fixed = 300 - 150 = 150ms.
     */
    @Test
    void aKnownFixedAndCapturePairIsRecoveredFromTheGroupRows() {
        // given
        List<DistributedRunGroup> groups = Arrays.asList(
                completed(0, 500L, 100L, 1),
                completed(1, 300L, 100L, 1));

        // when
        DistributedRunOverheadModel model =
                DistributedRunOverheadModel.solve(600L, 3, groups, assigned(1, 1));

        // then
        assertTrue(model.isSolved(), "two groups at a suite count below the tracked total supply "
                + "the second equation the solve needs");
        assertEquals(150L, model.getFixedOverheadMs(),
                "the fixed part is what a group's overhead comes to once its suites' share is "
                        + "removed");
        assertEquals(150L, model.getCaptureOverheadPerSuiteMs(),
                "the capture part is the whole-run overhead's excess over a group's, spread across "
                        + "the suites only the whole run covered");
    }

    /**
     * The per-group overhead is estimated with the <b>mean</b>, not the minimum
     * {@link DistributedRunTotals} takes from the same measurement. The two have opposite jobs: the
     * totals are correcting a figure the build actually took, so they need a floor no runner can
     * fall below; this is a forecast, and a forecast wants the expected value. Groups leaving 400ms
     * and 200ms must therefore be read as 300ms, not 200ms.
     */
    @Test
    void thePerGroupOverheadIsTheMeanAcrossGroupsNotTheMinimum() {
        // given - the same build as above, whose minimum per-group overhead is 200ms
        List<DistributedRunGroup> groups = Arrays.asList(
                completed(0, 500L, 100L, 1),
                completed(1, 300L, 100L, 1));

        // when
        DistributedRunOverheadModel model =
                DistributedRunOverheadModel.solve(600L, 3, groups, assigned(1, 1));

        // then
        assertEquals(150L, model.getFixedOverheadMs(),
                "reading the per-group overhead as the 200ms minimum would give a fixed part of "
                        + "100ms and a capture of 200ms, which is a different model");
    }

    /**
     * The suite count each group's overhead is attributed to comes from what the plan assigned it,
     * never from {@code suitesRan}. That counter accumulates executions, so a Surefire retry inside
     * a runner's JVM sums into it - and it would inflate the suite count on exactly the group whose
     * overhead the retry also inflated, corrupting both sides of the equation at once.
     */
    @Test
    void theSuiteCountComesFromThePlanNotFromTheExecutionCounter() {
        // given - group 0 reports 4 executions of the 1 suite it was assigned, as a retry produces
        List<DistributedRunGroup> groups = Arrays.asList(
                completedWithRuns(0, 500L, 100L, 4),
                completed(1, 300L, 100L, 1));

        // when
        DistributedRunOverheadModel model =
                DistributedRunOverheadModel.solve(600L, 3, groups, assigned(1, 1));

        // then
        assertEquals(150L, model.getFixedOverheadMs(),
                "the retried group must be attributed the one suite the plan gave it, so the solve "
                        + "matches the un-retried build's");
        assertEquals(150L, model.getCaptureOverheadPerSuiteMs(),
                "the retried group must be attributed the one suite the plan gave it, so the solve "
                        + "matches the un-retried build's");
    }

    /**
     * A group that ran suites but timed none of them disqualifies the whole build, exactly as it
     * does for {@link DistributedRunTotals}: its entire duration would read as overhead, so the
     * per-group equation would be measuring something else entirely. Suite times are only recorded
     * by a build that updates the mapping DB.
     */
    @Test
    void aGroupThatRanSuitesWithoutTimingThemSkipsTheSolve() {
        // given
        List<DistributedRunGroup> groups = Arrays.asList(
                completed(0, 500L, 0L, 1),
                completed(1, 300L, 100L, 1));

        // when
        DistributedRunOverheadModel model =
                DistributedRunOverheadModel.solve(600L, 3, groups, assigned(1, 1));

        // then
        assertFalse(model.isSolved(), "a group with no suite-time split cannot supply an equation");
        assertNotNull(model.getSkipReason(), "a skip must carry the reason, so it can be logged");
    }

    /**
     * A build whose groups between them cover every tracked suite in one group supplies no second
     * equation - the per-group equation is the whole-run equation restated - so the pair is
     * degenerate and the solve is abandoned rather than dividing by nothing.
     */
    @Test
    void aSingleGroupCoveringEveryTrackedSuiteIsDegenerate() {
        // given
        List<DistributedRunGroup> groups =
                Collections.singletonList(completed(0, 900L, 300L, 3));

        // when
        DistributedRunOverheadModel model =
                DistributedRunOverheadModel.solve(600L, 3, groups, assigned(3));

        // then
        assertFalse(model.isSolved(), "one equation cannot separate two unknowns");
    }

    /**
     * A solve that comes out negative is discarded. Ordinary measurement noise produces it on a
     * project whose runners vary by more than the overhead being measured, and a negative constant
     * would make the estimate worse than the single-number model it replaces.
     */
    @Test
    void aNegativeSolveIsSkipped() {
        // given - the groups' own overhead exceeds the whole run's, which cannot be true of a
        // consistent build and drives the capture term negative
        List<DistributedRunGroup> groups = Arrays.asList(
                completed(0, 900L, 100L, 1),
                completed(1, 900L, 100L, 1));

        // when
        DistributedRunOverheadModel model =
                DistributedRunOverheadModel.solve(600L, 3, groups, assigned(1, 1));

        // then
        assertFalse(model.isSolved(), "a negative constant is worse than no measurement at all");
        assertEquals(0L, model.getFixedOverheadMs(), "an unsolved model reports no constants");
        assertEquals(0L, model.getCaptureOverheadPerSuiteMs(),
                "an unsolved model reports no constants");
    }

    /**
     * With no whole-run overhead to decompose there is nothing to solve. This is the state of a
     * project whose full-suite baseline does not exceed the sum of its suite averages, which is what
     * a build that ran its suites in parallel on one host records.
     */
    @Test
    void noWholeRunOverheadSkipsTheSolve() {
        // given
        List<DistributedRunGroup> groups = Arrays.asList(
                completed(0, 500L, 100L, 1),
                completed(1, 300L, 100L, 1));

        // when
        DistributedRunOverheadModel model =
                DistributedRunOverheadModel.solve(0L, 3, groups, assigned(1, 1));

        // then
        assertFalse(model.isSolved(), "there is no overhead to split");
    }

    /**
     * A group with no recorded duration supplies no equation, but must not invalidate the groups
     * that did record one - it simply is not one of the samples. Here the one measured group is
     * enough, and the answer must match the build without the unmeasured group at all.
     */
    @Test
    void aGroupWithNoRecordedDurationIsLeftOutRatherThanDisqualifyingTheBuild() {
        // given
        List<DistributedRunGroup> groups = Arrays.asList(
                completed(0, 500L, 100L, 1),
                unmeasured(1));

        // when
        DistributedRunOverheadModel model =
                DistributedRunOverheadModel.solve(600L, 3, groups, assigned(1, 1));

        // then
        assertTrue(model.isSolved(), "one measured group is still a second equation");
        assertEquals(100L, model.getCaptureOverheadPerSuiteMs(),
                "capture is the 600ms whole-run overhead less the measured group's 400ms, spread "
                        + "across the 2 suites it did not cover");
        assertEquals(300L, model.getFixedOverheadMs(),
                "fixed is the measured group's 400ms less its one suite's 100ms of capture");
    }

    /**
     * A group the plan has no recorded assignment for cannot have its overhead attributed to a suite
     * count, so the solve is abandoned rather than silently treating it as having run nothing - which
     * would push the whole of its overhead into the fixed term.
     */
    @Test
    void aGroupWithNoRecordedAssignmentSkipsTheSolve() {
        // given
        List<DistributedRunGroup> groups = Arrays.asList(
                completed(0, 500L, 100L, 1),
                completed(1, 300L, 100L, 1));
        Map<Integer, Integer> assignedSuiteCounts = new HashMap<>();
        assignedSuiteCounts.put(Integer.valueOf(0), Integer.valueOf(1));

        // when
        DistributedRunOverheadModel model =
                DistributedRunOverheadModel.solve(600L, 3, groups, assignedSuiteCounts);

        // then
        assertFalse(model.isSolved(),
                "a group whose assignment is unknown cannot be attributed a suite count");
    }

    /**
     * With no tracked suites the whole-run equation has no suite count, so there is nothing to
     * solve against.
     */
    @Test
    void noTrackedSuitesSkipsTheSolve() {
        // given
        List<DistributedRunGroup> groups =
                Collections.singletonList(completed(0, 500L, 100L, 1));

        // when
        DistributedRunOverheadModel model =
                DistributedRunOverheadModel.solve(600L, 0, groups, assigned(1));

        // then
        assertFalse(model.isSolved(), "the whole-run equation needs a suite count");
    }

    /**
     * Build the assigned-suite counts a plan records, keyed by group number in the order given.
     *
     * @param countsByGroupNumber how many suites each group was assigned, group 0 first
     * @return the counts keyed by group number
     */
    private static Map<Integer, Integer> assigned(final int... countsByGroupNumber) {
        Map<Integer, Integer> assignedSuiteCounts = new HashMap<>();
        for (int i = 0; i < countsByGroupNumber.length; i++) {
            assignedSuiteCounts.put(Integer.valueOf(i), Integer.valueOf(countsByGroupNumber[i]));
        }
        return assignedSuiteCounts;
    }

    /**
     * Build a COMPLETED group whose runner executed exactly the suites it was assigned.
     *
     * @param groupNumber the group's zero-based index within the run
     * @param actualDurationMs the group's measured test-execution time
     * @param suitesDurationMs the part of that time attributable to named suites
     * @param suitesRan the number of suites the group's runner executed
     * @return the completed group
     */
    private static DistributedRunGroup completed(final int groupNumber, final long actualDurationMs,
                                                 final long suitesDurationMs, final int suitesRan) {
        return completedWithRuns(groupNumber, actualDurationMs, suitesDurationMs, suitesRan);
    }

    /**
     * Build a COMPLETED group reporting a given number of suite <em>executions</em>, which a retry
     * inflates above the number of suites the plan assigned it.
     *
     * @param groupNumber the group's zero-based index within the run
     * @param actualDurationMs the group's measured test-execution time
     * @param suitesDurationMs the part of that time attributable to named suites
     * @param suiteExecutions the number of suite executions the runner reported
     * @return the completed group
     */
    private static DistributedRunGroup completedWithRuns(final int groupNumber,
                                                         final long actualDurationMs,
                                                         final long suitesDurationMs,
                                                         final int suiteExecutions) {
        return new DistributedRunGroup(RUN_ID, groupNumber, DistributedRunGroupStatus.COMPLETED,
                "runner-" + groupNumber, 5000L, 6000L, 1000L, Long.valueOf(actualDurationMs),
                suiteExecutions, 0, suiteExecutions, suitesDurationMs);
    }

    /**
     * Build a COMPLETED group that recorded no duration at all, which supplies no equation.
     *
     * @param groupNumber the group's zero-based index within the run
     * @return the completed group with a null duration
     */
    private static DistributedRunGroup unmeasured(final int groupNumber) {
        return new DistributedRunGroup(RUN_ID, groupNumber, DistributedRunGroupStatus.COMPLETED,
                "runner-" + groupNumber, 5000L, 6000L, 1000L, null, 0, 0, 0, 0L);
    }
}
