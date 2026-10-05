package org.tiatesting.maven;

import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.vcs.VCSAnalyzerException;
import org.tiatesting.core.vcs.VCSReaderFactory;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the VCS provider module for a VCS name ({@code org.tiatesting:tia-vcs-<name>} at the
 * plugin's own version, with its transitive dependencies) and loads it in an isolated class loader
 * whose parent is the plugin's class loader. The plugin depends on no VCS module, so a Git project
 * never downloads p4java and a Perforce project never downloads JGit; the VCS library also never
 * reaches the project's test classpath.
 *
 * <p>{@code tia-core} is excluded from the resolution: the parent class loader already has it, and
 * the provider must see the same {@code VCSReaderProvider} type the plugin looks it up by.
 *
 * <p>Class loaders are cached for the life of the plugin realm, so a multi-module reactor and the
 * several goals of one build resolve each provider once.
 */
class VcsProviderResolver {

    private static final Logger log = LoggerFactory.getLogger(VcsProviderResolver.class);

    private static final String TIA_CORE_ARTIFACT_ID = "tia-core";

    private static final Map<List<Object>, ClassLoader> LOADERS = new ConcurrentHashMap<>();

    private final RepositorySystem repositorySystem;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repositories;
    private final String pluginVersion;
    private final ClassLoader parent;

    /**
     * @param repositorySystem Maven's resolver, used to resolve the provider module
     * @param session the build's repository session (local repository, offline mode, mirrors)
     * @param repositories the remote repositories to resolve from - the project's plugin
     *                     repositories, since the provider is part of the plugin
     * @param pluginVersion the version of this plugin; the provider is resolved at the same version
     * @param parent the plugin's class loader, used as the parent of the provider class loader
     */
    VcsProviderResolver(final RepositorySystem repositorySystem, final RepositorySystemSession session,
                        final List<RemoteRepository> repositories, final String pluginVersion,
                        final ClassLoader parent) {
        this.repositorySystem = repositorySystem;
        this.session = session;
        this.repositories = repositories;
        this.pluginVersion = pluginVersion;
        this.parent = parent;
    }

    /**
     * Get a class loader holding the provider module for the VCS, resolving it on first use.
     *
     * @param vcsName the VCS name, as returned by {@code VcsDetector.detect}
     * @return a class loader on which {@code ServiceLoader} finds the provider
     * @throws VCSAnalyzerException if the provider module cannot be resolved
     */
    ClassLoader classLoaderFor(final String vcsName) {
        List<Object> key = Arrays.asList(parent, vcsName, pluginVersion);
        return LOADERS.computeIfAbsent(key, k -> newClassLoader(resolve(vcsName), parent));
    }

    /**
     * Resolve the provider module and its runtime dependencies (minus {@code tia-core}) to files.
     *
     * @param vcsName the VCS name
     * @return the resolved jar files
     * @throws VCSAnalyzerException if resolution fails, with a hint for offline builds
     */
    private List<File> resolve(final String vcsName) {
        CollectRequest collectRequest = collectRequest(vcsName, pluginVersion, repositories);
        log.debug("Resolving the VCS provider {}", collectRequest.getRoot().getArtifact());
        try {
            List<File> files = new ArrayList<>();
            for (ArtifactResult result : repositorySystem.resolveDependencies(session,
                    new DependencyRequest(collectRequest, null)).getArtifactResults()) {
                files.add(result.getArtifact().getFile());
            }
            return files;
        } catch (DependencyResolutionException e) {
            Artifact artifact = collectRequest.getRoot().getArtifact();
            String coordinates = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
            throw new VCSAnalyzerException("Could not resolve " + coordinates + " for the '" + vcsName + "' VCS. "
                    + "If this step should not read the VCS (e.g. a distributed test runner), set tiaBranch and "
                    + "tiaCommitValue and no VCS provider is needed. Otherwise, on an offline build, declare "
                    + coordinates + " as a dependency of tia-maven-plugin (in the plugin's <dependencies>, not the "
                    + "project's) so it is resolved with the plugin, or prefetch it with "
                    + "mvn dependency:get -Dartifact=" + coordinates, e);
        }
    }

    /**
     * Build the resolution request for a provider module: runtime scope, at the plugin's version,
     * excluding {@code tia-core}.
     *
     * @param vcsName the VCS name
     * @param version the provider version (the plugin's version)
     * @param repositories the remote repositories to resolve from
     * @return the collect request
     */
    static CollectRequest collectRequest(final String vcsName, final String version,
                                         final List<RemoteRepository> repositories) {
        DefaultArtifact artifact = new DefaultArtifact(VCSReaderFactory.PROVIDER_GROUP_ID,
                VCSReaderFactory.providerArtifactId(vcsName), "jar", version);
        Exclusion tiaCore = new Exclusion(VCSReaderFactory.PROVIDER_GROUP_ID, TIA_CORE_ARTIFACT_ID, "*", "*");
        Dependency dependency = new Dependency(artifact, "runtime", false, Collections.singletonList(tiaCore));
        return new CollectRequest(dependency, repositories);
    }

    /**
     * Create a parent-first class loader over the given jars. Parent-first keeps the
     * {@code tia-core} types shared with the plugin.
     *
     * @param files the jars to load
     * @param parent the parent class loader
     * @return the new class loader
     */
    static ClassLoader newClassLoader(final List<File> files, final ClassLoader parent) {
        URL[] urls = new URL[files.size()];
        for (int i = 0; i < files.size(); i++) {
            try {
                urls[i] = files.get(i).toURI().toURL();
            } catch (MalformedURLException e) {
                throw new VCSAnalyzerException("Invalid VCS provider jar path: " + files.get(i), e);
            }
        }
        return new URLClassLoader(urls, parent);
    }
}
