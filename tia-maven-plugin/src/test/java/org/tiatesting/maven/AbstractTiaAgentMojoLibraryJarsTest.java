package org.tiatesting.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the producer selection in {@link AbstractTiaAgentMojo}'s library-jar resolution: when
 * {@code tiaLibraryJarsDirs} is set the offline directory matcher is used, otherwise the pom-based
 * resolver is used, and either way a correct {@code library-jars.txt} is written.
 */
class AbstractTiaAgentMojoLibraryJarsTest {

    /**
     * Minimal mojo whose configuration getters are overridden so the resolution branch can be
     * driven without a Maven session. The pom producer is stubbed so the "otherwise" branch is
     * observable without constructing a real dependency graph.
     */
    private static final class TestMojo extends AbstractTiaAgentMojo {
        private String sourceLibs;
        private String libraryJarsDirs;
        private String buildDir;
        private boolean pomResolverCalled;

        @Override
        public String getTiaSourceLibs() {
            return sourceLibs;
        }

        @Override
        public String getTiaLibraryJarsDirs() {
            return libraryJarsDirs;
        }

        @Override
        public String getTiaBuildDir() {
            return buildDir;
        }

        /**
         * Stub the pom producer so selecting it is observable and it never touches a real session.
         *
         * @param libraries the {@code tiaSourceLibs} CSV
         * @return a fixed single-path list standing in for a pom-resolved jar
         */
        @Override
        List<String> resolvePomLibraryJarPaths(String libraries) {
            pomResolverCalled = true;
            return java.util.Collections.singletonList("/from/pom/widgets-1.0.jar");
        }

        /**
         * @return null - no VCS reader is needed by the resolution paths under test
         */
        @Override
        public org.tiatesting.core.vcs.VCSReader getVCSReader() {
            return null;
        }

        /**
         * @return a placeholder agent artifact name; no JVM is forked in these tests
         */
        @Override
        public String getAgentArtifactName() {
            return "org.tiatesting:tia-junit5-agent";
        }

        /**
         * @return an empty artifact map; unused by the resolution paths under test
         */
        @Override
        public java.util.Map<String, org.apache.maven.artifact.Artifact> getPluginArtifactMap() {
            return java.util.Collections.emptyMap();
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
        TestMojo mojo = new TestMojo();
        mojo.sourceLibs = "com.example:widgets";
        mojo.libraryJarsDirs = libDir.toString();

        // when
        List<String> resolved = mojo.resolveLibraryJarPaths(mojo.getTiaSourceLibs());

        // then - resolved from the directory, and the pom producer was never consulted
        assertEquals(java.util.Collections.singletonList(jar.getAbsolutePath()), resolved);
        assertFalse(mojo.pomResolverCalled);
    }

    @Test
    void pomModeSelectedWhenLibraryJarsDirsBlank() {
        // given - no tiaLibraryJarsDirs configured
        TestMojo mojo = new TestMojo();
        mojo.sourceLibs = "com.example:widgets";
        mojo.libraryJarsDirs = "  ";

        // when
        List<String> resolved = mojo.resolveLibraryJarPaths(mojo.getTiaSourceLibs());

        // then - the pom producer supplied the result
        assertTrue(mojo.pomResolverCalled);
        assertEquals(java.util.Collections.singletonList("/from/pom/widgets-1.0.jar"), resolved);
    }

    @Test
    void directoryModeWritesLibraryJarsFile(@TempDir Path libDir, @TempDir Path buildDir) throws IOException {
        // given
        File jar = touch(libDir, "widgets-1.2.3.jar");
        TestMojo mojo = new TestMojo();
        mojo.sourceLibs = "com.example:widgets";
        mojo.libraryJarsDirs = libDir.toString();
        mojo.buildDir = buildDir.toString();

        // when
        String written = mojo.writeLibraryJarsFile();

        // then - the sidecar file holds exactly the resolved absolute jar path
        assertNotNull(written);
        List<String> lines = Files.readAllLines(new File(written).toPath(), StandardCharsets.UTF_8);
        assertLinesMatch(java.util.Collections.singletonList(jar.getAbsolutePath()), lines);
    }

    @Test
    void returnsNullWhenSourceLibsUnset(@TempDir Path buildDir) {
        // given - no tiaSourceLibs even though a jars dir is configured
        TestMojo mojo = new TestMojo();
        mojo.sourceLibs = "";
        mojo.libraryJarsDirs = buildDir.toString();
        mojo.buildDir = buildDir.toString();

        // when
        String written = mojo.writeLibraryJarsFile();

        // then
        org.junit.jupiter.api.Assertions.assertNull(written);
    }
}
