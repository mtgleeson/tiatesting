package org.tiatesting.core.model;

import java.util.Objects;

/**
 * One logical distributed build: a plan created once per CI build and shared by every runner in
 * it. Immutable; lifecycle transitions produce new instances or are applied directly in SQL.
 */
public final class DistributedRun {

    private final String runId;
    private final String branch;
    private final String commitValue;
    private final DistributedRunStatus status;
    private final int groupCount;
    private final int groupsAvailable;
    private final Long targetRunTimeMs;
    private final long estimatedTotalMs;
    private final long createdAtMs;
    private final String sealedBy;
    private final Long sealedAtMs;
    private final SelectionMode selectionMode;
    private final String runSource;

    /**
     * Full constructor, used by the read path so a persisted row round-trips exactly.
     *
     * @param runId CI-supplied identifier, shared by every job in the build
     * @param branch VCS branch the plan targets
     * @param commitValue VCS commit the plan targets; every runner must match it
     * @param status lifecycle state of the run
     * @param groupCount number of groups the plan was split into
     * @param groupsAvailable number of groups (runner machines) the build had available: the
     *                        fixed group count, else the configured maximum, else {@code
     *                        groupCount} when target-run-time mode sets no ceiling. Never below
     *                        {@code groupCount}; the wall-clock savings divide the full-suite
     *                        baseline by it
     * @param targetRunTimeMs the configured target run time in ms, or null in static groups mode
     * @param estimatedTotalMs summed estimated run time of every selected suite, in ms
     * @param createdAtMs UTC epoch millis when the plan was written
     * @param sealedBy runner key of the runner that performed the seal, or null if not sealed
     * @param sealedAtMs UTC epoch millis of the seal, or null if not sealed
     * @param selectionMode how the plan's selection was decided; null is read as
     *                      {@link SelectionMode#SELECTIVE}. Every mode but SELECTIVE is a full
     *                      run whose groups were drawn from a disk scan - see {@link #isFullRun()}
     * @param runSource the run source the plan step resolved for the build ({@code CI}, {@code
     *                  LOCAL} or a declared label), or null for a run planned before the source
     *                  was recorded - the sealer then falls back to its own environment
     */
    public DistributedRun(String runId, String branch, String commitValue,
                          DistributedRunStatus status, int groupCount, int groupsAvailable,
                          Long targetRunTimeMs, long estimatedTotalMs, long createdAtMs,
                          String sealedBy, Long sealedAtMs, SelectionMode selectionMode,
                          String runSource) {
        this.runId = runId;
        this.branch = branch;
        this.commitValue = commitValue;
        this.status = status;
        this.groupCount = groupCount;
        this.groupsAvailable = groupsAvailable;
        this.targetRunTimeMs = targetRunTimeMs;
        this.estimatedTotalMs = estimatedTotalMs;
        this.createdAtMs = createdAtMs;
        this.sealedBy = sealedBy;
        this.sealedAtMs = sealedAtMs;
        this.selectionMode = selectionMode == null ? SelectionMode.SELECTIVE : selectionMode;
        this.runSource = runSource;
    }

    /**
     * Create a newly-planned run, which is by definition {@code OPEN} and unsealed.
     *
     * @param runId CI-supplied identifier, shared by every job in the build
     * @param branch VCS branch the plan targets
     * @param commitValue VCS commit the plan targets
     * @param groupCount number of groups the plan was split into
     * @param groupsAvailable number of groups (runner machines) the build had available: the
     *                        fixed group count, else the configured maximum, else {@code
     *                        groupCount} when target-run-time mode sets no ceiling. Never below
     *                        {@code groupCount}; the wall-clock savings divide the full-suite
     *                        baseline by it
     * @param targetRunTimeMs the configured target run time in ms, or null in static groups
     * @param estimatedTotalMs summed estimated run time of every selected suite, in ms
     * @param createdAtMs UTC epoch millis when the plan was written
     * @param selectionMode how the plan's selection was decided; null is read as
     *                      {@link SelectionMode#SELECTIVE}. Every mode but SELECTIVE is a full
     *                      run whose groups were drawn from a disk scan - see {@link #isFullRun()}
     * @param runSource the run source the plan step resolved for the build ({@code CI}, {@code
     *                  LOCAL} or a declared label), or null for a run planned before the source
     *                  was recorded - the sealer then falls back to its own environment
     * @return an OPEN run with no seal recorded
     */
    public static DistributedRun open(String runId, String branch, String commitValue,
                                      int groupCount, int groupsAvailable, Long targetRunTimeMs,
                                      long estimatedTotalMs, long createdAtMs, SelectionMode selectionMode,
                                      String runSource) {
        return new DistributedRun(runId, branch, commitValue, DistributedRunStatus.OPEN,
                groupCount, groupsAvailable, targetRunTimeMs, estimatedTotalMs, createdAtMs, null,
                null, selectionMode, runSource);
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

    /**
     * The number of groups the build had available to it, as opposed to the number it used. A
     * build in target-run-time mode can plan fewer groups than its configured maximum, and the
     * wall-clock savings are measured against the full suite spread across every available
     * machine, not just the ones this build needed.
     *
     * @return the groups available to the build; never below {@link #getGroupCount()}
     */
    public int getGroupsAvailable() { return groupsAvailable; }

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
     * How the plan's selection was decided: ordinary selection, a seed (no stored mapping, suites
     * split across groups by even count, or collapsed to a single group carrying no suite names
     * whose runner runs everything), or a forced full run. Persisted with the plan rather than
     * worked out again at seal time, because a full run's plan can otherwise be indistinguishable
     * from a nothing-impacted one - see {@code DistributedRunSealer.ignoredSuiteCount}.
     *
     * @return the selection mode the plan was made with
     */
    public SelectionMode getSelectionMode() { return selectionMode; }

    /**
     * Whether the plan runs every test - a seed or a forced run - so its groups were drawn from
     * the disk scan, its completion guard is loosened and it ignores no suite. See the "Forced
     * runs and re-seed" chapter in {@code WIKI.md}.
     *
     * @return true for every mode but {@link SelectionMode#SELECTIVE}
     */
    public boolean isFullRun() { return selectionMode.isFullRun(); }

    /**
     * The run source the plan step resolved for the build, which the sealer stamps on the build's
     * history row. Recorded at plan time because the plan step runs once per build on the CI
     * agent, while the seal runs in whichever runner's test JVM finishes last - often inside a
     * container that inherits none of the CI system's marker variables, so detecting the source
     * there labels a CI build {@code LOCAL}. See the "Test run history" chapter in {@code WIKI.md}.
     *
     * @return the planned run source, or null for a run planned before the source was recorded
     */
    public String getRunSource() { return runSource; }

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
                && groupsAvailable == that.groupsAvailable
                && estimatedTotalMs == that.estimatedTotalMs
                && createdAtMs == that.createdAtMs
                && selectionMode == that.selectionMode
                && Objects.equals(runId, that.runId)
                && Objects.equals(branch, that.branch)
                && Objects.equals(commitValue, that.commitValue)
                && status == that.status
                && Objects.equals(targetRunTimeMs, that.targetRunTimeMs)
                && Objects.equals(sealedBy, that.sealedBy)
                && Objects.equals(sealedAtMs, that.sealedAtMs)
                && Objects.equals(runSource, that.runSource);
    }

    /**
     * Hash consistent with {@link #equals}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(runId, branch, commitValue, status, groupCount, groupsAvailable,
                targetRunTimeMs, estimatedTotalMs, createdAtMs, sealedBy, sealedAtMs, selectionMode,
                runSource);
    }

    /**
     * Diagnostic rendering naming the run, its commit and its size.
     *
     * @return a short human-readable description
     */
    @Override
    public String toString() {
        return "DistributedRun{runId=" + runId + ", branch=" + branch + ", commit=" + commitValue
                + ", status=" + status + ", groupCount=" + groupCount
                + ", groupsAvailable=" + groupsAvailable + ", selectionMode=" + selectionMode
                + ", runSource=" + runSource + "}";
    }
}
