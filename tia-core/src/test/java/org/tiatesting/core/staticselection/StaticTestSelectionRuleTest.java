package org.tiatesting.core.staticselection;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.staticselection.pom.PomChangeCategory;
import org.tiatesting.core.staticselection.pom.PomInertOverrides;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
                () -> new StaticTestSelectionRule(null, "   ", StaticTestSelectionRuleMode.RUN_ALL, null, null, null));
    }

    @Test
    void nullModeIsRejected() {
        // given
        // when
        // then
        assertThrows(IllegalArgumentException.class,
                () -> new StaticTestSelectionRule(null, ".*\\.sql$", null, null, null, null));
    }

    @Test
    void annotationsTagsModeIsRejectedWithClearMessage() {
        // given
        StaticTestSelectionRuleMode mode = StaticTestSelectionRuleMode.ANNOTATIONS_TAGS;

        // when
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> new StaticTestSelectionRule(null, ".*\\.sql$", mode, null, null, null));

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
                null, ".*\\.sql$", StaticTestSelectionRuleMode.SUITE_NAMES, Collections.emptyList(), null, null));
    }

    @Test
    void suiteNamesModeRejectsBlankSuitePattern() {
        // given
        // when
        // then
        assertThrows(IllegalArgumentException.class, () -> new StaticTestSelectionRule(
                null, ".*\\.sql$", StaticTestSelectionRuleMode.SUITE_NAMES,
                Arrays.asList(".*IT$", "   "), null, null));
    }

    @Test
    void runAllModeRejectsSuitePatterns() {
        // given
        // when
        // then
        assertThrows(IllegalArgumentException.class, () -> new StaticTestSelectionRule(
                null, ".*\\.sql$", StaticTestSelectionRuleMode.RUN_ALL,
                Collections.singletonList(".*IT$"), null, null));
    }

    @Test
    void invalidFilePathRegexIsRejectedWithDescriptiveMessage() {
        // given
        String badRegex = "[unclosed";

        // when
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new StaticTestSelectionRule(null, badRegex,
                        StaticTestSelectionRuleMode.RUN_ALL, null, null, null));

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
                Collections.singletonList("[unclosed"), null, null));
    }

    @Test
    void runAllRuleCompilesAndDefaultsNameToFilePattern() {
        // given
        String filePattern = ".*\\.sql$";

        // when
        StaticTestSelectionRule rule = new StaticTestSelectionRule(
                "   ", filePattern, StaticTestSelectionRuleMode.RUN_ALL, null, null, null);

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
                Arrays.asList(".*MigrationIT$", "OrderServiceIT"), null, null);

        // then
        assertEquals("db-migrations", rule.getName());
        assertEquals(StaticTestSelectionRuleMode.SUITE_NAMES, rule.getMode());
        assertEquals(2, rule.getSuiteNamePatterns().size());
        assertEquals(".*MigrationIT$", rule.getSuiteNamePatterns().get(0).pattern());
        assertEquals("OrderServiceIT", rule.getSuiteNamePatterns().get(1).pattern());
    }

    @Test
    void pomAwareRuleDefaultsItsFilePathPatternToAnyPom() {
        // given
        Set<PomChangeCategory> categories = EnumSet.of(PomChangeCategory.FUNCTIONAL);

        // when
        StaticTestSelectionRule rule = new StaticTestSelectionRule(
                "poms", "  ", StaticTestSelectionRuleMode.RUN_ALL, null, categories, null);

        // then
        assertTrue(rule.isPomAware());
        assertEquals(StaticTestSelectionRule.DEFAULT_POM_FILE_PATH_PATTERN, rule.getFilePathPattern().pattern());
        assertTrue(rule.getFilePathPattern().matcher("pom.xml").find());
        assertTrue(rule.getFilePathPattern().matcher("module-a/pom.xml").find());
        assertFalse(rule.getFilePathPattern().matcher("module-a/mypom.xml").find());
    }

    @Test
    void pomAwareRuleWithNoNameIsNamedAfterTheDefaultPattern() {
        // given
        Set<PomChangeCategory> categories = EnumSet.of(PomChangeCategory.COMPILER);

        // when
        StaticTestSelectionRule rule = new StaticTestSelectionRule(
                null, null, StaticTestSelectionRuleMode.RUN_ALL, null, categories, null);

        // then
        assertEquals(StaticTestSelectionRule.DEFAULT_POM_FILE_PATH_PATTERN, rule.getName());
    }

    @Test
    void plainRuleIsNotPomAware() {
        // given
        // when
        StaticTestSelectionRule rule = new StaticTestSelectionRule(
                null, "\\.sql$", StaticTestSelectionRuleMode.RUN_ALL, null, null, null);

        // then
        assertFalse(rule.isPomAware());
        assertTrue(rule.getPomChanges().isEmpty());
    }

    @Test
    void otherCategoryIsRejected() {
        // given
        Set<PomChangeCategory> categories = EnumSet.of(PomChangeCategory.OTHER);

        // when
        // then
        assertThrows(IllegalArgumentException.class, () -> new StaticTestSelectionRule(
                null, null, StaticTestSelectionRuleMode.RUN_ALL, null, categories, null));
    }

    /**
     * Verify inert overrides on a rule without pomChanges fail, naming the rule.
     */
    @Test
    void inertOverridesNeedPomChanges() {
        // given
        PomInertOverrides overrides = PomInertOverrides.parse("upload-plugin", null);

        // when
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new StaticTestSelectionRule("sql", "\\.sql$", StaticTestSelectionRuleMode.RUN_ALL, null, null,
                        overrides));

        // then
        assertEquals("Static test selection rule 'sql': inertPlugins and inertProfiles need pomChanges.",
                error.getMessage());
    }

    /**
     * Verify null overrides mean none.
     */
    @Test
    void nullInertOverridesAreNone() {
        // given / when
        StaticTestSelectionRule rule = new StaticTestSelectionRule("poms", null, StaticTestSelectionRuleMode.RUN_ALL,
                null, EnumSet.of(PomChangeCategory.FUNCTIONAL), null);

        // then
        assertSame(PomInertOverrides.NONE, rule.getInertOverrides());
    }
}
