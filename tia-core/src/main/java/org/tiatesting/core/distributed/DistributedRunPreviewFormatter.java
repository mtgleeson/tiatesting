package org.tiatesting.core.distributed;

/**
 * Builds the {@code select-tests} grouping preview block: what {@link DistributedRunPlanner#balance}
 * would produce for the tests currently selected, shown without persisting anything. Shared by the
 * Maven {@code tia-select-tests} goal and the Gradle {@code tia-select-tests} task so both build
 * tools render identical wording, the same way
 * {@link org.tiatesting.core.diff.diffanalyze.selector.SelectTestsOutputFormatter} shares the rest
 * of that command's output.
 *
 * <p>Deliberately takes a {@link GroupingResult} - the output of {@link
 * DistributedRunPlanner#balance} - rather than a {@link DistributedRunPlanSummary}, since a preview
 * has no run id, branch or commit to report and building a {@link DistributedRunConfig} to get one
 * would require a run id the {@code select-tests} command does not have.
 */
public final class DistributedRunPreviewFormatter {

    private DistributedRunPreviewFormatter() {
    }

    /**
     * Render the grouping preview block: the group count, the average and heaviest group time, and
     * whether the configured target was met. When it was not met, names which configured lever
     * would help - a max-group ceiling that is limiting the group count, a single suite longer than
     * the whole target, or both, since {@link GroupingResult#isClampedToMaxGroups()} and {@link
     * GroupingResult#isSingleSuiteExceedsTarget()} are independent causes that can apply at once.
     *
     * @param result the balancer's grouping result to describe; nothing about it is persisted by
     *               this method or by the caller previewing it
     * @param targetRunTimeMs the configured target wall-clock run time in ms, or {@code null} when
     *                        the preview used a fixed group count instead - static groups mode,
     *                        which has no target to report
     * @param lineSep the line separator to use between lines
     * @return the multi-line preview block, prefixed with two {@code lineSep}s so it reads as a
     *         new, blank-line-separated section after the preceding estimate block
     */
    public static String formatPreview(final GroupingResult result, final Long targetRunTimeMs,
                                        final String lineSep) {
        long avgGroupMs = result.getGroupCount() == 0 ? 0L
                : result.getTotalEstimatedMs() / result.getGroupCount();

        StringBuilder preview = new StringBuilder();
        preview.append(lineSep).append(lineSep);
        preview.append("Distributed run grouping preview (not persisted):").append(lineSep);
        preview.append("  Groups: ").append(result.getGroupCount())
                .append(", average ").append(avgGroupMs)
                .append("ms per group, heaviest ").append(result.getHeaviestGroupMs()).append("ms");

        if (targetRunTimeMs == null) {
            preview.append(lineSep).append("  Target: none (static group count)");
        } else {
            preview.append(lineSep).append("  Target: ").append(targetRunTimeMs).append("ms - ")
                    .append(result.isTargetMet() ? "met" : "not met");
            if (!result.isTargetMet()) {
                if (result.isClampedToMaxGroups()) {
                    preview.append(lineSep).append("    lever: the configured max group count is "
                            + "limiting the group count - raising it may help");
                }
                if (result.isSingleSuiteExceedsTarget()) {
                    preview.append(lineSep).append("    lever: a single suite is longer than the "
                            + "whole target, so no group count can fix it");
                }
            }
        }
        return preview.toString();
    }
}
