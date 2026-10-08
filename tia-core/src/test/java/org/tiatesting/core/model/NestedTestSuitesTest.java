package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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
        Set<String> tracked = new HashSet<>(Arrays.asList("com.example.A", "com.example.A$B",
                "com.example.A$B$C", "com.example.A$D", "com.example.Other"));

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
        Set<String> tracked = new HashSet<>(Arrays.asList("com.example.A", "com.example.A$Inner",
                "com.example.Other"));

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
        NestedTestSuites.addFamilies(testsToRun, Collections.singleton("com.example.A$B"));

        // then
        assertEquals(Collections.singleton("com.example.A$B"), testsToRun);
    }
}
