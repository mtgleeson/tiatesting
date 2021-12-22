package org.tiatesting.persistence;

import org.tiatesting.core.coverage.ClassImpactTracker;

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
    private Map<String, List<ClassImpactTracker>> classesImpacted = new HashMap<>();

    public String getCommitValue() {
        return commitValue;
    }

    public void setCommitValue(String commitValue) {
        this.commitValue = commitValue;
    }

    public Map<String, List<ClassImpactTracker>> getClassesImpacted() {
        return classesImpacted;
    }

    public void setClassesImpacted(Map<String, List<ClassImpactTracker>> classesImpacted) {
        this.classesImpacted = classesImpacted;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StoredMapping that = (StoredMapping) o;
        return commitValue.equals(that.commitValue) && classesImpacted.equals(that.classesImpacted);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commitValue, classesImpacted);
    }
}
