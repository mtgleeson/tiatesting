package org.tiatesting.core.staticselection.pom;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.tiatesting.core.staticselection.pom.PomChangeCategory.OTHER;
import static org.tiatesting.core.staticselection.pom.TestPoms.dep;
import static org.tiatesting.core.staticselection.pom.TestPoms.dependencies;
import static org.tiatesting.core.staticselection.pom.TestPoms.modules;
import static org.tiatesting.core.staticselection.pom.TestPoms.parent;
import static org.tiatesting.core.staticselection.pom.TestPoms.project;

/**
 * Tests for {@link PomChangeAnalyzer} classifying an added or deleted POM by whether another
 * changed POM in the diff lists it as a module or points its parent's relative path at it.
 */
class PomChangeAnalyzerAddedPomTest {

    private static final String RELEASE_COPY = "libs/parentpom/releases/2.04.23.0/pom.xml";
    private static final String LIVE_PARENT = "libs/parentpom/java21/pom.xml";

    /**
     * Verify the build 358 shape: a frozen release copy of a parent POM that nothing references
     * is inert, even when another changed POM bumps its external parent to the copy's version.
     */
    @Test
    void unreferencedReleaseCopyIsInert() {
        // given
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .added(RELEASE_COPY, project("com.acme", "parentpom", "2.04.23.0"))
                .changed("app/pom.xml", project(null, "app", null, parent("com.acme", "parentpom", "2.04.22.0")),
                        project(null, "app", null, parent("com.acme", "parentpom", "2.04.23.0")));

        // when
        PomChangeReport report = analyze(repository, setOf(RELEASE_COPY, "app/pom.xml"), RELEASE_COPY);

        // then
        assertTrue(report.getCategories().isEmpty(), report.toString());
        assertEquals("POM added; no changed POM lists it as a module or parent (inert)",
                report.getChanges().get(0).toString());
    }

    /**
     * Verify an added POM listed as a new module of a changed aggregator is {@code OTHER}.
     */
    @Test
    void addedModuleOfAChangedAggregatorIsOther() {
        // given
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed("pom.xml", project("com.acme", "root", "1.0", modules("a")),
                        project("com.acme", "root", "1.0", modules("a", "b")))
                .added("b/pom.xml", project(null, "b", null, parent("com.acme", "root", "1.0")));

        // when
        PomChangeReport report = analyze(repository, setOf("pom.xml", "b/pom.xml"), "b/pom.xml");

        // then
        assertEquals(EnumSet.of(OTHER), report.getCategories());
        assertEquals("POM added; a changed POM lists it as a module or parent (OTHER)",
                report.getChanges().get(0).toString());
    }

    /**
     * Verify an added POM that a changed POM now uses as its relative-path parent is {@code OTHER}.
     */
    @Test
    void addedRelativePathParentIsOther() {
        // given
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .added("base/pom.xml", project("com.acme", "base", "1.0"))
                .changed("app/pom.xml", project("com.acme", "app", "1.0"),
                        project(null, "app", null, parent("com.acme", "base", "1.0", "../base")));

        // when
        PomChangeReport report = analyze(repository, setOf("base/pom.xml", "app/pom.xml"), "base/pom.xml");

        // then
        assertEquals(EnumSet.of(OTHER), report.getCategories());
    }

    /**
     * Verify a new dependency on an added POM's coordinates is not by itself a reference: Maven
     * resolves it from the repository unless the POM is a reactor module.
     */
    @Test
    void dependencyOnAnAddedPomsCoordinatesIsNotAReference() {
        // given
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .added("tools/pom.xml", project("com.acme", "tools", "1.0"))
                .changed("app/pom.xml", project("com.acme", "app", "1.0"),
                        project("com.acme", "app", "1.0", dependencies(dep("com.acme", "tools", "1.0"))));

        // when
        PomChangeReport report = analyze(repository, setOf("tools/pom.xml", "app/pom.xml"), "tools/pom.xml");

        // then
        assertTrue(report.getCategories().isEmpty(), report.toString());
    }

    /**
     * Verify a deleted POM is inert when no changed POM listed it, and {@code OTHER} when a changed
     * aggregator listed it as a module before the change.
     */
    @Test
    void deletedPomIsClassifiedByItsOldSideReferences() {
        // given
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed("pom.xml", project("com.acme", "root", "1.0", modules("a", "b")),
                        project("com.acme", "root", "1.0", modules("a")))
                .deleted("b/pom.xml", project(null, "b", null, parent("com.acme", "root", "1.0")))
                .deleted(RELEASE_COPY, project("com.acme", "parentpom", "2.04.23.0"));

        // when
        PomChangeReport module = analyze(repository, setOf("pom.xml", "b/pom.xml", RELEASE_COPY), "b/pom.xml");
        PomChangeReport copy = analyze(repository, setOf("pom.xml", "b/pom.xml", RELEASE_COPY), RELEASE_COPY);

        // then
        assertEquals(EnumSet.of(OTHER), module.getCategories());
        assertTrue(copy.getCategories().isEmpty(), copy.toString());
        assertEquals("POM deleted; no changed POM listed it as a module or parent (inert)",
                copy.getChanges().get(0).toString());
    }

    /**
     * Verify a changed {@code pom.xml} that was not analysed (outside every rule's pattern) still
     * counts as a referrer, read in one extra batched call.
     */
    @Test
    void changedPomOutsideTheAnalysedSetIsReadOnceAsAReferrer() {
        // given - only the added module is analysed; its aggregator changed but is not
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed("pom.xml", project("com.acme", "root", "1.0", modules("a")),
                        project("com.acme", "root", "1.0", modules("a", "b")))
                .added("b/pom.xml", project("com.acme", "b", "1.0"));

        // when
        PomChangeReport report = analyze(repository, setOf("pom.xml", "b/pom.xml", "src/A.java"), "b/pom.xml");

        // then
        assertEquals(EnumSet.of(OTHER), report.getCategories());
        long readsOfRoot = repository.getRequests().stream().filter(request -> request.contains("pom.xml")).count();
        assertEquals(1, readsOfRoot);
    }

    /**
     * Verify no POM beyond the analysed paths is read when nothing was added or deleted.
     */
    @Test
    void noReferrerIsReadWhenNoPomWasAddedOrDeleted() {
        // given
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed("app/pom.xml", project("com.acme", "app", "1.0"), project("com.acme", "app", "1.1"))
                .changed("other/pom.xml", project("com.acme", "other", "1.0"), project("com.acme", "other", "1.1"));

        // when
        analyze(repository, setOf("app/pom.xml", "other/pom.xml"), "app/pom.xml");

        // then
        assertFalse(repository.getRequests().stream().anyMatch(request -> request.contains("other/pom.xml")));
    }

    /**
     * Analyse one POM in a diff whose changed paths are given.
     *
     * @param repository the in-memory repository.
     * @param changedPaths every changed path in the diff.
     * @param path the POM to analyse.
     * @return its report.
     */
    private static PomChangeReport analyze(InMemoryPomRepository repository, Set<String> changedPaths, String path) {
        PomChangeAnalyzer analyzer = new PomChangeAnalyzer(repository, "base", false, changedPaths,
                new PomChangeClassifier(Collections.<String>emptyList()), null);
        return analyzer.analyze(Collections.singleton(path)).get(path);
    }

    /**
     * @param values the values.
     * @return an insertion-ordered set of them.
     */
    private static Set<String> setOf(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}
