package org.tiatesting.core.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Object used to track data about a test suite used by Tia.
 */
public class TestSuiteTracker implements Serializable {
    private static final long serialVersionUID = 1L;

    public TestSuiteTracker(){}

    public TestSuiteTracker(String name) {
        this.name = name;
    }

    private long id;

    /**
     * Name of the test suite being tracked.
     */
    private String name;

    /**
     * The saved mapping of impacted classes/methods for each test suite.
     */
    private List<ClassImpactTracker> classesImpacted = new ArrayList<>();

    private TestStats testStats = new TestStats();

    /**
     * Increment the stats of this tracked test suite by the specified amounts.
     *
     * @param testStats
     */
    public void incrementStats(final TestStats testStats){
        this.testStats.incrementStats(testStats);
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<ClassImpactTracker> getClassesImpacted() {
        return classesImpacted;
    }

    public void setClassesImpacted(List<ClassImpactTracker> classesImpacted) {
        this.classesImpacted = classesImpacted;
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
        TestSuiteTracker that = (TestSuiteTracker) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "TestSuiteTracker{" +
                "name='" + name + '\'' +
                ", classesImpacted=" + classesImpacted +
                ", testStats=" + testStats +
                '}';
    }
}
