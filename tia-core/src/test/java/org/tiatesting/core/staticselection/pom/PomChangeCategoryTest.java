package org.tiatesting.core.staticselection.pom;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

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

    @Test
    void parseListAcceptsCaseInsensitiveCommaSeparatedNames() {
        // given
        String csv = " functional , Dependency_Versions,TEST_RUNNER ";

        // when
        Set<PomChangeCategory> categories = PomChangeCategory.parseList(csv);

        // then
        assertEquals(EnumSet.of(PomChangeCategory.FUNCTIONAL, PomChangeCategory.DEPENDENCY_VERSIONS,
                PomChangeCategory.TEST_RUNNER), categories);
    }

    @Test
    void parseListOfBlankIsEmpty() {
        // given
        // when
        Set<PomChangeCategory> fromNull = PomChangeCategory.parseList(null);
        Set<PomChangeCategory> fromBlank = PomChangeCategory.parseList("  ");

        // then
        assertTrue(fromNull.isEmpty());
        assertTrue(fromBlank.isEmpty());
    }

    @Test
    void parseListRejectsUnknownBlankAndOtherNames() {
        // given
        // when
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> PomChangeCategory.parseList("FUNCTIONAL,DEPENDENCIES"));

        // then
        assertTrue(unknown.getMessage().contains("'DEPENDENCIES'"), unknown.getMessage());
        assertTrue(unknown.getMessage().contains("FUNCTIONAL"), unknown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> PomChangeCategory.parseList("FUNCTIONAL,,COMPILER"));
        assertThrows(IllegalArgumentException.class, () -> PomChangeCategory.parseList("OTHER"));
    }
}
