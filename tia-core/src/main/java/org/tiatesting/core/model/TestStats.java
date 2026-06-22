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

    public void setNumAllTestsRuns(long numAllTestsRuns) {
        this.numAllTestsRuns = numAllTestsRuns;
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
                '}';
    }
}
