package org.tiatesting.core.distributed;

import org.tiatesting.core.model.DistributedRunGroup;

import java.util.List;
import java.util.Map;

/**
 * The two constants a run's overhead decomposes into, solved from a distributed build's group rows.
 *
 * <p>Tia's run-time estimate has always modelled overhead as one per-suite number: the recorded
 * full-suite time minus the sum of every tracked suite's average, divided by the tracked suite
 * count. That is the model
 *
 * <pre>
 *   run total = Σ suite averages + capture × suites
 * </pre>
 *
 * and it is missing a term. Real overhead is
 *
 * <pre>
 *   run total = Σ suite averages
 *             + capture × suites     per-suite coverage collection (JaCoCo's per-suite dump)
 *             + fixed                per-JVM, paid once no matter how few suites run
 * </pre>
 *
 * <p>Dividing the whole overhead by the suite count keeps only {@code capture} and amortises
 * {@code fixed} away. For a single-host run that is harmless - multiplying back up by the selected
 * suite count recovers the whole figure. For a <b>distributed</b> run it is wrong, because every
 * runner is its own JVM and pays {@code fixed} in full: the cost is duplicated per group, not
 * divided across them. On the fixture this was measured against, two runners paid 519ms of overhead
 * between them where one host paid 300ms, while the estimate divided that 300ms three ways.
 *
 * <p><b>Why this needs a distributed run.</b> One equation cannot separate two unknowns, which is
 * why the decomposition was never available before. A distributed build supplies a second equation
 * with a different suite count, because each group's overhead is already measured - every runner
 * reports {@link DistributedRunGroup#getSuitesDurationMs()} alongside
 * {@link DistributedRunGroup#getActualDurationMs()} so the sealer can charge the fixed cost once
 * (see {@link DistributedRunTotals}). Nothing new is measured here, only combined:
 *
 * <pre>
 *   wholeRunOverhead  = fixed + capture × trackedSuites      (from the all-tests baseline)
 *   meanGroupOverhead = fixed + capture × meanGroupSuites    (from this build's group rows)
 *
 *   capture = (wholeRunOverhead - meanGroupOverhead) / (trackedSuites - meanGroupSuites)
 *   fixed   = meanGroupOverhead - capture × meanGroupSuites
 * </pre>
 *
 * <p><b>The mean, where {@link DistributedRunTotals} takes the minimum.</b> The two use the same
 * per-group measurement and deliberately estimate it differently, and neither should be "corrected"
 * to match the other. {@code DistributedRunTotals} is applying a subtraction to a figure the build
 * actually took, so it needs the largest amount every runner <em>demonstrably</em> paid - the
 * minimum - or it would subtract time that was never there and claim savings the build did not
 * earn. This class is producing a forecast, and a forecast wants the expected value, not a floor.
 *
 * <p><b>The group's suite count comes from the plan, not from {@code suitesRan}.</b> That counter
 * accumulates <em>executions</em>, so a Surefire retry inside a runner's JVM legitimately sums into
 * it - the same trap documented on {@code DistributedRunSealer.ignoredSuiteCount}. Here it would
 * inflate the suite count on the group whose overhead the retry also inflated, corrupting both
 * sides of the equation at once. What the plan assigned a group cannot be moved by any number of
 * retries, and a group only completes once it has observed everything assigned to it, so the two
 * agree by the time the sealer reads them.
 *
 * <p>Every failure to solve is a skip, never a guess: an unsolved model leaves the stored rolling
 * averages exactly as they were, so a project falls back to the single-number behaviour that
 * predates this rather than to a nonsense constant. See the distributed test runs chapter in
 * {@code WIKI.md}.
 */
public final class DistributedRunOverheadModel {

    private final boolean solved;
    private final long fixedOverheadMs;
    private final long captureOverheadPerSuiteMs;
    private final String skipReason;

    /**
     * Store a solved or skipped model.
     *
     * @param solved whether the pair of equations yielded a usable answer
     * @param fixedOverheadMs the per-JVM cost paid once per group, in ms; 0 when unsolved
     * @param captureOverheadPerSuiteMs the per-suite coverage-collection cost, in ms; 0 when unsolved
     * @param skipReason why the solve was abandoned, for logging; null when solved
     */
    private DistributedRunOverheadModel(final boolean solved, final long fixedOverheadMs,
                                        final long captureOverheadPerSuiteMs,
                                        final String skipReason) {
        this.solved = solved;
        this.fixedOverheadMs = fixedOverheadMs;
        this.captureOverheadPerSuiteMs = captureOverheadPerSuiteMs;
        this.skipReason = skipReason;
    }

    /**
     * Split a build's overhead into its fixed per-JVM part and its per-suite capture part, using
     * the whole-run figure as one equation and this build's groups as the other.
     *
     * <p>Skipped, rather than approximated, whenever the inputs cannot support the solve: no
     * measurable whole-run overhead, no tracked suites, no group that recorded a duration, a group
     * that ran suites without timing any of them (the same disqualification
     * {@link DistributedRunTotals} applies, since its whole duration would read as overhead), a
     * build whose groups average the full tracked suite count (the second equation is then the
     * first one again, and the pair is degenerate), or a solve that comes out negative - which
     * ordinary measurement noise can produce on a project whose overhead is small relative to the
     * spread between its runners.
     *
     * @param wholeRunOverheadMs the all-tests baseline minus the sum of every tracked suite's
     *                           average run time, in ms
     * @param trackedSuiteCount the number of tracked suites the whole-run figure covers
     * @param groups the run's groups, as read back from the datastore after the barrier
     * @param assignedSuiteCounts how many suites the plan assigned each group, keyed by group number
     * @return the solved model, or an unsolved one carrying the reason it was skipped
     */
    public static DistributedRunOverheadModel solve(final long wholeRunOverheadMs,
                                                    final int trackedSuiteCount,
                                                    final List<DistributedRunGroup> groups,
                                                    final Map<Integer, Integer> assignedSuiteCounts) {
        if (wholeRunOverheadMs <= 0L) {
            return skipped("the all-tests baseline does not exceed the sum of the tracked suite "
                    + "averages, so there is no whole-run overhead to decompose");
        }
        if (trackedSuiteCount <= 0) {
            return skipped("no suites are tracked, so the whole-run equation has no suite count");
        }

        long summedOverheadMs = 0L;
        long summedSuites = 0L;
        int measuredGroups = 0;

        for (DistributedRunGroup group : groups) {
            Long groupDurationMs = group.getActualDurationMs();
            if (groupDurationMs == null) {
                // Nothing measured for this group, so it supplies no equation. It cannot invalidate
                // the others either - it simply is not one of the samples.
                continue;
            }
            if (group.getSuitesRan() > 0 && group.getSuitesDurationMs() <= 0L) {
                return skipped("group " + group.getGroupNumber() + " ran " + group.getSuitesRan()
                        + " suite(s) but timed none of them, so its whole duration would read as "
                        + "overhead. Suite times are only recorded on a build that updates the mapping DB");
            }

            Integer assigned = assignedSuiteCounts.get(Integer.valueOf(group.getGroupNumber()));
            if (assigned == null) {
                return skipped("group " + group.getGroupNumber() + " has no recorded suite "
                        + "assignment, so its overhead cannot be attributed to a suite count");
            }

            summedOverheadMs += Math.max(0L, groupDurationMs.longValue() - group.getSuitesDurationMs());
            summedSuites += assigned.intValue();
            measuredGroups++;
        }

        if (measuredGroups == 0) {
            return skipped("no group recorded a duration, so the build supplies no second equation");
        }

        double meanOverheadMs = (double) summedOverheadMs / measuredGroups;
        double meanSuites = (double) summedSuites / measuredGroups;
        double suiteCountGap = trackedSuiteCount - meanSuites;

        if (suiteCountGap <= 0.0d) {
            return skipped("the build's groups averaged " + meanSuites + " of " + trackedSuiteCount
                    + " tracked suite(s), so the per-group equation is the whole-run equation again "
                    + "and the pair cannot be solved");
        }

        double captureMs = (wholeRunOverheadMs - meanOverheadMs) / suiteCountGap;
        double fixedMs = meanOverheadMs - (captureMs * meanSuites);

        if (captureMs < 0.0d || fixedMs < 0.0d) {
            return skipped("the solve came out negative (fixed " + Math.round(fixedMs)
                    + "ms, capture " + Math.round(captureMs) + "ms per suite), which measurement "
                    + "noise can produce when the spread between runners is large relative to the "
                    + "overhead being measured");
        }

        return new DistributedRunOverheadModel(true, Math.round(fixedMs), Math.round(captureMs),
                null);
    }

    /**
     * Build an unsolved model carrying the reason, so the caller has something to log rather than a
     * bare {@code false}.
     *
     * @param reason why the solve was abandoned
     * @return an unsolved model
     */
    private static DistributedRunOverheadModel skipped(final String reason) {
        return new DistributedRunOverheadModel(false, 0L, 0L, reason);
    }

    /** @return whether the equations yielded a usable pair of constants */
    public boolean isSolved() { return solved; }

    /** @return the per-JVM cost in ms that every group pays once; 0 when unsolved */
    public long getFixedOverheadMs() { return fixedOverheadMs; }

    /** @return the per-suite coverage-collection cost in ms; 0 when unsolved */
    public long getCaptureOverheadPerSuiteMs() { return captureOverheadPerSuiteMs; }

    /** @return why the solve was skipped, for logging; null when {@link #isSolved()} is true */
    public String getSkipReason() { return skipReason; }

    /**
     * Diagnostic rendering naming both constants, or the reason there are none.
     *
     * @return a short human-readable description
     */
    @Override
    public String toString() {
        return solved
                ? "DistributedRunOverheadModel{fixedMs=" + fixedOverheadMs + ", captureMsPerSuite="
                        + captureOverheadPerSuiteMs + "}"
                : "DistributedRunOverheadModel{unsolved: " + skipReason + "}";
    }
}
