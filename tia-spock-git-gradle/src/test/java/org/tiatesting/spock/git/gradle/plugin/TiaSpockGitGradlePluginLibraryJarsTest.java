package org.tiatesting.spock.git.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.gradle.plugin.LibraryJarResolver;
import org.tiatesting.gradle.plugin.TiaBaseTaskExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the Gradle-side producer selection for {@code tiaLibraryJars}: when {@code libraryJarsDirs}
 * is set the offline directory matcher is used and the dependency-graph resolver is not consulted;
 * when it is blank the dependency-graph resolver is used instead. Both feed the same CSV.
 */
class TiaSpockGitGradlePluginLibraryJarsTest {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(TiaSpockGitGradlePluginLibraryJarsTest.class);

    /**
     * A resolver stub that records whether the dependency-graph path was consulted and returns a
     * marker so the graph branch is observable without a real Gradle classpath.
     */
    private static final class StubResolver extends LibraryJarResolver {
        private boolean called;

        StubResolver(Project project) {
            super(project, LOGGER);
        }

        /**
         * @param sourceLibsCsv ignored
         * @param sourceProjectDir ignored
         * @return a fixed marker path, recording that the graph resolver was consulted
         */
        @Override
        public String resolveLibraryJarsCsv(String sourceLibsCsv, String sourceProjectDir) {
            called = true;
            return "/from/graph/widgets-1.0.jar";
        }
    }

    /**
     * Create an empty file at {@code dir/name}.
     *
     * @param dir the directory to create the file in
     * @param name the filename
     * @return the created file
     * @throws IOException if the file cannot be created
     */
    private static File touch(Path dir, String name) throws IOException {
        File f = dir.resolve(name).toFile();
        Files.createFile(f.toPath());
        return f;
    }

    @Test
    void directoryModeSelectedWhenLibraryJarsDirsSet(@TempDir Path libDir) throws IOException {
        // given
        File jar = touch(libDir, "widgets-1.2.3.jar");
        Project project = ProjectBuilder.builder().build();
        StubResolver resolver = new StubResolver(project);
        TiaBaseTaskExtension ext = new TiaBaseTaskExtension();
        ext.setSourceLibs("com.example:widgets");
        ext.setLibraryJarsDirs(libDir.toString());

        // when
        String csv = TiaSpockGitGradlePluginTestExtension.resolveLibraryJarsCsv(ext, resolver, LOGGER);

        // then - resolved from the directory, graph resolver never consulted
        assertEquals(jar.getAbsolutePath(), csv);
        assertFalse(resolver.called);
    }

    @Test
    void graphModeSelectedWhenLibraryJarsDirsBlank() {
        // given
        Project project = ProjectBuilder.builder().build();
        StubResolver resolver = new StubResolver(project);
        TiaBaseTaskExtension ext = new TiaBaseTaskExtension();
        ext.setSourceLibs("com.example:widgets");
        ext.setLibraryJarsDirs("  ");

        // when
        String csv = TiaSpockGitGradlePluginTestExtension.resolveLibraryJarsCsv(ext, resolver, LOGGER);

        // then - the graph resolver supplied the result
        assertTrue(resolver.called);
        assertEquals("/from/graph/widgets-1.0.jar", csv);
    }

    @Test
    void directoryModeWithNoMatchReturnsNull(@TempDir Path libDir) throws IOException {
        // given - a directory holding only an unrelated jar
        touch(libDir, "other-9.9.jar");
        Project project = ProjectBuilder.builder().build();
        StubResolver resolver = new StubResolver(project);
        TiaBaseTaskExtension ext = new TiaBaseTaskExtension();
        ext.setSourceLibs("com.example:widgets");
        ext.setLibraryJarsDirs(libDir.toString());

        // when
        String csv = TiaSpockGitGradlePluginTestExtension.resolveLibraryJarsCsv(ext, resolver, LOGGER);

        // then - nothing matched, and the graph resolver was still not used
        assertNull(csv);
        assertFalse(resolver.called);
    }

    @Test
    void directoryModeResolvesMultipleCoordinatesAcrossDirectories(@TempDir Path dirA, @TempDir Path dirB)
            throws IOException {
        // given
        File widgets = touch(dirA, "widgets-1.0.jar");
        File gadgets = touch(dirB, "gadgets-2.0.jar");
        Project project = ProjectBuilder.builder().build();
        StubResolver resolver = new StubResolver(project);
        TiaBaseTaskExtension ext = new TiaBaseTaskExtension();
        ext.setSourceLibs("com.example:widgets,com.example:gadgets");
        ext.setLibraryJarsDirs(dirA + "," + dirB);

        // when
        String csv = TiaSpockGitGradlePluginTestExtension.resolveLibraryJarsCsv(ext, resolver, LOGGER);

        // then - both jars resolve, comma separated, in coordinate order
        assertEquals(widgets.getAbsolutePath() + "," + gadgets.getAbsolutePath(), csv);
        assertFalse(resolver.called);
    }
}
