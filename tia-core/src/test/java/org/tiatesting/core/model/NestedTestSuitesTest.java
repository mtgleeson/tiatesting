package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies how {@link NestedTestSuites} reads a suite's family from its binary name and selects
 * whole families.
 */
class NestedTestSuitesTest {

    @Test
    void topLevelSuiteOfANestedSuiteIsItsOutermostClass() {
        // given
        String suite = "com.example.A$B$C";

        // when
        String topLevel = NestedTestSuites.topLevelSuite(suite);

        // then
        assertEquals("com.example.A", topLevel);
    }

    @Test
    void topLevelSuiteOfATopLevelSuiteIsItself() {
        // given
        String suite = "com.example.A";

        // when
        String topLevel = NestedTestSuites.topLevelSuite(suite);

        // then
        assertEquals("com.example.A", topLevel);
    }

    @Test
    void aSelectedNestedSuiteBringsInItsWholeTrackedFamily() {
        // given - A$B$C is selected; A, A$B and a sibling A$D are tracked too
        Set<String> testsToRun = new HashSet<>(Collections.singletonList("com.example.A$B$C"));
        Map<String, TestSuiteTracker> tracked = tracked("com.example.A", "com.example.A$B",
                "com.example.A$B$C", "com.example.A$D", "com.example.Other");

        // when
        NestedTestSuites.addFamilies(testsToRun, tracked);

        // then
        assertEquals(new HashSet<>(Arrays.asList("com.example.A", "com.example.A$B", "com.example.A$B$C",
                "com.example.A$D")), testsToRun);
    }

    @Test
    void aSelectedTopLevelSuiteBringsInItsNestedSuites() {
        // given - an edited test file names only its top-level class
        Set<String> testsToRun = new HashSet<>(Collections.singletonList("com.example.A"));
        Map<String, TestSuiteTracker> tracked = tracked("com.example.A", "com.example.A$Inner",
                "com.example.Other");

        // when
        NestedTestSuites.addFamilies(testsToRun, tracked);

        // then
        assertEquals(new HashSet<>(Arrays.asList("com.example.A", "com.example.A$Inner")), testsToRun);
    }

    @Test
    void untrackedFamilyMembersAreNotAdded() {
        // given - nothing else in the family is tracked, so nothing else is ever ignored
        Set<String> testsToRun = new HashSet<>(Collections.singletonList("com.example.A$B"));

        // when
        NestedTestSuites.addFamilies(testsToRun, tracked("com.example.A$B"));

        // then
        assertEquals(Collections.singleton("com.example.A$B"), testsToRun);
    }

    @Test
    void developerDisabledFamilyMembersAreNotAdded() {
        // given - A$Off is disabled in source, so it would not run
        Set<String> testsToRun = new HashSet<>(Collections.singletonList("com.example.A"));
        Map<String, TestSuiteTracker> tracked = tracked("com.example.A", "com.example.A$On", "com.example.A$Off");
        tracked.get("com.example.A$Off").setDeveloperDisabled(true);

        // when
        NestedTestSuites.addFamilies(testsToRun, tracked);

        // then
        assertEquals(new HashSet<>(Arrays.asList("com.example.A", "com.example.A$On")), testsToRun);
    }

    /**
     * Build trackers for the given suite names.
     *
     * @param names the tracked suite names
     * @return a tracker per name, keyed by name
     */
    private static Map<String, TestSuiteTracker> tracked(final String... names) {
        Map<String, TestSuiteTracker> tracked = new LinkedHashMap<>();
        for (String name : names) {
            tracked.put(name, new TestSuiteTracker(name));
        }
        return tracked;
    }
}
