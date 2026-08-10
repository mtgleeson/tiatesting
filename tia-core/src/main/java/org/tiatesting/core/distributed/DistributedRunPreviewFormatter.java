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
     * <p>When {@code seedRun} is true - the selection carries no stored mapping for this branch
     * yet, so {@link DistributedRunPlanner#balance} collapsed {@code result} to a single empty
     * group - the target verdict is replaced with a line explaining that a real plan would collapse
     * the same way, since {@code result} always reports a trivially-met target in that case and
     * printing it as if real balancing happened would be misleading.
     *
     * @param result the balancer's grouping result to describe; nothing about it is persisted by
     *               this method or by the caller previewing it
     * @param targetRunTimeMs the configured target wall-clock run time in ms, or {@code null} when
     *                        the preview used a fixed group count instead - static groups mode,
     *                        which has no target to report; ignored when {@code seedRun} is true
     * @param seedRun whether the previewed selection carries no stored mapping for this branch yet
     *                - {@link org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult#isRunAllTests()} -
     *                so a real plan of this selection would be a seed run
     * @param lineSep the line separator to use between lines
     * @return the multi-line preview block, prefixed with two {@code lineSep}s so it reads as a
     *         new, blank-line-separated section after the preceding estimate block
     */
    public static String formatPreview(final GroupingResult result, final Long targetRunTimeMs,
                                        final boolean seedRun, final String lineSep) {
        long avgGroupMs = result.getGroupCount() == 0 ? 0L
                : result.getTotalEstimatedMs() / result.getGroupCount();

        StringBuilder preview = new StringBuilder();
        preview.append(lineSep).append(lineSep);
        preview.append("Distributed run grouping preview (not persisted):").append(lineSep);
        if (seedRun) {
            preview.append("  Seed run: no stored mapping exists yet for this branch, so a real ")
                    .append("distributed run would plan one group covering the whole suite, ")
                    .append("ignoring the configured group count and target run time, and would ")
                    .append("record the mapping for the next build.").append(lineSep);
        }
        preview.append("  Groups: ").append(result.getGroupCount())
                .append(", average ").append(avgGroupMs)
                .append("ms per group, heaviest ").append(result.getHeaviestGroupMs()).append("ms");

        if (!seedRun) {
            if (targetRunTimeMs == null) {
                preview.append(lineSep).append("  Target: none (static group count)");
            } else {
                preview.append(lineSep).append("  Target: ").append(targetRunTimeMs).append("ms - ")
                        .append(result.isTargetMet() ? "met" : "not met");
                if (!result.isTargetMet()) {
                    if (result.isClampedToMaxGroups()) {
                        preview.append(lineSep).append("    lever: ")
                                .append(DistributedRunMissReasons.MAX_GROUPS_LIMITING);
                    }
                    if (result.isSingleSuiteExceedsTarget()) {
                        preview.append(lineSep).append("    lever: ")
                                .append(DistributedRunMissReasons.SINGLE_SUITE_EXCEEDS_TARGET);
                    }
                }
            }
        }
        return preview.toString();
    }
}
