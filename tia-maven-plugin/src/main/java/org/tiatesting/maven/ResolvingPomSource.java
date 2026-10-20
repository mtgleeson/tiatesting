package org.tiatesting.maven;

import org.apache.maven.plugin.logging.Log;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.tiatesting.core.staticselection.pom.ExternalPomSource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads external POMs (parents, imported BOMs) for POM-aware static rules: from the Maven local
 * repository first and, when a POM is not there, from the build's remote repositories through
 * Maven's resolver. The resolver honours {@code settings.xml} mirrors, credentials and offline
 * mode, and stores the POM in the local repository, so the next build reads it locally. A fresh
 * CI cache usually holds the version a build uses but not the previous one, which is the case
 * this covers.
 *
 * <p>Each coordinate is looked up at most once per instance, misses included. A POM that can't be
 * read either way is logged once at INFO with the reason, since a version bump of it then counts
 * as {@code DEPENDENCY_VERSIONS}. See the "Static test selection" chapter in {@code WIKI.md}.
 */
public class ResolvingPomSource implements ExternalPomSource {

    private final LocalRepositoryPomSource localRepository;
    private final RepositorySystem repositorySystem;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repositories;
    private final Log log;
    private final Map<String, String> pomsByCoordinates = new HashMap<>();

    /**
     * Construct a source.
     *
     * @param localRepository the local repository reader, tried first.
     * @param repositorySystem Maven's resolver, used on a local miss.
     * @param session the build's repository session (local repository, mirrors, credentials, offline mode).
     * @param repositories the remote repositories to resolve from.
     * @param log the Maven log.
     */
    public ResolvingPomSource(final LocalRepositoryPomSource localRepository, final RepositorySystem repositorySystem,
                              final RepositorySystemSession session, final List<RemoteRepository> repositories,
                              final Log log) {
        this.localRepository = localRepository;
        this.repositorySystem = repositorySystem;
        this.session = session;
        this.repositories = repositories;
        this.log = log;
    }

    /**
     * Read a POM from the local repository, resolving it from the remote repositories on a miss.
     * The result, found or not, is cached for the coordinate.
     *
     * @param groupId the groupId.
     * @param artifactId the artifactId.
     * @param version the exact version.
     * @return the POM text, or {@code null} when it can't be read locally or resolved.
     */
    @Override
    public String readPom(final String groupId, final String artifactId, final String version) {
        String coordinates = groupId + ":" + artifactId + ":" + version;
        if (pomsByCoordinates.containsKey(coordinates)) {
            return pomsByCoordinates.get(coordinates);
        }
        String pom = localRepository.readPom(groupId, artifactId, version);
        if (pom == null) {
            pom = resolve(groupId, artifactId, version, coordinates);
        }
        pomsByCoordinates.put(coordinates, pom);
        return pom;
    }

    /**
     * Resolve a POM from the remote repositories, unless the build is offline. Logs the attempt
     * and its time at DEBUG, and an unavailable POM at INFO.
     *
     * @param groupId the groupId.
     * @param artifactId the artifactId.
     * @param version the exact version.
     * @param coordinates {@code groupId:artifactId:version}, for logging.
     * @return the POM text, or {@code null} when it can't be resolved.
     */
    private String resolve(final String groupId, final String artifactId, final String version,
                           final String coordinates) {
        if (session.isOffline()) {
            log.info(unavailable(coordinates, "it is not in the local repository and the build is offline"));
            return null;
        }
        ArtifactRequest request = new ArtifactRequest(new DefaultArtifact(groupId, artifactId, "pom", version),
                repositories, null);
        log.debug("Resolving POM " + coordinates + " from the remote repositories.");
        long start = System.nanoTime();
        try {
            File file = repositorySystem.resolveArtifact(session, request).getArtifact().getFile();
            String pom = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            log.debug("Resolved POM " + coordinates + " to " + file + " in " + elapsedMs(start) + " ms.");
            return pom;
        } catch (ArtifactResolutionException | IOException | RuntimeException e) {
            log.debug("Could not resolve POM " + coordinates + " in " + elapsedMs(start) + " ms: " + e.getMessage());
            log.info(unavailable(coordinates, "it is not in the local repository and could not be resolved: "
                    + e.getMessage()));
            return null;
        }
    }

    /**
     * @param start a {@link System#nanoTime()} reading.
     * @return the milliseconds since it.
     */
    private static long elapsedMs(final long start) {
        return (System.nanoTime() - start) / 1_000_000L;
    }

    /**
     * @param coordinates the POM's {@code groupId:artifactId:version}.
     * @param reason why it can't be read.
     * @return the INFO line for a POM that can't be read.
     */
    private static String unavailable(final String coordinates, final String reason) {
        return "POM-aware static rules could not read the external POM " + coordinates + ": " + reason
                + ". A version bump of it counts as DEPENDENCY_VERSIONS.";
    }
}
