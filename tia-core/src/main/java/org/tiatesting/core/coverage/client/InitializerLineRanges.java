package org.tiatesting.core.coverage.client;

import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.IMethodCoverage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Works out the exact source lines that belong to a constructor ({@code <init>}) or static
 * initializer ({@code <clinit>}).
 * <p>
 * The compiler folds every field initializer (and initializer block) into these methods, so JaCoCo
 * reports their first/last line as the lowest/highest initializer line. A field declared after other
 * methods therefore stretches the initializer's range over those methods, and a change to any of
 * them would wrongly be seen as a change to the initializer. This class removes the other members'
 * lines from the initializer's range, keeping the lines between members (where a new field could be
 * added) and the lines the initializer itself has code on.
 * <p>
 * See the "Constructor and static initializer line ranges" chapter in {@code WIKI.md}.
 */
final class InitializerLineRanges {

    private static final String CONSTRUCTOR_NAME = "<init>";
    private static final String STATIC_INITIALIZER_NAME = "<clinit>";

    private InitializerLineRanges() {
    }

    /**
     * Check whether a JVM method name is a constructor or static initializer - the methods the
     * compiler folds field initializers into.
     *
     * @param methodName the JVM method name (e.g. {@code <init>})
     * @return true for {@code <init>} and {@code <clinit>}
     */
    static boolean isInitializer(String methodName) {
        return CONSTRUCTOR_NAME.equals(methodName) || STATIC_INITIALIZER_NAME.equals(methodName);
    }

    /**
     * Compute the exact line ranges for an initializer method.
     * <p>
     * Starts from the initializer's range padded by one line either side (matching the allowance the
     * diff matcher gives a method's signature and closing brace), then removes:
     * <ul>
     *     <li>the signature line and body of every non-initializer method in the owning class, and of
     *     every method of the other classes compiled from the same source file (nested, inner,
     *     anonymous);</li>
     *     <li>the code lines of the owning class's other initializers that this initializer does not
     *     share - i.e. the other constructors' bodies, while the field initializer lines they all
     *     share are kept.</li>
     * </ul>
     * The initializer's own code lines are then added back, so a member that sits on the same line
     * as a field initializer (e.g. a lambda) can't remove it.
     * <p>
     * A method's line after its last code line is deliberately not removed: for a void method that
     * line is the blank line after its closing brace, and with one blank line between members it is
     * the only line a new field inserted between two methods can be matched against.
     *
     * @param initializer the constructor or static initializer to compute ranges for
     * @param ownerClassMethods all methods of the class declaring the initializer (the initializer
     *                          itself is skipped)
     * @param otherClassesMethods all methods of the other classes compiled from the same source file
     * @return flat inclusive {@code [start, end, ...]} pairs in ascending order, or {@code null} when
     *         the initializer has no line info or no line between its first and last line was
     *         removed (so plain padded start-end matching already fits it)
     */
    static int[] compute(IMethodCoverage initializer,
                         Collection<IMethodCoverage> ownerClassMethods,
                         Collection<IMethodCoverage> otherClassesMethods) {
        int firstLine = initializer.getFirstLine();
        int lastLine = initializer.getLastLine();
        if (firstLine < 0 || lastLine < 0) {
            return null;
        }

        int rangeStart = firstLine - 1;
        int rangeEnd = lastLine + 1;
        boolean[] included = new boolean[rangeEnd - rangeStart + 1];
        Arrays.fill(included, true);

        for (IMethodCoverage other : ownerClassMethods) {
            if (other == initializer) {
                continue;
            }
            if (isInitializer(other.getName())) {
                excludeUnsharedCodeLines(included, rangeStart, initializer, other);
            } else {
                excludeSignatureAndBody(included, rangeStart, other);
            }
        }
        for (IMethodCoverage other : otherClassesMethods) {
            excludeSignatureAndBody(included, rangeStart, other);
        }

        for (int line = firstLine; line <= lastLine; line++) {
            if (hasCode(initializer, line)) {
                included[line - rangeStart] = true;
            }
        }

        if (allIncluded(included, firstLine - rangeStart, lastLine - rangeStart)) {
            return null;
        }
        return toRanges(included, rangeStart);
    }

    /**
     * Check whether every line in an index range is still included.
     *
     * @param included per-line inclusion flags
     * @param fromIndex the first index to check (inclusive)
     * @param toIndex the last index to check (inclusive)
     * @return true when no line in the range was removed
     */
    private static boolean allIncluded(boolean[] included, int fromIndex, int toIndex) {
        for (int i = fromIndex; i <= toIndex; i++) {
            if (!included[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Remove another method's signature line (the line before its first code line) and its body
     * (first to last code line) from the included lines.
     *
     * @param included per-line inclusion flags, indexed from {@code rangeStart}
     * @param rangeStart the source line held at index 0 of {@code included}
     * @param other the method whose lines are removed
     */
    private static void excludeSignatureAndBody(boolean[] included, int rangeStart, IMethodCoverage other) {
        if (other.getFirstLine() < 0 || other.getLastLine() < 0) {
            return;
        }
        int from = Math.max(other.getFirstLine() - 1, rangeStart);
        int to = Math.min(other.getLastLine(), rangeStart + included.length - 1);
        for (int line = from; line <= to; line++) {
            included[line - rangeStart] = false;
        }
    }

    /**
     * Remove the code lines of another initializer in the same class that this initializer doesn't
     * also have code on. Shared lines are the field initializers every constructor runs; unshared ones
     * are the other constructor's own body.
     *
     * @param included per-line inclusion flags, indexed from {@code rangeStart}
     * @param rangeStart the source line held at index 0 of {@code included}
     * @param initializer the initializer the ranges are being computed for
     * @param other another initializer of the same class
     */
    private static void excludeUnsharedCodeLines(boolean[] included, int rangeStart,
                                                 IMethodCoverage initializer, IMethodCoverage other) {
        if (other.getFirstLine() < 0 || other.getLastLine() < 0) {
            return;
        }
        int from = Math.max(other.getFirstLine(), rangeStart);
        int to = Math.min(other.getLastLine(), rangeStart + included.length - 1);
        for (int line = from; line <= to; line++) {
            if (hasCode(other, line) && !hasCode(initializer, line)) {
                included[line - rangeStart] = false;
            }
        }
    }

    /**
     * Check whether a method has any bytecode on a source line.
     *
     * @param method the method to check
     * @param line the source line number
     * @return true when JaCoCo reports instructions for the method on that line
     */
    private static boolean hasCode(IMethodCoverage method, int line) {
        return method.getLine(line).getStatus() != ICounter.EMPTY;
    }

    /**
     * Collapse per-line inclusion flags into flat inclusive start/end pairs.
     *
     * @param included per-line inclusion flags, indexed from {@code rangeStart}
     * @param rangeStart the source line held at index 0 of {@code included}
     * @return flat inclusive {@code [start, end, ...]} pairs in ascending order
     */
    private static int[] toRanges(boolean[] included, int rangeStart) {
        List<Integer> bounds = new ArrayList<>();
        int runStart = -1;
        for (int i = 0; i <= included.length; i++) {
            boolean in = i < included.length && included[i];
            if (in && runStart < 0) {
                runStart = i;
            } else if (!in && runStart >= 0) {
                bounds.add(rangeStart + runStart);
                bounds.add(rangeStart + i - 1);
                runStart = -1;
            }
        }
        int[] ranges = new int[bounds.size()];
        for (int i = 0; i < ranges.length; i++) {
            ranges[i] = bounds.get(i);
        }
        return ranges;
    }
}
