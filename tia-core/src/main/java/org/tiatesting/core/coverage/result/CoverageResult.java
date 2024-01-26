package org.tiatesting.core.coverage.result;

import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;

import java.util.*;

public class CoverageResult {

    /**
     * This list tracks the list of classes with only the subset of its methods that were invoked.
     * This list is used for updating the stored mapping.
     */
    List<ClassImpactTracker> classesInvoked = new ArrayList<>();

    /**
     * This list tracks the list of classes that were invoked and all its methods regardless of whether they were
     * invoked. This list is used to update the method start and end line numbers for all methods that were associated
     * with an invoked class.
     * i.e. when one method in a class is involved by a test, it will most likely have an impact on the start and end
     * numbers of the other methods in the class (i.e. lines were added to the source code).
     * So we need to update the line numbers for all methods in the class afer each test run to keep them in sync
     * with the code source changes.
     */
    Map<Integer, MethodImpactTracker> allMethodsClassesInvoked = new HashMap<>();

    public List<ClassImpactTracker> getClassesInvoked() {
        return classesInvoked;
    }

    public void setClassesInvoked(List<ClassImpactTracker> classesInvoked) {
        this.classesInvoked = classesInvoked;
    }

    public Map<Integer, MethodImpactTracker> getAllMethodsClassesInvoked() {
        return allMethodsClassesInvoked;
    }

    public void setAllMethodsClassesInvoked(Map<Integer, MethodImpactTracker> allMethodsClassesInvoked) {
        this.allMethodsClassesInvoked = allMethodsClassesInvoked;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CoverageResult that = (CoverageResult) o;
        return Objects.equals(classesInvoked, that.classesInvoked);
    }

    @Override
    public int hashCode() {
        return Objects.hash(classesInvoked);
    }
}
