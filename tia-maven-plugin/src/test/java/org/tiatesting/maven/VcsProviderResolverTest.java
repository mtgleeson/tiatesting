package org.tiatesting.maven;

import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.vcs.VCSAnalyzerException;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link VcsProviderResolver}: the resolution request it builds (coordinates, scope, the
 * {@code tia-core} exclusion), that the provider class loader shares the plugin's types, that a
 * provider is resolved once and then served from the cache, and that a failed resolution names the
 * fixes: VCS-free runner settings, a plugin dependency, or a prefetch. {@link RepositorySystem} is stubbed with a dynamic proxy. The loader cache is
 * static and keyed by the parent loader, so each test uses its own parent.
 */
class VcsProviderResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void collectRequestTargetsTheProviderAtThePluginVersionWithoutTiaCore() {
        // given
        List<RemoteRepository> repositories = Collections.singletonList(
                new RemoteRepository.Builder("central", "default", "https://repo.example.com/maven2").build());

        // when
        CollectRequest request = VcsProviderResolver.collectRequest("perforce", "1.2.3", repositories);

        // then
        Dependency root = request.getRoot();
        assertEquals("org.tiatesting:tia-vcs-perforce:jar:1.2.3", root.getArtifact().toString());
        assertEquals("runtime", root.getScope());
        Exclusion exclusion = root.getExclusions().iterator().next();
        assertEquals("org.tiatesting", exclusion.getGroupId());
        assertEquals("tia-core", exclusion.getArtifactId());
        assertEquals(repositories, request.getRepositories());
    }

    @Test
    void providerClassLoaderSeesItsJarsAndSharesTheParentTypes() throws IOException {
        // given
        Path jarDir = Files.createDirectory(tempDir.resolve("provider"));
        Files.write(jarDir.resolve("marker.txt"), "x".getBytes(StandardCharsets.UTF_8));

        // when
        ClassLoader loader = VcsProviderResolver.newClassLoader(
                Collections.singletonList(jarDir.toFile()), getClass().getClassLoader());

        // then
        assertNotNull(loader.getResource("marker.txt"));
        assertSame(VCSReader.class, loadClass(loader, VCSReader.class.getName()));
    }

    @Test
    void providerIsResolvedOnceAndThenServedFromTheCache() throws IOException {
        // given
        AtomicInteger resolutions = new AtomicInteger();
        File jar = Files.createDirectory(tempDir.resolve("git-provider")).toFile();
        VcsProviderResolver resolver = resolver(repositorySystem(resolutions, jar), newParent());

        // when
        ClassLoader first = resolver.classLoaderFor("git");
        ClassLoader second = resolver.classLoaderFor("git");

        // then
        assertSame(first, second);
        assertEquals(1, resolutions.get());
    }

    @Test
    void failedResolutionNamesEveryFix() {
        // given
        RepositorySystem failing = (RepositorySystem) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{RepositorySystem.class}, (proxy, method, args) -> {
                    if (method.getName().equals("resolveDependencies")) {
                        throw new DependencyResolutionException(new DependencyResult((DependencyRequest) args[1]),
                                new IOException("offline"));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        VcsProviderResolver resolver = resolver(failing, newParent());

        // when
        VCSAnalyzerException exception = assertThrows(VCSAnalyzerException.class,
                () -> resolver.classLoaderFor("perforce"));

        // then
        String message = exception.getMessage();
        assertTrue(message.contains("tiaBranch and tiaCommitValue"), message);
        assertTrue(message.contains("dependency of tia-maven-plugin"), message);
        assertTrue(message.contains("dependency:get -Dartifact=org.tiatesting:tia-vcs-perforce:9.9.9"), message);
    }

    /**
     * @param repositorySystem the stubbed resolver
     * @param parent the parent class loader, unique per test so the static cache never crosses tests
     * @return a resolver at plugin version 9.9.9 with no remote repositories
     */
    private static VcsProviderResolver resolver(final RepositorySystem repositorySystem, final ClassLoader parent) {
        return new VcsProviderResolver(repositorySystem, null, Collections.emptyList(), "9.9.9", parent);
    }

    /**
     * Stub resolver that counts {@code resolveDependencies} calls and resolves the root to the file.
     *
     * @param resolutions incremented per resolution
     * @param file the file the root artifact resolves to
     * @return the stub
     */
    private RepositorySystem repositorySystem(final AtomicInteger resolutions, final File file) {
        return (RepositorySystem) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{RepositorySystem.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("resolveDependencies")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    resolutions.incrementAndGet();
                    DependencyRequest request = (DependencyRequest) args[1];
                    Artifact artifact = request.getCollectRequest().getRoot().getArtifact().setFile(file);
                    ArtifactResult artifactResult = new ArtifactResult(new ArtifactRequest());
                    artifactResult.setArtifact(artifact);
                    DependencyResult result = new DependencyResult(request);
                    result.setArtifactResults(Collections.singletonList(artifactResult));
                    return result;
                });
    }

    /**
     * @return a fresh class loader to act as the plugin's loader
     */
    private ClassLoader newParent() {
        return new URLClassLoader(new URL[0], getClass().getClassLoader());
    }

    /**
     * @param loader the loader to load from
     * @param name the class name
     * @return the loaded class
     */
    private static Class<?> loadClass(final ClassLoader loader, final String name) {
        try {
            return loader.loadClass(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }
}
