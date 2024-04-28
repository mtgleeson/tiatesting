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
     * Increment the stats of this tracked test suite by the specified amounts.
     *
     * @param testStats the test statistics
     */
    public void incrementStats(final TestStats testStats){
        long totalRunTime = (this.numRuns * this.avgRunTime) + (testStats.getNumRuns() * testStats.getAvgRunTime());
        this.numRuns += testStats.getNumRuns();
        this.avgRunTime = totalRunTime / this.numRuns;
        this.numSuccessRuns += testStats.getNumSuccessRuns();
        this.numFailRuns += testStats.getNumFailRuns();
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

    @Override
    public String toString() {
        return "TestStats{" +
                "numRuns=" + numRuns +
                ", avgRunTime=" + avgRunTime +
                ", numSuccessRuns=" + numSuccessRuns +
                ", numFailRuns=" + numFailRuns +
                '}';
    }
}
