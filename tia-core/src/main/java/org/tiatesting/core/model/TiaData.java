package org.tiatesting.core.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.*;

public class TiaData implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * The version of project used to generate the saved class/method impact analysis for the test stuites.
     */
    private String commitValue;

    /**
     * The date and time the stored mapping was last updated.
     */
    private Instant lastUpdated;

    /**
     * The saved mapping of impacted classes/methods for each test suite.
     */
    private Map<String, TestSuiteTracker> testSuitesTracked = new HashMap<>();

    /**
     * The set of test suites that failed on the previous run. These tests will be forced to re-run in the next
     * (official) selective test run where the mapping is being updated.
     */
    private Set<String> testSuitesFailed = new HashSet<>();

    /**
     * The set of source code methods that are tracked in the DB due to having been part of the test suite coverage.
     * We store the methods keyed by the method name hashcode as an index for storage optimization.
     * i.e. rather than store the method details for every class tracked, we stored the method hashcode only.
     * We can use the method hashcode to look up the method details from this map.
     */
    private Map<Integer, MethodImpactTracker> methodsTracked = new HashMap<>();

    private TestStats testStats = new TestStats();

    /**
     * Increment the stats for Tia.
     *
     * @param testStats the test statistics
     */
    public void incrementStats(final TestStats testStats){
        this.testStats.incrementStats(testStats);
    }

    public String getCommitValue() {
        return commitValue;
    }

    public void setCommitValue(String commitValue) {
        this.commitValue = commitValue;
    }

    public Map<String, TestSuiteTracker> getTestSuitesTracked() {
        return testSuitesTracked;
    }

    public void setTestSuitesTracked(Map<String, TestSuiteTracker> testSuitesTracked) {
        this.testSuitesTracked = testSuitesTracked;
    }

    public Set<String> getTestSuitesFailed() {
        return testSuitesFailed;
    }

    public void setTestSuitesFailed(Set<String> testSuitesFailed) {
        this.testSuitesFailed = testSuitesFailed;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Instant lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public Map<Integer, MethodImpactTracker> getMethodsTracked() {
        return methodsTracked;
    }

    public void setMethodsTracked(Map<Integer, MethodImpactTracker> methodsTracked) {
        this.methodsTracked = methodsTracked;
    }

    public TestStats getTestStats() {
        return testStats;
    }

    public void setTestStats(TestStats testStats) {
        this.testStats = testStats;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TiaData that = (TiaData) o;
        return Objects.equals(commitValue, that.commitValue) && Objects.equals(lastUpdated, that.lastUpdated);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commitValue, lastUpdated);
    }
}
