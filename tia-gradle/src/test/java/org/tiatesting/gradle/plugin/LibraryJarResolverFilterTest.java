package org.tiatesting.gradle.plugin;

import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.GradleModuleVersion;
import org.gradle.tooling.model.ProjectIdentifier;
import org.gradle.tooling.model.eclipse.ClasspathAttribute;
import org.gradle.tooling.model.eclipse.EclipseSourceDirectory;
import org.gradle.tooling.model.gradle.GradlePublication;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.LibraryBuildMetadata;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link LibraryJarResolver}'s source-dir filter. The Tooling-API model
 * objects ({@link EclipseSourceDirectory}, {@link ClasspathAttribute}) are interfaces, so a small
 * fake suffices — we don't need to spin up a Gradle build to verify the filter rules.
 */
class LibraryJarResolverFilterTest {

    @Test
    void mainScopeSourcesAreIncluded() {
        FakeSourceDir entry = new FakeSourceDir("/lib/src/main/java")
                .withAttribute("gradle_used_by_scope", "main");
        assertTrue(LibraryJarResolver.isMainCodeSourceDir(entry, entry.dir));
    }

    @Test
    void testScopeSourcesAreExcluded() {
        FakeSourceDir entry = new FakeSourceDir("/lib/src/test/java")
                .withAttribute("gradle_used_by_scope", "test");
        assertFalse(LibraryJarResolver.isMainCodeSourceDir(entry, entry.dir));
    }

    @Test
    void mainCommaTestScopeIsIncluded() {
        // Gradle sometimes emits "main,test" for sources used by both scopes — keep them.
        FakeSourceDir entry = new FakeSourceDir("/lib/src/main/java")
                .withAttribute("gradle_used_by_scope", "main,test");
        assertTrue(LibraryJarResolver.isMainCodeSourceDir(entry, entry.dir));
    }

    @Test
    void resourcesDirectoryIsAlwaysExcluded() {
        FakeSourceDir mainResources = new FakeSourceDir("/lib/src/main/resources")
                .withAttribute("gradle_used_by_scope", "main");
        assertFalse(LibraryJarResolver.isMainCodeSourceDir(mainResources, mainResources.dir),
                "src/main/resources must be excluded even with main scope.");

        FakeSourceDir testResources = new FakeSourceDir("/lib/src/test/resources")
                .withAttribute("gradle_used_by_scope", "test");
        assertFalse(LibraryJarResolver.isMainCodeSourceDir(testResources, testResources.dir));
    }

    @Test
    void missingScopeAttributeFallsBackToPathContainsSrcMain() {
        // Path filter (option B): include only if path contains /src/main/.
        FakeSourceDir mainJava = new FakeSourceDir("/lib/src/main/java");  // no attribute
        assertTrue(LibraryJarResolver.isMainCodeSourceDir(mainJava, mainJava.dir));

        FakeSourceDir mainGroovy = new FakeSourceDir("/lib/src/main/groovy");
        assertTrue(LibraryJarResolver.isMainCodeSourceDir(mainGroovy, mainGroovy.dir));

        FakeSourceDir testJava = new FakeSourceDir("/lib/src/test/java");
        assertFalse(LibraryJarResolver.isMainCodeSourceDir(testJava, testJava.dir),
                "Path lacking /src/main/ must be excluded when scope attribute is absent.");

        FakeSourceDir customLayout = new FakeSourceDir("/lib/customSourceRoot");
        assertFalse(LibraryJarResolver.isMainCodeSourceDir(customLayout, customLayout.dir),
                "Non-standard layout without scope attribute is excluded — known false-negative we accept.");
    }

    @Test
    void nullDirectoryIsExcluded() {
        FakeSourceDir entry = new FakeSourceDir(null);
        assertFalse(LibraryJarResolver.isMainCodeSourceDir(entry, null));
    }

    @Test
    void matchPublicationsReturnsVersionForMatchingCoordinate() {
        // The reported runtime bug: a library's metadata read returned no version, so the
        // recorder skipped the pending stamp. Fix: look up the library's own publication and
        // read its declared version.
        List<GradlePublication> pubs = Collections.singletonList(
                new FakePublication("com.example", "spock-lib", "1.1.1-SNAPSHOT"));
        List<LibraryBuildMetadata> result = LibraryJarResolver.matchPublications(
                pubs, Arrays.asList("com.example:spock-lib"));
        assertEquals(1, result.size());
        assertEquals("com.example:spock-lib", result.get(0).getGroupArtifact());
        assertEquals("1.1.1-SNAPSHOT", result.get(0).getDeclaredVersion());
    }

    @Test
    void matchPublicationsIgnoresNonMatchingPublications() {
        List<GradlePublication> pubs = Arrays.asList(
                new FakePublication("com.other", "different-lib", "9.9.9"),
                new FakePublication("com.example", "spock-lib", "1.1.1-SNAPSHOT"));
        List<LibraryBuildMetadata> result = LibraryJarResolver.matchPublications(
                pubs, Arrays.asList("com.example:spock-lib"));
        assertEquals(1, result.size());
        assertEquals("1.1.1-SNAPSHOT", result.get(0).getDeclaredVersion());
    }

    @Test
    void matchPublicationsHandlesNoPublications() {
        List<LibraryBuildMetadata> result = LibraryJarResolver.matchPublications(
                Collections.<GradlePublication>emptyList(),
                Arrays.asList("com.example:spock-lib"));
        assertTrue(result.isEmpty());
    }

    @Test
    void matchPublicationsHandlesNullPublicationsArg() {
        // ProjectPublications.getPublications() should never be null in practice, but the helper
        // is defensive — better than NPE inside the connect() try/catch swallowing the cause.
        List<LibraryBuildMetadata> result = LibraryJarResolver.matchPublications(
                null, Arrays.asList("com.example:spock-lib"));
        assertTrue(result.isEmpty());
    }

    @Test
    void matchPublicationsTrimsCoordinateWhitespace() {
        List<GradlePublication> pubs = Collections.singletonList(
                new FakePublication("com.example", "spock-lib", "1.1.1-SNAPSHOT"));
        List<LibraryBuildMetadata> result = LibraryJarResolver.matchPublications(
                pubs, Arrays.asList("  com.example:spock-lib  "));
        assertEquals(1, result.size());
    }

    @Test
    void matchPublicationsSupportsMultipleCoordinates() {
        List<GradlePublication> pubs = Arrays.asList(
                new FakePublication("com.example", "lib-a", "1.0"),
                new FakePublication("com.example", "lib-b", "2.0"),
                new FakePublication("com.example", "lib-c", "3.0"));
        List<LibraryBuildMetadata> result = LibraryJarResolver.matchPublications(
                pubs, Arrays.asList("com.example:lib-a", "com.example:lib-c"));
        assertEquals(2, result.size());
    }

    @Test
    void looksLikeGradleProjectRootDetectsBuildAndSettingsFiles() throws Exception {
        File tmp = File.createTempFile("tia-gradle-root-", "");
        tmp.delete();
        tmp.mkdirs();
        try {
            assertFalse(LibraryJarResolver.looksLikeGradleProjectRoot(tmp),
                    "Empty dir must not look like a Gradle project root.");

            File buildFile = new File(tmp, "build.gradle");
            buildFile.createNewFile();
            assertTrue(LibraryJarResolver.looksLikeGradleProjectRoot(tmp),
                    "build.gradle present → Gradle project root.");
            buildFile.delete();

            File ktsFile = new File(tmp, "build.gradle.kts");
            ktsFile.createNewFile();
            assertTrue(LibraryJarResolver.looksLikeGradleProjectRoot(tmp),
                    "build.gradle.kts present → Gradle project root.");
            ktsFile.delete();

            File settingsFile = new File(tmp, "settings.gradle");
            settingsFile.createNewFile();
            assertTrue(LibraryJarResolver.looksLikeGradleProjectRoot(tmp),
                    "settings.gradle present → Gradle project root.");
            settingsFile.delete();
        } finally {
            tmp.delete();
        }
    }

    /**
     * Minimal in-memory fake for the Eclipse Tooling-API source-directory model. Only implements
     * what {@link LibraryJarResolver#isMainCodeSourceDir} actually inspects.
     */
    private static class FakeSourceDir implements EclipseSourceDirectory {
        final File dir;
        final Map<String, String> attributes = new LinkedHashMap<>();

        FakeSourceDir(String path) {
            this.dir = path != null ? new File(path) : null;
        }

        FakeSourceDir withAttribute(String name, String value) {
            attributes.put(name, value);
            return this;
        }

        @Override public File getDirectory() { return dir; }
        @Override public String getPath() { return dir != null ? dir.getAbsolutePath() : null; }
        @Override public String getOutput() { return null; }

        @Override
        public DomainObjectSet<? extends ClasspathAttribute> getClasspathAttributes() {
            List<ClasspathAttribute> attrs = new ArrayList<>();
            for (Map.Entry<String, String> e : attributes.entrySet()) {
                attrs.add(new FakeAttribute(e.getKey(), e.getValue()));
            }
            return new FakeDomainObjectSet<>(attrs);
        }

        @Override public java.util.List<String> getExcludes() { return java.util.Collections.emptyList(); }
        @Override public java.util.List<String> getIncludes() { return java.util.Collections.emptyList(); }
        @Override public DomainObjectSet<? extends org.gradle.tooling.model.eclipse.AccessRule> getAccessRules() {
            return new FakeDomainObjectSet<org.gradle.tooling.model.eclipse.AccessRule>(java.util.Collections.<org.gradle.tooling.model.eclipse.AccessRule>emptyList());
        }
    }

    private static class FakeAttribute implements ClasspathAttribute {
        private final String name;
        private final String value;
        FakeAttribute(String name, String value) { this.name = name; this.value = value; }
        @Override public String getName() { return name; }
        @Override public String getValue() { return value; }
    }

    private static class FakeDomainObjectSet<T> extends ArrayList<T> implements DomainObjectSet<T> {
        FakeDomainObjectSet(List<T> items) { super(items); }
        @Override public Iterator<T> iterator() { return super.iterator(); }
        @Override public List<T> getAll() { return new ArrayList<>(this); }
        @Override public T getAt(int index) { return get(index); }
    }

    /**
     * Minimal in-memory fake for the Tooling-API publication model. Only implements what
     * {@link LibraryJarResolver#matchPublications} actually inspects.
     */
    private static class FakePublication implements GradlePublication {
        private final GradleModuleVersion id;
        FakePublication(String group, String name, String version) {
            this.id = new FakeModuleVersion(group, name, version);
        }
        @Override public GradleModuleVersion getId() { return id; }
        @Override public ProjectIdentifier getProjectIdentifier() { return null; }
    }

    private static class FakeModuleVersion implements GradleModuleVersion {
        private final String group;
        private final String name;
        private final String version;
        FakeModuleVersion(String group, String name, String version) {
            this.group = group;
            this.name = name;
            this.version = version;
        }
        @Override public String getGroup() { return group; }
        @Override public String getName() { return name; }
        @Override public String getVersion() { return version; }
    }
}
