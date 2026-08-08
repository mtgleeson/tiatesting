package org.tiatesting.core.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * One runner's slice of a distributed run. Written {@code PENDING} by the planner, then claimed
 * and completed by whichever runner wins it - no runner is told its group number, it claims one.
 */
public final class DistributedRunGroup implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String runId;
    private final int groupNumber;
    private final DistributedRunGroupStatus status;
    private final String runnerKey;
    private final Long claimedAtMs;
    private final Long completedAtMs;
    private final long estimatedMs;
    private final Long actualDurationMs;
    private final int suitesRan;
    private final int suitesFailed;

    /**
     * Full constructor, used by the read path so a persisted row round-trips exactly.
     *
     * @param runId the owning run's identifier
     * @param groupNumber zero-based index of this group within the run
     * @param status lifecycle state of the group
     * @param runnerKey identity of the claiming runner, or null while PENDING
     * @param claimedAtMs UTC epoch millis of the claim, or null while PENDING
     * @param completedAtMs UTC epoch millis of completion, or null until COMPLETED
     * @param estimatedMs summed estimated run time of this group's suites, in ms
     * @param actualDurationMs measured test-execution time, or null until COMPLETED
     * @param suitesRan number of suites the runner executed
     * @param suitesFailed number of this group's suites with at least one failed test
     */
    public DistributedRunGroup(String runId, int groupNumber, DistributedRunGroupStatus status,
                               String runnerKey, Long claimedAtMs, Long completedAtMs,
                               long estimatedMs, Long actualDurationMs, int suitesRan,
                               int suitesFailed) {
        this.runId = runId;
        this.groupNumber = groupNumber;
        this.status = status;
        this.runnerKey = runnerKey;
        this.claimedAtMs = claimedAtMs;
        this.completedAtMs = completedAtMs;
        this.estimatedMs = estimatedMs;
        this.actualDurationMs = actualDurationMs;
        this.suitesRan = suitesRan;
        this.suitesFailed = suitesFailed;
    }

    /**
     * Create a freshly-planned group: PENDING, unclaimed, with only its estimate known.
     *
     * @param runId the owning run's identifier
     * @param groupNumber zero-based index of this group within the run
     * @param estimatedMs summed estimated run time of this group's suites, in ms
     * @return a PENDING group with no runner and no measurements
     */
    public static DistributedRunGroup pending(String runId, int groupNumber, long estimatedMs) {
        return new DistributedRunGroup(runId, groupNumber, DistributedRunGroupStatus.PENDING,
                null, null, null, estimatedMs, null, 0, 0);
    }

    /** @return the owning run's identifier */
    public String getRunId() { return runId; }

    /** @return the zero-based index of this group within the run */
    public int getGroupNumber() { return groupNumber; }

    /** @return the lifecycle state of the group */
    public DistributedRunGroupStatus getStatus() { return status; }

    /** @return the claiming runner's key, or null while PENDING */
    public String getRunnerKey() { return runnerKey; }

    /** @return UTC epoch millis of the claim, or null while PENDING */
    public Long getClaimedAtMs() { return claimedAtMs; }

    /** @return UTC epoch millis of completion, or null until COMPLETED */
    public Long getCompletedAtMs() { return completedAtMs; }

    /** @return the summed estimated run time of this group's suites, in ms */
    public long getEstimatedMs() { return estimatedMs; }

    /** @return measured test-execution time in ms, or null until COMPLETED */
    public Long getActualDurationMs() { return actualDurationMs; }

    /** @return the number of suites the runner executed */
    public int getSuitesRan() { return suitesRan; }

    /** @return the number of this group's suites with at least one failed test */
    public int getSuitesFailed() { return suitesFailed; }

    /**
     * Value equality across every field, so a persisted row can be asserted equal to the object
     * it was written from.
     *
     * @param o the object to compare against
     * @return true if o is a DistributedRunGroup with identical field values
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        DistributedRunGroup that = (DistributedRunGroup) o;
        return groupNumber == that.groupNumber
                && estimatedMs == that.estimatedMs
                && suitesRan == that.suitesRan
                && suitesFailed == that.suitesFailed
                && Objects.equals(runId, that.runId)
                && status == that.status
                && Objects.equals(runnerKey, that.runnerKey)
                && Objects.equals(claimedAtMs, that.claimedAtMs)
                && Objects.equals(completedAtMs, that.completedAtMs)
                && Objects.equals(actualDurationMs, that.actualDurationMs);
    }

    /**
     * Hash consistent with {@link #equals}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(runId, groupNumber, status, runnerKey, claimedAtMs, completedAtMs,
                estimatedMs, actualDurationMs, suitesRan, suitesFailed);
    }

    /**
     * Diagnostic rendering naming the group and its state.
     *
     * @return a short human-readable description
     */
    @Override
    public String toString() {
        return "DistributedRunGroup{runId=" + runId + ", group=" + groupNumber
                + ", status=" + status + ", runnerKey=" + runnerKey + "}";
    }
}
