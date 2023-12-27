package org.tiatesting.persistence;

import org.tiatesting.core.coverage.ClassImpactTracker;
import org.tiatesting.core.coverage.TestSuiteTracker;

import java.io.Serializable;
import java.util.*;

public class StoredMapping implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * The version of project used to generate the saved class/method impact analysis for the test stuites.
     */
    private String commitValue;

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
     * THe date and time the stored mapping was last updated.
     */
    private Date lastUpdated;

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

    public Date getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Date lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StoredMapping that = (StoredMapping) o;
        return commitValue.equals(that.commitValue) && testSuitesTracked.equals(that.testSuitesTracked)
                && testSuitesFailed.equals(that.testSuitesFailed) && lastUpdated.equals(that.lastUpdated);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commitValue, testSuitesTracked, testSuitesFailed, lastUpdated);
    }
}
