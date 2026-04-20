package org.tiatesting.core.diff.diffanalyze.selector;

import org.tiatesting.core.library.LibraryImpactDrainResult;

import java.util.Objects;
import java.util.Set;

public class TestSelectorResult {

    private Set<String> testsToRun;

    private Set<String> testsToIgnore;

    private LibraryImpactDrainResult libraryImpactDrainResult;

    public TestSelectorResult(Set<String> testsToRun, Set<String> testsToIgnore) {
        this.testsToRun = testsToRun;
        this.testsToIgnore = testsToIgnore;
    }

    public TestSelectorResult(Set<String> testsToRun, Set<String> testsToIgnore,
                               LibraryImpactDrainResult libraryImpactDrainResult) {
        this.testsToRun = testsToRun;
        this.testsToIgnore = testsToIgnore;
        this.libraryImpactDrainResult = libraryImpactDrainResult;
    }

    public Set<String> getTestsToRun() {
        return testsToRun;
    }

    public Set<String> getTestsToIgnore() {
        return testsToIgnore;
    }

    public LibraryImpactDrainResult getLibraryImpactDrainResult() {
        return libraryImpactDrainResult;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestSelectorResult that = (TestSelectorResult) o;
        return Objects.equals(testsToRun, that.testsToRun) && Objects.equals(testsToIgnore, that.testsToIgnore);
    }

    @Override
    public int hashCode() {
        return Objects.hash(testsToRun, testsToIgnore);
    }
}
