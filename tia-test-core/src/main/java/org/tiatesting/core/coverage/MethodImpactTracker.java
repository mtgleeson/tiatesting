package org.tiatesting.core.coverage;

import java.io.Serializable;
import java.util.Objects;

public class MethodImpactTracker implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String methodName;
    private final int lineNumberStart;
    private final int lineNumberEnd;

    public MethodImpactTracker(String methodName, int lineNumberStart, int lineNumberEnd) {
        this.methodName = methodName;
        this.lineNumberStart = lineNumberStart;
        this.lineNumberEnd = lineNumberEnd;
    }

    public String getMethodName() {
        return methodName;
    }

    public int getLineNumberStart() {
        return lineNumberStart;
    }

    public int getLineNumberEnd() {
        return lineNumberEnd;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MethodImpactTracker that = (MethodImpactTracker) o;
        return lineNumberStart == that.lineNumberStart && lineNumberEnd == that.lineNumberEnd && methodName.equals(that.methodName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(methodName, lineNumberStart, lineNumberEnd);
    }
}
