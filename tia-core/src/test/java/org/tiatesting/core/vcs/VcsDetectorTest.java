package org.tiatesting.core.vcs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link VcsDetector}'s precedence (explicit name, then server URI, then a {@code .git}
 * entry) and that a failed detection names the settings a user can add. Each test builds its
 * project layout under a JUnit temp dir; the temp dir's own parents are assumed not to contain a
 * {@code .git}.
 */
class VcsDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void explicitNameWinsOverGitDirAndServerUri() throws IOException {
        // given
        Files.createDirectory(tempDir.resolve(".git"));
        VcsSettings settings = VcsSettings.builder().projectDir(tempDir.toString())
                .vcsName("perforce").serverUri("p4java://server:1666").build();

        // when
        String name = VcsDetector.detect(settings);

        // then
        assertEquals(VcsDetector.PERFORCE, name);
    }

    @Test
    void explicitNameIsTrimmedAndLowerCased() {
        // given
        VcsSettings settings = VcsSettings.builder().projectDir(tempDir.toString()).vcsName("  Git ").build();

        // when
        String name = VcsDetector.detect(settings);

        // then
        assertEquals(VcsDetector.GIT, name);
    }

    @Test
    void unknownExplicitNameFailsListingSupportedValues() {
        // given
        VcsSettings settings = VcsSettings.builder().projectDir(tempDir.toString()).vcsName("svn").build();

        // when
        VCSAnalyzerException exception = assertThrows(VCSAnalyzerException.class,
                () -> VcsDetector.detect(settings));

        // then
        assertTrue(exception.getMessage().contains("'svn'"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[git, perforce]"), exception.getMessage());
    }

    @Test
    void blankExplicitNameFallsBackToDetection() throws IOException {
        // given
        Files.createDirectory(tempDir.resolve(".git"));
        VcsSettings settings = VcsSettings.builder().projectDir(tempDir.toString()).vcsName("  ").build();

        // when
        String name = VcsDetector.detect(settings);

        // then
        assertEquals(VcsDetector.GIT, name);
    }

    @Test
    void serverUriMeansPerforceEvenInsideAGitRepository() throws IOException {
        // given
        Files.createDirectory(tempDir.resolve(".git"));
        VcsSettings settings = VcsSettings.builder().projectDir(tempDir.toString())
                .serverUri("p4java://server:1666").build();

        // when
        String name = VcsDetector.detect(settings);

        // then
        assertEquals(VcsDetector.PERFORCE, name);
    }

    @Test
    void gitDirInProjectDirMeansGit() throws IOException {
        // given
        Files.createDirectory(tempDir.resolve(".git"));
        VcsSettings settings = VcsSettings.builder().projectDir(tempDir.toString()).build();

        // when
        String name = VcsDetector.detect(settings);

        // then
        assertEquals(VcsDetector.GIT, name);
    }

    @Test
    void gitDirInAParentDirMeansGit() throws IOException {
        // given
        Files.createDirectory(tempDir.resolve(".git"));
        Path moduleDir = Files.createDirectories(tempDir.resolve("app").resolve("module"));
        VcsSettings settings = VcsSettings.builder().projectDir(moduleDir.toString()).build();

        // when
        String name = VcsDetector.detect(settings);

        // then
        assertEquals(VcsDetector.GIT, name);
    }

    @Test
    void gitPointerFileMeansGit() throws IOException {
        // given - worktrees and submodules have a .git file pointing at the real git dir
        Files.write(tempDir.resolve(".git"), "gitdir: /elsewhere/.git/worktrees/wt".getBytes(StandardCharsets.UTF_8));
        VcsSettings settings = VcsSettings.builder().projectDir(tempDir.toString()).build();

        // when
        String name = VcsDetector.detect(settings);

        // then
        assertEquals(VcsDetector.GIT, name);
    }

    @Test
    void nothingDetectedFailsNamingTheSettingsToAdd() {
        // given
        File projectDir = tempDir.toFile();
        VcsSettings settings = VcsSettings.builder().projectDir(projectDir.getPath()).build();

        // when
        VCSAnalyzerException exception = assertThrows(VCSAnalyzerException.class,
                () -> VcsDetector.detect(settings));

        // then
        assertTrue(exception.getMessage().contains("tiaVcs"), exception.getMessage());
        assertTrue(exception.getMessage().contains("tiaVcsServerUri"), exception.getMessage());
        assertTrue(exception.getMessage().contains(projectDir.getPath()), exception.getMessage());
    }
}
