package org.tiatesting.core.distributed;

/**
 * An immutable, validated bundle of the settings that drive a distributed test run: splitting a
 * build's selected tests across CI runners that coordinate through a shared database. Built only
 * through {@link #validated(String, Integer, Long, Integer, String)}, so any {@code
 * DistributedRunConfig} in hand has already passed every rule below - callers never need to
 * re-check it.
 *
 * <p>Exactly one of {@code groupCount} or {@code targetRunTimeMs} must be supplied, giving two
 * mutually exclusive modes. {@link #isStaticGroups()} is true when the caller fixed the group
 * count directly: the balancer splits the selection into that many groups and minimises the
 * heaviest one. {@link #isDynamicGroups()} is true when the caller fixed a target wall-clock run
 * time instead: the balancer works out how many groups are needed to hit it, optionally bounded
 * by {@code maxGroups}. A ceiling only makes sense against a target that could otherwise grow the
 * group count arbitrarily; against a fixed count it is meaningless, so supplying both is rejected
 * rather than silently ignored - a user who set a ceiling would otherwise believe it was in
 * effect when it was not.
 *
 * <p>This class validates only what it is given. Two further rules belong to the calling context
 * and are deliberately not enforced here, because this class has no way to know either fact:
 * <ul>
 *   <li>rejecting an embedded H2 datastore, since a distributed run needs a datastore every
 *       runner can reach concurrently, and only the plugin layer knows which datastore is
 *       configured;
 *   <li>rejecting {@code tiaCheckLocalChanges=true}, since that check only makes sense against a
 *       single runner's working copy, and only the plugin layer knows whether it is enabled.
 * </ul>
 * Both rules must be enforced at the Maven/Gradle plugin layer (stage 4b) instead. They are
 * named here so that building this class does not read as having covered them.
 *
 * <p>{@code runnerKey} is optional and is not validated or interpreted by this class or by the
 * planner that consumes this config: it is a per-runner identity value that stage 5's claim
 * protocol reads, falling back to {@code runId + hostname + pid} when it is not supplied.
 */
public final class DistributedRunConfig {

    private final String runId;
    private final Integer groupCount;
    private final Long targetRunTimeMs;
    private final Integer maxGroups;
    private final String runnerKey;

    /**
     * Store the already-validated fields. Private so that the only way to obtain an instance is
     * through {@link #validated(String, Integer, Long, Integer, String)}, keeping every rule in
     * one place.
     *
     * @param runId the distributed run's shared identifier
     * @param groupCount the fixed number of groups to split into, or null in dynamic-groups mode
     * @param targetRunTimeMs the target wall-clock run time in ms, or null in static-groups mode
     * @param maxGroups an optional ceiling on the group count in dynamic-groups mode, or null for
     *                  no ceiling
     * @param runnerKey an optional per-runner identity value, or null to let stage 5 derive one
     */
    private DistributedRunConfig(String runId, Integer groupCount, Long targetRunTimeMs,
                                  Integer maxGroups, String runnerKey) {
        this.runId = runId;
        this.groupCount = groupCount;
        this.targetRunTimeMs = targetRunTimeMs;
        this.maxGroups = maxGroups;
        this.runnerKey = runnerKey;
    }

    /**
     * Validate and build a distributed run config from the raw property values a build-tool
     * plugin collects. Every failure message names the user-facing property (for example {@code
     * tiaDistributedGroupCount}) rather than this class's field, since the caller who needs to
     * act on the message set that property, not this constructor argument.
     *
     * @param runId the distributed run's shared identifier ({@code tiaRunId}); must not be null
     *              or blank, since every runner in the distributed run must agree on it to find
     *              each other's rows in the shared datastore
     * @param groupCount the fixed number of groups to split into ({@code
     *                    tiaDistributedGroupCount}), or null to use a target run time instead;
     *                    when set, must be at least 1
     * @param targetRunTimeMs the target wall-clock run time in ms ({@code
     *                        tiaDistributedTargetRunTime}), or null to use a fixed group count
     *                        instead; when set, must be positive
     * @param maxGroups an optional ceiling on the group count ({@code tiaDistributedMaxGroups}),
     *                  or null for no ceiling; only meaningful alongside {@code targetRunTimeMs},
     *                  since a ceiling on a fixed {@code groupCount} would either be a no-op or a
     *                  contradiction; when set, must be at least 1
     * @param runnerKey an optional per-runner identity value ({@code tiaDistributedRunnerKey});
     *                  not validated or used by this class or by the planner, since it is read
     *                  only by stage 5's claim protocol
     * @return a validated, immutable config bundle, with {@code runId} and (if supplied) {@code
     *         runnerKey} trimmed of leading and trailing whitespace
     * @throws IllegalArgumentException if {@code runId} is null or blank; if neither or both of
     *                                  {@code groupCount} and {@code targetRunTimeMs} are set; if
     *                                  {@code groupCount} is set and below 1; if {@code
     *                                  targetRunTimeMs} is set and not positive; if {@code
     *                                  maxGroups} is set and below 1; or if {@code maxGroups} is
     *                                  set together with a fixed {@code groupCount}
     */
    public static DistributedRunConfig validated(String runId, Integer groupCount,
                                                   Long targetRunTimeMs, Integer maxGroups,
                                                   String runnerKey) {
        if (runId == null || runId.trim().isEmpty()) {
            throw new IllegalArgumentException("tiaRunId must be set");
        }
        runId = runId.trim();
        runnerKey = runnerKey == null ? null : runnerKey.trim();

        validateGroupingShape(groupCount, targetRunTimeMs, maxGroups);

        return new DistributedRunConfig(runId, groupCount, targetRunTimeMs, maxGroups, runnerKey);
    }

    /**
     * Validate and build the config a <em>runner</em> needs, which is only the run id it claims
     * from and the identity it claims under.
     *
     * <p>Deliberately does not ask for the grouping properties {@link #validated} requires. How the
     * build was split is decided once, by {@code tia-dist-plan}, and is already recorded in the
     * plan the runner is about to claim from; a runner neither reads those values nor could act on
     * them. Requiring them here would do two unhelpful things: force every runner job to repeat
     * configuration that only the planning job uses, and accept a value that disagrees with the one
     * the plan was actually built with while silently ignoring it. See the "Group assignment"
     * chapter in {@code WIKI.md}.
     *
     * @param runId the distributed run's shared identifier ({@code tiaRunId}); must not be null or
     *              blank, since it is what locates the plan this runner claims from
     * @param runnerKey an optional stable per-runner identity ({@code tiaDistributedRunnerKey});
     *                  when null the coordinator derives one, at the cost of a retried CI job no
     *                  longer re-claiming the group its first attempt held
     * @return a validated, immutable config carrying no grouping shape, with {@code runId} and (if
     *         supplied) {@code runnerKey} trimmed of leading and trailing whitespace
     * @throws IllegalArgumentException if {@code runId} is null or blank
     */
    public static DistributedRunConfig forRunner(String runId, String runnerKey) {
        if (runId == null || runId.trim().isEmpty()) {
            throw new IllegalArgumentException("tiaRunId must be set");
        }
        return new DistributedRunConfig(runId.trim(), null, null, null,
                runnerKey == null ? null : runnerKey.trim());
    }

    /**
     * Validate the grouping-shape rules shared by {@link #validated} and {@link
     * DistributedRunPlanner#balance}: exactly one of {@code groupCount} or {@code targetRunTimeMs}
     * must be set, a set {@code groupCount} must be at least 1, a set {@code targetRunTimeMs} must
     * be positive, and a set {@code maxGroups} must be at least 1 and only supplied alongside a set
     * {@code targetRunTimeMs}. Package-private and static so the one caller that cannot build a
     * full {@link DistributedRunConfig} - {@link DistributedRunPlanner#balance}, the {@code
     * select-tests} grouping preview's entry point, which has no run id to build one with - checks
     * the exact same shape a real plan would, rather than a narrower, differently-worded rule of
     * its own that could let a config through the preview only for the real plan to then reject it.
     *
     * @param groupCount the fixed number of groups to split into ({@code
     *                    tiaDistributedGroupCount}), or null to use a target run time instead
     * @param targetRunTimeMs the target wall-clock run time in ms ({@code
     *                        tiaDistributedTargetRunTime}), or null to use a fixed group count
     *                        instead
     * @param maxGroups an optional ceiling on the group count ({@code tiaDistributedMaxGroups}),
     *                  or null for no ceiling; only meaningful alongside {@code targetRunTimeMs}
     * @throws IllegalArgumentException if neither or both of {@code groupCount} and {@code
     *                                  targetRunTimeMs} are set; if {@code groupCount} is set and
     *                                  below 1; if {@code targetRunTimeMs} is set and not positive;
     *                                  if {@code maxGroups} is set and below 1; or if {@code
     *                                  maxGroups} is set together with a fixed {@code groupCount}
     */
    static void validateGroupingShape(final Integer groupCount, final Long targetRunTimeMs,
                                       final Integer maxGroups) {
        boolean groupCountSet = groupCount != null;
        boolean targetRunTimeSet = targetRunTimeMs != null;
        if (!groupCountSet && !targetRunTimeSet) {
            throw new IllegalArgumentException(
                    "exactly one of tiaDistributedGroupCount or tiaDistributedTargetRunTime "
                            + "must be set, but neither was");
        }
        if (groupCountSet && targetRunTimeSet) {
            throw new IllegalArgumentException(
                    "only one of tiaDistributedGroupCount or tiaDistributedTargetRunTime may be "
                            + "set, but both were");
        }

        if (groupCountSet && groupCount < 1) {
            throw new IllegalArgumentException(
                    "tiaDistributedGroupCount must be at least 1, was " + groupCount);
        }
        if (targetRunTimeSet && targetRunTimeMs <= 0) {
            throw new IllegalArgumentException(
                    "tiaDistributedTargetRunTime must be positive, was " + targetRunTimeMs);
        }
        if (maxGroups != null && maxGroups < 1) {
            throw new IllegalArgumentException(
                    "tiaDistributedMaxGroups must be at least 1, was " + maxGroups);
        }
        if (maxGroups != null && groupCountSet) {
            throw new IllegalArgumentException(
                    "tiaDistributedMaxGroups only applies alongside tiaDistributedTargetRunTime; "
                            + "it cannot be combined with a fixed tiaDistributedGroupCount");
        }
    }

    /** @return the distributed run's shared identifier */
    public String getRunId() { return runId; }

    /** @return the fixed number of groups to split into, or null in dynamic-groups mode */
    public Integer getGroupCount() { return groupCount; }

    /** @return the target wall-clock run time in ms, or null in static-groups mode */
    public Long getTargetRunTimeMs() { return targetRunTimeMs; }

    /** @return the optional ceiling on the group count, or null for no ceiling */
    public Integer getMaxGroups() { return maxGroups; }

    /** @return the optional per-runner identity value, or null if not supplied */
    public String getRunnerKey() { return runnerKey; }

    /**
     * Report whether this config fixes the group count directly. Mutually exclusive with
     * {@link #isDynamicGroups()} - validation guarantees exactly one is true.
     *
     * @return true if a fixed {@code groupCount} was supplied
     */
    public boolean isStaticGroups() { return groupCount != null; }

    /**
     * Report whether this config targets a wall-clock run time instead of a fixed group count.
     * Mutually exclusive with {@link #isStaticGroups()} - validation guarantees exactly one is
     * true.
     *
     * @return true if a {@code targetRunTimeMs} was supplied
     */
    public boolean isDynamicGroups() { return targetRunTimeMs != null; }

    /**
     * Diagnostic rendering naming the run id, the active mode and its value, and the optional
     * ceiling and runner key.
     *
     * @return a short human-readable description
     */
    @Override
    public String toString() {
        return "DistributedRunConfig{runId=" + runId + ", groupCount=" + groupCount
                + ", targetRunTimeMs=" + targetRunTimeMs + ", maxGroups=" + maxGroups
                + ", runnerKey=" + runnerKey + "}";
    }
}
