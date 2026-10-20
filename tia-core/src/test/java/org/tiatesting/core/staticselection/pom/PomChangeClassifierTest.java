package org.tiatesting.core.staticselection.pom;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.tiatesting.core.staticselection.pom.PomChangeCategory.BUILD_PLUGINS;
import static org.tiatesting.core.staticselection.pom.PomChangeCategory.CODE_GENERATION;
import static org.tiatesting.core.staticselection.pom.PomChangeCategory.COMPILER;
import static org.tiatesting.core.staticselection.pom.PomChangeCategory.DEPENDENCY_SET;
import static org.tiatesting.core.staticselection.pom.PomChangeCategory.DEPENDENCY_VERSIONS;
import static org.tiatesting.core.staticselection.pom.PomChangeCategory.OTHER;
import static org.tiatesting.core.staticselection.pom.PomChangeCategory.RESOURCES;
import static org.tiatesting.core.staticselection.pom.PomChangeCategory.TEST_RUNNER;

/**
 * Unit tests for {@link PomChangeClassifier}: one or more tests per category, per inert rule and
 * per failure mode.
 */
class PomChangeClassifierTest {

    private static final String TRACKED = "com.example:widgets";

    private final PomChangeClassifier classifier = new PomChangeClassifier(Collections.singleton(TRACKED));

    // ---- equivalence ----

    @Test
    void identicalPomsHaveNoChanges() {
        // given
        String pom = jarPom("1.0", dependency("org.lib", "lib", "1.0"));

        // when
        PomChangeReport report = classifier.classify(pom, pom);

        // then
        assertTrue(report.getChanges().isEmpty());
    }

    @Test
    void whitespaceCommentsAndDependencyOrderAreNotChanges() {
        // given
        String before = jarPom("1.0", "<dependencies>" + dep("org.a", "a", "1") + dep("org.b", "b", "2") + "</dependencies>");
        String after = jarPom("1.0", "<!-- reordered -->\n  <dependencies>\n    "
                + dep("org.b", "b", "2") + "\n\n" + dep("org.a", "a", "1") + "\n  </dependencies>");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertTrue(report.getChanges().isEmpty());
    }

    // ---- release bumps ----

    @Test
    void releaseBumpWithReactorParentAndModuleDependenciesIsInert() {
        // given - child module inheriting its version from a reactor parent, depending on a
        // sibling through ${project.version} and on another sibling by a literal lockstep version
        String before = pom(parent("com.acme", "acme-parent", "1.0"), "<artifactId>module-b</artifactId>",
                "<dependencies>" + dep("com.acme", "module-a", "${project.version}")
                        + dep("com.acme", "module-c", "1.0") + "</dependencies>");
        String after = pom(parent("com.acme", "acme-parent", "1.1"), "<artifactId>module-b</artifactId>",
                "<dependencies>" + dep("com.acme", "module-a", "${project.version}")
                        + dep("com.acme", "module-c", "1.1") + "</dependencies>");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then - the version, parent and literal sibling changes are recorded, all inert
        assertCategories(report);
        assertTrue(report.getChanges().size() >= 3, report.toString());
    }

    @Test
    void projectVersionReferenceIsAReactorModuleWhateverItsGroupId() {
        // given - a sibling module published under another groupId, versioned by ${project.version}
        String before = jarPom("1.0", dependency("com.acme.tools", "codegen", "${project.version}"));
        String after = jarPom("1.1", dependency("com.acme.tools", "codegen", "${project.version}"));

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report);
        assertTrue(report.getChanges().stream()
                .anyMatch(change -> change.getDescription().startsWith("reactor module com.acme.tools:codegen")),
                report.toString());
    }

    @Test
    void literalVersionMatchingTheProjectVersionUnderAnotherGroupIdIsDependencyVersions() {
        // given - an external library that happens to move to the same numbers as the project
        String before = jarPom("1.0", dependency("org.lib", "lib", "1.0"));
        String after = jarPom("1.1", dependency("org.lib", "lib", "1.1"));

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, DEPENDENCY_VERSIONS);
    }

    @Test
    void ownVersionBumpIsInert() {
        // given
        String before = jarPom("1.0");
        String after = jarPom("1.1");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report);
        assertEquals("project version 1.0 -> 1.1", report.getChanges().get(0).getDescription());
    }

    @Test
    void ciFriendlyRevisionPropertyInAParentIsInert() {
        // given - a pom-packaged root whose version is ${revision}
        String before = pom("<groupId>com.acme</groupId><artifactId>root</artifactId><version>${revision}</version>",
                "<packaging>pom</packaging><modules><module>a</module></modules>",
                "<properties><revision>1.0</revision></properties>");
        String after = before.replace("<revision>1.0</revision>", "<revision>1.1</revision>");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report);
    }

    // ---- parent ----

    @Test
    void externalParentVersionBumpIsDependencyVersions() {
        // given - a module with its own version and an external parent
        String before = pom(parent("org.springframework.boot", "spring-boot-starter-parent", "3.2.0"),
                "<groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>");
        String after = before.replace("3.2.0", "3.3.0");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, DEPENDENCY_VERSIONS);
    }

    @Test
    void replacingTheParentIsOther() {
        // given
        String before = pom(parent("com.acme", "parent-a", "1"), "<artifactId>app</artifactId>");
        String after = pom(parent("com.acme", "parent-b", "1"), "<artifactId>app</artifactId>");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, OTHER);
    }

    // ---- dependency versions ----

    @Test
    void externalDependencyVersionBumpIsDependencyVersions() {
        // given
        String before = jarPom("1.0", dependency("org.lib", "lib", "1.0"));
        String after = jarPom("1.0", dependency("org.lib", "lib", "2.0"));

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, DEPENDENCY_VERSIONS);
        assertEquals("dependency org.lib:lib version 1.0 -> 2.0", report.getChanges().get(0).getDescription());
    }

    @Test
    void dependencyVersionBumpThroughAPropertyIsClassifiedByItsUse() {
        // given - a jar module (no children) bumping a version property it references
        String before = jarPom("1.0", "<properties><lib.version>1.0</lib.version></properties>",
                dependency("org.lib", "lib", "${lib.version}"));
        String after = before.replace("<lib.version>1.0</lib.version>", "<lib.version>2.0</lib.version>");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then - the dependency is DEPENDENCY_VERSIONS; the property itself is inert
        assertCategories(report, DEPENDENCY_VERSIONS);
        assertEquals(2, report.getChanges().size(), report.toString());
    }

    @Test
    void trackedLibraryVersionBumpIsInert() {
        // given
        String before = jarPom("1.0", dependency("com.example", "widgets", "1.0"));
        String after = jarPom("1.0", dependency("com.example", "widgets", "1.1"));

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report);
        assertTrue(report.getChanges().get(0).getDescription().startsWith("tracked library com.example:widgets"));
    }

    @Test
    void trackedLibraryVersionBumpThroughAPropertyIsInert() {
        // given
        String before = jarPom("1.0", "<properties><widgets.version>1.0</widgets.version></properties>",
                dependency("com.example", "widgets", "${widgets.version}"));
        String after = before.replace("<widgets.version>1.0", "<widgets.version>1.1");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report);
    }

    @Test
    void sharedPropertyDrivingTrackedAndUntrackedDependenciesIsDependencyVersions() {
        // given
        String before = jarPom("1.0", "<properties><suite.version>1.0</suite.version></properties>",
                "<dependencies>" + dep("com.example", "widgets", "${suite.version}")
                        + dep("com.example", "gadgets", "${suite.version}") + "</dependencies>");
        String after = before.replace("<suite.version>1.0", "<suite.version>1.1");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, DEPENDENCY_VERSIONS);
    }

    @Test
    void managedDependencyAndBomImportChangesAreDependencyVersions() {
        // given - a new managed version pin and a BOM version bump
        String bom = "<dependency><groupId>org.platform</groupId><artifactId>bom</artifactId><version>%s</version>"
                + "<type>pom</type><scope>import</scope></dependency>";
        String before = jarPom("1.0", "<dependencyManagement><dependencies>" + String.format(bom, "1")
                + "</dependencies></dependencyManagement>");
        String after = jarPom("1.0", "<dependencyManagement><dependencies>" + String.format(bom, "2")
                + dep("org.lib", "lib", "3.0") + "</dependencies></dependencyManagement>");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, DEPENDENCY_VERSIONS);
        assertEquals(2, report.getChanges().size(), report.toString());
    }

    @Test
    void managedTrackedLibraryAddedIsInert() {
        // given
        String before = jarPom("1.0");
        String after = jarPom("1.0", "<dependencyManagement><dependencies>" + dep("com.example", "widgets", "1.0")
                + "</dependencies></dependencyManagement>");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report);
    }

    // ---- dependency set ----

    @Test
    void addedAndRemovedDependenciesAreDependencySet() {
        // given
        String before = jarPom("1.0", dependency("org.a", "a", "1"));
        String after = jarPom("1.0", dependency("org.b", "b", "1"));

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, DEPENDENCY_SET);
        assertEquals(2, report.getChanges().size(), report.toString());
    }

    @Test
    void trackedLibraryScopeChangeIsDependencySet() {
        // given
        String before = jarPom("1.0", dependency("com.example", "widgets", "1.0"));
        String after = jarPom("1.0", "<dependencies><dependency><groupId>com.example</groupId>"
                + "<artifactId>widgets</artifactId><version>1.0</version><scope>test</scope></dependency></dependencies>");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, DEPENDENCY_SET);
        assertTrue(report.getChanges().get(0).getDescription().contains("scope compile -> test"));
    }

    @Test
    void exclusionChangeIsDependencySet() {
        // given
        String before = jarPom("1.0", dependency("org.a", "a", "1"));
        String after = jarPom("1.0", "<dependencies><dependency><groupId>org.a</groupId><artifactId>a</artifactId>"
                + "<version>1</version><exclusions><exclusion><groupId>org.x</groupId><artifactId>x</artifactId>"
                + "</exclusion></exclusions></dependency></dependencies>");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, DEPENDENCY_SET);
    }

    // ---- plugins ----

    @Test
    void compilerPluginConfigurationIsCompiler() {
        // given
        String before = jarPom("1.0", build(plugin("maven-compiler-plugin", "<configuration><release>17</release></configuration>")));
        String after = jarPom("1.0", build(plugin("maven-compiler-plugin", "<configuration><release>21</release></configuration>")));

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, COMPILER);
        assertTrue(report.getChanges().get(0).getDescription().contains("configuration"));
    }

    @Test
    void compilerPropertyIsCompiler() {
        // given - read implicitly by the compiler plugin, never referenced in the POM
        String before = jarPom("1.0", "<properties><maven.compiler.release>17</maven.compiler.release></properties>");
        String after = before.replace(">17<", ">21<");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, COMPILER);
    }

    @Test
    void surefireConfigurationAndArgLinePropertyAreTestRunner() {
        // given
        String before = jarPom("1.0", "<properties><argLine>-Xmx1g</argLine></properties>",
                build(plugin("maven-surefire-plugin", "<configuration><forkCount>1</forkCount></configuration>")));
        String after = jarPom("1.0", "<properties><argLine>-Xmx2g</argLine></properties>",
                build(plugin("maven-surefire-plugin", "<configuration><forkCount>2</forkCount></configuration>")));

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, TEST_RUNNER);
        assertEquals(2, report.getChanges().size(), report.toString());
    }

    @Test
    void generatorPluginIsCodeGeneration() {
        // given
        String before = jarPom("1.0", build("<plugin><groupId>org.openapitools</groupId>"
                + "<artifactId>openapi-generator-maven-plugin</artifactId><version>7.0.0</version></plugin>"));
        String after = before.replace("7.0.0", "7.1.0");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, CODE_GENERATION);
        assertTrue(report.getChanges().get(0).getDescription().contains("version 7.0.0 -> 7.1.0"));
    }

    @Test
    void unknownPluginAndManagedPluginAreBuildPlugins() {
        // given
        String before = jarPom("1.0", "<build><plugins>" + plugin("exec-maven-plugin", "") + "</plugins>"
                + "<pluginManagement><plugins>" + plugin("maven-shade-plugin", "") + "</plugins></pluginManagement></build>");
        String after = before.replace("<version>1.0</version></plugin>", "<version>2.0</version></plugin>");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, BUILD_PLUGINS);
        assertEquals(2, report.getChanges().size(), report.toString());
    }

    @Test
    void inertPluginsAndTiaConfigurationAreInert() {
        // given - javadoc plugin, Tia's plugin and a tia* property all change
        String tiaPlugin = "<plugin><groupId>org.tiatesting</groupId><artifactId>tia-maven-plugin</artifactId>"
                + "<configuration><tiaEnabled>%s</tiaEnabled></configuration></plugin>";
        String before = jarPom("1.0", "<properties><tiaProjectDir>a</tiaProjectDir></properties>",
                build(plugin("maven-javadoc-plugin", "") + String.format(tiaPlugin, "true")));
        String after = jarPom("1.0", "<properties><tiaProjectDir>b</tiaProjectDir></properties>",
                build(plugin("maven-javadoc-plugin", "<configuration><quiet>true</quiet></configuration>")
                        + String.format(tiaPlugin, "false")));

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report);
        assertEquals(3, report.getChanges().size(), report.toString());
    }

    // ---- build elements and other sections ----

    @Test
    void resourceFilteringIsResources() {
        // given
        String before = jarPom("1.0", "<build><resources><resource><directory>src/main/resources</directory>"
                + "</resource></resources></build>");
        String after = before.replace("</directory>", "</directory><filtering>true</filtering>");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, RESOURCES);
    }

    @Test
    void metadataAndFinalNameAreInert() {
        // given
        String before = jarPom("1.0", "<scm><url>a</url></scm><developers><developer><id>x</id></developer></developers>",
                "<build><finalName>a</finalName></build>");
        String after = jarPom("1.0", "<scm><url>b</url></scm><developers><developer><id>y</id></developer></developers>",
                "<build><finalName>b</finalName></build>");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report);
        assertEquals(3, report.getChanges().size(), report.toString());
    }

    /**
     * Verify that, with no knowledge of other POMs, an added or deleted POM stays {@code OTHER}.
     */
    @Test
    void addedOrDeletedPomWithoutAnEnvironmentIsOther() {
        // given
        String content = pom("<groupId>com.acme</groupId>", "<artifactId>app</artifactId>", "<version>1.0</version>");

        // when
        PomChangeReport added = classifier.classify(null, content);
        PomChangeReport deleted = classifier.classify(content, null);

        // then
        assertCategories(added, OTHER);
        assertCategories(deleted, OTHER);
        assertEquals("POM added (OTHER)", added.getChanges().get(0).toString());
    }

    @Test
    void modulesPackagingAndRepositoriesAreOther() {
        // given
        String before = jarPom("1.0");
        String after = jarPom("1.0", "<packaging>pom</packaging><modules><module>a</module></modules>",
                "<repositories><repository><id>r</id><url>https://repo</url></repository></repositories>");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, OTHER);
        assertEquals(3, report.getChanges().size(), report.toString());
    }

    // ---- properties ----

    @Test
    void unreferencedPropertyIsOther() {
        // given
        String before = jarPom("1.0", "<properties><some.flag>a</some.flag></properties>");
        String after = before.replace(">a<", ">b<");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, OTHER);
        assertTrue(report.getChanges().get(0).getDescription().endsWith("(not referenced in this POM)"));
    }

    @Test
    void referencedPropertyInAPossibleParentIsOther() {
        // given - a pom-packaged POM: children may reference the property too
        String before = pom("<groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version>",
                "<packaging>pom</packaging><properties><widgets.version>1.0</widgets.version></properties>",
                "<dependencyManagement><dependencies>" + dep("com.example", "widgets", "${widgets.version}")
                        + "</dependencies></dependencyManagement>");
        String after = before.replace("<widgets.version>1.0", "<widgets.version>1.1");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then - the managed tracked library is inert, the property itself is OTHER
        assertCategories(report, OTHER);
        assertTrue(report.getChanges().stream()
                .anyMatch(change -> change.getDescription().endsWith("(child modules may reference it)")), report.toString());
    }

    @Test
    void propertyUsedOnlyByAnInertPluginIsInert() {
        // given
        String before = jarPom("1.0", "<properties><doc.title>A</doc.title></properties>",
                build(plugin("maven-javadoc-plugin", "<configuration><doctitle>${doc.title}</doctitle></configuration>")));
        String after = before.replace(">A<", ">B<");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report);
    }

    // ---- profiles ----

    @Test
    void profileContentIsClassifiedWithItsLabel() {
        // given - a dependency added inside an existing profile
        String before = jarPom("1.0", "<profiles><profile><id>ci</id></profile></profiles>");
        String after = jarPom("1.0", "<profiles><profile><id>ci</id>" + dependency("org.a", "a", "1")
                + "</profile></profiles>");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, DEPENDENCY_SET);
        assertTrue(report.getChanges().get(0).getDescription().endsWith(" in profile ci"));
    }

    @Test
    void profileActivationChangeIsOther() {
        // given
        String before = jarPom("1.0", "<profiles><profile><id>ci</id><activation><property><name>ci</name>"
                + "</property></activation></profile></profiles>");
        String after = before.replace("<name>ci</name>", "<name>build</name>");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, OTHER);
    }

    @Test
    void profilePropertyOverrideIsResolvedInsideTheProfile() {
        // given - a profile overriding a version property that the profile's dependency uses
        String profile = "<profiles><profile><id>ci</id><properties><lib.version>%s</lib.version></properties>"
                + dependency("org.lib", "lib", "${lib.version}") + "</profile></profiles>";
        String before = jarPom("1.0", "<properties><lib.version>1.0</lib.version></properties>",
                String.format(profile, "2.0"));
        String after = jarPom("1.0", "<properties><lib.version>1.0</lib.version></properties>",
                String.format(profile, "3.0"));

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report, DEPENDENCY_VERSIONS);
        assertTrue(report.getChanges().stream()
                .anyMatch(change -> change.getDescription().contains("version 2.0 -> 3.0 in profile ci")), report.toString());
    }

    @Test
    void addedProfileWithOnlyInertContentIsInert() {
        // given
        String before = jarPom("1.0");
        String after = jarPom("1.0", "<profiles><profile><id>release</id><activation><property><name>release</name>"
                + "</property></activation>" + build(plugin("maven-gpg-plugin", "")) + "</profile></profiles>");

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertCategories(report);
    }

    /**
     * Verify a plugin change is tagged with the plugin's resolved key and no profile.
     */
    @Test
    void pluginChangeIsTaggedWithItsKey() {
        // given
        String before = jarPom("1.0", build(plugin("maven-compiler-plugin", "<configuration><release>17</release></configuration>")));
        String after = jarPom("1.0", build(plugin("maven-compiler-plugin", "<configuration><release>21</release></configuration>")));

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertEquals("org.apache.maven.plugins:maven-compiler-plugin", report.getChanges().get(0).getPlugin());
        assertNull(report.getChanges().get(0).getProfile());
    }

    /**
     * Verify every change inside a profile, whether a plugin, a property or the activation, is
     * tagged with the profile id.
     */
    @Test
    void changesInsideAProfileAreTaggedWithIt() {
        // given
        String profile = "<profiles><profile><id>DeployToGCS</id><activation><property><name>%s</name></property>"
                + "</activation><properties><gcs.region>%s</gcs.region></properties>"
                + build(plugin("gcs-upload-plugin", "<configuration><bucket>%s</bucket></configuration>"))
                + "</profile></profiles>";
        String before = jarPom("1.0", String.format(profile, "deploy", "us", "a"));
        String after = jarPom("1.0", String.format(profile, "gcs", "eu", "b"));

        // when
        PomChangeReport report = classifier.classify(before, after);

        // then
        assertEquals(3, report.getChanges().size(), report.toString());
        assertTrue(report.getChanges().stream().allMatch(change -> "DeployToGCS".equals(change.getProfile())),
                report.toString());
        assertTrue(report.getChanges().stream().anyMatch(change ->
                "org.apache.maven.plugins:gcs-upload-plugin".equals(change.getPlugin())), report.toString());
    }

    // ---- added, deleted, unparseable ----

    @Test
    void addedDeletedAndUnparseablePomsAreOther() {
        // given
        String pom = jarPom("1.0");

        // when
        PomChangeReport added = classifier.classify(null, pom);
        PomChangeReport deleted = classifier.classify(pom, null);
        PomChangeReport broken = classifier.classify(pom, "<project><unclosed></project>");
        PomChangeReport notAPom = classifier.classify(pom, "<settings/>");

        // then
        assertCategories(added, OTHER);
        assertCategories(deleted, OTHER);
        assertCategories(broken, OTHER);
        assertCategories(notAPom, OTHER);
    }

    @Test
    void doctypeDeclarationsAreRejected() {
        // given - an external entity payload
        String pom = jarPom("1.0");
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE project [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<project><modelVersion>4.0.0</modelVersion><name>&x;</name></project>";

        // when
        PomChangeReport report = classifier.classify(pom, xxe);

        // then - treated as unparseable rather than expanded
        assertCategories(report, OTHER);
        assertTrue(report.getChanges().get(0).getDescription().startsWith("POM could not be parsed"));
    }

    @Test
    void reportFiresOnlyForCoveredCategories() {
        // given
        String before = jarPom("1.0", dependency("org.lib", "lib", "1.0"));
        String after = jarPom("1.0", dependency("org.lib", "lib", "2.0"));
        PomChangeReport report = classifier.classify(before, after);

        // when
        boolean firesForFunctional = report.firesFor(EnumSet.of(PomChangeCategory.FUNCTIONAL));
        boolean firesForVersions = report.firesFor(EnumSet.of(DEPENDENCY_VERSIONS, COMPILER));
        boolean firesForTestRunner = report.firesFor(EnumSet.of(TEST_RUNNER));

        // then
        assertTrue(firesForFunctional);
        assertTrue(firesForVersions);
        assertFalse(firesForTestRunner);
    }

    // ---- helpers ----

    /**
     * Assert the report's functional categories are exactly the given ones (none = all inert).
     *
     * @param report the report.
     * @param expected the expected categories.
     */
    private static void assertCategories(PomChangeReport report, PomChangeCategory... expected) {
        Set<PomChangeCategory> expectedSet = expected.length == 0
                ? EnumSet.noneOf(PomChangeCategory.class) : EnumSet.copyOf(Arrays.asList(expected));
        assertEquals(expectedSet, report.getCategories(), report.toString());
    }

    /**
     * Wrap elements in a {@code <project>} with the POM namespace and model version.
     *
     * @param elements the project's child elements.
     * @return the POM text.
     */
    private static String pom(String... elements) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n<modelVersion>4.0.0</modelVersion>\n"
                + String.join("\n", elements) + "\n</project>\n";
    }

    /**
     * A jar module {@code com.acme:app} at the given version.
     *
     * @param version the project version.
     * @param elements further child elements.
     * @return the POM text.
     */
    private static String jarPom(String version, String... elements) {
        return pom("<groupId>com.acme</groupId><artifactId>app</artifactId><version>" + version + "</version>",
                String.join("\n", elements));
    }

    /**
     * @param groupId the parent groupId.
     * @param artifactId the parent artifactId.
     * @param version the parent version.
     * @return a {@code <parent>} element.
     */
    private static String parent(String groupId, String artifactId, String version) {
        return "<parent><groupId>" + groupId + "</groupId><artifactId>" + artifactId + "</artifactId><version>"
                + version + "</version></parent>";
    }

    /**
     * @param groupId the groupId.
     * @param artifactId the artifactId.
     * @param version the version.
     * @return a {@code <dependency>} element.
     */
    private static String dep(String groupId, String artifactId, String version) {
        return "<dependency><groupId>" + groupId + "</groupId><artifactId>" + artifactId + "</artifactId><version>"
                + version + "</version></dependency>";
    }

    /**
     * @param groupId the groupId.
     * @param artifactId the artifactId.
     * @param version the version.
     * @return a {@code <dependencies>} element holding one dependency.
     */
    private static String dependency(String groupId, String artifactId, String version) {
        return "<dependencies>" + dep(groupId, artifactId, version) + "</dependencies>";
    }

    /**
     * @param artifactId an {@code org.apache.maven.plugins} plugin (groupId left to the default).
     * @param body extra plugin children, e.g. a configuration.
     * @return a {@code <plugin>} element at version 1.0.
     */
    private static String plugin(String artifactId, String body) {
        return "<plugin><artifactId>" + artifactId + "</artifactId>" + body + "<version>1.0</version></plugin>";
    }

    /**
     * @param plugins plugin elements.
     * @return a {@code <build>} element holding the plugins.
     */
    private static String build(String plugins) {
        return "<build><plugins>" + plugins + "</plugins></build>";
    }
}
