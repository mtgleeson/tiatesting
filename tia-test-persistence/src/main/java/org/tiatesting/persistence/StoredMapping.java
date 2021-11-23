package org.tiatesting.persistence;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class StoredMapping implements Serializable {
    private static final long serialVersionUID = 1L;

    private String commitValue;
    private Map<String, Set<String>> testMethodsCalled = new HashMap<>();

    public String getCommitValue() {
        return commitValue;
    }

    public void setCommitValue(String commitValue) {
        this.commitValue = commitValue;
    }

    public Map<String, Set<String>> getTestMethodsCalled() {
        return testMethodsCalled;
    }

    public void setTestMethodsCalled(Map<String, Set<String>> testMethodsCalled) {
        this.testMethodsCalled = testMethodsCalled;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StoredMapping that = (StoredMapping) o;
        return commitValue.equals(that.commitValue) && testMethodsCalled.equals(that.testMethodsCalled);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commitValue, testMethodsCalled);
    }
}
