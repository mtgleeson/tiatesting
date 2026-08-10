package org.tiatesting.core.distributed;

/**
 * An immutable summary of a distributed run plan: the facts a CI pipeline and a human both need
 * once the balancer has decided on groups. Task 3's planner builds one of these from a validated
 * {@link DistributedRunConfig} and the {@link GroupingResult} the balancer produced. Stage 4b then
 * writes {@link #toJson()} to {@code ${tiaBuildDir}/tia-run-plan.json} and prints
 * {@link #toConsoleSummary()}.
 *
 * <p>{@link #toJson()} is a published contract, not a debug dump: a user's CI pipeline parses it
 * to decide how many jobs to start. Its field names, order and shape are fixed and must not
 * change without updating every consumer of the file. {@code targetMs} is {@code null} in static
 * groups mode - {@link DistributedRunConfig#isStaticGroups()} - because a fixed group count has
 * no target run time to report; it is rendered as JSON {@code null}, never {@code 0}, since zero
 * would read as an (impossible) target of zero rather than the absence of one.
 *
 * <p>{@code seedRun} is true exactly when the plan was collapsed to a single empty group because
 * no stored mapping existed yet for this branch - see {@link DistributedRunPlanner#plan}. A
 * pipeline reading {@code tia-run-plan.json} can use it to explain why it only received one job
 * despite the configured group count.
 */
public final class DistributedRunPlanSummary {

    private final String runId;
    private final String branch;
    private final String commit;
    private final boolean seedRun;
    private final int groupCount;
    private final long avgGroupMs;
    private final Long targetMs;
    private final boolean targetMet;
    private final boolean clampedToMaxGroups;
    private final boolean singleSuiteExceedsTarget;
    private final long totalEstimatedMs;
    private final long heaviestGroupMs;
    private final int selectedSuiteCount;

    /**
     * Create a plan summary, deriving the average group time once at construction rather than on
     * each read, since both {@link #toJson()} and {@link #toConsoleSummary()} consume it.
     *
     * @param runId the distributed run's shared identifier ({@code tiaRunId})
     * @param branch the VCS branch the run was planned against
     * @param commit the VCS commit the run was planned against
     * @param groupCount the number of groups the balancer produced
     * @param targetMs the configured target wall-clock run time in ms, or null in static groups
     *                 mode where no target applies
     * @param targetMet whether the heaviest group came in at or under {@code targetMs}; always
     *                  true for static groups, which have no target
     * @param clampedToMaxGroups whether the group count was limited by the configured ceiling
     * @param singleSuiteExceedsTarget whether a single suite's weight alone exceeds {@code
     *                                 targetMs}, so no group count could have met it
     * @param totalEstimatedMs the summed estimated run time of every group, in ms
     * @param heaviestGroupMs the weight of the heaviest group in ms - the run's expected
     *                        wall-clock test time, since the groups execute in parallel; unlike
     *                        {@code avgGroupMs} this reflects uneven packing, which is exactly the
     *                        case a pipeline setting a job timeout needs to know about
     * @param selectedSuiteCount the number of test suites selected for this run, across all
     *                           groups
     * @param seedRun whether this plan was collapsed to a single empty group because no stored
     *                mapping existed yet for this branch, rather than balanced from the selection
     */
    public DistributedRunPlanSummary(String runId, String branch, String commit, int groupCount,
                                      Long targetMs, boolean targetMet, boolean clampedToMaxGroups,
                                      boolean singleSuiteExceedsTarget, long totalEstimatedMs,
                                      long heaviestGroupMs, int selectedSuiteCount,
                                      boolean seedRun) {
        this.runId = runId;
        this.branch = branch;
        this.commit = commit;
        this.groupCount = groupCount;
        this.targetMs = targetMs;
        this.targetMet = targetMet;
        this.clampedToMaxGroups = clampedToMaxGroups;
        this.singleSuiteExceedsTarget = singleSuiteExceedsTarget;
        this.totalEstimatedMs = totalEstimatedMs;
        this.heaviestGroupMs = heaviestGroupMs;
        this.selectedSuiteCount = selectedSuiteCount;
        this.seedRun = seedRun;
        this.avgGroupMs = groupCount == 0 ? 0L : totalEstimatedMs / groupCount;
    }

    /** @return the distributed run's shared identifier */
    public String getRunId() { return runId; }

    /** @return the VCS branch the run was planned against */
    public String getBranch() { return branch; }

    /** @return the VCS commit the run was planned against */
    public String getCommit() { return commit; }

    /** @return the number of groups the balancer produced */
    public int getGroupCount() { return groupCount; }

    /**
     * @return the average group weight in ms, being {@code totalEstimatedMs / groupCount}; 0 when
     *         {@code groupCount} is 0 rather than dividing by zero
     */
    public long getAvgGroupMs() { return avgGroupMs; }

    /** @return the configured target wall-clock run time in ms, or null in static groups mode */
    public Long getTargetMs() { return targetMs; }

    /** @return whether the heaviest group came in at or under the target; always true for static groups */
    public boolean isTargetMet() { return targetMet; }

    /** @return whether the group count was limited by the configured ceiling */
    public boolean isClampedToMaxGroups() { return clampedToMaxGroups; }

    /**
     * @return whether a single suite's weight alone exceeds the target, so no group count could
     *         have met it
     */
    public boolean isSingleSuiteExceedsTarget() { return singleSuiteExceedsTarget; }

    /** @return the summed estimated run time of every group, in ms */
    public long getTotalEstimatedMs() { return totalEstimatedMs; }

    /**
     * @return the weight of the heaviest group in ms - the run's expected wall-clock test time,
     *         since the groups execute in parallel. Understates nothing that {@code avgGroupMs}
     *         would hide when the packing is uneven
     */
    public long getHeaviestGroupMs() { return heaviestGroupMs; }

    /** @return the number of test suites selected for this run, across all groups */
    public int getSelectedSuiteCount() { return selectedSuiteCount; }

    /**
     * @return whether this plan was collapsed to a single empty group because no stored mapping
     *         existed yet for this branch, rather than balanced from the selection; when true,
     *         {@link #getGroupCount()} is always 1 regardless of the configured group count or
     *         target run time
     */
    public boolean isSeedRun() { return seedRun; }

    /**
     * Render this summary as the {@code tia-run-plan.json} document a CI pipeline parses to
     * decide how many jobs to start. There is no JSON library on this project's classpath, so the
     * document is built by hand; {@code runId}, {@code branch} and {@code commit} come from user
     * or VCS data and are escaped with {@link #escapeJsonString(String)} since any of them can
     * legitimately contain a double quote or a backslash. Field names and order match the
     * published contract exactly and must not be changed independently of it.
     *
     * @return the pretty-printed JSON document
     */
    public String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"runId\": \"").append(escapeJsonString(runId)).append("\",\n");
        json.append("  \"branch\": \"").append(escapeJsonString(branch)).append("\",\n");
        json.append("  \"commit\": \"").append(escapeJsonString(commit)).append("\",\n");
        json.append("  \"seedRun\": ").append(seedRun).append(",\n");
        json.append("  \"groupCount\": ").append(groupCount).append(",\n");
        json.append("  \"avgGroupMs\": ").append(avgGroupMs).append(",\n");
        json.append("  \"heaviestGroupMs\": ").append(heaviestGroupMs).append(",\n");
        json.append("  \"targetMs\": ").append(targetMs == null ? "null" : targetMs.toString()).append(",\n");
        json.append("  \"targetMet\": ").append(targetMet).append(",\n");
        json.append("  \"clampedToMaxGroups\": ").append(clampedToMaxGroups).append(",\n");
        json.append("  \"singleSuiteExceedsTarget\": ").append(singleSuiteExceedsTarget).append(",\n");
        json.append("  \"totalEstimatedMs\": ").append(totalEstimatedMs).append(",\n");
        json.append("  \"selectedSuiteCount\": ").append(selectedSuiteCount).append("\n");
        json.append("}");
        return json.toString();
    }

    /**
     * Escape a string for safe embedding between JSON double quotes. Handles the two characters
     * that would otherwise break the document's syntax - a literal backslash and a literal double
     * quote - plus the common control characters (newline, carriage return, tab) and any other
     * character below {@code 0x20}, which JSON does not allow unescaped inside a string. {@code
     * runId}, {@code branch} and {@code commit} all come from user or VCS data, so any of them can
     * legitimately contain a quote or a backslash (for example a branch named {@code
     * feature/say-"hi"}); without this, such a value would produce a document that fails to parse.
     *
     * @param value the raw string to escape
     * @return the escaped string, safe to place between double quotes in a JSON document
     */
    private static String escapeJsonString(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
            }
        }
        return escaped.toString();
    }

    /**
     * Render this summary as the short human-readable block stage 4b prints to the console
     * alongside writing {@link #toJson()}. Unlike the JSON document, this text has no consumer
     * other than a human reading build output, so most of its wording is free to evolve - except
     * the two target-miss reason lines, which share their exact text with {@link
     * DistributedRunPreviewFormatter#formatPreview} via {@link DistributedRunMissReasons}, so a
     * developer who saw a miss explained in the {@code select-tests} preview sees the identical
     * explanation once the real plan is persisted.
     *
     * @return a multi-line human-readable summary naming the run, its groups, and whether the
     *         target was met; when {@link #isSeedRun()} is true, names that instead of the target
     *         verdict, since a seed run has no target to report
     */
    public String toConsoleSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("Distributed run plan for ").append(runId)
                .append(" (branch ").append(branch).append(", commit ").append(commit).append(")\n");
        if (seedRun) {
            summary.append("  Seed run: no stored mapping exists yet for this branch, so this ")
                    .append("plan has one group covering the whole suite - the configured group ")
                    .append("count and target run time were ignored. Running it will record the ")
                    .append("mapping; the next build will plan normally.\n");
        }
        summary.append("  Groups: ").append(groupCount)
                .append(", average ").append(avgGroupMs)
                .append("ms per group, heaviest ").append(heaviestGroupMs).append("ms\n");
        if (!seedRun) {
            if (targetMs == null) {
                summary.append("  Target: none (static group count)\n");
            } else {
                summary.append("  Target: ").append(targetMs).append("ms - ")
                        .append(targetMet ? "met" : "not met").append("\n");
                if (!targetMet) {
                    if (clampedToMaxGroups) {
                        summary.append("    reason: ").append(DistributedRunMissReasons.MAX_GROUPS_LIMITING)
                                .append("\n");
                    }
                    if (singleSuiteExceedsTarget) {
                        summary.append("    reason: ").append(DistributedRunMissReasons.SINGLE_SUITE_EXCEEDS_TARGET)
                                .append("\n");
                    }
                }
            }
        }
        summary.append("  Total estimated time: ").append(totalEstimatedMs)
                .append("ms across ").append(selectedSuiteCount).append(" selected suites\n");
        return summary.toString();
    }

    /**
     * Diagnostic rendering naming the run, its group count and weights, and the target-related
     * flags.
     *
     * @return a short human-readable description
     */
    @Override
    public String toString() {
        return "DistributedRunPlanSummary{runId=" + runId + ", branch=" + branch
                + ", commit=" + commit + ", seedRun=" + seedRun + ", groupCount=" + groupCount
                + ", avgGroupMs=" + avgGroupMs
                + ", heaviestGroupMs=" + heaviestGroupMs
                + ", targetMs=" + targetMs + ", targetMet=" + targetMet
                + ", clampedToMaxGroups=" + clampedToMaxGroups
                + ", singleSuiteExceedsTarget=" + singleSuiteExceedsTarget
                + ", totalEstimatedMs=" + totalEstimatedMs
                + ", selectedSuiteCount=" + selectedSuiteCount + "}";
    }
}
