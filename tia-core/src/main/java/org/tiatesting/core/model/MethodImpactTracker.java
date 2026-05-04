package org.tiatesting.core.model;

import org.tiatesting.core.sourcefile.FileExtensions;

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

    public String getNameForDisplay() {
        return methodName.replaceAll("/", ".");
    }

    /**
     * Display name with the parameter list and return descriptor stripped — everything from the
     * first {@code (} onwards is removed, along with the trailing {@code .} that separates the
     * method name from the signature. Used in the HTML report to keep table cells and headings
     * narrow; the full signature is exposed as a {@code title} tooltip on hover.
     *
     * @return 1st part of the method name, with parameter list and return descriptor stripped.
     */
    public String getShortNameForDisplay() {
        String full = getNameForDisplay();
        int paren = full.indexOf('(');
        if (paren < 0) {
            return full;
        }
        String head = full.substring(0, paren);
        if (head.endsWith(".")) {
            head = head.substring(0, head.length() - 1);
        }
        return head;
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
