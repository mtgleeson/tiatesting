package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestSuiteTracker;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link TestRunnerService#updateDeveloperDisabledFlags(Map, Set, Set, Set)} maintains
 * the per-suite {@code developerDisabled} flag from the run signals: a suite that executed is
 * cleared, a suite Tia selected that was discovered but skipped is set, and a suite Tia neither
 * selected nor ran keeps its stored value.
 */
class TestRunnerServiceDeveloperDisabledTest {

    /**
     * A suite that actually executed this run cannot be disabled, so its flag is cleared even if
     * it was previously set (the developer re-enabled it).
     */
    @Test
    void updateDeveloperDisabledFlags_executed_clearsFlag(){
        // given
        Map<String, TestSuiteTracker> tracked = trackedSuites(suite("FooTest", true));
        Set<String> selected = setOf("FooTest");
        Set<String> runnerSuites = setOf("FooTest");
        Set<String> executed = setOf("FooTest");

        // when
        TestRunnerService.updateDeveloperDisabledFlags(tracked, selected, runnerSuites, executed);

        // then
        assertFalse(tracked.get("FooTest").isDeveloperDisabled());
    }

    /**
     * A suite Tia selected to run that the runner discovered but did not execute was disabled by
     * the developer, so its flag is set.
     */
    @Test
    void updateDeveloperDisabledFlags_selectedDiscoveredButSkipped_setsFlag(){
        // given
        Map<String, TestSuiteTracker> tracked = trackedSuites(suite("FooTest", false));
        Set<String> selected = setOf("FooTest");
        Set<String> runnerSuites = setOf("FooTest");
        Set<String> executed = setOf();

        // when
        TestRunnerService.updateDeveloperDisabledFlags(tracked, selected, runnerSuites, executed);

        // then
        assertTrue(tracked.get("FooTest").isDeveloperDisabled());
    }

    /**
     * A suite Tia ignored (not selected) and did not execute keeps its stored flag - the run
     * gives no unambiguous signal, so a previously-disabled suite stays disabled.
     */
    @Test
    void updateDeveloperDisabledFlags_tiaIgnored_keepsStoredValue(){
        // given
        Map<String, TestSuiteTracker> tracked = trackedSuites(suite("FooTest", true), suite("BarTest", false));
        Set<String> selected = setOf("BarTest");
        Set<String> runnerSuites = setOf("FooTest", "BarTest");
        Set<String> executed = setOf("BarTest");

        // when
        TestRunnerService.updateDeveloperDisabledFlags(tracked, selected, runnerSuites, executed);

        // then
        assertTrue(tracked.get("FooTest").isDeveloperDisabled());
        assertFalse(tracked.get("BarTest").isDeveloperDisabled());
    }

    /**
     * A suite Tia selected but the runner did not discover this run (e.g. not present in the
     * workspace) gives no disable signal, so its stored flag is left unchanged.
     */
    @Test
    void updateDeveloperDisabledFlags_selectedNotDiscovered_keepsStoredValue(){
        // given
        Map<String, TestSuiteTracker> tracked = trackedSuites(suite("FooTest", false));
        Set<String> selected = setOf("FooTest");
        Set<String> runnerSuites = setOf();
        Set<String> executed = setOf();

        // when
        TestRunnerService.updateDeveloperDisabledFlags(tracked, selected, runnerSuites, executed);

        // then
        assertFalse(tracked.get("FooTest").isDeveloperDisabled());
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
        return new HashSet<>(Arrays.asList(names));
    }
}
