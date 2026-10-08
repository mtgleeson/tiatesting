package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how {@link NestedTestSuites} reads the enclosing classes of a suite from its binary name
 * and keeps them out of an ignore set when a nested suite is selected.
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
    void enclosingSuitesAreListedOutermostFirst() {
        // given
        String suite = "com.example.A$B$C";

        // when
        List<String> enclosing = NestedTestSuites.enclosingSuites(suite);

        // then
        assertEquals(Arrays.asList("com.example.A", "com.example.A$B"), enclosing);
    }

    @Test
    void aTopLevelSuiteHasNoEnclosingSuites() {
        // given
        String suite = "com.example.A";

        // when
        List<String> enclosing = NestedTestSuites.enclosingSuites(suite);

        // then
        assertTrue(enclosing.isEmpty());
    }

    @Test
    void enclosingSuitesOfASelectedNestedSuiteLeaveTheIgnoreSet() {
        // given
        Set<String> ignore = new HashSet<>(Arrays.asList("com.example.A", "com.example.A$B", "com.example.Other"));

        // when
        NestedTestSuites.keepEnclosingSuitesOfSelected(ignore, Collections.singleton("com.example.A$B$C"));

        // then
        assertEquals(Collections.singleton("com.example.Other"), ignore);
    }

    @Test
    void aSelectedTopLevelSuiteLeavesItsNestedSuitesIgnored() {
        // given
        Set<String> ignore = new HashSet<>(Collections.singletonList("com.example.A$B"));

        // when
        NestedTestSuites.keepEnclosingSuitesOfSelected(ignore, Collections.singleton("com.example.A"));

        // then
        assertEquals(Collections.singleton("com.example.A$B"), ignore);
    }
}
