package org.tiatesting.core.model;

/**
 * One run's contribution to the Tia-level stats on the core row, expressed as a delta rather than
 * as the already-merged absolute values.
 *
 * <p><b>Why a delta.</b> A run reads the core row at the start of its persist and writes at the end,
 * with the whole mapping persist in between. Merging the new figures into that snapshot in memory
 * and writing back absolutes makes the update a read-modify-write across that entire window: a
 * second run that commits inside it has its increment silently overwritten. Handing the store a
 * delta lets it accumulate in SQL, against the row's current values at write time, so the window
 * closes. See the "Persist flow and crash safety" chapter in {@code WIKI.md}.
 *
 * <p>{@link #getNumRuns()} of zero means "nothing to add" - the state a Surefire retry produces,
 * since a retry must not be counted as another run. Stores skip the stats columns entirely in that
 * case rather than writing a no-op arithmetic update.
 */
public final class CoreStatsIncrement {

    private final long numRuns;
    private final long runTimeMs;
    private final long numSuccessRuns;
    private final long numFailRuns;
    private final boolean allTestsRun;
    private final Long fixedOverheadMs;
    private final Long captureOverheadPerSuiteMs;

    private CoreStatsIncrement(final long numRuns, final long runTimeMs, final long numSuccessRuns,
                               final long numFailRuns, final boolean allTestsRun,
                               final Long fixedOverheadMs, final Long captureOverheadPerSuiteMs) {
        this.numRuns = numRuns;
        this.runTimeMs = runTimeMs;
        this.numSuccessRuns = numSuccessRuns;
        this.numFailRuns = numFailRuns;
        this.allTestsRun = allTestsRun;
        this.fixedOverheadMs = fixedOverheadMs;
        this.captureOverheadPerSuiteMs = captureOverheadPerSuiteMs;
    }

    /**
     * Build the increment a run contributes, from the stats it measured.
     *
     * @param runStats the run's own measured stats; {@code null}, or a zero run count, means the
     *                 run contributes nothing
     * @param allTestsRun true when the run ignored zero suites, which routes its duration into the
     *                    all-tests average rather than the selected-run average
     * @return the increment to hand to the seal
     */
    public static CoreStatsIncrement of(final TestStats runStats, final boolean allTestsRun) {
        if (runStats == null) {
            return none();
        }
        return new CoreStatsIncrement(runStats.getNumRuns(), runStats.getAvgRunTime(),
                runStats.getNumSuccessRuns(), runStats.getNumFailRuns(), allTestsRun, null, null);
    }

    /**
     * An increment that contributes nothing, for a seal with no stats to record.
     *
     * @return an empty increment
     */
    public static CoreStatsIncrement none() {
        return new CoreStatsIncrement(0L, 0L, 0L, 0L, false, null, null);
    }

    /**
     * Add one distributed build's solved overhead constants to this increment. Only a distributed
     * build can measure them, and only when the decomposition succeeded, so they are attached
     * separately rather than being part of every increment.
     *
     * @param solvedFixedOverheadMs the per-JVM overhead this build measured, in ms
     * @param solvedCaptureOverheadPerSuiteMs the per-suite capture overhead this build measured, in ms
     * @return a copy of this increment carrying the overhead pair
     */
    public CoreStatsIncrement withOverheadModel(final long solvedFixedOverheadMs,
                                                final long solvedCaptureOverheadPerSuiteMs) {
        return new CoreStatsIncrement(numRuns, runTimeMs, numSuccessRuns, numFailRuns, allTestsRun,
                Long.valueOf(solvedFixedOverheadMs), Long.valueOf(solvedCaptureOverheadPerSuiteMs));
    }

    /** @return the number of runs to add; {@code 0} means the run contributes no stats at all */
    public long getNumRuns() { return numRuns; }

    /** @return this run's duration in ms, folded into whichever average {@link #isAllTestsRun()} selects */
    public long getRunTimeMs() { return runTimeMs; }

    /** @return the number of successful runs to add */
    public long getNumSuccessRuns() { return numSuccessRuns; }

    /** @return the number of failed runs to add */
    public long getNumFailRuns() { return numFailRuns; }

    /**
     * @return true when the run ignored zero suites, so its duration belongs in the all-tests
     *         average - the baseline savings are measured against - rather than the selected-run one
     */
    public boolean isAllTestsRun() { return allTestsRun; }

    /** @return true when this increment carries a solved overhead pair to fold in */
    public boolean hasOverheadModel() { return fixedOverheadMs != null && captureOverheadPerSuiteMs != null; }

    /** @return the per-JVM overhead to fold in; only meaningful when {@link #hasOverheadModel()} */
    public long getFixedOverheadMs() { return fixedOverheadMs.longValue(); }

    /** @return the per-suite capture overhead to fold in; only meaningful when {@link #hasOverheadModel()} */
    public long getCaptureOverheadPerSuiteMs() { return captureOverheadPerSuiteMs.longValue(); }
}
