package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    void trackedEnclosingSuitesOfASelectedNestedSuiteAreAddedToTheRunSet() {
        // given - A and A$B are tracked; A$B$C is selected
        Set<String> testsToRun = new HashSet<>(Collections.singletonList("com.example.A$B$C"));
        Set<String> tracked = new HashSet<>(Arrays.asList("com.example.A", "com.example.A$B", "com.example.A$B$C"));

        // when
        NestedTestSuites.addEnclosingSuites(testsToRun, tracked);

        // then
        assertEquals(new HashSet<>(tracked), testsToRun);
    }

    @Test
    void untrackedEnclosingSuitesAreNotAdded() {
        // given - the enclosing class is not tracked, so it is never ignored anyway
        Set<String> testsToRun = new HashSet<>(Collections.singletonList("com.example.A$B"));

        // when
        NestedTestSuites.addEnclosingSuites(testsToRun, Collections.singleton("com.example.A$B"));

        // then
        assertEquals(Collections.singleton("com.example.A$B"), testsToRun);
    }

    @Test
    void aTopLevelSuitesOwnTimeExcludesItsNestedSuites() {
        // given - A's 60ms container includes A$N's 40ms; B$M has no top-level suite present
        Map<String, Long> times = new HashMap<>();
        times.put("A", 60L);
        times.put("A$N", 40L);
        times.put("B$M", 30L);
        times.put("C", 5L);

        // when
        Map<String, Long> own = NestedTestSuites.ownTimes(times);

        // then
        Map<String, Long> expected = new HashMap<>();
        expected.put("A", 20L);
        expected.put("A$N", 40L);
        expected.put("B$M", 30L);
        expected.put("C", 5L);
        assertEquals(expected, own);
    }

    @Test
    void anOwnTimeIsNeverNegative() {
        // given - A's figure predates nested classes that now take longer
        Map<String, Long> times = new HashMap<>();
        times.put("A", 10L);
        times.put("A$N", 40L);

        // when
        Map<String, Long> own = NestedTestSuites.ownTimes(times);

        // then
        assertEquals(Long.valueOf(0L), own.get("A"));
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
