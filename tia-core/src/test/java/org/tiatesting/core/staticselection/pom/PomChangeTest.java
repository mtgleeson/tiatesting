package org.tiatesting.core.staticselection.pom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for relabelling a {@link PomChange} when it is attributed to another POM.
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
    }
}
