package org.tiatesting.core.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Suite families, read from binary class names: a top-level test class and every class nested in it
 * ({@code Outer}, {@code Outer$Inner}, {@code Outer$Inner$Deeper}). A JUnit 5 {@code @Nested} class
 * is tracked as its own suite, but it only ever runs inside its enclosing class, and skipping the
 * enclosing class (the agent marks it {@code @Disabled}) skips every class nested in it. Tia
 * therefore selects whole families and a distributed plan keeps a family in one group. See the
 * "Nested test classes" section of the "How Tia exchanges data with the test runner" chapter in
 * {@code WIKI.md}.
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
     * It names the suite's family.
     *
     * @param suiteName a suite's binary class name
     * @return the top-level class the suite is nested in, or the suite itself when it is not nested
     */
    public static String topLevelSuite(final String suiteName) {
        int separator = suiteName.indexOf(NESTED_SEPARATOR);
        return separator < 0 ? suiteName : suiteName.substring(0, separator);
    }

    /**
     * Add to a run set every tracked suite in the family of a suite in it. Families are selected
     * whole: a selected nested class only runs inside its enclosing class; an edited test file names
     * only its top-level class, though the nested classes declared in it are separate suites; and
     * an enclosing class's {@code @BeforeAll} and static set-up run once for the whole family but
     * are credited to one suite's coverage. Selecting less than the family could skip tests the
     * change affects. Every suite added is counted as selected, so the run-time estimate, the
     * history counts and a distributed plan include it. Families whose members Tia does not track
     * are never ignored, so only tracked suites are added, and a suite flagged developer-disabled is
     * not added: it would not run, as a forced run leaves it out too.
     *
     * @param testsToRun the suites selected to run; modified in place
     * @param trackedSuites the suites Tia tracks, keyed by name
     */
    public static void addFamilies(final Set<String> testsToRun, final Map<String, TestSuiteTracker> trackedSuites) {
        if (testsToRun.isEmpty()) {
            return;
        }
        Set<String> selectedFamilies = new HashSet<>();
        for (String suite : testsToRun) {
            selectedFamilies.add(topLevelSuite(suite));
        }
        List<String> familyMembers = new ArrayList<>();
        for (Map.Entry<String, TestSuiteTracker> tracked : trackedSuites.entrySet()) {
            if (selectedFamilies.contains(topLevelSuite(tracked.getKey()))
                    && !tracked.getValue().isDeveloperDisabled()) {
                familyMembers.add(tracked.getKey());
            }
        }
        testsToRun.addAll(familyMembers);
    }
}
