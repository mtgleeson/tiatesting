package org.tiatesting.core.model;

/**
 * Lifecycle state of a distributed run plan. A run is inserted {@code OPEN} together with its
 * groups in a single transaction, so a runner never observes a half-written plan, and moves to
 * {@code SEALED} exactly once when the last group completes and the winning runner performs the
 * seal.
 */
public enum DistributedRunStatus {
    /** The plan is complete and its groups are available to claim. */
    OPEN,
    /** Every group completed and the commit value has been written. */
    SEALED
}
