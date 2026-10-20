package org.tiatesting.core.staticselection.pom;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.tiatesting.core.staticselection.pom.TestPoms.modules;
import static org.tiatesting.core.staticselection.pom.TestPoms.parent;
import static org.tiatesting.core.staticselection.pom.TestPoms.project;

/**
 * Tests for {@link PomReferences}: which POM files the changed POMs of a diff point at through
 * {@code <modules>} or their parent's relative path, on each side of the diff.
 */
class PomReferencesTest {

    /**
     * Verify a module added to an aggregator is referenced on the new side only.
     */
    @Test
    void moduleAddedToAnAggregatorIsReferencedOnTheNewSideOnly() {
        // given
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed("pom.xml", project("com.acme", "root", "1.0", modules("a")),
                        project("com.acme", "root", "1.0", modules("a", "b")));

        // when
        PomReferences references = build(repository, "pom.xml");

        // then
        assertTrue(references.isReferenced("b/pom.xml", true));
        assertFalse(references.isReferenced("b/pom.xml", false));
        assertTrue(references.isReferenced("a/pom.xml", false));
    }

    /**
     * Verify a module listed only inside a profile counts as a reference.
     */
    @Test
    void moduleListedInAProfileIsReferenced() {
        // given
        String profile = "<profiles><profile><id>extra</id><modules><module>extra</module></modules></profile></profiles>";
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed("pom.xml", project("com.acme", "root", "1.0"), project("com.acme", "root", "1.1", profile));

        // when
        PomReferences references = build(repository, "pom.xml");

        // then
        assertTrue(references.isReferenced("extra/pom.xml", true));
    }

    /**
     * Verify a parent's explicit relative path and the default {@code ../pom.xml} both count.
     */
    @Test
    void parentRelativePathsAreReferenced() {
        // given
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed("app/pom.xml", project(null, "app", null, parent("com.acme", "base", "1.0", "../base")),
                        project(null, "app", null, parent("com.acme", "base", "1.1", "../base")))
                .changed("lib/core/pom.xml", project(null, "core", null, parent("com.acme", "lib", "1.0")),
                        project(null, "core", null, parent("com.acme", "lib", "1.1")));

        // when
        PomReferences references = build(repository, "app/pom.xml", "lib/core/pom.xml");

        // then
        assertTrue(references.isReferenced("base/pom.xml", true));
        assertTrue(references.isReferenced("lib/pom.xml", false));
    }

    /**
     * Verify an empty relative path, which tells Maven to skip the repository lookup, is not a
     * reference.
     */
    @Test
    void emptyRelativePathIsNotAReference() {
        // given
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed("app/pom.xml", project(null, "app", null, parent("com.acme", "base", "1.0", "")),
                        project(null, "app", null, parent("com.acme", "base", "1.1", "")));

        // when
        PomReferences references = build(repository, "app/pom.xml");

        // then
        assertFalse(references.isReferenced("pom.xml", true));
        assertFalse(references.isReferenced("pom.xml", false));
    }

    /**
     * Verify the referring POMs are read in one batched VCS call and a missing or unparseable POM
     * is skipped rather than failing.
     */
    @Test
    void referringPomsAreReadInOneCallAndUnreadablePomsAreSkipped() {
        // given
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed("pom.xml", "not xml", project("com.acme", "root", "1.0", modules("a")));

        // when
        PomReferences references = build(repository, "pom.xml", "gone/pom.xml");

        // then
        assertEquals(1, repository.getRequests().size());
        assertTrue(references.isReferenced("a/pom.xml", true));
        assertFalse(references.isReferenced("a/pom.xml", false));
    }

    /**
     * Build the index over the given referring POMs.
     *
     * @param repository the in-memory repository.
     * @param referringPoms the changed POMs whose references are indexed.
     * @return the index.
     */
    private static PomReferences build(InMemoryPomRepository repository, String... referringPoms) {
        PomTree tree = new PomTree(repository, "base", false, null);
        return PomReferences.build(tree, Arrays.asList(referringPoms));
    }
}
