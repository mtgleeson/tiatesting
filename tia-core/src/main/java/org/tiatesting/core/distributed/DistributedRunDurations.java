package org.tiatesting.core.distributed;

/**
 * The two lines that name a distributed run's two durations and say which is which, for the plan
 * step's console summary ({@link DistributedRunPlanSummary#toConsoleSummary()}).
 *
 * <p>The plan step needs them because it prints no estimate block: {@code dist-plan} reports a
 * persisted plan, and its reader - usually whoever is setting a CI job's timeout - has nothing else
 * on screen telling them which of the two figures the build will actually wait for. On the {@code
 * select-tests} side the same job is done by {@code SelectTestsOutputFormatter.formatEstimateBlock},
 * which reports both durations in the estimate itself, so the grouping preview beneath it is left
 * to describe the shape of the split alone.
 *
 * <p>Exists because the plan summary previously printed both figures without saying what either
 * was. A user reading "Estimated total run time: 497ms" beside a grouping showing a 275ms heaviest
 * group has no way to tell that the first is deliberately the same number a non-distributed build
 * would print, and reasonably concludes the estimate has ignored the distribution. It has not: the
 * two figures answer different questions and only one of them changes when a build is split.
 *
 * <ul>
 *   <li>The <b>wall clock</b> is the heaviest group, since the build is not finished until that
 *       group is. It is what a job timeout has to accommodate and what a developer waits for.</li>
 *   <li>The <b>serial equivalent</b> is what the same selection would cost on one host. It is the
 *       figure Tia records and computes savings from in <b>both</b> modes, deliberately, so that
 *       savings keep meaning "time saved by not running unimpacted tests" rather than silently
 *       absorbing the parallelism the CI system provided, and so a project's history stays
 *       comparable across the build where distributed mode was switched on.</li>
 * </ul>
 *
 * <p>That split is not a presentation choice made here - it is the same one {@link
 * DistributedRunTotals} applies when the sealer writes the build's history row, where the two land
 * in the {@code Duration} and {@code Wall clock} columns. These lines exist so the estimate a user
 * sees before the run uses the same two words as the record they see after it.
 */
public final class DistributedRunDurations {

    private DistributedRunDurations() {
    }

    /**
     * Render the wall-clock and serial-equivalent lines for a grouping, indented two spaces to sit
     * inside the plan summary block that prints them.
     *
     * <p>Both values are rendered in raw milliseconds rather than through {@code
     * ReportUtils.prettyDuration}, matching the surrounding lines in that block, which report group
     * weights the same way.
     *
     * @param heaviestGroupMs the weight of the heaviest group in ms - the run's expected wall clock,
     *                        since the groups run in parallel
     * @param totalEstimatedMs the summed weight of every group in ms - what the same selection would
     *                         cost on one host
     * @param lineSep the line separator to join the two lines with
     * @return the two-line block, with no leading or trailing separator
     */
    public static String format(final long heaviestGroupMs, final long totalEstimatedMs,
                                 final String lineSep) {
        return "  Wall clock: " + heaviestGroupMs + "ms - the heaviest group, which is what the "
                + "build waits for since the groups run in parallel." + lineSep
                + "  Serial equivalent: " + totalEstimatedMs + "ms - what the same selection costs "
                + "on one host. This is the" + lineSep
                + "    figure Tia records and computes savings from in both modes, so a project's "
                + "history stays" + lineSep
                + "    comparable; it is not what a distributed build waits for.";
    }
}
