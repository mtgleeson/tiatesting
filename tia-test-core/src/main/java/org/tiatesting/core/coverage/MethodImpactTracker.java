package org.tiatesting.core.coverage;

import java.io.Serializable;
import java.util.Objects;

public class MethodImpactTracker implements Serializable {
    private static final long serialVersionUID = 1L;

    private String methodName;
    private int methodLineNumber;

    public MethodImpactTracker(String methodName, int methodLineNumber) {
        this.methodName = methodName;
        this.methodLineNumber = methodLineNumber;
    }

    public String getMethodName() {
        return methodName;
    }

    public int getMethodLineNumber() {
        return methodLineNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MethodImpactTracker that = (MethodImpactTracker) o;
        return methodLineNumber == that.methodLineNumber && methodName.equals(that.methodName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(methodName, methodLineNumber);
    }
}
