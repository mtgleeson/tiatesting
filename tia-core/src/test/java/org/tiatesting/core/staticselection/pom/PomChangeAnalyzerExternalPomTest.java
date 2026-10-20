package org.tiatesting.core.staticselection.pom;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.tiatesting.core.staticselection.pom.PomChangeCategory.BUILD_PLUGINS;
import static org.tiatesting.core.staticselection.pom.PomChangeCategory.DEPENDENCY_VERSIONS;
import static org.tiatesting.core.staticselection.pom.TestPoms.dep;
import static org.tiatesting.core.staticselection.pom.TestPoms.dependencies;
import static org.tiatesting.core.staticselection.pom.TestPoms.modules;
import static org.tiatesting.core.staticselection.pom.TestPoms.parent;
import static org.tiatesting.core.staticselection.pom.TestPoms.project;
import static org.tiatesting.core.staticselection.pom.TestPoms.properties;

/**
 * Tests for classifying external parent and imported BOM version bumps by what they change, with
 * the external POMs read through an {@link ExternalPomSource}.
 */
class PomChangeAnalyzerExternalPomTest {

    private static final String ROOT = "pom.xml";
    private static final String MODULE_A = "module-a/pom.xml";
    private static final String BOOT = "org.boot";

    @Test
    void externalParentBumpWithOnlyItsVersionChangedIsInert() {
        // given
        MapPomSource source = new MapPomSource()
                .add(BOOT, "boot-parent", "1", bootParent("1", properties("x", "1")))
                .add(BOOT, "boot-parent", "2", bootParent("2", properties("x", "1")));
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, rootWithParent("1"), rootWithParent("2"));

        // when
        PomChangeReport report = analyze(repository, source, ROOT);

        // then
        assertTrue(report.getCategories().isEmpty(), report.toString());
        assertTrue(report.getChanges().get(0).getDescription().endsWith("(no functional changes in it)"),
                report.toString());
    }

    @Test
    void externalParentManagedDependencyBumpIsDependencyVersionsLabelledWithTheParent() {
        // given
        MapPomSource source = new MapPomSource()
                .add(BOOT, "boot-parent", "1", bootParent("1", managed(dep("org.lib", "lib", "1.0"))))
                .add(BOOT, "boot-parent", "2", bootParent("2", managed(dep("org.lib", "lib", "1.1"))));
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, rootWithParent("1"), rootWithParent("2"));

        // when
        PomChangeReport report = analyze(repository, source, ROOT);

        // then
        assertEquals(EnumSet.of(DEPENDENCY_VERSIONS), report.getCategories());
        assertTrue(report.getChanges().stream().anyMatch(change -> change.getDescription()
                .equals("managed dependency org.lib:lib version 1.0 -> 1.1 (in parent org.boot:boot-parent:2)")),
                report.toString());
    }

    @Test
    void externalParentManagedTrackedLibraryBumpIsInert() {
        // given
        MapPomSource source = new MapPomSource()
                .add(BOOT, "boot-parent", "1", bootParent("1", managed(dep("com.example", "widgets", "1.0"))))
                .add(BOOT, "boot-parent", "2", bootParent("2", managed(dep("com.example", "widgets", "1.1"))));
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, rootWithParent("1"), rootWithParent("2"));

        // when
        PomChangeReport report = analyze(repository, source, ROOT);

        // then
        assertTrue(report.getCategories().isEmpty(), report.toString());
    }

    @Test
    void externalParentPluginManagementChangeKeepsItsCategory() {
        // given
        String plugin = "<build><pluginManagement><plugins><plugin><artifactId>exec-maven-plugin</artifactId>"
                + "<version>%s</version></plugin></plugins></pluginManagement></build>";
        MapPomSource source = new MapPomSource()
                .add(BOOT, "boot-parent", "1", bootParent("1", String.format(plugin, "3.0")))
                .add(BOOT, "boot-parent", "2", bootParent("2", String.format(plugin, "3.1")));
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, rootWithParent("1"), rootWithParent("2"));

        // when
        PomChangeReport report = analyze(repository, source, ROOT);

        // then
        assertEquals(EnumSet.of(BUILD_PLUGINS), report.getCategories());
    }

    @Test
    void externalParentPropertyUsedByTheRootIsClassifiedByTheRootsUse() {
        // given - the parent bumps lib.version; the in-repo root uses it for a dependency
        MapPomSource source = new MapPomSource()
                .add(BOOT, "boot-parent", "1", bootParent("1", properties("lib.version", "1.0")))
                .add(BOOT, "boot-parent", "2", bootParent("2", properties("lib.version", "2.0")));
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, rootWithParent("1", dependencies(dep("org.lib", "lib", "${lib.version}"))),
                        rootWithParent("2", dependencies(dep("org.lib", "lib", "${lib.version}"))));

        // when
        PomChangeReport report = analyze(repository, source, ROOT);

        // then - the root's own dependency change; the parent's property itself is inert
        assertEquals(EnumSet.of(DEPENDENCY_VERSIONS), report.getCategories());
        assertTrue(report.getChanges().stream().anyMatch(change -> change.getDescription()
                .equals("dependency org.lib:lib version 1.0 -> 2.0")), report.toString());
    }

    @Test
    void externalParentPropertyUsedByAModuleIsAttributedToTheModule() {
        // given - only module-a uses the external parent's lib.version
        MapPomSource source = new MapPomSource()
                .add(BOOT, "boot-parent", "1", bootParent("1", properties("lib.version", "1.0")))
                .add(BOOT, "boot-parent", "2", bootParent("2", properties("lib.version", "2.0")));
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, rootWithParent("1", modules("module-a")), rootWithParent("2", modules("module-a")))
                .unchanged(MODULE_A, project(null, "a", null, parent("com.acme", "root", "1.0"),
                        dependencies(dep("org.lib", "lib", "${lib.version}"))));

        // when
        PomChangeReport report = analyze(repository, source, ROOT);

        // then
        assertEquals(EnumSet.of(DEPENDENCY_VERSIONS), report.getCategories());
        assertTrue(report.getChanges().stream().anyMatch(change -> change.getDescription()
                .startsWith("dependency org.lib:lib version 1.0 -> 2.0 (in module-a/pom.xml)")), report.toString());
    }

    @Test
    void grandparentBumpIsFollowedThroughTheParent() {
        // given - boot-parent 1 -> 2 moves its own parent boot-dependencies 1 -> 2, which bumps a managed lib
        MapPomSource source = new MapPomSource()
                .add(BOOT, "boot-parent", "1", project(BOOT, "boot-parent", "1", parent(BOOT, "boot-dependencies", "1"),
                        "<packaging>pom</packaging>"))
                .add(BOOT, "boot-parent", "2", project(BOOT, "boot-parent", "2", parent(BOOT, "boot-dependencies", "2"),
                        "<packaging>pom</packaging>"))
                .add(BOOT, "boot-dependencies", "1", project(BOOT, "boot-dependencies", "1", "<packaging>pom</packaging>",
                        properties("lib.version", "1.0"), managed(dep("org.lib", "lib", "${lib.version}"))))
                .add(BOOT, "boot-dependencies", "2", project(BOOT, "boot-dependencies", "2", "<packaging>pom</packaging>",
                        properties("lib.version", "1.1"), managed(dep("org.lib", "lib", "${lib.version}"))));
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, rootWithParent("1"), rootWithParent("2"));

        // when
        PomChangeReport report = analyze(repository, source, ROOT);

        // then
        assertEquals(EnumSet.of(DEPENDENCY_VERSIONS), report.getCategories());
        assertTrue(report.getChanges().stream().anyMatch(change -> change.getDescription()
                .contains("managed dependency org.lib:lib version 1.0 -> 1.1")), report.toString());
    }

    @Test
    void externalParentMissingFromTheSourceIsDependencyVersions() {
        // given - only the new version is available
        MapPomSource source = new MapPomSource()
                .add(BOOT, "boot-parent", "2", bootParent("2"));
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, rootWithParent("1"), rootWithParent("2"));

        // when
        PomChangeReport report = analyze(repository, source, ROOT);

        // then
        assertEquals(EnumSet.of(DEPENDENCY_VERSIONS), report.getCategories());
        assertEquals("external parent org.boot:boot-parent version 1 -> 2", report.getChanges().get(0).getDescription());
    }

    @Test
    void externalParentPropertiesResolveInRepoValues() {
        // given - the root switches a dependency between two properties its external parent defines
        MapPomSource source = new MapPomSource()
                .add(BOOT, "boot-parent", "1", bootParent("1", properties("old.version", "1.0", "new.version", "2.0")));
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, rootWithParent("1", dependencies(dep("org.lib", "lib", "${old.version}"))),
                        rootWithParent("1", dependencies(dep("org.lib", "lib", "${new.version}"))));

        // when
        PomChangeReport report = analyze(repository, source, ROOT);

        // then
        assertEquals("dependency org.lib:lib version 1.0 -> 2.0", report.getChanges().get(0).getDescription());
    }

    @Test
    void bomBumpCountsOnlyItsDependencyManagement() {
        // given - the BOM changes a plugin and a property nobody uses, and bumps a managed library
        MapPomSource source = new MapPomSource()
                .add("org.platform", "bom", "1", bom("1", "<properties><unused>a</unused></properties>",
                        managed(dep("org.lib", "lib", "1.0")), "<build><plugins><plugin><artifactId>exec-maven-plugin"
                                + "</artifactId><version>1</version></plugin></plugins></build>"))
                .add("org.platform", "bom", "2", bom("2", "<properties><unused>b</unused></properties>",
                        managed(dep("org.lib", "lib", "1.1")), "<build><plugins><plugin><artifactId>exec-maven-plugin"
                                + "</artifactId><version>2</version></plugin></plugins></build>"));
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, rootImporting("1"), rootImporting("2"));

        // when
        PomChangeReport report = analyze(repository, source, ROOT);

        // then
        assertEquals(EnumSet.of(DEPENDENCY_VERSIONS), report.getCategories());
        assertTrue(report.getChanges().stream().anyMatch(change -> change.getDescription()
                .equals("managed dependency org.lib:lib version 1.0 -> 1.1 (in BOM org.platform:bom:2)")),
                report.toString());
    }

    @Test
    void bomBumpWithNoDependencyChangesIsInert() {
        // given - only the BOM's own version and an unused property changed
        MapPomSource source = new MapPomSource()
                .add("org.platform", "bom", "1", bom("1", properties("unused", "a"), managed(dep("org.lib", "lib", "1.0"))))
                .add("org.platform", "bom", "2", bom("2", properties("unused", "b"), managed(dep("org.lib", "lib", "1.0"))));
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, rootImporting("1"), rootImporting("2"));

        // when
        PomChangeReport report = analyze(repository, source, ROOT);

        // then
        assertTrue(report.getCategories().isEmpty(), report.toString());
    }

    @Test
    void bomBumpOfOnlyATrackedLibraryIsInert() {
        // given
        MapPomSource source = new MapPomSource()
                .add("org.platform", "bom", "1", bom("1", managed(dep("com.example", "widgets", "1.0"))))
                .add("org.platform", "bom", "2", bom("2", managed(dep("com.example", "widgets", "1.1"))));
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, rootImporting("1"), rootImporting("2"));

        // when
        PomChangeReport report = analyze(repository, source, ROOT);

        // then
        assertTrue(report.getCategories().isEmpty(), report.toString());
    }

    @Test
    void bomMissingFromTheSourceIsDependencyVersions() {
        // given - no source content at all
        InMemoryPomRepository repository = new InMemoryPomRepository()
                .changed(ROOT, rootImporting("1"), rootImporting("2"));

        // when
        PomChangeReport report = analyze(repository, new MapPomSource(), ROOT);

        // then
        assertEquals(EnumSet.of(DEPENDENCY_VERSIONS), report.getCategories());
    }

    /**
     * Analyze one changed POM with {@code com.example:widgets} tracked.
     *
     * @param repository the repository.
     * @param source the external POM source.
     * @param path the changed POM.
     * @return its report.
     */
    private static PomChangeReport analyze(InMemoryPomRepository repository, ExternalPomSource source, String path) {
        PomChangeAnalyzer analyzer = new PomChangeAnalyzer(repository, "base", false, Collections.<String>emptySet(),
                new PomChangeClassifier(Collections.singletonList("com.example:widgets")), source);
        return analyzer.analyze(Collections.singleton(path)).get(path);
    }

    /**
     * @param version the parent version.
     * @param elements extra elements.
     * @return the in-repo root {@code com.acme:root:1.0} with the external {@code org.boot:boot-parent} parent.
     */
    private static String rootWithParent(String version, String... elements) {
        String[] all = new String[elements.length + 1];
        all[0] = parent(BOOT, "boot-parent", version);
        System.arraycopy(elements, 0, all, 1, elements.length);
        return project("com.acme", "root", "1.0", all);
    }

    /**
     * @param bomVersion the imported BOM version.
     * @return the in-repo root importing {@code org.platform:bom}.
     */
    private static String rootImporting(String bomVersion) {
        return project("com.acme", "root", "1.0", "<dependencyManagement><dependencies><dependency>"
                + "<groupId>org.platform</groupId><artifactId>bom</artifactId><version>" + bomVersion + "</version>"
                + "<type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>");
    }

    /**
     * @param version the version.
     * @param elements extra elements.
     * @return the external {@code org.boot:boot-parent} POM.
     */
    private static String bootParent(String version, String... elements) {
        String[] all = new String[elements.length + 1];
        all[0] = "<packaging>pom</packaging>";
        System.arraycopy(elements, 0, all, 1, elements.length);
        return project(BOOT, "boot-parent", version, all);
    }

    /**
     * @param version the version.
     * @param elements extra elements.
     * @return the external {@code org.platform:bom} POM.
     */
    private static String bom(String version, String... elements) {
        String[] all = new String[elements.length + 1];
        all[0] = "<packaging>pom</packaging>";
        System.arraycopy(elements, 0, all, 1, elements.length);
        return project("org.platform", "bom", version, all);
    }

    /**
     * @param dependencies dependency elements.
     * @return a {@code <dependencyManagement>} element.
     */
    private static String managed(String... dependencies) {
        return "<dependencyManagement>" + dependencies(dependencies) + "</dependencyManagement>";
    }

    /**
     * External POM source backed by a map.
     */
    private static final class MapPomSource implements ExternalPomSource {
        private final Map<String, String> poms = new HashMap<>();

        /**
         * @param groupId the groupId.
         * @param artifactId the artifactId.
         * @param version the version.
         * @param pom the POM text.
         * @return this source.
         */
        MapPomSource add(String groupId, String artifactId, String version, String pom) {
            poms.put(groupId + ":" + artifactId + ":" + version, pom);
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String readPom(String groupId, String artifactId, String version) {
            return poms.get(groupId + ":" + artifactId + ":" + version);
        }
    }
}
