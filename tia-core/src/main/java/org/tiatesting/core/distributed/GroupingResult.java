package org.tiatesting.core.distributed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of balancing a selection into groups: the groups themselves plus the three facts a
 * caller needs to explain the outcome to a user. {@code targetMet} answers "is this build going
 * to come in under the target". {@code clampedToMaxGroups} and {@code singleSuiteExceedsTarget}
 * are the two independent reasons {@code targetMet} can be false, not alternatives to pick
 * between: {@code clampedToMaxGroups} means the configured group ceiling limited the group count,
 * and {@code singleSuiteExceedsTarget} means one suite alone is heavier than the whole target, so
 * no group count could have met it. Either can be true without the other, and both can be true at
 * once, so a caller must check both to explain a miss rather than assuming one implies the other.
 */
public final class GroupingResult {

    private final List<SuiteGroup> groups;
    private final boolean targetMet;
    private final boolean clampedToMaxGroups;
    private final boolean singleSuiteExceedsTarget;
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
     * @param singleSuiteExceedsTarget whether a single suite's weight alone exceeds the
     *                                 configured target run time; always false for static groups,
     *                                 which have no target to exceed
     * @throws IllegalArgumentException if any group's group number does not equal its index in
     *                                  {@code groups}
     */
    public GroupingResult(List<SuiteGroup> groups, boolean targetMet, boolean clampedToMaxGroups,
                           boolean singleSuiteExceedsTarget) {
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
     * @return whether a single suite's weight alone exceeds the configured target run time, so no
     *         group count could have met it; always false for static groups, which have no target
     */
    public boolean isSingleSuiteExceedsTarget() { return singleSuiteExceedsTarget; }

    /**
     * @return the weight of the heaviest group in ms, which is the run's expected wall-clock test
     *         time since the groups execute in parallel
     */
    public long getHeaviestGroupMs() { return heaviestGroupMs; }

    /**
     * @return the summed weight of every group in ms, which is the serial-equivalent time the
     *         same suites would take on one runner
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
                + ", singleSuiteExceedsTarget=" + singleSuiteExceedsTarget + "}";
    }
}
