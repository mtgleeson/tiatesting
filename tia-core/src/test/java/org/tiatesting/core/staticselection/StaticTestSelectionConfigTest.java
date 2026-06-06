package org.tiatesting.core.staticselection;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StaticTestSelectionConfig}.
 */
class StaticTestSelectionConfigTest {

    @Test
    void emptyConstantIsDisabled() {
        // given
        StaticTestSelectionConfig config = StaticTestSelectionConfig.EMPTY;

        // when
        boolean enabled = config.isEnabled();

        // then
        assertFalse(enabled);
        assertTrue(config.getRules().isEmpty());
    }

    @Test
    void nullRulesListBehavesAsEmpty() {
        // given
        // when
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(null);

        // then
        assertFalse(config.isEnabled());
        assertTrue(config.getRules().isEmpty());
    }

    @Test
    void emptyRulesListBehavesAsDisabled() {
        // given
        // when
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(Collections.emptyList());

        // then
        assertFalse(config.isEnabled());
    }

    @Test
    void nonEmptyRulesEnableTheConfig() {
        // given
        StaticTestSelectionRule rule = new StaticTestSelectionRule(
                "sql-migration", ".*\\.sql$", StaticTestSelectionRuleMode.RUN_ALL, null);

        // when
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(Collections.singletonList(rule));

        // then
        assertTrue(config.isEnabled());
        assertEquals(1, config.getRules().size());
        assertEquals(rule, config.getRules().get(0));
    }

    @Test
    void rulesListIsImmutable() {
        // given
        StaticTestSelectionRule rule = new StaticTestSelectionRule(
                "sql-migration", ".*\\.sql$", StaticTestSelectionRuleMode.RUN_ALL, null);
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(
                new java.util.ArrayList<>(Collections.singletonList(rule)));

        // when
        List<StaticTestSelectionRule> rules = config.getRules();

        // then
        assertThrows(UnsupportedOperationException.class, () -> rules.add(rule));
    }

    @Test
    void multipleRulesArePreservedInOrder() {
        // given
        StaticTestSelectionRule first = new StaticTestSelectionRule(
                "first", ".*\\.sql$", StaticTestSelectionRuleMode.RUN_ALL, null);
        StaticTestSelectionRule second = new StaticTestSelectionRule(
                "second", ".*\\.properties$", StaticTestSelectionRuleMode.SUITE_NAMES,
                Collections.singletonList(".*IT$"));

        // when
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(Arrays.asList(first, second));

        // then
        assertEquals(2, config.getRules().size());
        assertEquals("first", config.getRules().get(0).getName());
        assertEquals("second", config.getRules().get(1).getName());
    }
}
