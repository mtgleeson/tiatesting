package org.tiatesting.core.staticselection.pom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the plugin and profile tags a {@link PomChange} carries.
 */
class PomChangeTest {

    /**
     * Verify a new change carries no tags.
     */
    @Test
    void newChangeHasNoTags() {
        // given / when
        PomChange change = PomChange.functional(PomChangeCategory.BUILD_PLUGINS, "plugin x changed");

        // then
        assertNull(change.getPlugin());
        assertNull(change.getProfile());
    }

    /**
     * Verify appending to the description keeps the category and both tags.
     */
    @Test
    void descriptionSuffixKeepsCategoryAndTags() {
        // given
        PomChange change = PomChange.functional(PomChangeCategory.BUILD_PLUGINS, "plugin x changed")
                .inPlugin("com.acme:x").inProfile("deploy");

        // when
        PomChange relabelled = change.withDescriptionSuffix(" (in module-a/pom.xml)");

        // then
        assertEquals("plugin x changed (in module-a/pom.xml)", relabelled.getDescription());
        assertEquals(PomChangeCategory.BUILD_PLUGINS, relabelled.getCategory());
        assertEquals("com.acme:x", relabelled.getPlugin());
        assertEquals("deploy", relabelled.getProfile());
    }

    /**
     * Verify a change keeps the profile it was first tagged with.
     */
    @Test
    void firstProfileTagWins() {
        // given
        PomChange change = PomChange.inert("x").inProfile("inner");

        // when
        PomChange retagged = change.inProfile("outer");

        // then
        assertEquals("inner", retagged.getProfile());
    }

    /**
     * Verify equality ignores the tags, which are already part of the description.
     */
    @Test
    void equalityIgnoresTags() {
        // given
        PomChange plain = PomChange.functional(PomChangeCategory.BUILD_PLUGINS, "plugin x changed");

        // when
        PomChange tagged = plain.inPlugin("com.acme:x");

        // then
        assertEquals(plain, tagged);
        assertEquals(plain.hashCode(), tagged.hashCode());
    }
}
