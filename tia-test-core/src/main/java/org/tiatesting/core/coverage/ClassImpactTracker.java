package org.tiatesting.core.coverage;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

public class ClassImpactTracker implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * The name of the class that was invoked when running and test suite.
     * The format should. be the fully qualified name using slashes instead of dots. i.e. com/example/DoorService
     */
    private String className;

    /**
     * The name of the source file associated with the class.
     */
    private String sourceFilename;

    /**
     * Set of methods that were invoked for the class as part of running a test suite.
     */
    private List<MethodImpactTracker> methodsImpacted;

    public ClassImpactTracker(String className, String sourceFilename, List<MethodImpactTracker> methodsImpacted) {
        this.className = className;
        this.sourceFilename = sourceFilename;
        this.methodsImpacted = methodsImpacted;
    }

    public String getClassName() {
        return className;
    }

    public String getSourceFilename() {
        return sourceFilename;
    }

    public List<MethodImpactTracker> getMethodsImpacted() {
        return methodsImpacted;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClassImpactTracker that = (ClassImpactTracker) o;
        return className.equals(that.className) && sourceFilename.equals(that.sourceFilename) && methodsImpacted.equals(that.methodsImpacted);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, sourceFilename, methodsImpacted);
    }
}
