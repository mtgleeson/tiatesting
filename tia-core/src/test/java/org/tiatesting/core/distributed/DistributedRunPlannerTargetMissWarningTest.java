package org.tiatesting.core.distributed;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock the text of the planner's target-miss WARN, the only INFO-level line that explains why a
 * plan came in over its target: it must name every reason that applies, in the shared order and
 * wording of {@link DistributedRunMissReasons}, along with the figures needed to act on them.
 */
class DistributedRunPlannerTargetMissWarningTest {

    private static final long TARGET_MS = 1_800_000L;

    /**
     * Build a missed-target single-group result carrying the given reasons and figures.
     *
     * @param clamped whether the ceiling limited the group count
     * @param singleSuite whether a single suite exceeds the target
     * @param fixedOverhead whether the fixed per-JVM cost alone exceeds the target
     * @param firstFitGroupCount the group count first-fit packing wanted
     * @param heaviestSuiteMs the heaviest single suite in ms
     * @return the constructed result
     */
    private static GroupingResult missed(boolean clamped, boolean singleSuite, boolean fixedOverhead,
                                         int firstFitGroupCount, long heaviestSuiteMs) {
        return new GroupingResult(Collections.singletonList(
                new SuiteGroup(0, Collections.singletonList("Suite0"), 3_935_801L)), false, clamped,
                singleSuite, fixedOverhead, firstFitGroupCount, heaviestSuiteMs);
    }

    /**
     * Verify a clamped plan states the ceiling and the group count first-fit packing wanted, so
     * the operator can size {@code tiaDistributedMaxGroups} without DEBUG logging.
     */
    @Test
    void shouldStateTheCeilingAndWantedGroupCountWhenClamped() {
        // given
        GroupingResult result = missed(true, false, false, 20, 11_161L);

        // when
        String warning = DistributedRunPlanner.formatTargetMissWarning(result, TARGET_MS, 9);

        // then
        assertEquals("Distributed run planning did not meet its target run time of 1800000ms: the "
                + "heaviest group is 3935801ms across 1 group(s) (ceiling 9, first-fit packing wanted "
                + "20). Reason: " + DistributedRunMissReasons.MAX_GROUPS_LIMITING + ".", warning);
    }

    /**
     * Verify the fixed per-JVM overhead reason is reported, and no ceiling or first-fit figure is
     * stated when no ceiling is configured and none bound the plan.
     */
    @Test
    void shouldReportTheFixedOverheadReason() {
        // given
        GroupingResult result = missed(false, false, true, 1, 0L);

        // when
        String warning = DistributedRunPlanner.formatTargetMissWarning(result, TARGET_MS, null);

        // then
        assertTrue(warning.contains("Reason: " + DistributedRunMissReasons.FIXED_OVERHEAD_EXCEEDS_TARGET),
                warning);
        assertFalse(warning.contains("ceiling"), warning);
        assertFalse(warning.contains("first-fit"), warning);
    }

    /**
     * Verify a single over-target suite is reported with its duration.
     */
    @Test
    void shouldStateTheHeaviestSuiteWhenASingleSuiteExceedsTheTarget() {
        // given
        GroupingResult result = missed(false, true, false, 1, 2_000_000L);

        // when
        String warning = DistributedRunPlanner.formatTargetMissWarning(result, TARGET_MS, null);

        // then
        assertTrue(warning.contains("(heaviest single suite 2000000ms)"), warning);
        assertTrue(warning.contains("Reason: " + DistributedRunMissReasons.SINGLE_SUITE_EXCEEDS_TARGET),
                warning);
    }

    /**
     * Verify every applying reason is joined with {@code "; "} in the shared order, ending in a full
     * stop rather than a dangling separator.
     */
    @Test
    void shouldJoinEveryReasonInOrderWithNoTrailingSeparator() {
        // given
        GroupingResult result = missed(true, true, true, 20, 2_000_000L);

        // when
        String warning = DistributedRunPlanner.formatTargetMissWarning(result, TARGET_MS, 9);

        // then
        assertTrue(warning.contains("(ceiling 9, first-fit packing wanted 20, heaviest single suite "
                + "2000000ms)"), warning);
        assertTrue(warning.endsWith(" Reasons: " + DistributedRunMissReasons.MAX_GROUPS_LIMITING + "; "
                + DistributedRunMissReasons.FIXED_OVERHEAD_EXCEEDS_TARGET + "; "
                + DistributedRunMissReasons.SINGLE_SUITE_EXCEEDS_TARGET + "."), warning);
    }

    /**
     * Verify nothing is produced to log when the target was met.
     */
    @Test
    void shouldReturnNullWhenTheTargetWasMet() {
        // given
        GroupingResult result = new GroupingResult(Collections.singletonList(
                new SuiteGroup(0, Collections.singletonList("Suite0"), 1_000L)), true, false, false,
                false, 1, 1_000L);

        // when
        String warning = DistributedRunPlanner.formatTargetMissWarning(result, TARGET_MS, 9);

        // then
        assertNull(warning);
    }
}
