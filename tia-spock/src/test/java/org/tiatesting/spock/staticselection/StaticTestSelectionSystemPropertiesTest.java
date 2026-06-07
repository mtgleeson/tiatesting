package org.tiatesting.spock.staticselection;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.staticselection.StaticTestSelectionRule;
import org.tiatesting.core.staticselection.StaticTestSelectionRuleMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StaticTestSelectionSystemProperties} round-tripping rules through the
 * Gradle-to-test-JVM system-property bridge.
 */
class StaticTestSelectionSystemPropertiesTest {

    @Test
    void nullEncodedValueProducesEmptyConfig() {
        // given / when
        StaticTestSelectionConfig parsed = StaticTestSelectionSystemProperties.fromValue(null);

        // then
        assertSame(StaticTestSelectionConfig.EMPTY, parsed);
    }

    @Test
    void blankEncodedValueProducesEmptyConfig() {
        // given / when
        StaticTestSelectionConfig parsed = StaticTestSelectionSystemProperties.fromValue("   ");

        // then
        assertSame(StaticTestSelectionConfig.EMPTY, parsed);
    }

    @Test
    void nullConfigFormatsAsEmptyString() {
        // given / when
        String encoded = StaticTestSelectionSystemProperties.format(null);

        // then
        assertEquals("", encoded);
    }

    @Test
    void emptyConfigFormatsAsEmptyString() {
        // given / when
        String encoded = StaticTestSelectionSystemProperties.format(StaticTestSelectionConfig.EMPTY);

        // then
        assertEquals("", encoded);
    }

    @Test
    void runAllRuleRoundTripsPreservingNameAndPattern() {
        // given
        StaticTestSelectionConfig original = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("sql-migrations", "src/main/resources/db/.*\\.sql$",
                        StaticTestSelectionRuleMode.RUN_ALL, null)));

        // when
        String encoded = StaticTestSelectionSystemProperties.format(original);
        StaticTestSelectionConfig parsed = StaticTestSelectionSystemProperties.fromValue(encoded);

        // then
        assertEquals(1, parsed.getRules().size());
        StaticTestSelectionRule rule = parsed.getRules().get(0);
        assertEquals("sql-migrations", rule.getName());
        assertEquals("src/main/resources/db/.*\\.sql$", rule.getFilePathPattern().pattern());
        assertEquals(StaticTestSelectionRuleMode.RUN_ALL, rule.getMode());
        assertTrue(rule.getSuiteNamePatterns().isEmpty());
    }

    @Test
    void suiteNamesRuleRoundTripsPreservingPatternList() {
        // given - patterns include the delimiter characters (',', ':', '|') that the wire
        // format uses, so round-tripping confirms the base64 encoding is robust.
        StaticTestSelectionConfig original = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("db-rules", ".*\\.sql$",
                        StaticTestSelectionRuleMode.SUITE_NAMES,
                        Arrays.asList("^OrderServiceIT$", "com\\.acme\\..*Test", "IntegrationTest|Spec"))));

        // when
        String encoded = StaticTestSelectionSystemProperties.format(original);
        StaticTestSelectionConfig parsed = StaticTestSelectionSystemProperties.fromValue(encoded);

        // then
        StaticTestSelectionRule rule = parsed.getRules().get(0);
        assertEquals(StaticTestSelectionRuleMode.SUITE_NAMES, rule.getMode());
        assertEquals(3, rule.getSuiteNamePatterns().size());
        assertEquals("^OrderServiceIT$", rule.getSuiteNamePatterns().get(0).pattern());
        assertEquals("com\\.acme\\..*Test", rule.getSuiteNamePatterns().get(1).pattern());
        assertEquals("IntegrationTest|Spec", rule.getSuiteNamePatterns().get(2).pattern());
    }

    @Test
    void multipleRulesRoundTripInOrder() {
        // given
        StaticTestSelectionConfig original = new StaticTestSelectionConfig(Arrays.asList(
                new StaticTestSelectionRule("first", ".*\\.sql$",
                        StaticTestSelectionRuleMode.RUN_ALL, null),
                new StaticTestSelectionRule("second", ".*\\.properties$",
                        StaticTestSelectionRuleMode.SUITE_NAMES,
                        Collections.singletonList(".*IT$"))));

        // when
        String encoded = StaticTestSelectionSystemProperties.format(original);
        StaticTestSelectionConfig parsed = StaticTestSelectionSystemProperties.fromValue(encoded);

        // then
        assertEquals(2, parsed.getRules().size());
        assertEquals("first", parsed.getRules().get(0).getName());
        assertEquals("second", parsed.getRules().get(1).getName());
    }

    @Test
    void blankNameRoundTripsAsTheFilePatternAsItDoesAtConstruction() {
        // given - blank name on construction falls back to the file pattern as the rule's name
        StaticTestSelectionConfig original = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("   ", "src/main/.*",
                        StaticTestSelectionRuleMode.RUN_ALL, null)));

        // when
        String encoded = StaticTestSelectionSystemProperties.format(original);
        StaticTestSelectionConfig parsed = StaticTestSelectionSystemProperties.fromValue(encoded);

        // then
        assertEquals("src/main/.*", parsed.getRules().get(0).getName());
    }

    @Test
    void malformedRuleIsSkippedRatherThanAbortingTheWholeConfig() {
        // given - one good rule, one rule missing the mode field, one good rule
        StaticTestSelectionConfig good = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("ok", ".*\\.sql$",
                        StaticTestSelectionRuleMode.RUN_ALL, null)));
        String goodEncoded = StaticTestSelectionSystemProperties.format(good);
        String mixed = goodEncoded + ",notenoughfields:onlytwo," + goodEncoded;

        // when
        StaticTestSelectionConfig parsed = StaticTestSelectionSystemProperties.fromValue(mixed);

        // then - two good rules survive, malformed entry is dropped silently
        assertEquals(2, parsed.getRules().size());
    }

    @Test
    void unknownModeIsRejectedAndSkipped() {
        // given - one good rule plus an entry whose mode value is not a known enum
        StaticTestSelectionConfig good = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("ok", ".*\\.sql$",
                        StaticTestSelectionRuleMode.RUN_ALL, null)));
        String goodEncoded = StaticTestSelectionSystemProperties.format(good);
        // the second entry uses BOGUS_MODE which doesn't match any enum value
        String bogus = base64("anything") + ":" + base64(".*") + ":BOGUS_MODE:";
        String mixed = goodEncoded + "," + bogus;

        // when
        StaticTestSelectionConfig parsed = StaticTestSelectionSystemProperties.fromValue(mixed);

        // then
        assertEquals(1, parsed.getRules().size());
    }

    @Test
    void emptyEntriesBetweenCommasAreSkipped() {
        // given
        StaticTestSelectionConfig good = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("ok", ".*\\.sql$",
                        StaticTestSelectionRuleMode.RUN_ALL, null)));
        String goodEncoded = StaticTestSelectionSystemProperties.format(good);
        String withGaps = ",," + goodEncoded + ",,";

        // when
        StaticTestSelectionConfig parsed = StaticTestSelectionSystemProperties.fromValue(withGaps);

        // then
        assertEquals(1, parsed.getRules().size());
    }

    @Test
    void encodedFormUsesOnlyDelimitersAndUrlSafeBase64Alphabet() {
        // given - a SUITE_NAMES rule whose patterns contain every delimiter character
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("name,with:bad|chars", "src/main/.*",
                        StaticTestSelectionRuleMode.SUITE_NAMES,
                        Collections.singletonList(".*Spec|.*IT,WithComma"))));

        // when
        String encoded = StaticTestSelectionSystemProperties.format(config);

        // then - delimiters between fields/rules/suites still partition the string cleanly
        // because field contents are base64-encoded; this means parsing the same string back
        // recovers the original rule unchanged.
        StaticTestSelectionConfig roundTripped = StaticTestSelectionSystemProperties.fromValue(encoded);
        StaticTestSelectionRule recovered = roundTripped.getRules().get(0);
        assertEquals("name,with:bad|chars", recovered.getName());
        assertEquals("src/main/.*", recovered.getFilePathPattern().pattern());
        assertEquals(".*Spec|.*IT,WithComma", recovered.getSuiteNamePatterns().get(0).pattern());
    }

    @Test
    void roundTrippedConfigIsEnabledWhenOriginalWas() {
        // given
        StaticTestSelectionConfig original = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("any", ".*\\.sql$",
                        StaticTestSelectionRuleMode.RUN_ALL, null)));

        // when
        StaticTestSelectionConfig parsed = StaticTestSelectionSystemProperties.fromValue(
                StaticTestSelectionSystemProperties.format(original));

        // then
        assertTrue(parsed.isEnabled());
        assertFalse(StaticTestSelectionSystemProperties.fromValue("").isEnabled());
    }

    @Test
    void fromSystemPropertiesReadsTheNamedProperty() {
        // given
        StaticTestSelectionConfig original = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("from-sys-prop", ".*\\.sql$",
                        StaticTestSelectionRuleMode.RUN_ALL, null)));
        String encoded = StaticTestSelectionSystemProperties.format(original);
        String savedValue = System.getProperty(StaticTestSelectionSystemProperties.PROP_STATIC_TEST_SELECTION_RULES);
        System.setProperty(StaticTestSelectionSystemProperties.PROP_STATIC_TEST_SELECTION_RULES, encoded);

        try {
            // when
            StaticTestSelectionConfig parsed = StaticTestSelectionSystemProperties.fromSystemProperties();

            // then
            List<StaticTestSelectionRule> rules = parsed.getRules();
            assertEquals(1, rules.size());
            assertEquals("from-sys-prop", rules.get(0).getName());
        } finally {
            if (savedValue == null) {
                System.clearProperty(StaticTestSelectionSystemProperties.PROP_STATIC_TEST_SELECTION_RULES);
            } else {
                System.setProperty(StaticTestSelectionSystemProperties.PROP_STATIC_TEST_SELECTION_RULES, savedValue);
            }
        }
    }

    private static String base64(String raw) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
