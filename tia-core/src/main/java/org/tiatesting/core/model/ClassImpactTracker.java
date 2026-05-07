package org.tiatesting.core.model;

import org.tiatesting.core.sourcefile.FileExtensions;

import java.io.Serializable;
import java.util.Collection;
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
     * This set contains the unique hashcode associated with the method. Backed by a
     * primitive-int storage to keep heap and GC pressure low on large databases —
     * {@link MethodIdSet} still implements {@link Set Set&lt;Integer&gt;} so all existing
     * callers see the same API.
     */
    private MethodIdSet methodsImpacted;

    public ClassImpactTracker(String sourceFilename, MethodIdSet methodsImpacted) {
        this.sourceFilename = sourceFilename;
        this.methodsImpacted = methodsImpacted;
    }

    /**
     * Backwards-compatible constructor accepting any {@code Collection<Integer>}. If the
     * supplied collection is already a {@link MethodIdSet} it is adopted directly (preserving
     * reference semantics for callers that mutate it after construction); otherwise its
     * contents are copied into a fresh {@link MethodIdSet}.
     *
     * @param sourceFilename the source-file path for the class.
     * @param methodsImpacted the set of method IDs invoked by the test suite for this class.
     */
    public ClassImpactTracker(String sourceFilename, Collection<Integer> methodsImpacted) {
        this.sourceFilename = sourceFilename;
        this.methodsImpacted = methodsImpacted instanceof MethodIdSet
                ? (MethodIdSet) methodsImpacted
                : new MethodIdSet(methodsImpacted);
    }

    public String getSourceFilename() {
        return sourceFilename;
    }

    public String getSourceFilenameForDisplay() {
        return sourceFilename.replaceAll("/", ".")
                .replaceAll("." + FileExtensions.JAVA_FILE_EXT, "")
                .replaceAll("." + FileExtensions.GROOVY_FILE_EXT, "");
    }

    public MethodIdSet getMethodsImpacted() {
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
