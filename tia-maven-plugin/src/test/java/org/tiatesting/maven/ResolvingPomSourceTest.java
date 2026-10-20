package org.tiatesting.maven;

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ResolvingPomSource}: the local repository first, the resolver on a miss, the
 * per-coordinate cache, offline mode and the INFO line for an unavailable POM.
 */
class ResolvingPomSourceTest {

    private final List<ArtifactRequest> requests = new ArrayList<>();
    private final RecordingLog log = new RecordingLog();

    /**
     * A POM in the local repository is returned without calling the resolver.
     *
     * @param repository a temporary local repository.
     * @throws IOException if the fixture can't be written.
     */
    @Test
    void localRepositoryHitDoesNotCallTheResolver(@TempDir File repository) throws IOException {
        // given
        writePom(repository, "<project>local</project>");
        ResolvingPomSource source = source(repository, resolvingTo(null), false);

        // when
        String pom = source.readPom("org.boot", "boot-parent", "1.0");

        // then
        assertEquals("<project>local</project>", pom);
        assertTrue(requests.isEmpty(), requests.toString());
    }

    /**
     * A POM missing locally is resolved as a {@code pom} artifact and its file content returned.
     *
     * @param repository a temporary, empty local repository.
     * @param remote a directory standing in for the resolved file's location.
     * @throws IOException if the fixture can't be written.
     */
    @Test
    void localMissIsResolvedFromTheRemoteRepositories(@TempDir File repository, @TempDir File remote)
            throws IOException {
        // given
        File resolved = new File(remote, "boot-parent-1.0.pom");
        Files.write(resolved.toPath(), "<project>remote</project>".getBytes(StandardCharsets.UTF_8));
        ResolvingPomSource source = source(repository, resolvingTo(resolved), false);

        // when
        String pom = source.readPom("org.boot", "boot-parent", "1.0");

        // then
        assertEquals("<project>remote</project>", pom);
        assertEquals(1, requests.size());
        Artifact artifact = requests.get(0).getArtifact();
        assertEquals("org.boot:boot-parent:pom:1.0", artifact.getGroupId() + ":" + artifact.getArtifactId() + ":"
                + artifact.getExtension() + ":" + artifact.getVersion());
    }

    /**
     * A resolver failure gives {@code null}, a DEBUG line, and one INFO line naming the POM and the reason.
     *
     * @param repository a temporary, empty local repository.
     */
    @Test
    void resolutionFailureIsNullAndLoggedOnceAtInfo(@TempDir File repository) {
        // given
        ResolvingPomSource source = source(repository, failingWith("Could not find artifact org.boot:boot-parent:pom:1.0"),
                false);

        // when
        String first = source.readPom("org.boot", "boot-parent", "1.0");
        String second = source.readPom("org.boot", "boot-parent", "1.0");

        // then
        assertNull(first);
        assertNull(second);
        assertEquals(1, requests.size());
        assertTrue(log.debugLines.stream().anyMatch(line -> line.startsWith("Could not resolve POM org.boot:boot-parent:1.0")),
                log.debugLines.toString());
        assertEquals(1, log.infoLines.size(), log.infoLines.toString());
        assertTrue(log.infoLines.get(0).startsWith("POM-aware static rules could not read the external POM "
                + "org.boot:boot-parent:1.0: it is not in the local repository and could not be resolved: "
                + "Could not find artifact"), log.infoLines.get(0));
        assertTrue(log.infoLines.get(0).endsWith("A version bump of it counts as DEPENDENCY_VERSIONS."),
                log.infoLines.get(0));
    }

    /**
     * An offline build never calls the resolver and says why the POM is unavailable.
     *
     * @param repository a temporary, empty local repository.
     */
    @Test
    void offlineBuildSkipsTheResolver(@TempDir File repository) {
        // given
        ResolvingPomSource source = source(repository, resolvingTo(null), true);

        // when
        String pom = source.readPom("org.boot", "boot-parent", "1.0");

        // then
        assertNull(pom);
        assertTrue(requests.isEmpty(), requests.toString());
        assertEquals(Collections.singletonList("POM-aware static rules could not read the external POM "
                + "org.boot:boot-parent:1.0: it is not in the local repository and the build is offline. "
                + "A version bump of it counts as DEPENDENCY_VERSIONS."), log.infoLines);
    }

    /**
     * A coordinate is resolved once, however many times it is read.
     *
     * @param repository a temporary, empty local repository.
     * @param remote a directory standing in for the resolved file's location.
     * @throws IOException if the fixture can't be written.
     */
    @Test
    void repeatedCoordinateIsResolvedOnce(@TempDir File repository, @TempDir File remote) throws IOException {
        // given
        File resolved = new File(remote, "boot-parent-1.0.pom");
        Files.write(resolved.toPath(), "<project/>".getBytes(StandardCharsets.UTF_8));
        ResolvingPomSource source = source(repository, resolvingTo(resolved), false);

        // when
        source.readPom("org.boot", "boot-parent", "1.0");
        source.readPom("org.boot", "boot-parent", "1.0");

        // then
        assertEquals(1, requests.size());
    }

    /**
     * @param repository the local repository directory.
     * @param system the stubbed resolver.
     * @param offline whether the session is offline.
     * @return a source over them, with no remote repositories.
     */
    private ResolvingPomSource source(File repository, RepositorySystem system, boolean offline) {
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();
        session.setOffline(offline);
        return new ResolvingPomSource(new LocalRepositoryPomSource(repository, log), system, session,
                Collections.emptyList(), log);
    }

    /**
     * @param file the file every resolution returns; {@code null} when no call is expected.
     * @return a resolver that records each request and resolves it to {@code file}.
     */
    private RepositorySystem resolvingTo(File file) {
        return (RepositorySystem) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{RepositorySystem.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("resolveArtifact")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    ArtifactRequest request = (ArtifactRequest) args[1];
                    requests.add(request);
                    ArtifactResult result = new ArtifactResult(request);
                    result.setArtifact(request.getArtifact().setFile(file));
                    return result;
                });
    }

    /**
     * @param message the resolver's failure message.
     * @return a resolver that records each request and fails it.
     */
    private RepositorySystem failingWith(String message) {
        return (RepositorySystem) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{RepositorySystem.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("resolveArtifact")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    ArtifactRequest request = (ArtifactRequest) args[1];
                    requests.add(request);
                    throw new ArtifactResolutionException(Collections.singletonList(new ArtifactResult(request)),
                            message, null);
                });
    }

    /**
     * Write {@code org.boot:boot-parent:1.0} into a local repository in the standard layout.
     *
     * @param repository the local repository directory.
     * @param content the POM text.
     * @throws IOException if the file can't be written.
     */
    private static void writePom(File repository, String content) throws IOException {
        File pom = new File(repository, "org/boot/boot-parent/1.0/boot-parent-1.0.pom");
        pom.getParentFile().mkdirs();
        Files.write(pom.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Maven log that records DEBUG and INFO lines.
     */
    private static final class RecordingLog extends SystemStreamLog {
        private final List<String> debugLines = new ArrayList<>();
        private final List<String> infoLines = new ArrayList<>();

        /**
         * @param content the line.
         */
        @Override
        public void debug(CharSequence content) {
            debugLines.add(content.toString());
        }

        /**
         * @param content the line.
         */
        @Override
        public void info(CharSequence content) {
            infoLines.add(content.toString());
        }

        /**
         * @return {@code true}, so DEBUG lines are built and recorded.
         */
        @Override
        public boolean isDebugEnabled() {
            return true;
        }
    }
}
