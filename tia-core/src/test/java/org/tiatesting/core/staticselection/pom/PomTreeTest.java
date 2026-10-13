package org.tiatesting.core.staticselection.pom;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.tiatesting.core.staticselection.pom.TestPoms.modules;
import static org.tiatesting.core.staticselection.pom.TestPoms.parent;
import static org.tiatesting.core.staticselection.pom.TestPoms.project;
import static org.tiatesting.core.staticselection.pom.TestPoms.properties;

/**
 * Unit tests for {@link PomTree}: path resolution, in-repo parent lookup, inherited properties,
 * reactor discovery, child modules and batched reads.
 */
class PomTreeTest {

    private static final Predicate<String> NEW = path -> true;

    @Test
    void pomReferencesResolveRelativeToTheReferringDirectory() {
        // given
        String directory = "services/orders";

        // when
        String module = PomTree.pomFileAt(directory, "api");
        String parent = PomTree.pomFileAt(directory, "../../pom.xml");
        String alternate = PomTree.pomFileAt(directory, "./build/alt-pom.xml");
        String root = PomTree.pomFileAt("", ".");

        // then
        assertEquals("services/orders/api/pom.xml", module);
        assertEquals("pom.xml", parent);
        assertEquals("services/orders/build/alt-pom.xml", alternate);
        assertEquals("pom.xml", root);
    }

    @Test
    void referencesLeavingTheRepositoryAreRejected() {
        // given
        String directory = "app";

        // when
        String escaping = PomTree.pomFileAt(directory, "../../shared/pom.xml");
        String absolute = PomTree.pomFileAt(directory, "/etc/pom.xml");

        // then
        assertNull(escaping);
        assertNull(absolute);
    }

    @Test
    void parentIsFoundOnlyWhenCoordinatesAndVersionMatch() {
        // given - module-a's parent is the root; module-b names an external parent; module-c names
        // the root's coordinates at a version the repository does not have
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .unchanged("pom.xml", project("com.acme", "root", "1.0", modules("module-a")))
                .unchanged("module-a/pom.xml", project(null, "a", null, parent("com.acme", "root", "1.0")))
                .unchanged("module-b/pom.xml", project("com.acme", "b", "1.0",
                        parent("org.springframework.boot", "spring-boot-starter-parent", "3.2.0")))
                .unchanged("module-c/pom.xml", project(null, "c", null, parent("com.acme", "root", "0.9")));
        PomTree tree = new PomTree(repository, "base", false, null);

        // when
        String parentOfA = tree.parentPath("module-a/pom.xml", NEW);
        String parentOfB = tree.parentPath("module-b/pom.xml", NEW);
        String parentOfC = tree.parentPath("module-c/pom.xml", NEW);

        // then
        assertEquals("pom.xml", parentOfA);
        assertNull(parentOfB);
        assertNull(parentOfC);
    }

    @Test
    void emptyRelativePathSkipsTheRepositoryLookup() {
        // given
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .unchanged("pom.xml", project("com.acme", "root", "1.0"))
                .unchanged("app/pom.xml", project(null, "app", null, parent("com.acme", "root", "1.0", "")));
        PomTree tree = new PomTree(repository, "base", false, null);

        // when
        String parent = tree.parentPath("app/pom.xml", NEW);

        // then
        assertNull(parent);
    }

    @Test
    void ciFriendlyParentVersionResolvesAgainstTheParentsProperties() {
        // given - root version ${revision}, child declares the parent at ${revision}
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .unchanged("pom.xml", project("com.acme", "root", "${revision}", properties("revision", "2.0")))
                .unchanged("app/pom.xml", project(null, "app", null, parent("com.acme", "root", "${revision}")));
        PomTree tree = new PomTree(repository, "base", false, null);

        // when
        String parent = tree.parentPath("app/pom.xml", NEW);

        // then
        assertEquals("pom.xml", parent);
    }

    @Test
    void inheritedPropertiesMergeAncestorsNearestFirst() {
        // given - grandparent and parent both define lib.version; the parent wins
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .unchanged("pom.xml", project("com.acme", "root", "1.0",
                        properties("lib.version", "1", "other", "x")))
                .unchanged("platform/pom.xml", project(null, "platform", null, parent("com.acme", "root", "1.0"),
                        properties("lib.version", "2")))
                .unchanged("platform/app/pom.xml", project(null, "app", null,
                        parent("com.acme", "platform", "1.0")));
        PomTree tree = new PomTree(repository, "base", false, null);

        // when
        Map<String, String> inherited = tree.inheritedProperties("platform/app/pom.xml", NEW);

        // then
        assertEquals("2", inherited.get("lib.version"));
        assertEquals("x", inherited.get("other"));
    }

    @Test
    void reactorIsTheTopMostAggregatorTreeIncludingThePom() {
        // given - root aggregates parent and services; services aggregates orders
        InMemoryPomRepository repository = reactorWithSeparateParent();
        PomTree tree = new PomTree(repository, "base", false, null);

        // when
        Set<String> reactor = tree.reactor("services/orders/pom.xml");

        // then
        assertEquals(new HashSet<>(Arrays.asList("pom.xml", "parent/pom.xml", "services/pom.xml",
                "services/orders/pom.xml")), reactor);
    }

    @Test
    void childrenAreFoundThroughTheReactorEvenWhenTheParentDoesNotAggregate() {
        // given - parent/pom.xml lists no modules; orders declares it as parent via relativePath
        InMemoryPomRepository repository = reactorWithSeparateParent();
        PomTree tree = new PomTree(repository, "base", false, null);

        // when
        Set<String> children = tree.children("parent/pom.xml");

        // then
        assertEquals(new LinkedHashSet<>(Collections.singletonList("services/orders/pom.xml")), children);
    }

    @Test
    void reactorModulesUseInheritedGroupIds() {
        // given
        InMemoryPomRepository repository = reactorWithSeparateParent();
        PomTree tree = new PomTree(repository, "base", false, null);

        // when
        Set<String> modules = tree.reactorModules("services/orders/pom.xml");

        // then
        assertTrue(modules.contains("com.acme:orders"), modules.toString());
        assertTrue(modules.contains("com.acme:acme-parent"), modules.toString());
    }

    @Test
    void parentChainsAreReadOneLevelPerCall() {
        // given - two changed modules three levels deep
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .unchanged("pom.xml", project("com.acme", "root", "1.0"))
                .unchanged("a/pom.xml", project(null, "a", null, parent("com.acme", "root", "1.0")))
                .unchanged("a/x/pom.xml", project(null, "x", null, parent("com.acme", "a", "1.0")))
                .unchanged("a/y/pom.xml", project(null, "y", null, parent("com.acme", "a", "1.0")));
        PomTree tree = new PomTree(repository, "base", false, null);

        // when
        tree.loadParentChains(Arrays.asList("a/x/pom.xml", "a/y/pom.xml"));

        // then - the modules, then a/pom.xml, then pom.xml
        assertEquals(3, repository.getRequests().size(), repository.getRequests().toString());
        assertEquals(Collections.singleton("a/pom.xml"), repository.getRequests().get(1));
    }

    /**
     * A reactor whose aggregator and parent are separate POMs: the root aggregates
     * {@code parent} and {@code services}; {@code services} aggregates {@code orders}, whose
     * parent is {@code ../../parent/pom.xml}.
     *
     * @return the repository.
     */
    private static InMemoryPomRepository reactorWithSeparateParent() {
        return new InMemoryPomRepository()
                .unchanged("pom.xml", project("com.acme", "aggregator", "1.0", modules("parent", "services")))
                .unchanged("parent/pom.xml", project("com.acme", "acme-parent", "1.0",
                        "<packaging>pom</packaging>", properties("lib.version", "1.0")))
                .unchanged("services/pom.xml", project("com.acme", "services", "1.0", modules("orders")))
                .unchanged("services/orders/pom.xml", project(null, "orders", null,
                        parent("com.acme", "acme-parent", "1.0", "../../parent/pom.xml")));
    }
}
