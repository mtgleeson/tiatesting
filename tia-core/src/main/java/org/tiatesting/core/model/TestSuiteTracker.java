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
     * Whether this suite is currently disabled by the developer (e.g. {@code @Disabled} /
     * {@code @Ignore} in source) rather than ignored by Tia's selection. Maintained from the test
     * run and persisted so the selector can exclude these suites from the Tia-ignored count.
     */
    private boolean developerDisabled;

    public TestSuiteTracker(){}

    public TestSuiteTracker(String name) {
        this.name = name;
    }

    /**
     * Increment the stats of this tracked test suite by the specified amounts. Per-suite stats
     * always use the selected-run average; the all-tests-run regime is Tia-level only, so this
     * passes {@code allTestsRun = false}.
     *
     * @param testStats the test statistics
     */
    public void incrementStats(final TestStats testStats){
        this.testStats.incrementStats(testStats, false);
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

    /**
     * @return {@code true} when the developer has disabled this suite in source (so it would not
     *         run even if Tia selected it), {@code false} otherwise.
     */
    public boolean isDeveloperDisabled() {
        return developerDisabled;
    }

    /**
     * Set whether this suite is currently developer-disabled.
     *
     * @param developerDisabled {@code true} if the developer has disabled the suite in source
     */
    public void setDeveloperDisabled(boolean developerDisabled) {
        this.developerDisabled = developerDisabled;
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
                ", developerDisabled=" + developerDisabled +
                '}';
    }
}
