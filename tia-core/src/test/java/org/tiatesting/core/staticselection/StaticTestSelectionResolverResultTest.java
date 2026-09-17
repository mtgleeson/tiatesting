package org.tiatesting.core.staticselection;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestRunTrigger;
import org.tiatesting.core.model.TestSuiteTracker;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StaticTestSelectionResolver#resolve(Set, Map)}'s
 * {@link StaticTestSelectionResult} return value: the forced-suite union plus one
 * {@link TestRunTrigger} per fired rule. Mirrors the config/tracked-suite construction used by
 * {@link StaticTestSelectionResolverTest}.
 */
class StaticTestSelectionResolverResultTest {

    @Test
    void resultCarriesForcedSuitesAndPerRuleTrigger() {
        // given
        Map<String, TestSuiteTracker> tracked = trackedWith("com.example.ASpec", "com.example.BSpec");
        StaticTestSelectionConfig config = configWith(runAllRule("MDP", ".*\\.sql$"));
        Set<String> changedPaths = setOf("db/schema.sql");

        // when
        StaticTestSelectionResult result = new StaticTestSelectionResolver(config).resolve(changedPaths, tracked);

        // then
        assertEquals(setOf("com.example.ASpec", "com.example.BSpec"), result.getForcedSuites());
        assertEquals(1, result.getRuleTriggers().size());
        TestRunTrigger trigger = result.getRuleTriggers().get(0);
        assertEquals(TestRunTrigger.Type.STATIC_RULE, trigger.getType());
        assertEquals("MDP", trigger.getName());
        assertEquals(2, trigger.getTestCount());
    }

    @Test
    void noMatchingPathsYieldsEmptyResult() {
        // given
        Map<String, TestSuiteTracker> tracked = trackedWith("com.example.ASpec");
        StaticTestSelectionConfig config = configWith(runAllRule("MDP", ".*\\.sql$"));
        Set<String> changedPaths = setOf("src/main/java/Foo.java");

        // when
        StaticTestSelectionResult result = new StaticTestSelectionResolver(config).resolve(changedPaths, tracked);

        // then
        assertTrue(result.getForcedSuites().isEmpty());
        assertTrue(result.getRuleTriggers().isEmpty());
    }

    @Test
    void multipleFiredRulesEachContributeOneTrigger() {
        // given
        Map<String, TestSuiteTracker> tracked = trackedWith("com.acme.OrderServiceIT", "com.acme.PaymentServiceIT");
        StaticTestSelectionConfig config = configWith(
                runAllRule("sql-migrations", ".*\\.sql$"),
                suiteNamesRule("props-orders", ".*\\.properties$", "OrderServiceIT"));
        Set<String> changedPaths = setOf(
                "src/main/resources/db/V001.sql",
                "src/main/resources/application.properties");

        // when
        StaticTestSelectionResult result = new StaticTestSelectionResolver(config).resolve(changedPaths, tracked);

        // then - both rules fired, so both contribute a trigger even though their forced sets overlap
        assertEquals(setOf("com.acme.OrderServiceIT", "com.acme.PaymentServiceIT"), result.getForcedSuites());
        assertEquals(2, result.getRuleTriggers().size());
    }

    @Test
    void emptyChangedPathsProducesEmptyResult() {
        // given
        Map<String, TestSuiteTracker> tracked = trackedWith("com.acme.OrderServiceIT");
        StaticTestSelectionConfig config = configWith(runAllRule("sql-migrations", ".*\\.sql$"));

        // when
        StaticTestSelectionResult result =
                new StaticTestSelectionResolver(config).resolve(Collections.<String>emptySet(), tracked);

        // then
        assertTrue(result.getForcedSuites().isEmpty());
        assertTrue(result.getRuleTriggers().isEmpty());
    }

    private static Map<String, TestSuiteTracker> trackedWith(String... suiteNames) {
        Map<String, TestSuiteTracker> tracked = new LinkedHashMap<>();
        for (String name : suiteNames) {
            tracked.put(name, new TestSuiteTracker(name));
        }
        return tracked;
    }

    private static StaticTestSelectionConfig configWith(StaticTestSelectionRule... rules) {
        return new StaticTestSelectionConfig(Arrays.asList(rules));
    }

    private static StaticTestSelectionRule runAllRule(String name, String filePattern) {
        return new StaticTestSelectionRule(name, filePattern, StaticTestSelectionRuleMode.RUN_ALL, null);
    }

    private static StaticTestSelectionRule suiteNamesRule(String name, String filePattern,
                                                          String... suitePatterns) {
        return new StaticTestSelectionRule(name, filePattern, StaticTestSelectionRuleMode.SUITE_NAMES,
                Arrays.asList(suitePatterns));
    }

    private static Set<String> setOf(String... values) {
        return new java.util.HashSet<>(Arrays.asList(values));
    }
}
