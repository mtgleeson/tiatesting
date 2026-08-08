package org.tiatesting.core.model;

/**
 * Lifecycle state of a single group within a distributed run. A group advances
 * {@code PENDING -> CLAIMED -> COMPLETED}; it never moves backwards, and only a group in
 * {@code PENDING} can be claimed.
 */
public enum DistributedRunGroupStatus {
    /** Written by the planner; no runner has taken this group yet. */
    PENDING,
    /** A runner has claimed the group and is expected to run its suites. */
    CLAIMED,
    /** The runner finished the group's suites and persisted its own mapping rows. */
    COMPLETED
}
