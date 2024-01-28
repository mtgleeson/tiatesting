package org.tiatesting.core.model;

import java.io.Serializable;
import java.util.Objects;

public class MethodImpactTracker implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * This is the full package.class name + method name + method signature.
     * For example: com/example/HandleService.getHandleModel.(Ljava/lang/Long;)Ljava/lang/String
     */
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
        return Objects.equals(methodName, that.methodName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(methodName);
    }
}
