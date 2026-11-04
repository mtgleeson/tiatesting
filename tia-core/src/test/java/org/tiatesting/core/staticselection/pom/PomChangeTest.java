package org.tiatesting.core.staticselection.pom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for relabelling a {@link PomChange} when it is attributed to another POM, and for the
 * origin it records when inherited from an external parent or BOM.
 */
class PomChangeTest {

    /**
     * Verify appending to the description keeps the category.
     */
    @Test
    void descriptionSuffixKeepsCategory() {
        // given
        PomChange change = PomChange.functional(PomChangeCategory.BUILD_PLUGINS, "plugin x changed");

        // when
        PomChange relabelled = change.withDescriptionSuffix(" (in module-a/pom.xml)");

        // then
        assertEquals("plugin x changed (in module-a/pom.xml)", relabelled.getDescription());
        assertEquals(PomChangeCategory.BUILD_PLUGINS, relabelled.getCategory());
        assertNull(relabelled.getOrigin());
    }

    /**
     * Verify a change inherited from an external parent names it in the description and as its
     * origin, keeping the category.
     */
    @Test
    void inheritedChangeRecordsItsOrigin() {
        // given
        PomChange change = PomChange.functional(PomChangeCategory.DEPENDENCY_VERSIONS, "managed dependency a:b 1 -> 2");

        // when
        PomChange inherited = change.inheritedFrom("parent org.boot:boot-parent:2");

        // then
        assertEquals("managed dependency a:b 1 -> 2 (in parent org.boot:boot-parent:2)", inherited.getDescription());
        assertEquals("parent org.boot:boot-parent:2", inherited.getOrigin());
        assertEquals(PomChangeCategory.DEPENDENCY_VERSIONS, inherited.getCategory());
    }

    /**
     * Verify a change inherited through a chain of parents is attributed to the outermost one, the
     * parent the classified POM declares, while the description keeps the whole chain.
     */
    @Test
    void outerParentReplacesTheOriginOfAChainedChange() {
        // given
        PomChange fromGrandparent = PomChange.functional(PomChangeCategory.DEPENDENCY_VERSIONS, "managed dependency a:b 1 -> 2")
                .inheritedFrom("parent org.base:base:5");

        // when
        PomChange fromParent = fromGrandparent.inheritedFrom("parent org.boot:boot-parent:2");

        // then
        assertEquals("parent org.boot:boot-parent:2", fromParent.getOrigin());
        assertEquals("managed dependency a:b 1 -> 2 (in parent org.base:base:5) (in parent org.boot:boot-parent:2)",
                fromParent.getDescription());
    }

    /**
     * Verify relabelling for a child module keeps an inherited change's origin.
     */
    @Test
    void descriptionSuffixKeepsOrigin() {
        // given
        PomChange inherited = PomChange.functional(PomChangeCategory.DEPENDENCY_VERSIONS, "dependency a:b 1 -> 2")
                .inheritedFrom("BOM org.platform:bom:2");

        // when
        PomChange relabelled = inherited.withDescriptionSuffix(" (in module-a/pom.xml)");

        // then
        assertEquals("BOM org.platform:bom:2", relabelled.getOrigin());
    }

    /**
     * Verify the origin plays no part in equality: the description already names it.
     */
    @Test
    void equalityIgnoresTheOrigin() {
        // given
        PomChange inherited = PomChange.inert("x").inheritedFrom("parent g:a:1");
        PomChange same = PomChange.inert("x (in parent g:a:1)");

        // when
        boolean equal = inherited.equals(same) && inherited.hashCode() == same.hashCode();

        // then
        assertTrue(equal);
    }
}
