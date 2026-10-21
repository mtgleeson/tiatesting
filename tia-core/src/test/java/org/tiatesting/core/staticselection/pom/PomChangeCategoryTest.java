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
    void codeAndTestsCoversOnlyCategoriesThatChangeCodeOrTests() {
        // given
        PomChangeCategory rule = PomChangeCategory.CODE_AND_TESTS;
        Set<PomChangeCategory> expected = EnumSet.of(PomChangeCategory.DEPENDENCY_VERSIONS,
                PomChangeCategory.DEPENDENCY_SET, PomChangeCategory.COMPILER, PomChangeCategory.TEST_RUNNER,
                PomChangeCategory.CODE_GENERATION, PomChangeCategory.RESOURCES);

        // when
        Set<PomChangeCategory> covered = EnumSet.noneOf(PomChangeCategory.class);
        for (PomChangeCategory category : PomChangeCategory.values()) {
            if (!category.isAggregate() && rule.covers(category)) {
                covered.add(category);
            }
        }

        // then
        assertEquals(expected, covered);
        assertFalse(rule.covers(PomChangeCategory.BUILD_PLUGINS));
        assertFalse(rule.covers(PomChangeCategory.OTHER));
    }

    @Test
    void onlyFunctionalAndCodeAndTestsAreAggregates() {
        // given
        Set<PomChangeCategory> aggregates = EnumSet.noneOf(PomChangeCategory.class);

        // when
        for (PomChangeCategory category : PomChangeCategory.values()) {
            if (category.isAggregate()) {
                aggregates.add(category);
            }
        }

        // then
        assertEquals(EnumSet.of(PomChangeCategory.FUNCTIONAL, PomChangeCategory.CODE_AND_TESTS), aggregates);
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
        assertThrows(IllegalArgumentException.class,
                () -> PomChange.functional(PomChangeCategory.CODE_AND_TESTS, description));
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
    void parseListAcceptsCodeAndTests() {
        // given
        String csv = "code_and_tests";

        // when
        Set<PomChangeCategory> categories = PomChangeCategory.parseList(csv);

        // then
        assertEquals(EnumSet.of(PomChangeCategory.CODE_AND_TESTS), categories);
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
