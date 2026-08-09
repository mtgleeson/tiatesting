package org.tiatesting.core.distributed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of balancing a selection into groups: the groups themselves plus the two facts a
 * caller needs to explain the outcome to a user. {@code targetMet} answers "is this build going
 * to come in under the target", and {@code clampedToMaxGroups} distinguishes the two reasons it
 * might not: the configured group ceiling was too low, or a single suite is longer than the whole
 * target and no amount of splitting can help.
 */
public final class GroupingResult {

    private final List<SuiteGroup> groups;
    private final boolean targetMet;
    private final boolean clampedToMaxGroups;
    private final long heaviestGroupMs;
    private final long totalEstimatedMs;

    /**
     * Create a result, deriving the heaviest-group and total weights once rather than on each
     * read, since callers report both.
     *
     * @param groups the groups produced, in group-number order
     * @param targetMet whether the heaviest group came in at or under the configured target;
     *                  always true for static groups, which have no target
     * @param clampedToMaxGroups whether the group count was limited by the configured ceiling
     */
    public GroupingResult(List<SuiteGroup> groups, boolean targetMet, boolean clampedToMaxGroups) {
        this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
        this.targetMet = targetMet;
        this.clampedToMaxGroups = clampedToMaxGroups;
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
     * Diagnostic rendering naming the group count and the two headline weights.
     *
     * @return a short human-readable description
     */
    @Override
    public String toString() {
        return "GroupingResult{groups=" + groups.size() + ", heaviestMs=" + heaviestGroupMs
                + ", totalMs=" + totalEstimatedMs + ", targetMet=" + targetMet
                + ", clamped=" + clampedToMaxGroups + "}";
    }
}
