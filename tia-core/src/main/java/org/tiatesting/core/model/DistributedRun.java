package org.tiatesting.core.model;


import java.io.Serializable;
import java.util.Objects;

/**
 * One logical distributed build: a plan created once per CI build and shared by every runner in
 * it. Immutable; lifecycle transitions produce new instances or are applied directly in SQL.
 */
public final class DistributedRun implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String runId;
    private final String branch;
    private final String commitValue;
    private final DistributedRunStatus status;
    private final int groupCount;
    private final Long targetRunTimeMs;
    private final long estimatedTotalMs;
    private final long createdAtMs;
    private final String sealedBy;
    private final Long sealedAtMs;
    private final boolean seedRun;

    /**
     * Full constructor, used by the read path so a persisted row round-trips exactly.
     *
     * @param runId CI-supplied identifier, shared by every job in the build
     * @param branch VCS branch the plan targets
     * @param commitValue VCS commit the plan targets; every runner must match it
     * @param status lifecycle state of the run
     * @param groupCount number of groups the plan was split into
     * @param targetRunTimeMs the configured target run time in ms, or null in static groups mode
     * @param estimatedTotalMs summed estimated run time of every selected suite, in ms
     * @param createdAtMs UTC epoch millis when the plan was written
     * @param sealedBy runner key of the runner that performed the seal, or null if not sealed
     * @param sealedAtMs UTC epoch millis of the seal, or null if not sealed
     * @param seedRun whether the planner collapsed this run to a single group with no suite names
     *                because no stored mapping existed yet for the branch
     */
    public DistributedRun(String runId, String branch, String commitValue,
                          DistributedRunStatus status, int groupCount, Long targetRunTimeMs,
                          long estimatedTotalMs, long createdAtMs, String sealedBy,
                          Long sealedAtMs, boolean seedRun) {
        this.runId = runId;
        this.branch = branch;
        this.commitValue = commitValue;
        this.status = status;
        this.groupCount = groupCount;
        this.targetRunTimeMs = targetRunTimeMs;
        this.estimatedTotalMs = estimatedTotalMs;
        this.createdAtMs = createdAtMs;
        this.sealedBy = sealedBy;
        this.sealedAtMs = sealedAtMs;
        this.seedRun = seedRun;
    }

    /**
     * Create a newly-planned run, which is by definition {@code OPEN} and unsealed.
     *
     * @param runId CI-supplied identifier, shared by every job in the build
     * @param branch VCS branch the plan targets
     * @param commitValue VCS commit the plan targets
     * @param groupCount number of groups the plan was split into
     * @param targetRunTimeMs the configured target run time in ms, or null in static groups
     * @param estimatedTotalMs summed estimated run time of every selected suite, in ms
     * @param createdAtMs UTC epoch millis when the plan was written
     * @param seedRun whether the planner collapsed this run to a single group with no suite names
     *                because no stored mapping existed yet for the branch
     * @return an OPEN run with no seal recorded
     */
    public static DistributedRun open(String runId, String branch, String commitValue,
                                      int groupCount, Long targetRunTimeMs,
                                      long estimatedTotalMs, long createdAtMs, boolean seedRun) {
        return new DistributedRun(runId, branch, commitValue, DistributedRunStatus.OPEN,
                groupCount, targetRunTimeMs, estimatedTotalMs, createdAtMs, null, null, seedRun);
    }

    /** @return the CI-supplied run identifier */
    public String getRunId() { return runId; }

    /** @return the VCS branch the plan targets */
    public String getBranch() { return branch; }

    /** @return the VCS commit the plan targets */
    public String getCommitValue() { return commitValue; }

    /** @return the lifecycle state of the run */
    public DistributedRunStatus getStatus() { return status; }

    /** @return the number of groups the plan was split into */
    public int getGroupCount() { return groupCount; }

    /** @return the configured target run time in ms, or null in static groups mode */
    public Long getTargetRunTimeMs() { return targetRunTimeMs; }

    /** @return the summed estimated run time of every selected suite, in ms */
    public long getEstimatedTotalMs() { return estimatedTotalMs; }

    /** @return UTC epoch millis when the plan was written */
    public long getCreatedAtMs() { return createdAtMs; }

    /** @return the runner key that performed the seal, or null if not sealed */
    public String getSealedBy() { return sealedBy; }

    /** @return UTC epoch millis of the seal, or null if not sealed */
    public Long getSealedAtMs() { return sealedAtMs; }

    /**
     * Whether this run is a seed run: the planner found no stored mapping for the branch and
     * collapsed the plan to a single group carrying no suite names, so its runner ignores nothing
     * and runs everything. Persisted with the plan rather than worked out again at seal time,
     * because the shape of a seed run's plan is indistinguishable from a nothing-impacted one - see
     * {@code DistributedRunSealer.ignoredSuiteCount}.
     *
     * @return true if the plan was collapsed to a seed run
     */
    public boolean isSeedRun() { return seedRun; }

    /**
     * Value equality across every field, so a persisted row can be asserted equal to the object
     * it was written from.
     *
     * @param o the object to compare against
     * @return true if o is a DistributedRun with identical field values
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        DistributedRun that = (DistributedRun) o;
        return groupCount == that.groupCount
                && estimatedTotalMs == that.estimatedTotalMs
                && createdAtMs == that.createdAtMs
                && seedRun == that.seedRun
                && Objects.equals(runId, that.runId)
                && Objects.equals(branch, that.branch)
                && Objects.equals(commitValue, that.commitValue)
                && status == that.status
                && Objects.equals(targetRunTimeMs, that.targetRunTimeMs)
                && Objects.equals(sealedBy, that.sealedBy)
                && Objects.equals(sealedAtMs, that.sealedAtMs);
    }

    /**
     * Hash consistent with {@link #equals}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(runId, branch, commitValue, status, groupCount, targetRunTimeMs,
                estimatedTotalMs, createdAtMs, sealedBy, sealedAtMs, seedRun);
    }

    /**
     * Diagnostic rendering naming the run, its commit and its size.
     *
     * @return a short human-readable description
     */
    @Override
    public String toString() {
        return "DistributedRun{runId=" + runId + ", branch=" + branch + ", commit=" + commitValue
                + ", status=" + status + ", groupCount=" + groupCount + ", seedRun=" + seedRun + "}";
    }
}
