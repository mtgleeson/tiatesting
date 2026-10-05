package org.tiatesting.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.vcs.VCSAnalyzerException;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.vcs.VcsSettings;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how {@link AbstractTiaMojo#getVCSReader()} finds a VCS provider: a provider declared on
 * the plugin is used without resolving anything; otherwise the detected VCS name is what gets
 * resolved, and a failed detection resolves nothing. The plugin and resolved class loaders are
 * substituted - the test classpath carries {@link StubGitReaderProvider} as its only provider.
 */
class AbstractTiaMojoVcsReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void providerDeclaredOnThePluginIsUsedWithoutResolving() {
        // given
        TestMojo mojo = new TestMojo(testClassLoader());
        mojo.tiaProjectDir = tempDir.toString();

        // when
        VCSReader reader = mojo.getVCSReader();

        // then
        assertEquals(StubGitReaderProvider.STUB_BRANCH, reader.getBranchName());
        assertTrue(mojo.resolvedNames.isEmpty());
    }

    @Test
    void detectedGitProviderIsResolvedWhenNoneIsDeclared() throws IOException {
        // given
        Files.createDirectory(tempDir.resolve(".git"));
        TestMojo mojo = new TestMojo(emptyClassLoader());
        mojo.tiaProjectDir = tempDir.toString();

        // when
        VCSReader reader = mojo.getVCSReader();

        // then
        assertEquals(StubGitReaderProvider.STUB_BRANCH, reader.getBranchName());
        assertEquals(tempDir.toString(), reader.getHeadCommit());
        assertEquals(singletonList("git"), mojo.resolvedNames);
    }

    @Test
    void serverUriResolvesThePerforceProvider() {
        // given - only the name passed to resolution matters here
        TestMojo mojo = new TestMojo(emptyClassLoader());
        mojo.tiaProjectDir = tempDir.toString();
        mojo.tiaVcsServerUri = "p4java://server:1666";

        // when
        mojo.getVCSReader();

        // then
        assertEquals(singletonList("perforce"), mojo.resolvedNames);
    }

    @Test
    void failedDetectionResolvesNothing() {
        // given - no provider declared, no .git, no server URI
        TestMojo mojo = new TestMojo(emptyClassLoader());
        mojo.tiaProjectDir = tempDir.toString();

        // when
        VCSAnalyzerException exception = assertThrows(VCSAnalyzerException.class, mojo::getVCSReader);

        // then
        assertTrue(exception.getMessage().contains("tiaVcs"), exception.getMessage());
        assertTrue(mojo.resolvedNames.isEmpty());
    }

    @Test
    void resolvedModuleWithoutAProviderFails() {
        // given
        TestMojo mojo = new TestMojo(emptyClassLoader());
        mojo.resolvedLoader = emptyClassLoader();
        mojo.tiaProjectDir = tempDir.toString();
        mojo.tiaVcs = "perforce";

        // when
        VCSAnalyzerException exception = assertThrows(VCSAnalyzerException.class, mojo::getVCSReader);

        // then
        assertTrue(exception.getMessage().contains("tia-vcs-perforce"), exception.getMessage());
    }

    @Test
    void vcsSettingsCarryTheMojoParameters() {
        // given
        TestMojo mojo = new TestMojo(emptyClassLoader());
        mojo.tiaProjectDir = "/work/project";
        mojo.tiaEnabled = false;
        mojo.tiaVcs = "perforce";
        mojo.tiaVcsServerUri = "p4java://server:1666";
        mojo.tiaVcsUserName = "builder";
        mojo.tiaVcsPassword = "secret";
        mojo.tiaVcsClientName = "builder-ws";

        // when
        VcsSettings settings = mojo.buildVcsSettings();

        // then
        assertEquals("/work/project", settings.getProjectDir());
        assertFalse(settings.isEnabled());
        assertEquals("perforce", settings.getVcsName());
        assertEquals("p4java://server:1666", settings.getServerUri());
        assertEquals("builder", settings.getUserName());
        assertEquals("secret", settings.getPassword());
        assertEquals("builder-ws", settings.getClientName());
    }

    /**
     * @return the test classpath's loader, on which {@link StubGitReaderProvider} is registered
     */
    private static ClassLoader testClassLoader() {
        return AbstractTiaMojoVcsReaderTest.class.getClassLoader();
    }

    /**
     * @return a loader with no parent, which sees no service registrations
     */
    private static ClassLoader emptyClassLoader() {
        return new URLClassLoader(new URL[0], null);
    }

    /**
     * @param value the single element
     * @return a mutable list holding the value, comparable with {@link TestMojo#resolvedNames}
     */
    private static List<String> singletonList(final String value) {
        List<String> list = new ArrayList<>();
        list.add(value);
        return list;
    }

    /**
     * Mojo with the plugin class loader and provider resolution substituted. Resolution records the
     * VCS name it was asked for and returns {@link #resolvedLoader}.
     */
    private static final class TestMojo extends AbstractTiaMojo {

        private final ClassLoader pluginLoader;
        private final List<String> resolvedNames = new ArrayList<>();
        private ClassLoader resolvedLoader = testClassLoader();

        /**
         * @param pluginLoader the loader to present as the plugin's own class loader
         */
        private TestMojo(final ClassLoader pluginLoader) {
            this.pluginLoader = pluginLoader;
            this.tiaEnabled = true;
        }

        /**
         * @return the substituted plugin class loader
         */
        @Override
        protected ClassLoader pluginClassLoader() {
            return pluginLoader;
        }

        /**
         * Record the requested name instead of resolving.
         *
         * @param vcsName the VCS name to resolve
         * @return {@link #resolvedLoader}
         */
        @Override
        protected ClassLoader vcsProviderClassLoader(final String vcsName) {
            resolvedNames.add(vcsName);
            return resolvedLoader;
        }

        /**
         * Never called by these tests.
         */
        @Override
        public void execute() {
            throw new UnsupportedOperationException("these tests do not execute the mojo");
        }
    }
}
