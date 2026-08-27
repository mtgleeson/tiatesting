package org.tiatesting.core.model;

import java.io.Serializable;

public class TestStats implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * The number if times this test suite was run.
     */
    private long numRuns;

    /**
     * The average amount of time in ms to execute this test suite.
     */
    private long avgRunTime;

    /**
     * The total number of successful runs.
     */
    private long numSuccessRuns;

    /**
     * The total number of failed runs.
     */
    private long numFailRuns;

    /**
     * The average amount of time in ms to run all tests (runs where Tia ignored zero suites:
     * the seed run or a run where every suite was selected). Kept separate from {@link #avgRunTime}
     * so the selected-run average isn't polluted by full-suite runs. Tia-level only; stays 0 for
     * per-suite stats.
     */
    private long allTestsRunTime;

    /**
     * The number of all-tests runs folded into {@link #allTestsRunTime}. Tia-level only; stays 0
     * for per-suite stats.
     */
    private long numAllTestsRuns;

    /**
     * The average per-JVM overhead in ms a test run pays once, no matter how few suites it runs -
     * engine start-up, class loading, the final coverage dump. Only a distributed run can measure
     * it, since separating it from {@link #captureOverheadPerSuiteMs} needs two runs of the same
     * suites at different suite counts; see {@code DistributedRunOverheadModel}. Tia-level only;
     * stays 0 for per-suite stats, and 0 for a project that has never distributed a build, which is
     * the signal to fall back to the single-number overhead estimate.
     */
    private long fixedOverheadMs;

    /**
     * The average per-suite overhead in ms beyond the suite's own execution time - JaCoCo's
     * per-suite coverage collection, which is excluded from {@link #avgRunTime} because the
     * listeners freeze a suite's run time before coverage is collected. The other half of the pair
     * {@link #fixedOverheadMs} belongs to. Tia-level only; stays 0 for per-suite stats.
     */
    private long captureOverheadPerSuiteMs;

    /**
     * The number of distributed builds whose measurements were folded into {@link #fixedOverheadMs}
     * and {@link #captureOverheadPerSuiteMs}. One counter for both because the pair is always solved
     * together from one build's group rows, so they can never have contributed different run counts.
     */
    private long numOverheadMeasurements;

    /**
     * Increment the stats by the specified amounts, routing the incoming run's duration into the
     * matching rolling average so the selected-run and all-tests-run averages stay independent.
     *
     * <p>The incoming duration is carried in {@code testStats.getAvgRunTime()} with
     * {@code numRuns == 1} (or {@code 0} on a Surefire retry, which is a no-op).
     *
     * @param testStats   the test statistics being added
     * @param allTestsRun {@code true} when Tia ignored zero suites this run (fold into
     *                    {@link #allTestsRunTime} / {@link #numAllTestsRuns}); {@code false} for a
     *                    Tia-selected run (fold into {@link #avgRunTime})
     */
    public void incrementStats(final TestStats testStats, final boolean allTestsRun){
        // only increment stats if there was a test run
        if (testStats.getNumRuns() > 0){
            if (allTestsRun){
                long totalAllTestsRunTime = (this.numAllTestsRuns * this.allTestsRunTime)
                        + (testStats.getNumRuns() * testStats.getAvgRunTime());
                this.numAllTestsRuns += testStats.getNumRuns();
                this.allTestsRunTime = totalAllTestsRunTime / this.numAllTestsRuns;
            } else {
                // The selected-run average is over the selected sub-count (total runs minus
                // all-tests runs), computed before numRuns is bumped below.
                long selectedRuns = this.numRuns - this.numAllTestsRuns;
                long totalSelectedRunTime = (selectedRuns * this.avgRunTime)
                        + (testStats.getNumRuns() * testStats.getAvgRunTime());
                this.avgRunTime = totalSelectedRunTime / (selectedRuns + testStats.getNumRuns());
            }

            this.numRuns += testStats.getNumRuns();
            this.numSuccessRuns += testStats.getNumSuccessRuns();
            this.numFailRuns += testStats.getNumFailRuns();
        }
    }

    /**
     * Fold one distributed build's solved overhead constants into their rolling averages, in the
     * same shape {@link #incrementStats} uses for the run-time averages.
     *
     * <p>Only called with a solved pair. A build whose measurements could not be decomposed leaves
     * the stored averages untouched rather than contributing a zero, which would drag both
     * constants down towards nothing and quietly undo every earlier measurement - the failure mode
     * that matters here, since the two are consumed as constants rather than as a trend.
     *
     * @param fixedOverheadMs the per-JVM overhead this build measured, in ms
     * @param captureOverheadPerSuiteMs the per-suite capture overhead this build measured, in ms
     */
    public void incrementOverheadModel(final long fixedOverheadMs,
                                       final long captureOverheadPerSuiteMs){
        long totalFixed = (this.numOverheadMeasurements * this.fixedOverheadMs) + fixedOverheadMs;
        long totalCapture = (this.numOverheadMeasurements * this.captureOverheadPerSuiteMs)
                + captureOverheadPerSuiteMs;
        this.numOverheadMeasurements++;
        this.fixedOverheadMs = totalFixed / this.numOverheadMeasurements;
        this.captureOverheadPerSuiteMs = totalCapture / this.numOverheadMeasurements;
    }

    public long getNumRuns() {
        return numRuns;
    }

    public void setNumRuns(long numRuns) {
        this.numRuns = numRuns;
    }

    public long getAvgRunTime() {
        return avgRunTime;
    }

    public void setAvgRunTime(long avgRunTime) {
        this.avgRunTime = avgRunTime;
    }

    public long getNumSuccessRuns() {
        return numSuccessRuns;
    }

    public void setNumSuccessRuns(long numSuccessRuns) {
        this.numSuccessRuns = numSuccessRuns;
    }

    public long getNumFailRuns() {
        return numFailRuns;
    }

    public void setNumFailRuns(long numFailRuns) {
        this.numFailRuns = numFailRuns;
    }

    public long getAllTestsRunTime() {
        return allTestsRunTime;
    }

    public void setAllTestsRunTime(long allTestsRunTime) {
        this.allTestsRunTime = allTestsRunTime;
    }

    public long getNumAllTestsRuns() {
        return numAllTestsRuns;
    }

    /**
     * The number of partial (Tia-selected) runs: the total run count minus the all-tests runs.
     * These are the runs that fed {@link #avgRunTime}, so the two are reported together.
     *
     * @return {@code numRuns - numAllTestsRuns}
     */
    public long getNumPartialRuns() {
        return numRuns - numAllTestsRuns;
    }

    public void setNumAllTestsRuns(long numAllTestsRuns) {
        this.numAllTestsRuns = numAllTestsRuns;
    }

    public long getFixedOverheadMs() {
        return fixedOverheadMs;
    }

    public void setFixedOverheadMs(long fixedOverheadMs) {
        this.fixedOverheadMs = fixedOverheadMs;
    }

    public long getCaptureOverheadPerSuiteMs() {
        return captureOverheadPerSuiteMs;
    }

    public void setCaptureOverheadPerSuiteMs(long captureOverheadPerSuiteMs) {
        this.captureOverheadPerSuiteMs = captureOverheadPerSuiteMs;
    }

    public long getNumOverheadMeasurements() {
        return numOverheadMeasurements;
    }

    public void setNumOverheadMeasurements(long numOverheadMeasurements) {
        this.numOverheadMeasurements = numOverheadMeasurements;
    }

    @Override
    public String toString() {
        return "TestStats{" +
                "numRuns=" + numRuns +
                ", avgRunTime=" + avgRunTime +
                ", numSuccessRuns=" + numSuccessRuns +
                ", numFailRuns=" + numFailRuns +
                ", allTestsRunTime=" + allTestsRunTime +
                ", numAllTestsRuns=" + numAllTestsRuns +
                ", fixedOverheadMs=" + fixedOverheadMs +
                ", captureOverheadPerSuiteMs=" + captureOverheadPerSuiteMs +
                ", numOverheadMeasurements=" + numOverheadMeasurements +
                '}';
    }
}
