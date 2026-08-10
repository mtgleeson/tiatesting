package org.tiatesting.core.distributed;

/**
 * The wording for the two independent reasons {@link GroupingResult#isTargetMet()} can be false,
 * shared verbatim between {@link DistributedRunPreviewFormatter} (the {@code select-tests}
 * grouping preview) and {@link DistributedRunPlanSummary#toConsoleSummary()} (the persisted {@code
 * tia-dist-plan} summary) so the two commands can never drift on how they explain a missed target -
 * a preview that describes a miss one way and a real plan that describes the identical miss another
 * way would defeat the point of previewing first. Package-private since both consumers live in this
 * package.
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
     * no group count could have met it.
     */
    static final String SINGLE_SUITE_EXCEEDS_TARGET = "a single suite is longer than the whole "
            + "target, so no group count can fix it";

    private DistributedRunMissReasons() {
    }
}
