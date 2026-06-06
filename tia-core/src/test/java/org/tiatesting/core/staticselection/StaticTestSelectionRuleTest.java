package org.tiatesting.core.staticselection;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StaticTestSelectionRule}.
 */
class StaticTestSelectionRuleTest {

    @Test
    void blankFilePathPatternIsRejected() {
        // given
        // when
        // then
        assertThrows(IllegalArgumentException.class,
                () -> new StaticTestSelectionRule(null, "   ", StaticTestSelectionRuleMode.RUN_ALL, null));
    }

    @Test
    void nullModeIsRejected() {
        // given
        // when
        // then
        assertThrows(IllegalArgumentException.class,
                () -> new StaticTestSelectionRule(null, ".*\\.sql$", null, null));
    }

    @Test
    void annotationsTagsModeIsRejectedWithClearMessage() {
        // given
        StaticTestSelectionRuleMode mode = StaticTestSelectionRuleMode.ANNOTATIONS_TAGS;

        // when
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> new StaticTestSelectionRule(null, ".*\\.sql$", mode, null));

        // then
        assertTrue(thrown.getMessage().contains("not yet supported"),
                "Error must explain that the ANNOTATIONS_TAGS mode is not yet supported.");
    }

    @Test
    void suiteNamesModeRequiresAtLeastOnePattern() {
        // given
        // when
        // then
        assertThrows(IllegalArgumentException.class, () -> new StaticTestSelectionRule(
                null, ".*\\.sql$", StaticTestSelectionRuleMode.SUITE_NAMES, Collections.emptyList()));
    }

    @Test
    void suiteNamesModeRejectsBlankSuitePattern() {
        // given
        // when
        // then
        assertThrows(IllegalArgumentException.class, () -> new StaticTestSelectionRule(
                null, ".*\\.sql$", StaticTestSelectionRuleMode.SUITE_NAMES,
                Arrays.asList(".*IT$", "   ")));
    }

    @Test
    void runAllModeRejectsSuitePatterns() {
        // given
        // when
        // then
        assertThrows(IllegalArgumentException.class, () -> new StaticTestSelectionRule(
                null, ".*\\.sql$", StaticTestSelectionRuleMode.RUN_ALL,
                Collections.singletonList(".*IT$")));
    }

    @Test
    void invalidFilePathRegexIsRejectedWithDescriptiveMessage() {
        // given
        String badRegex = "[unclosed";

        // when
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new StaticTestSelectionRule(null, badRegex,
                        StaticTestSelectionRuleMode.RUN_ALL, null));

        // then
        assertTrue(thrown.getMessage().contains("file path pattern"),
                "Error must identify which pattern failed to compile.");
        assertTrue(thrown.getMessage().contains(badRegex),
                "Error must include the offending regex string.");
    }

    @Test
    void invalidSuiteNameRegexIsRejected() {
        // given
        // when
        // then
        assertThrows(IllegalArgumentException.class, () -> new StaticTestSelectionRule(
                null, ".*\\.sql$", StaticTestSelectionRuleMode.SUITE_NAMES,
                Collections.singletonList("[unclosed")));
    }

    @Test
    void runAllRuleCompilesAndDefaultsNameToFilePattern() {
        // given
        String filePattern = ".*\\.sql$";

        // when
        StaticTestSelectionRule rule = new StaticTestSelectionRule(
                "   ", filePattern, StaticTestSelectionRuleMode.RUN_ALL, null);

        // then
        assertNotNull(rule.getFilePathPattern());
        assertEquals(filePattern, rule.getFilePathPattern().pattern());
        assertEquals(filePattern, rule.getName());
        assertEquals(StaticTestSelectionRuleMode.RUN_ALL, rule.getMode());
        assertTrue(rule.getSuiteNamePatterns().isEmpty());
    }

    @Test
    void suiteNamesRuleCompilesWithMultiplePatterns() {
        // given
        // when
        StaticTestSelectionRule rule = new StaticTestSelectionRule(
                "db-migrations", "src/main/resources/db/.*\\.sql$",
                StaticTestSelectionRuleMode.SUITE_NAMES,
                Arrays.asList(".*MigrationIT$", "OrderServiceIT"));

        // then
        assertEquals("db-migrations", rule.getName());
        assertEquals(StaticTestSelectionRuleMode.SUITE_NAMES, rule.getMode());
        assertEquals(2, rule.getSuiteNamePatterns().size());
        assertEquals(".*MigrationIT$", rule.getSuiteNamePatterns().get(0).pattern());
        assertEquals("OrderServiceIT", rule.getSuiteNamePatterns().get(1).pattern());
    }
}
