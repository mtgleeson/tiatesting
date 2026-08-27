package org.tiatesting.core.distributed;

import org.tiatesting.core.model.DistributedRunGroup;

/**
 * What happened when a runner asked {@link DistributedRunCoordinator#claim} for a slice of a
 * distributed run: either it holds a group, or the run had none left to give it.
 *
 * <p>Only the two <em>survivable</em> outcomes are modelled here. The two failure cases -
 * the run row being absent, and the runner's workspace sitting on a different commit than the plan
 * was built for - are thrown by {@link DistributedRunCoordinator#claim} rather than represented as
 * a state, precisely so that a caller cannot handle them by accident. A runner that treated "there
 * is no run" as an ordinary "nothing to do" would exit successfully having run no tests at all,
 * reporting a green build for code nothing tested.
 *
 * <p>{@link #isClaimed()} false is legitimate and expected: a pipeline that fans out to more jobs
 * than the plan has groups - because the fan-out is fixed in CI config while the group count is
 * chosen per build - produces surplus runners, and a surplus runner has genuinely nothing to run.
 *
 * <p>The runner key is carried on both outcomes, not only the claimed one, because it is the
 * identity the coordinator actually claimed with, which may be one it derived rather than one the
 * user configured. The completion step records a group's completion under that same key, and logs
 * of a no-op outcome need to name the runner that found nothing.
 */
public final class ClaimOutcome {

    private final DistributedRunGroup group;
    private final String runnerKey;

    /**
     * Store the outcome's fields. Private so instances can only be built through the two factory
     * methods, which name the two states rather than leaving a caller to infer them from a null.
     *
     * @param group the group this runner holds, or null when the run had none left to claim
     * @param runnerKey the identity the claim was attempted under
     */
    private ClaimOutcome(DistributedRunGroup group, String runnerKey) {
        this.group = group;
        this.runnerKey = runnerKey;
    }

    /**
     * Record that this runner holds a group and should run that group's share of the suite.
     *
     * @param group the claimed group, as read back from the datastore after the claim
     * @param runnerKey the identity the group was claimed under
     * @return a claimed outcome carrying the group
     */
    public static ClaimOutcome claimed(DistributedRunGroup group, String runnerKey) {
        return new ClaimOutcome(group, runnerKey);
    }

    /**
     * Record that the run exists and is valid for this workspace, but every group in it was
     * already claimed - a surplus runner outside the plan's fan-out, which has nothing to run and
     * should finish without failing the build.
     *
     * @param runnerKey the identity the claim was attempted under
     * @return a no-op outcome carrying no group
     */
    public static ClaimOutcome nothingToClaim(String runnerKey) {
        return new ClaimOutcome(null, runnerKey);
    }

    /**
     * Report whether this runner holds a group, and therefore whether it has any tests to run at
     * all. Callers branch on this rather than null-checking {@link #getGroup()}.
     *
     * @return true if a group was claimed
     */
    public boolean isClaimed() { return group != null; }

    /** @return the claimed group, or null when there was nothing left to claim */
    public DistributedRunGroup getGroup() { return group; }

    /** @return the runner identity the claim was attempted under, configured or derived */
    public String getRunnerKey() { return runnerKey; }

    /**
     * Diagnostic rendering naming the runner and either its group or the fact it got none.
     *
     * @return a short human-readable description
     */
    @Override
    public String toString() {
        return "ClaimOutcome{runnerKey=" + runnerKey
                + ", group=" + (group == null ? "none" : String.valueOf(group.getGroupNumber()))
                + "}";
    }
}
