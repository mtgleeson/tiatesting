package org.tiatesting.core.staticselection;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestSuiteTracker;

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
        Map<String, TestSuiteTracker> tracked = trackedWith("com.acme.OrderServiceIT", "com.acme.PaymentServiceIT");
        StaticTestSelectionConfig config = configWith(runAllRule("sql-migrations", ".*\\.sql$"));
        Set<String> changedPaths = setOf("src/main/resources/db/migrations/V001.sql");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tracked);

        // then
        assertEquals(setOf("com.acme.OrderServiceIT", "com.acme.PaymentServiceIT"), forced);
    }

    @Test
    void runAllRuleIsNoOpWhenNoChangedPathMatches() {
        // given
        Map<String, TestSuiteTracker> tracked = trackedWith("com.acme.OrderServiceIT");
        StaticTestSelectionConfig config = configWith(runAllRule("sql-migrations", ".*\\.sql$"));
        Set<String> changedPaths = setOf("src/main/java/com/acme/Order.java");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tracked);

        // then
        assertTrue(forced.isEmpty());
    }

    @Test
    void runAllRuleResolvesToEmptyWhenNoTrackedSuites() {
        // given
        Map<String, TestSuiteTracker> tracked = trackedWith(); // no suites tracked
        StaticTestSelectionConfig config = configWith(runAllRule("sql-migrations", ".*\\.sql$"));
        Set<String> changedPaths = setOf("src/main/resources/db/migrations/V001.sql");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tracked);

        // then
        assertTrue(forced.isEmpty());
    }

    @Test
    void multipleRunAllRulesUnionAndDeduplicate() {
        // given
        Map<String, TestSuiteTracker> tracked = trackedWith("com.acme.OrderServiceIT", "com.acme.PaymentServiceIT");
        StaticTestSelectionConfig config = configWith(
                runAllRule("sql-migrations", ".*\\.sql$"),
                runAllRule("properties-changes", ".*\\.properties$"));
        Set<String> changedPaths = setOf(
                "src/main/resources/db/V001.sql",
                "src/main/resources/application.properties");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tracked);

        // then - the same two suites, not duplicated by each rule firing
        assertEquals(setOf("com.acme.OrderServiceIT", "com.acme.PaymentServiceIT"), forced);
    }

    @Test
    void onlyMatchingRuleContributesWhenMultipleRulesConfigured() {
        // given
        Map<String, TestSuiteTracker> tracked = trackedWith("com.acme.OrderServiceIT");
        StaticTestSelectionConfig config = configWith(
                runAllRule("sql-migrations", ".*\\.sql$"),
                runAllRule("yaml-changes", ".*\\.yaml$"));
        Set<String> changedPaths = setOf("src/main/resources/db/V001.sql");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tracked);

        // then
        assertEquals(setOf("com.acme.OrderServiceIT"), forced);
    }

    @Test
    void emptyChangedPathsProducesNoForcedSuites() {
        // given
        Map<String, TestSuiteTracker> tracked = trackedWith("com.acme.OrderServiceIT");
        StaticTestSelectionConfig config = configWith(runAllRule("sql-migrations", ".*\\.sql$"));

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(Collections.emptySet(), tracked);

        // then
        assertTrue(forced.isEmpty());
    }

    @Test
    void nullChangedPathsProducesNoForcedSuites() {
        // given
        Map<String, TestSuiteTracker> tracked = trackedWith("com.acme.OrderServiceIT");
        StaticTestSelectionConfig config = configWith(runAllRule("sql-migrations", ".*\\.sql$"));

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(null, tracked);

        // then
        assertTrue(forced.isEmpty());
    }

    @Test
    void filePathPatternMatchesAnywhereInPathViaFind() {
        // given - pattern with no anchors should find a substring match
        Map<String, TestSuiteTracker> tracked = trackedWith("com.acme.OrderServiceIT");
        StaticTestSelectionConfig config = configWith(runAllRule("migrations", "migrations/"));
        Set<String> changedPaths = setOf("src/main/resources/db/migrations/V001.sql");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tracked);

        // then
        assertEquals(setOf("com.acme.OrderServiceIT"), forced);
    }

    @Test
    void suiteNamesRuleMatchesSimpleNameViaFind() {
        // given - pattern matches the simple class name (not the package prefix)
        Map<String, TestSuiteTracker> tracked = trackedWith("com.acme.OrderServiceIT", "com.acme.PaymentServiceSpec");
        StaticTestSelectionConfig config = configWith(suiteNamesRule(
                "it-suites", ".*\\.sql$", "OrderServiceIT"));
        Set<String> changedPaths = setOf("src/main/resources/db/V001.sql");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tracked);

        // then
        assertEquals(setOf("com.acme.OrderServiceIT"), forced);
    }

    @Test
    void suiteNamesRuleMatchesFqnViaFind() {
        // given - pattern includes package fragment so only FQN match catches it
        Map<String, TestSuiteTracker> tracked = trackedWith("com.acme.OrderServiceIT", "com.other.OrderServiceIT");
        StaticTestSelectionConfig config = configWith(suiteNamesRule(
                "acme-only", ".*\\.sql$", "com\\.acme\\..*IT$"));
        Set<String> changedPaths = setOf("src/main/resources/db/V001.sql");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tracked);

        // then - both suites share simple name "OrderServiceIT" but only com.acme.* matches the FQN
        assertEquals(setOf("com.acme.OrderServiceIT"), forced);
    }

    @Test
    void suiteNamesRuleSimpleNameMatchPicksUpAllSuitesSharingThatSimpleName() {
        // given - two suites in different packages with the same simple class name
        Map<String, TestSuiteTracker> tracked = trackedWith("com.acme.OrderServiceIT", "com.other.OrderServiceIT");
        StaticTestSelectionConfig config = configWith(suiteNamesRule(
                "all-orders", ".*\\.sql$", "^OrderServiceIT$"));
        Set<String> changedPaths = setOf("src/main/resources/db/V001.sql");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tracked);

        // then - both FQNs sharing the simple name are matched
        assertEquals(setOf("com.acme.OrderServiceIT", "com.other.OrderServiceIT"), forced);
    }

    @Test
    void suiteNamesRuleSubstringPatternFindsBothSimpleAndFqnMatches() {
        // given - "Service" is a substring of multiple simple names and FQNs
        Map<String, TestSuiteTracker> tracked = trackedWith(
                "com.acme.OrderServiceIT", "com.acme.PaymentServiceSpec", "com.acme.OtherTest");
        StaticTestSelectionConfig config = configWith(suiteNamesRule(
                "services", ".*\\.sql$", "Service"));
        Set<String> changedPaths = setOf("src/main/resources/db/V001.sql");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tracked);

        // then
        assertEquals(setOf("com.acme.OrderServiceIT", "com.acme.PaymentServiceSpec"), forced);
    }

    @Test
    void suiteNamesRuleAnchoredPatternDoesNotMatchFqnButMatchesSimpleName() {
        // given - anchored pattern matches the simple name end-to-end but not the FQN
        // ('OrderServiceIT' as a whole vs 'com.acme.OrderServiceIT' fully anchored).
        Map<String, TestSuiteTracker> tracked = trackedWith("com.acme.OrderServiceIT");
        StaticTestSelectionConfig config = configWith(suiteNamesRule(
                "exact", ".*\\.sql$", "^OrderServiceIT$"));
        Set<String> changedPaths = setOf("src/main/resources/db/V001.sql");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tracked);

        // then - the simple-name match still picks up the suite
        assertEquals(setOf("com.acme.OrderServiceIT"), forced);
    }

    @Test
    void suiteNamesRuleWithMultiplePatternsUnionsResults() {
        // given
        Map<String, TestSuiteTracker> tracked = trackedWith(
                "com.acme.OrderServiceIT", "com.acme.PaymentServiceSpec", "com.acme.LegacyHelper");
        StaticTestSelectionConfig config = configWith(suiteNamesRule(
                "multi", ".*\\.sql$", "OrderServiceIT", "PaymentServiceSpec"));
        Set<String> changedPaths = setOf("src/main/resources/db/V001.sql");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tracked);

        // then
        assertEquals(setOf("com.acme.OrderServiceIT", "com.acme.PaymentServiceSpec"), forced);
    }

    @Test
    void suiteNamesAndRunAllRulesUnionInTheSameRun() {
        // given - one RUN_ALL on .sql, one SUITE_NAMES on .properties
        Map<String, TestSuiteTracker> tracked = trackedWith("com.acme.OrderServiceIT", "com.acme.PaymentServiceSpec");
        StaticTestSelectionConfig config = configWith(
                runAllRule("sql-migrations", ".*\\.sql$"),
                suiteNamesRule("props-orders", ".*\\.properties$", "OrderServiceIT"));
        Set<String> changedPaths = setOf(
                "src/main/resources/db/V001.sql",
                "src/main/resources/application.properties");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tracked);

        // then - RUN_ALL picks both; SUITE_NAMES picks one; union is the RUN_ALL set
        assertEquals(setOf("com.acme.OrderServiceIT", "com.acme.PaymentServiceSpec"), forced);
    }

    @Test
    void suiteNamesRuleResolvesToEmptyWhenNoTrackedSuites() {
        // given
        Map<String, TestSuiteTracker> tracked = trackedWith();
        StaticTestSelectionConfig config = configWith(suiteNamesRule(
                "any", ".*\\.sql$", ".*IT$"));
        Set<String> changedPaths = setOf("src/main/resources/db/V001.sql");

        // when
        Set<String> forced = new StaticTestSelectionResolver(config).resolve(changedPaths, tracked);

        // then
        assertTrue(forced.isEmpty());
    }

    @Test
    void warnOnEmptyRulesDoesNotThrowWhenAllRulesResolveToNonEmpty() {
        // given
        Map<String, TestSuiteTracker> tracked = trackedWith("com.acme.OrderServiceIT");
        StaticTestSelectionConfig config = configWith(runAllRule("sql-migrations", ".*\\.sql$"));

        // when / then - no exception means the happy path doesn't trip on enabled rules
        new StaticTestSelectionResolver(config).warnOnEmptyRules(tracked);
    }

    @Test
    void warnOnEmptyRulesDoesNotThrowWhenSomeRulesResolveToEmpty() {
        // given - no tracked suites means RUN_ALL resolves to empty; warn path must still complete
        Map<String, TestSuiteTracker> tracked = trackedWith();
        StaticTestSelectionConfig config = configWith(runAllRule("sql-migrations", ".*\\.sql$"));

        // when / then
        new StaticTestSelectionResolver(config).warnOnEmptyRules(tracked);
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
        List<String> patterns = Arrays.asList(suitePatterns);
        return new StaticTestSelectionRule(name, filePattern, StaticTestSelectionRuleMode.SUITE_NAMES, patterns);
    }

    private static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
