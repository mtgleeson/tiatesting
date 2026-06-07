package org.tiatesting.core.staticselection;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StaticTestSelectionResolver}, covering stage 2 (RUN_ALL mode) and the
 * empty-resolution warning hook.
 */
class StaticTestSelectionResolverTest {

    @Test
    void runAllRuleForcesAllTrackedSuitesWhenFilePathMatches() {
        // given
        TiaData tiaData = tiaDataWith("com.acme.OrderServiceIT", "com.acme.PaymentServiceIT");
        StaticTestSelectionConfig config = configWith(runAllRule("sql-migrations", ".*\\.sql$"));
        Set<String> changedPaths = setOf("src/main/resources/db/migrations/V001.sql");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tiaData);

        // then
        assertEquals(setOf("com.acme.OrderServiceIT", "com.acme.PaymentServiceIT"), forced);
    }

    @Test
    void runAllRuleIsNoOpWhenNoChangedPathMatches() {
        // given
        TiaData tiaData = tiaDataWith("com.acme.OrderServiceIT");
        StaticTestSelectionConfig config = configWith(runAllRule("sql-migrations", ".*\\.sql$"));
        Set<String> changedPaths = setOf("src/main/java/com/acme/Order.java");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tiaData);

        // then
        assertTrue(forced.isEmpty());
    }

    @Test
    void runAllRuleResolvesToEmptyWhenNoTrackedSuites() {
        // given
        TiaData tiaData = tiaDataWith(); // no suites tracked
        StaticTestSelectionConfig config = configWith(runAllRule("sql-migrations", ".*\\.sql$"));
        Set<String> changedPaths = setOf("src/main/resources/db/migrations/V001.sql");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tiaData);

        // then
        assertTrue(forced.isEmpty());
    }

    @Test
    void multipleRunAllRulesUnionAndDeduplicate() {
        // given
        TiaData tiaData = tiaDataWith("com.acme.OrderServiceIT", "com.acme.PaymentServiceIT");
        StaticTestSelectionConfig config = configWith(
                runAllRule("sql-migrations", ".*\\.sql$"),
                runAllRule("properties-changes", ".*\\.properties$"));
        Set<String> changedPaths = setOf(
                "src/main/resources/db/V001.sql",
                "src/main/resources/application.properties");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tiaData);

        // then - the same two suites, not duplicated by each rule firing
        assertEquals(setOf("com.acme.OrderServiceIT", "com.acme.PaymentServiceIT"), forced);
    }

    @Test
    void onlyMatchingRuleContributesWhenMultipleRulesConfigured() {
        // given
        TiaData tiaData = tiaDataWith("com.acme.OrderServiceIT");
        StaticTestSelectionConfig config = configWith(
                runAllRule("sql-migrations", ".*\\.sql$"),
                runAllRule("yaml-changes", ".*\\.yaml$"));
        Set<String> changedPaths = setOf("src/main/resources/db/V001.sql");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tiaData);

        // then
        assertEquals(setOf("com.acme.OrderServiceIT"), forced);
    }

    @Test
    void emptyChangedPathsProducesNoForcedSuites() {
        // given
        TiaData tiaData = tiaDataWith("com.acme.OrderServiceIT");
        StaticTestSelectionConfig config = configWith(runAllRule("sql-migrations", ".*\\.sql$"));

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(Collections.emptySet(), tiaData);

        // then
        assertTrue(forced.isEmpty());
    }

    @Test
    void nullChangedPathsProducesNoForcedSuites() {
        // given
        TiaData tiaData = tiaDataWith("com.acme.OrderServiceIT");
        StaticTestSelectionConfig config = configWith(runAllRule("sql-migrations", ".*\\.sql$"));

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(null, tiaData);

        // then
        assertTrue(forced.isEmpty());
    }

    @Test
    void filePathPatternMatchesAnywhereInPathViaFind() {
        // given - pattern with no anchors should find a substring match
        TiaData tiaData = tiaDataWith("com.acme.OrderServiceIT");
        StaticTestSelectionConfig config = configWith(runAllRule("migrations", "migrations/"));
        Set<String> changedPaths = setOf("src/main/resources/db/migrations/V001.sql");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tiaData);

        // then
        assertEquals(setOf("com.acme.OrderServiceIT"), forced);
    }

    @Test
    void suiteNamesRuleResolvesToEmptyInStage2() {
        // given - SUITE_NAMES is reserved for stage 3; stage 2 silently resolves it to empty
        TiaData tiaData = tiaDataWith("com.acme.OrderServiceIT");
        StaticTestSelectionConfig config = configWith(new StaticTestSelectionRule(
                "suite-name-rule", ".*\\.sql$", StaticTestSelectionRuleMode.SUITE_NAMES,
                Collections.singletonList(".*IT$")));
        Set<String> changedPaths = setOf("src/main/resources/db/V001.sql");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tiaData);

        // then
        assertTrue(forced.isEmpty());
    }

    @Test
    void warnOnEmptyRulesDoesNotThrowWhenAllRulesResolveToNonEmpty() {
        // given
        TiaData tiaData = tiaDataWith("com.acme.OrderServiceIT");
        StaticTestSelectionConfig config = configWith(runAllRule("sql-migrations", ".*\\.sql$"));

        // when / then - no exception means the happy path doesn't trip on enabled rules
        new StaticTestSelectionResolver(config).warnOnEmptyRules(tiaData);
    }

    @Test
    void warnOnEmptyRulesDoesNotThrowWhenSomeRulesResolveToEmpty() {
        // given - empty TiaData means RUN_ALL resolves to empty; warn path must still complete
        TiaData tiaData = tiaDataWith();
        StaticTestSelectionConfig config = configWith(runAllRule("sql-migrations", ".*\\.sql$"));

        // when / then
        new StaticTestSelectionResolver(config).warnOnEmptyRules(tiaData);
    }

    private static TiaData tiaDataWith(String... suiteNames) {
        TiaData tiaData = new TiaData();
        Map<String, TestSuiteTracker> tracked = new LinkedHashMap<>();
        for (String name : suiteNames) {
            tracked.put(name, new TestSuiteTracker(name));
        }
        tiaData.setTestSuitesTracked(tracked);
        return tiaData;
    }

    private static StaticTestSelectionConfig configWith(StaticTestSelectionRule... rules) {
        return new StaticTestSelectionConfig(Arrays.asList(rules));
    }

    private static StaticTestSelectionRule runAllRule(String name, String filePattern) {
        return new StaticTestSelectionRule(name, filePattern, StaticTestSelectionRuleMode.RUN_ALL, null);
    }

    private static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
