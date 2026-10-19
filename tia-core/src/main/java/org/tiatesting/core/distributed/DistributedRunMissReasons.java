package org.tiatesting.core.distributed;

import java.util.ArrayList;
import java.util.List;

/**
 * The wording for the three independent reasons {@link GroupingResult#isTargetMet()} can be false,
 * shared verbatim between {@link DistributedRunPreviewFormatter} (the {@code select-tests}
 * grouping preview), {@link DistributedRunPlanSummary#toConsoleSummary()} (the persisted plan
 * summary) and the planner's target-miss WARN, so they can never drift on how they explain a missed
 * target - a preview that describes a miss one way and a real plan that describes the identical miss
 * another way would defeat the point of previewing first. {@link #applying} fixes the order too.
 * Package-private since every consumer lives in this package.
 */
final class DistributedRunMissReasons {

    /**
     * Explains a miss caused by the configured max-group ceiling limiting the group count below
     * what would have been needed to meet the target.
     */
    static final String MAX_GROUPS_LIMITING = "the configured max group count is limiting the "
            + "group count - raising it may help";

    /**
     * Explains a miss caused by a single suite alone being heavier than the whole target, meaning
     * no group count could have met it. A test class is planned together with the {@code @Nested}
     * classes that run inside it, so "a single suite" includes such a family.
     */
    static final String SINGLE_SUITE_EXCEEDS_TARGET = "a single suite is longer than the whole "
            + "target (a test class counts with the @Nested classes that run inside it), so no group "
            + "count can fix it";

    /**
     * Explains a miss caused by the fixed per-JVM overhead alone being at or above the whole target.
     * Distinct from {@link #SINGLE_SUITE_EXCEEDS_TARGET} because no change to the selection can fix
     * it: every runner pays this before it executes a suite, so adding runners only adds copies of a
     * cost that already exceeds the target. Raising the target is the only lever.
     */
    static final String FIXED_OVERHEAD_EXCEEDS_TARGET = "each runner pays the fixed per-JVM "
            + "start-up cost before running a single suite, and that alone meets or exceeds the "
            + "target - adding runners cannot help, only a higher target can";

    /**
     * List the reasons that apply to a missed target, in the one order every consumer reports them:
     * the max-group ceiling, then the fixed per-JVM overhead, then a single heavy suite. The flags
     * are independent, so any combination can apply.
     *
     * @param clampedToMaxGroups whether the configured ceiling limited the group count
     * @param fixedOverheadExceedsTarget whether the fixed per-JVM cost alone meets or exceeds the
     *                                   target
     * @param singleSuiteExceedsTarget whether a single suite alone exceeds the target's suite budget
     * @return the wording of each reason that applies, in reporting order; empty when none does
     */
    static List<String> applying(boolean clampedToMaxGroups, boolean fixedOverheadExceedsTarget,
                                 boolean singleSuiteExceedsTarget) {
        List<String> reasons = new ArrayList<>(3);
        if (clampedToMaxGroups) {
            reasons.add(MAX_GROUPS_LIMITING);
        }
        if (fixedOverheadExceedsTarget) {
            reasons.add(FIXED_OVERHEAD_EXCEEDS_TARGET);
        }
        if (singleSuiteExceedsTarget) {
            reasons.add(SINGLE_SUITE_EXCEEDS_TARGET);
        }
        return reasons;
    }

    private DistributedRunMissReasons() {
    }
}
