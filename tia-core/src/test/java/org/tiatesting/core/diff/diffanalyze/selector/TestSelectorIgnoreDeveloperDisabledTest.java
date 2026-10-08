package org.tiatesting.core.diff.diffanalyze.selector;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestSuiteTracker;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link TestSelector#getTestsToIgnore(Map, Set)} builds the ignore set from tracked
 * suites not selected to run, but excludes any suite flagged developer-disabled so it never
 * counts as a Tia-ignored suite.
 */
class TestSelectorIgnoreDeveloperDisabledTest {

    /**
     * A tracked suite that wasn't selected to run and is not developer-disabled is ignored by Tia.
     */
    @Test
    void getTestsToIgnore_trackedNotSelected_isIgnored(){
        // given
        Map<String, TestSuiteTracker> tracked = trackedSuites(suite("FooTest", false), suite("BarTest", false));
        Set<String> testsToRun = setOf("BarTest");

        // when
        Set<String> ignore = TestSelector.getTestsToIgnore(tracked, testsToRun);

        // then
        assertTrue(ignore.contains("FooTest"));
        assertFalse(ignore.contains("BarTest"));
    }

    /**
     * A tracked suite that wasn't selected to run but is developer-disabled is excluded from the
     * ignore set - the developer disabled it, so Tia did not save time by skipping it.
     */
    @Test
    void getTestsToIgnore_developerDisabled_isExcludedFromIgnoreSet(){
        // given
        Map<String, TestSuiteTracker> tracked = trackedSuites(suite("FooTest", true), suite("BarTest", false));
        Set<String> testsToRun = setOf("BarTest");

        // when
        Set<String> ignore = TestSelector.getTestsToIgnore(tracked, testsToRun);

        // then
        assertFalse(ignore.contains("FooTest"));
        assertTrue(ignore.isEmpty());
    }

    private static TestSuiteTracker suite(String name, boolean developerDisabled){
        TestSuiteTracker tracker = new TestSuiteTracker(name);
        tracker.setDeveloperDisabled(developerDisabled);
        return tracker;
    }

    private static Map<String, TestSuiteTracker> trackedSuites(TestSuiteTracker... suites){
        Map<String, TestSuiteTracker> map = new HashMap<>();
        for (TestSuiteTracker suite : suites){
            map.put(suite.getName(), suite);
        }
        return map;
    }

    private static Set<String> setOf(String... names){
        return new HashSet<>(java.util.Arrays.asList(names));
    }
}
