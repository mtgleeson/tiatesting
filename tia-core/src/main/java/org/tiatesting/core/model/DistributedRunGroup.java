package org.tiatesting.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One runner's slice of a distributed run. Written {@code PENDING} by the planner, then claimed
 * and completed by whichever runner wins it - no runner is told its group number, it claims one.
 */
public final class DistributedRunGroup {

    private final String runId;
    private final int groupNumber;
    private final DistributedRunGroupStatus status;
    private final String runnerKey;
    private final Long claimedAtMs;
    private final Long completedAtMs;
    private final long estimatedMs;
    private final Long actualDurationMs;
    private final Long wallClockMs;
    private final int suitesRan;
    private final int suitesFailed;
    private final int suitesObserved;
    private final long suitesDurationMs;

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
     * @param actualDurationMs every runner JVM's summed test-execution time, retries included, or
     *                         null until a JVM reports; derived from the group's contributions
     * @param wallClockMs the longest single runner JVM's test time, or null until a JVM reports; the
     *                    group's wall clock when its JVMs ran side by side, and its duration when one
     *                    JVM ran it; derived from the group's contributions
     * @param suitesRan number of suites the runner's JVMs <b>executed</b> in their first attempt,
     *                  derived from the group's contributions. This is the figure the sealer
     *                  aggregates into the build's history row, where {@code ignoredSuiteCount} and
     *                  {@code allTestsRun} depend on it meaning "ran to completion".
     * @param suitesFailed number of this group's suites whose latest execution failed, derived from
     *                     the group's contributions
     * @param suitesObserved number of this group's assigned suites the runner <b>observed</b> - saw
     *                       finish or saw skipped - which is the count of its suite rows flagged
     *                       {@code observed}, so the union of every report against the group. On a
     *                       full run it is the figure the completeness guard reads; on any other run
     *                       the guard counts the flagged rows directly. It is deliberately not
     *                       the same figure as {@code suitesRan}, since a suite Tia never got to run
     *                       (a disabled class, a Surefire filter, a class deleted since the last
     *                       mapping run) is observed (skipped) without ever being executed.
     * @param suitesDurationMs summed measured run time of the suites the runner's JVMs timed in their
     *                         first attempt, in ms, derived from the group's contributions - the
     *                         part of {@code actualDurationMs} that is attributable to a named
     *                         suite. The remainder,
     *                         {@code actualDurationMs - suitesDurationMs}, is this runner's fixed
     *                         per-JVM overhead, which is what lets the sealer charge that overhead
     *                         once for the build instead of once per group - see
     *                         {@code DistributedRunTotals}. Zero when suite timing was not
     *                         collected (the build does not own the mapping), which the sealer treats as
     *                         "no decomposition available" rather than as "no overhead".
     */
    public DistributedRunGroup(String runId, int groupNumber, DistributedRunGroupStatus status,
                               String runnerKey, Long claimedAtMs, Long completedAtMs,
                               long estimatedMs, Long actualDurationMs, Long wallClockMs,
                               int suitesRan, int suitesFailed, int suitesObserved,
                               long suitesDurationMs) {
        this.runId = runId;
        this.groupNumber = groupNumber;
        this.status = status;
        this.runnerKey = runnerKey;
        this.claimedAtMs = claimedAtMs;
        this.completedAtMs = completedAtMs;
        this.estimatedMs = estimatedMs;
        this.actualDurationMs = actualDurationMs;
        this.wallClockMs = wallClockMs;
        this.suitesRan = suitesRan;
        this.suitesFailed = suitesFailed;
        this.suitesObserved = suitesObserved;
        this.suitesDurationMs = suitesDurationMs;
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
                null, null, null, estimatedMs, null, null, 0, 0, 0, 0L);
    }

    /**
     * Copy this group with its figures derived from its runner JVMs' contributions: the duration is
     * every JVM's plan time (retries included, since retry time is real time), the wall clock the
     * longest single JVM's, the suites ran and suite time the first-attempt totals, and the failed
     * count the latest-outcome failures across first attempt and reruns. For a group one JVM ran
     * these are exactly the figures that JVM reported. See the "Distributed test runs" chapter in
     * {@code WIKI.md}.
     *
     * @param groupContributions the contributions of this group's runner JVMs, or a subset of them
     * @return a copy carrying the derived figures; a group with no contribution has a null duration
     *         and wall clock and zero counts
     */
    public DistributedRunGroup withFigures(final List<TestRunContribution> groupContributions) {
        List<TestRunContribution> firstAttempt = new ArrayList<>();
        for (TestRunContribution contribution : groupContributions) {
            if (!contribution.isRerun()) {
                firstAttempt.add(contribution);
            }
        }
        TestRunContributionTotals all = TestRunContributionTotals.of(groupContributions);
        TestRunContributionTotals first = TestRunContributionTotals.of(firstAttempt);
        return new DistributedRunGroup(runId, groupNumber, status, runnerKey, claimedAtMs, completedAtMs,
                estimatedMs, all.isEmpty() ? null : Long.valueOf(all.getTestTimeMs()),
                all.isEmpty() ? null : Long.valueOf(all.getLongestJvmTestTimeMs()), first.getSuitesRan(),
                all.getLatestOutcomeFailedSuites().size(), suitesObserved, first.getSuitesTimeMs());
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

    /** @return every runner JVM's summed test-execution time in ms, or null until a JVM reports */
    public Long getActualDurationMs() { return actualDurationMs; }

    /**
     * @return the longest single runner JVM's test time in ms - the group's wall clock when its JVMs
     *         ran side by side - or null until a JVM reports
     */
    public Long getWallClockMs() { return wallClockMs; }

    /**
     * @return the number of suites the runner executed. See {@link #getSuitesObserved()} for the
     *         completeness guard's figure, which counts suites the runner saw finish or saw
     *         skipped and is deliberately not the same number.
     */
    public int getSuitesRan() { return suitesRan; }

    /** @return the number of this group's suites whose latest execution failed */
    public int getSuitesFailed() { return suitesFailed; }

    /**
     * @return the number of suites the runner observed (saw finish or saw skipped): the count of its
     *         suite rows flagged observed, the union of every report against the group. Compared against
     *         the group's assigned suite count by the completeness guard; not the same figure as
     *         {@link #getSuitesRan()}, which counts only suites that finished.
     */
    public int getSuitesObserved() { return suitesObserved; }

    /**
     * @return the summed first-attempt run time of the suites the runner's JVMs timed, in ms - the part of
     *         {@link #getActualDurationMs()} attributable to a named suite. The difference between
     *         the two is the runner's fixed per-JVM overhead; see {@code DistributedRunTotals} for
     *         why the sealer charges that overhead once per build rather than once per group. Zero
     *         when suite timing was not collected, which is not the same statement as "this runner
     *         had no overhead".
     */
    public long getSuitesDurationMs() { return suitesDurationMs; }

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
                && suitesObserved == that.suitesObserved
                && suitesDurationMs == that.suitesDurationMs
                && Objects.equals(runId, that.runId)
                && status == that.status
                && Objects.equals(runnerKey, that.runnerKey)
                && Objects.equals(claimedAtMs, that.claimedAtMs)
                && Objects.equals(completedAtMs, that.completedAtMs)
                && Objects.equals(actualDurationMs, that.actualDurationMs)
                && Objects.equals(wallClockMs, that.wallClockMs);
    }

    /**
     * Hash consistent with {@link #equals}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(runId, groupNumber, status, runnerKey, claimedAtMs, completedAtMs,
                estimatedMs, actualDurationMs, wallClockMs, suitesRan, suitesFailed, suitesObserved,
                suitesDurationMs);
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
