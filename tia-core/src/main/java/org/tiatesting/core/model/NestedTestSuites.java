package org.tiatesting.core.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Relationships between a test suite and the classes it is nested in, read from binary class names
 * ({@code Outer$Inner}). A JUnit 5 {@code @Nested} class is tracked as its own suite, but it only
 * ever runs inside its enclosing class: skipping the enclosing class (the agent marks it
 * {@code @Disabled}) skips every class nested in it too. So a selected nested suite needs its
 * enclosing suites left runnable, and a distributed plan must keep a nested suite in the same group
 * as its top-level class. See the "Nested test classes" section of the "How Tia exchanges data with
 * the test runner" chapter in {@code WIKI.md}.
 */
public final class NestedTestSuites {

    private static final char NESTED_SEPARATOR = '$';

    /**
     * Static utility; not instantiable.
     */
    private NestedTestSuites() {
    }

    /**
     * @param suiteName a suite's binary class name
     * @return the top-level class the suite is nested in, or the suite itself when it is not nested
     */
    public static String topLevelSuite(final String suiteName) {
        int separator = suiteName.indexOf(NESTED_SEPARATOR);
        return separator < 0 ? suiteName : suiteName.substring(0, separator);
    }

    /**
     * @param suiteName a suite's binary class name
     * @return every class the suite is nested in, outermost first, e.g. {@code A} and {@code A$B}
     *         for {@code A$B$C}; empty when the suite is not nested
     */
    public static List<String> enclosingSuites(final String suiteName) {
        List<String> enclosing = new ArrayList<>();
        int separator = suiteName.indexOf(NESTED_SEPARATOR);
        while (separator > 0) {
            enclosing.add(suiteName.substring(0, separator));
            separator = suiteName.indexOf(NESTED_SEPARATOR, separator + 1);
        }
        return enclosing;
    }

    /**
     * Remove from an ignore set every suite that encloses a suite selected to run, so skipping an
     * enclosing class never skips a selected nested one. The enclosing class's own tests then run
     * too - an over-selection, never a missed test.
     *
     * @param testsToIgnore the ignore set; modified in place
     * @param testsToRun the suites selected to run
     */
    public static void keepEnclosingSuitesOfSelected(final Set<String> testsToIgnore,
                                                     final Collection<String> testsToRun) {
        if (testsToIgnore.isEmpty()) {
            return;
        }
        for (String suite : testsToRun) {
            if (suite.indexOf(NESTED_SEPARATOR) > 0) {
                testsToIgnore.removeAll(enclosingSuites(suite));
            }
        }
    }
}
