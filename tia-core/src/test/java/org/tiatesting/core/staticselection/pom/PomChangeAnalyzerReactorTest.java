package org.tiatesting.core.staticselection.pom;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.tiatesting.core.staticselection.pom.PomChangeCategory.DEPENDENCY_VERSIONS;
import static org.tiatesting.core.staticselection.pom.PomChangeCategory.OTHER;
import static org.tiatesting.core.staticselection.pom.TestPoms.dep;
import static org.tiatesting.core.staticselection.pom.TestPoms.dependencies;
import static org.tiatesting.core.staticselection.pom.TestPoms.modules;
import static org.tiatesting.core.staticselection.pom.TestPoms.parent;
import static org.tiatesting.core.staticselection.pom.TestPoms.project;
import static org.tiatesting.core.staticselection.pom.TestPoms.properties;

/**
 * Tests for {@link PomChangeAnalyzer} classifying POMs in the environment of the rest of the
 * repository: properties consumed by child modules, tracked libraries versioned from a parent,
 * real reactor membership and in-repo parents.
 */
class PomChangeAnalyzerReactorTest {

    private static final String ROOT = "pom.xml";
    private static final String MODULE_A = "module-a/pom.xml";

    @Test
    void parentPropertyConsumedByAChildDependencyIsDependencyVersions() {
        // given - the root bumps lib.version, which only module-a uses
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, root(properties("lib.version", "1.0")), root(properties("lib.version", "2.0")))
                .unchanged(MODULE_A, module("a", dependencies(dep("org.lib", "lib", "${lib.version}"))));

        // when
        PomChangeReport report = analyze(repository, ROOT);

        // then - the property itself is inert; module-a's dependency change is attributed to the root
        assertEquals(EnumSet.of(DEPENDENCY_VERSIONS), report.getCategories());
        assertTrue(report.getChanges().stream().anyMatch(change -> change.getDescription()
                .equals("dependency org.lib:lib version 1.0 -> 2.0 (in module-a/pom.xml)")), report.toString());
    }

    @Test
    void trackedLibraryVersionPropertyInAParentIsInert() {
        // given - the root bumps the version property of a tracked library used by module-a
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, root(properties("widgets.version", "1.0")), root(properties("widgets.version", "1.1")))
                .unchanged(MODULE_A, module("a", dependencies(dep("com.example", "widgets", "${widgets.version}"))));

        // when
        PomChangeReport report = analyze(repository, ROOT);

        // then
        assertTrue(report.getCategories().isEmpty(), report.toString());
    }

    @Test
    void parentPropertyNothingReferencesIsOther() {
        // given
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, root(properties("unused", "a")), root(properties("unused", "b")))
                .unchanged(MODULE_A, module("a"));

        // when
        PomChangeReport report = analyze(repository, ROOT);

        // then
        assertEquals(EnumSet.of(OTHER), report.getCategories());
        assertTrue(report.getChanges().get(0).getDescription().endsWith("(not referenced in this POM or its modules)"));
    }

    @Test
    void childOverridingThePropertyIsUnaffected() {
        // given - module-a defines its own lib.version, shadowing the root's
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, root(properties("lib.version", "1.0")), root(properties("lib.version", "2.0")))
                .unchanged(MODULE_A, module("a", properties("lib.version", "9.9"),
                        dependencies(dep("org.lib", "lib", "${lib.version}"))));

        // when
        PomChangeReport report = analyze(repository, ROOT);

        // then
        assertTrue(report.getCategories().isEmpty(), report.toString());
    }

    @Test
    void aChildsOwnEditIsNotAttributedToTheParent() {
        // given - the root changes an unrelated managed property; module-a bumps its own dependency
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, root(properties("doc.title", "A")), root(properties("doc.title", "B")))
                .changed(MODULE_A, module("a", "<name>${doc.title}</name>", dependencies(dep("org.lib", "lib", "1.0"))),
                        module("a", "<name>${doc.title}</name>", dependencies(dep("org.lib", "lib", "2.0"))));

        // when
        PomChangeReport rootReport = analyze(repository, ROOT);
        PomChangeReport moduleReport = analyze(repository, MODULE_A);

        // then - the root's change only renames module-a (inert); the dependency bump is module-a's
        assertTrue(rootReport.getCategories().isEmpty(), rootReport.toString());
        assertEquals(EnumSet.of(DEPENDENCY_VERSIONS), moduleReport.getCategories());
    }

    @Test
    void changedChildResolvesVersionsFromItsInRepoParent() {
        // given - module-a switches its dependency from one root property to another
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .unchanged(ROOT, root(properties("old.version", "1.0", "new.version", "2.0")))
                .changed(MODULE_A, module("a", dependencies(dep("org.lib", "lib", "${old.version}"))),
                        module("a", dependencies(dep("org.lib", "lib", "${new.version}"))));

        // when
        PomChangeReport report = analyze(repository, MODULE_A);

        // then - the description shows the resolved versions
        assertEquals("dependency org.lib:lib version 1.0 -> 2.0", report.getChanges().get(0).getDescription());
    }

    @Test
    void siblingModuleDependencyIsInertWhateverItsVersion() {
        // given - module-a depends on module-b at a literal version that moved, not in lockstep
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .unchanged(ROOT, root())
                .changed(MODULE_A, module("a", dependencies(dep("com.acme", "b", "3.0"))),
                        module("a", dependencies(dep("com.acme", "b", "3.1"))))
                .unchanged("module-b/pom.xml", module("b"));

        // when
        PomChangeReport report = analyze(repository, MODULE_A);

        // then
        assertTrue(report.getCategories().isEmpty(), report.toString());
    }

    @Test
    void externalLibraryWithTheProjectsGroupIdIsNotAReactorModule() {
        // given - com.acme:legacy is not in the reactor, though it moves in lockstep with the project
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, rootAt("1.0", dependencies(dep("com.acme", "legacy", "1.0"))),
                        rootAt("1.1", dependencies(dep("com.acme", "legacy", "1.1"))))
                .unchanged(MODULE_A, module("a"));

        // when
        PomChangeReport report = analyze(repository, ROOT);

        // then - the single-file heuristic would have called it a reactor module
        assertEquals(EnumSet.of(DEPENDENCY_VERSIONS), report.getCategories());
    }

    @Test
    void inRepoParentBumpIsInertEvenWithoutLockstep() {
        // given - module-a keeps its own version while the in-repo parent moves
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, rootAt("1.0"), rootAt("1.1"))
                .changed(MODULE_A, project(null, "a", "5.0", parent("com.acme", "root", "1.0")),
                        project(null, "a", "5.0", parent("com.acme", "root", "1.1")));

        // when
        PomChangeReport report = analyze(repository, MODULE_A);

        // then
        assertTrue(report.getCategories().isEmpty(), report.toString());
    }

    @Test
    void parentPointingAtAVersionNotInTheRepositoryIsExternal() {
        // given - module-a moves to a root version the repository does not hold
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .unchanged(ROOT, rootAt("1.0"))
                .changed(MODULE_A, project(null, "a", null, parent("com.acme", "root", "1.0")),
                        project(null, "a", null, parent("com.acme", "root", "0.9")));

        // when
        PomChangeReport report = analyze(repository, MODULE_A);

        // then
        assertTrue(report.getCategories().contains(DEPENDENCY_VERSIONS), report.toString());
    }

    @Test
    void separateParentAndAggregatorLayoutAttributesChildChanges() {
        // given - the root aggregates parent/ and orders/; orders' parent is ../parent/pom.xml
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .unchanged(ROOT, project("com.acme", "aggregator", "1.0", modules("parent", "orders")))
                .changed("parent/pom.xml",
                        project("com.acme", "acme-parent", "1.0", "<packaging>pom</packaging>", properties("lib.version", "1")),
                        project("com.acme", "acme-parent", "1.0", "<packaging>pom</packaging>", properties("lib.version", "2")))
                .unchanged("orders/pom.xml", project(null, "orders", null,
                        parent("com.acme", "acme-parent", "1.0", "../parent/pom.xml"),
                        dependencies(dep("org.lib", "lib", "${lib.version}"))));

        // when
        PomChangeReport report = analyze(repository, "parent/pom.xml");

        // then
        assertEquals(EnumSet.of(DEPENDENCY_VERSIONS), report.getCategories());
    }

    @Test
    void reactorIsNotReadWhenNoDecisionNeedsIt() {
        // given - a module whose only change is a compiler property
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .unchanged(ROOT, root())
                .changed(MODULE_A, module("a", properties("maven.compiler.release", "17")),
                        module("a", properties("maven.compiler.release", "21")))
                .unchanged("module-b/pom.xml", module("b"));

        // when
        analyze(repository, MODULE_A);

        // then - module-b (only reachable through the reactor walk) was never read
        assertFalse(repository.getRequests().stream().anyMatch(request -> request.contains("module-b/pom.xml")),
                repository.getRequests().toString());
    }

    /**
     * Analyze one changed POM with {@code com.example:widgets} as the tracked library.
     *
     * @param repository the repository.
     * @param path the changed POM.
     * @return its report.
     */
    private static PomChangeReport analyze(InMemoryPomRepository repository, String path) {
        PomChangeAnalyzer analyzer = new PomChangeAnalyzer(repository, "base", false,
                new PomChangeClassifier(Collections.singletonList("com.example:widgets")), null);
        Set<String> paths = new HashSet<>(Arrays.asList(path));
        return analyzer.analyze(paths).get(path);
    }

    /**
     * @param elements extra elements.
     * @return the root aggregator {@code com.acme:root:1.0} listing module-a and module-b.
     */
    private static String root(String... elements) {
        return rootAt("1.0", elements);
    }

    /**
     * @param version the root version.
     * @param elements extra elements.
     * @return the root aggregator {@code com.acme:root} listing module-a and module-b.
     */
    private static String rootAt(String version, String... elements) {
        String[] all = new String[elements.length + 1];
        all[0] = modules("module-a", "module-b");
        System.arraycopy(elements, 0, all, 1, elements.length);
        return project("com.acme", "root", version, all);
    }

    /**
     * @param artifactId the module artifactId.
     * @param elements extra elements.
     * @return a module inheriting groupId and version from {@code com.acme:root:1.0}.
     */
    private static String module(String artifactId, String... elements) {
        String[] all = new String[elements.length + 1];
        all[0] = parent("com.acme", "root", "1.0");
        System.arraycopy(elements, 0, all, 1, elements.length);
        return project(null, artifactId, null, all);
    }
}
