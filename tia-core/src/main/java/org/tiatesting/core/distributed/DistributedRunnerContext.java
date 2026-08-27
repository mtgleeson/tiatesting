package org.tiatesting.core.distributed;

/**
 * What one runner must remember about the distributed run it belongs to, from the moment it claims
 * until the moment it persists: the run it claimed from, the identity it claimed under, and the
 * group it holds. Nothing else about the run crosses that gap, because nothing else is the
 * runner's to know - how the build was split is the planner's decision, already recorded in the
 * plan.
 *
 * <p>The three values are held together rather than passed around separately for one reason: the
 * completion write is guarded on all three at once, and a runner that completed with a re-derived
 * runner key or a mislaid group number would silently fail that guard and leave its group open
 * forever, so the run would never seal. See the "Distributed test runs" chapter in {@code WIKI.md}.
 *
 * <p>A context with no group is a legitimate state, not an error: a pipeline that fans out wider
 * than the plan's group count produces surplus runners, which run nothing and have nothing to
 * complete. {@link #isClaimed()} is how a caller tells the two apart - callers branch on it rather
 * than null-checking {@link #getGroupNumber()}, mirroring {@link DistributedRunnerAssignment}.
 */
public final class DistributedRunnerContext {

    private final String runId;
    private final String runnerKey;
    private final Integer groupNumber;

    /**
     * Store the already-validated fields. Private so that the only ways to obtain a context are
     * the two factories, which is what keeps "claimed a group" and "claimed nothing" the only two
     * shapes that can exist.
     *
     * @param runId the distributed run's shared identifier
     * @param runnerKey the identity this runner claimed under
     * @param groupNumber the group this runner holds, or null when it claimed none
     */
    private DistributedRunnerContext(final String runId, final String runnerKey,
                                     final Integer groupNumber) {
        this.runId = runId;
        this.runnerKey = runnerKey;
        this.groupNumber = groupNumber;
    }

    /**
     * Build the context of a runner that holds a group, and therefore has suites to run, mapping
     * to persist and a group to complete.
     *
     * @param runId the distributed run's shared identifier; must not be null or blank, since it is
     *              what every write this runner makes is keyed by
     * @param runnerKey the identity the group was claimed under; must not be null or blank, and
     *                  must be the value the claim recorded rather than one re-derived later, or
     *                  the guarded completion write will match no row
     * @param groupNumber the claimed group's zero-based index within the run; must not be negative
     * @return a validated context for a claimed runner
     * @throws IllegalArgumentException if {@code runId} or {@code runnerKey} is null or blank, or
     *                                  if {@code groupNumber} is negative
     */
    public static DistributedRunnerContext forClaimedGroup(final String runId, final String runnerKey,
                                                            final int groupNumber) {
        if (groupNumber < 0) {
            throw new IllegalArgumentException("a claimed distributed run group number cannot be "
                    + "negative, was " + groupNumber);
        }
        return new DistributedRunnerContext(requireValue(runId, "tiaRunId"),
                requireValue(runnerKey, "the distributed run runner key"), Integer.valueOf(groupNumber));
    }

    /**
     * Build the context of a runner that claimed no group because every group was already taken.
     * It ran nothing, so it has nothing to persist and no group to complete - but it is still a
     * distributed runner, and saying so is what keeps it off the single-host path, where it would
     * seal a build whose other runners are still going.
     *
     * @param runId the distributed run's shared identifier; must not be null or blank
     * @param runnerKey the identity the claim was attempted under; must not be null or blank
     * @return a validated context for a surplus runner
     * @throws IllegalArgumentException if {@code runId} or {@code runnerKey} is null or blank
     */
    public static DistributedRunnerContext surplusRunner(final String runId, final String runnerKey) {
        return new DistributedRunnerContext(requireValue(runId, "tiaRunId"),
                requireValue(runnerKey, "the distributed run runner key"), null);
    }

    /**
     * Validate one of the two identity values and return it trimmed, naming what was missing so a
     * misconfigured runner is told which value to supply.
     *
     * @param value the value to validate
     * @param description what the value is, used in the failure message
     * @return the trimmed value
     * @throws IllegalArgumentException if the value is null or blank
     */
    private static String requireValue(final String value, final String description) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(description + " must be set for a distributed runner");
        }
        return value.trim();
    }

    /**
     * Report whether this runner holds a group, and therefore whether it has anything to persist
     * and a group to complete.
     *
     * @return true if a group was claimed
     */
    public boolean isClaimed() {
        return groupNumber != null;
    }

    /** @return the distributed run's shared identifier; never null or blank */
    public String getRunId() {
        return runId;
    }

    /** @return the identity this runner claimed under; never null or blank */
    public String getRunnerKey() {
        return runnerKey;
    }

    /** @return the group this runner holds, or null when it claimed none */
    public Integer getGroupNumber() {
        return groupNumber;
    }

    /**
     * Diagnostic rendering naming the run, the runner and its group.
     *
     * @return a short human-readable description
     */
    @Override
    public String toString() {
        return "DistributedRunnerContext{runId=" + runId + ", runnerKey=" + runnerKey
                + ", group=" + (groupNumber == null ? "none" : String.valueOf(groupNumber)) + "}";
    }
}
