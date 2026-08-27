package org.tiatesting.core.distributed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of balancing a selection into groups: the groups themselves plus the facts a caller
 * needs to explain the outcome to a user. {@code targetMet} answers "is this build going to come in
 * under the target". {@code clampedToMaxGroups}, {@code singleSuiteExceedsTarget} and
 * {@code fixedOverheadExceedsTarget} are three independent reasons {@code targetMet} can be false,
 * not alternatives to pick between: the first means the configured group ceiling limited the group
 * count, the second means one suite alone is heavier than the budget left after the runner's own
 * start-up, and the third means that start-up cost is on its own at or above the whole target. Any
 * can be true without the others, and more than one can be true at once, so a caller must check them
 * all to explain a miss rather than assuming one implies the others.
 *
 * <p>The last of the three is the only one no change to the selection can fix. A ceiling can be
 * raised and a heavy suite can be split, but a target below what a test JVM costs to start cannot be
 * met by any number of runners - each one adds another copy of that cost. A caller that reported
 * only the first two would leave a user adding runners that cannot help.
 */
public final class GroupingResult {

    private final List<SuiteGroup> groups;
    private final boolean targetMet;
    private final boolean clampedToMaxGroups;
    private final boolean singleSuiteExceedsTarget;
    private final boolean fixedOverheadExceedsTarget;
    private final long heaviestGroupMs;
    private final long totalEstimatedMs;

    /**
     * Create a result, validating that group numbers match their position and deriving the
     * heaviest-group and total weights once rather than on each read, since callers report both.
     *
     * @param groups the groups produced, in group-number order; each group's
     *               {@link SuiteGroup#getGroupNumber()} must equal its index in this list
     * @param targetMet whether the heaviest group came in at or under the configured target;
     *                  always true for static groups, which have no target
     * @param clampedToMaxGroups whether the group count was limited by the configured ceiling
     * @param singleSuiteExceedsTarget whether a single suite's weight alone exceeds what is left of
     *                                 the configured target once each runner's fixed per-JVM cost is
     *                                 paid for; always false for static groups, which have no target
     *                                 to exceed
     * @param fixedOverheadExceedsTarget whether the fixed per-JVM cost is on its own at or above the
     *                                   configured target, so no group count can meet it; always
     *                                   false for static groups, which have no target
     * @throws IllegalArgumentException if any group's group number does not equal its index in
     *                                  {@code groups}
     */
    public GroupingResult(List<SuiteGroup> groups, boolean targetMet, boolean clampedToMaxGroups,
                           boolean singleSuiteExceedsTarget, boolean fixedOverheadExceedsTarget) {
        this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
        for (int i = 0; i < this.groups.size(); i++) {
            int actualGroupNumber = this.groups.get(i).getGroupNumber();
            if (actualGroupNumber != i) {
                throw new IllegalArgumentException("group at index " + i
                        + " must have group number " + i + " but was " + actualGroupNumber);
            }
        }
        this.targetMet = targetMet;
        this.clampedToMaxGroups = clampedToMaxGroups;
        this.singleSuiteExceedsTarget = singleSuiteExceedsTarget;
        this.fixedOverheadExceedsTarget = fixedOverheadExceedsTarget;
        long heaviest = 0L;
        long total = 0L;
        for (SuiteGroup group : this.groups) {
            total += group.getEstimatedMs();
            if (group.getEstimatedMs() > heaviest) {
                heaviest = group.getEstimatedMs();
            }
        }
        this.heaviestGroupMs = heaviest;
        this.totalEstimatedMs = total;
    }

    /** @return the groups produced, in group-number order, unmodifiable */
    public List<SuiteGroup> getGroups() { return groups; }

    /** @return the number of groups produced */
    public int getGroupCount() { return groups.size(); }

    /** @return whether the heaviest group came in at or under the configured target */
    public boolean isTargetMet() { return targetMet; }

    /** @return whether the group count was limited by the configured ceiling */
    public boolean isClampedToMaxGroups() { return clampedToMaxGroups; }

    /**
     * @return whether a single suite's weight alone exceeds what is left of the configured target
     *         once each runner's fixed per-JVM cost is paid for, so no group count could have met
     *         it; always false for static groups, which have no target
     */
    public boolean isSingleSuiteExceedsTarget() { return singleSuiteExceedsTarget; }

    /**
     * @return whether the fixed per-JVM cost is on its own at or above the configured target, so no
     *         group count can meet it and adding runners only adds copies of that cost; always false
     *         for static groups, which have no target
     */
    public boolean isFixedOverheadExceedsTarget() { return fixedOverheadExceedsTarget; }

    /**
     * @return the weight of the heaviest group in ms, including its own copy of the fixed per-JVM
     *         cost, which is the run's expected wall-clock test time since the groups execute in
     *         parallel
     */
    public long getHeaviestGroupMs() { return heaviestGroupMs; }

    /**
     * @return the summed weight of every group in ms, each carrying its own copy of the fixed
     *         per-JVM cost. This is the total machine time the fan-out costs, <b>not</b> the
     *         serial-equivalent time on one runner - one host would pay that fixed cost once, so
     *         the serial figure is this less {@code (groupCount - 1)} copies of it, mirroring the
     *         correction {@code DistributedRunTotals} applies to the measured durations
     */
    public long getTotalEstimatedMs() { return totalEstimatedMs; }

    /**
     * Diagnostic rendering naming the group count and the headline weights and flags.
     *
     * @return a short human-readable description
     */
    @Override
    public String toString() {
        return "GroupingResult{groups=" + groups.size() + ", heaviestMs=" + heaviestGroupMs
                + ", totalMs=" + totalEstimatedMs + ", targetMet=" + targetMet
                + ", clamped=" + clampedToMaxGroups
                + ", singleSuiteExceedsTarget=" + singleSuiteExceedsTarget
                + ", fixedOverheadExceedsTarget=" + fixedOverheadExceedsTarget + "}";
    }
}
