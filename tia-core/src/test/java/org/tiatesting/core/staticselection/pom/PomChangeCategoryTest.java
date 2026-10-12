package org.tiatesting.core.staticselection.pom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PomChangeCategory#covers} and the {@link PomChange} factories.
 */
class PomChangeCategoryTest {

    @Test
    void functionalCoversEveryCategory() {
        // given
        PomChangeCategory rule = PomChangeCategory.FUNCTIONAL;

        // when
        boolean coversAll = true;
        for (PomChangeCategory category : PomChangeCategory.values()) {
            coversAll &= rule.covers(category);
        }

        // then
        assertTrue(coversAll);
    }

    @Test
    void aConcreteCategoryCoversOnlyItself() {
        // given
        PomChangeCategory rule = PomChangeCategory.COMPILER;

        // when
        boolean coversCompiler = rule.covers(PomChangeCategory.COMPILER);
        boolean coversOther = rule.covers(PomChangeCategory.OTHER);

        // then
        assertTrue(coversCompiler);
        assertFalse(coversOther);
    }

    @Test
    void functionalChangesNeedAConcreteCategory() {
        // given
        String description = "something changed";

        // when
        PomChange inert = PomChange.inert(description);

        // then
        assertTrue(inert.isInert());
        assertEquals("something changed (inert)", inert.toString());
        assertThrows(IllegalArgumentException.class,
                () -> PomChange.functional(PomChangeCategory.FUNCTIONAL, description));
        assertThrows(IllegalArgumentException.class, () -> PomChange.functional(null, description));
    }
}
