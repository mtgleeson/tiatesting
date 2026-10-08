package org.tiatesting.core.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * Find the outermost class of a suite: the part of its binary name before the first {@code $}.
     * Used to keep a suite family together in a distributed plan.
     *
     * @param suiteName a suite's binary class name
     * @return the top-level class the suite is nested in, or the suite itself when it is not nested
     */
    public static String topLevelSuite(final String suiteName) {
        int separator = suiteName.indexOf(NESTED_SEPARATOR);
        return separator < 0 ? suiteName : suiteName.substring(0, separator);
    }

    /**
     * List the classes a suite is nested in, by cutting its binary name at each {@code $}. These
     * are the suites whose skipping would also skip it.
     *
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
     * Add to a run set every tracked suite that encloses a suite in it. Skipping an enclosing class
     * skips every class nested in it, so an enclosing class of a selected nested class always runs -
     * and is counted as selected, so the run-time estimate, the history counts and a distributed
     * plan include its own tests. An enclosing class Tia does not track is never ignored, so it is
     * left out.
     *
     * @param testsToRun the suites selected to run; modified in place
     * @param trackedSuites the suites Tia tracks
     */
    public static void addEnclosingSuites(final Set<String> testsToRun, final Collection<String> trackedSuites) {
        List<String> enclosing = new ArrayList<>();
        for (String suite : testsToRun) {
            if (suite.indexOf(NESTED_SEPARATOR) > 0) {
                enclosing.addAll(enclosingSuites(suite));
            }
        }
        for (String suite : enclosing) {
            if (trackedSuites.contains(suite)) {
                testsToRun.add(suite);
            }
        }
    }

    /**
     * Weigh suite families (a top-level suite and every suite nested in it) by run time. A top-level
     * suite's recorded time is the wall clock of its whole class container, which already includes
     * every nested class that ran inside it, so summing a family's members would count the nested
     * classes twice. A family weighs the larger of its top-level suite's weight and the sum of its
     * nested suites' weights: the top-level figure when the whole family ran when it was timed, the
     * nested sum when the top-level suite is absent or its figure predates the nested classes.
     *
     * @param weightsBySuite run time per suite; no null values
     * @return each family's weight, keyed by top-level suite
     */
    public static Map<String, Long> familyWeights(final Map<String, Long> weightsBySuite) {
        Map<String, Long> topLevelWeights = new HashMap<>();
        Map<String, Long> nestedWeights = new HashMap<>();
        for (Map.Entry<String, Long> entry : weightsBySuite.entrySet()) {
            String topLevel = topLevelSuite(entry.getKey());
            if (topLevel.equals(entry.getKey())) {
                topLevelWeights.put(topLevel, entry.getValue());
            } else {
                nestedWeights.merge(topLevel, entry.getValue(), Long::sum);
            }
        }
        Map<String, Long> families = new HashMap<>(topLevelWeights);
        nestedWeights.forEach((topLevel, nested) -> families.merge(topLevel, nested, Math::max));
        return families;
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
