package org.tiatesting.core.coverage;

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

    /**
     * Name of the test suite being tracked.
     */
    private String name;

    /**
     * The name of the source file associated with the test suite class.
     * Note: the format is package/filename. It does not include the full path from the root of the filesystem.
     * For example: com/example/MyTestSuite.java
     */
    private String sourceFilename;

    /**
     * The saved mapping of impacted classes/methods for each test suite.
     */
    private List<ClassImpactTracker> classesImpacted = new ArrayList<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestSuiteTracker that = (TestSuiteTracker) o;
        return Objects.equals(sourceFilename, that.sourceFilename);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceFilename);
    }

    public String getSourceFilename() {
        return sourceFilename;
    }

    public void setSourceFilename(String sourceFilename) {
        this.sourceFilename = sourceFilename;
    }

    public List<ClassImpactTracker> getClassesImpacted() {
        return classesImpacted;
    }

    public void setClassesImpacted(List<ClassImpactTracker> classesImpacted) {
        this.classesImpacted = classesImpacted;
    }
}
