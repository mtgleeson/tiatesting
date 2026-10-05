package org.tiatesting.core.vcs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how {@link VCSReaderFactory} picks a provider: none visible returns empty (the build
 * plugin then resolves one), an explicit name must match, a single provider is used without
 * detection, and several are disambiguated by {@link VcsDetector}. The {@code ServiceLoader} path
 * uses {@link StubVCSReaderProvider}, registered in the test resources.
 */
class VCSReaderFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void noProvidersReturnsEmpty() {
        // given
        VcsSettings settings = VcsSettings.builder().projectDir(tempDir.toString()).vcsName("git").build();

        // when
        Optional<VCSReaderProvider> provider = VCSReaderFactory.selectProvider(settings, Collections.emptyList());

        // then
        assertFalse(provider.isPresent());
    }

    @Test
    void singleProviderIsUsedWithoutDetection() {
        // given - no .git and no server URI, so detection alone would fail
        VcsSettings settings = VcsSettings.builder().projectDir(tempDir.toString()).build();
        List<VCSReaderProvider> providers = Collections.singletonList(new StubVCSReaderProvider("perforce"));

        // when
        Optional<VCSReaderProvider> provider = VCSReaderFactory.selectProvider(settings, providers);

        // then
        assertEquals("perforce", provider.get().name());
    }

    @Test
    void explicitNamePicksMatchingProvider() {
        // given
        VcsSettings settings = VcsSettings.builder().projectDir(tempDir.toString()).vcsName("Perforce").build();
        List<VCSReaderProvider> providers = Arrays.asList(
                new StubVCSReaderProvider("git"), new StubVCSReaderProvider("perforce"));

        // when
        Optional<VCSReaderProvider> provider = VCSReaderFactory.selectProvider(settings, providers);

        // then
        assertEquals("perforce", provider.get().name());
    }

    @Test
    void explicitNameNotMatchingTheProviderFailsNamingTheArtifact() {
        // given
        VcsSettings settings = VcsSettings.builder().projectDir(tempDir.toString()).vcsName("perforce").build();
        List<VCSReaderProvider> providers = Collections.singletonList(new StubVCSReaderProvider("git"));

        // when
        VCSAnalyzerException exception = assertThrows(VCSAnalyzerException.class,
                () -> VCSReaderFactory.selectProvider(settings, providers));

        // then
        assertTrue(exception.getMessage().contains("[git]"), exception.getMessage());
        assertTrue(exception.getMessage().contains("org.tiatesting:tia-vcs-perforce"), exception.getMessage());
    }

    @Test
    void severalProvidersArePickedByDetection() throws IOException {
        // given
        Files.createDirectory(tempDir.resolve(".git"));
        VcsSettings settings = VcsSettings.builder().projectDir(tempDir.toString()).build();
        List<VCSReaderProvider> providers = Arrays.asList(
                new StubVCSReaderProvider("perforce"), new StubVCSReaderProvider("git"));

        // when
        Optional<VCSReaderProvider> provider = VCSReaderFactory.selectProvider(settings, providers);

        // then
        assertEquals("git", provider.get().name());
    }

    @Test
    void severalProvidersWithoutTheDetectedOneFails() {
        // given
        VcsSettings settings = VcsSettings.builder().projectDir(tempDir.toString())
                .serverUri("p4java://server:1666").build();
        List<VCSReaderProvider> providers = Arrays.asList(
                new StubVCSReaderProvider("git"), new StubVCSReaderProvider("other"));

        // when
        VCSAnalyzerException exception = assertThrows(VCSAnalyzerException.class,
                () -> VCSReaderFactory.selectProvider(settings, providers));

        // then
        assertTrue(exception.getMessage().contains("'perforce'"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[git, other]"), exception.getMessage());
    }

    @Test
    void createUsesTheProviderRegisteredOnTheClassLoader() {
        // given
        VcsSettings settings = VcsSettings.builder().projectDir(tempDir.toString()).build();

        // when
        Optional<VCSReader> reader = VCSReaderFactory.create(settings, getClass().getClassLoader());

        // then
        assertEquals("stub", reader.get().getHeadCommit());
        assertEquals(tempDir.toString(), reader.get().getBranchName());
    }

    @Test
    void createReturnsEmptyWhenTheClassLoaderHasNoProviders() throws IOException {
        // given - a loader with no parent sees no service files
        VcsSettings settings = VcsSettings.builder().projectDir(tempDir.toString()).build();

        try (URLClassLoader emptyLoader = new URLClassLoader(new URL[0], null)) {
            // when
            Optional<VCSReader> reader = VCSReaderFactory.create(settings, emptyLoader);

            // then
            assertFalse(reader.isPresent());
        }
    }

    @Test
    void providerArtifactIdIsPrefixedName() {
        // given
        String vcsName = VcsDetector.PERFORCE;

        // when
        String artifactId = VCSReaderFactory.providerArtifactId(vcsName);

        // then
        assertEquals("tia-vcs-perforce", artifactId);
    }
}
