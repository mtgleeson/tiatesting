package org.tiatesting.core.model;

import org.tiatesting.core.sourcefile.FileExtensions;

import java.io.Serializable;
import java.util.Objects;
import java.util.Set;

/**
 * Object used to track data about the code source file that is executed by a test suite.
 * Class refers to the project source code, not the test suite source code.
 *
 * A test suite could execute multiple methods within the same source class.
 */
public class ClassImpactTracker implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * The name of the source file associated with the class.
     */
    private String sourceFilename;

    /**
     * Set of methods that were invoked for the class as part of running a test suite.
     * This set contains the unique hashcode associated with the method.
     */
    private Set<Integer> methodsImpacted;

    public ClassImpactTracker(String sourceFilename, Set<Integer> methodsImpacted) {
        this.sourceFilename = sourceFilename;
        this.methodsImpacted = methodsImpacted;
    }

    public String getSourceFilename() {
        return sourceFilename;
    }

    public String getSourceFilenameForDisplay() {
        return sourceFilename.replaceAll("/", ".")
                .replaceAll("." + FileExtensions.JAVA_FILE_EXT, "")
                .replaceAll("." + FileExtensions.GROOVY_FILE_EXT, "");
    }

    public Set<Integer> getMethodsImpacted() {
        return methodsImpacted;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClassImpactTracker that = (ClassImpactTracker) o;
        return sourceFilename.equals(that.sourceFilename) && methodsImpacted.equals(that.methodsImpacted);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceFilename, methodsImpacted);
    }

    @Override
    public String toString() {
        return "ClassImpactTracker{" +
                "sourceFilename='" + sourceFilename + '\'' +
                ", methodsImpacted=" + methodsImpacted +
                '}';
    }
}
