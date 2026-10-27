package org.tiatesting.vcs.git;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.MappingDrift;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link GitReader#findMappingDrift} (delegating to {@link GitDiffAnalyzer})
 * against a throwaway on-disk Git repository: a locally changed file whose blob at {@code HEAD}
 * differs from its blob at the stored commit is reported with the commits that touched it on each
 * side of the merge base (behind, ahead or both), an unchanged blob is not reported, and a stored
 * commit not in the repository is reported as not found.
 */
class GitReaderMappingDriftTest {

    private static final String FOO = "src/Foo.java";
    private static final String BAR = "src/Bar.java";
    private static final String OTHER = "src/Other.java";

    private File tempDir;
    private Git git;

    /**
     * Create an empty repository in a fresh temp directory.
     *
     * @throws Exception if the repository can't be created.
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("tia-git-drift-").toFile();
        git = Git.init().setDirectory(tempDir).setInitialBranch("main").call();
    }

    /**
     * Close the repository and delete the temp directory.
     *
     * @throws Exception if cleanup fails.
     */
    @AfterEach
    void tearDown() throws Exception {
        if (git != null) {
            git.getRepository().close();
            git.close();
        }
        if (tempDir != null) {
            FileUtils.deleteDirectory(tempDir);
        }
    }

    @Test
    void workspaceBehindReportsTheCommitsThatTouchedEachLocallyChangedFile() throws Exception {
        // given - main: A adds Foo/Bar/Other, B and C edit Foo, D edits Other; the mapping is at D.
        // The workspace is on a branch at A with local edits to Foo and Bar.
        String commitA = commitFiles("A", FOO, "foo-a", BAR, "bar-a", OTHER, "other-a");
        String commitB = commitFiles("B", FOO, "foo-b");
        String commitC = commitFiles("C", FOO, "foo-c");
        String storedCommit = commitFiles("D", OTHER, "other-d");
        git.checkout().setCreateBranch(true).setName("old").setStartPoint(commitA).call();
        writeFile(FOO, "foo-local");
        writeFile(BAR, "bar-local");

        GitReader reader = new GitReader(tempDir.getAbsolutePath());
        try {
            // when
            MappingDrift drift = reader.findMappingDrift(storedCommit,
                    Arrays.asList(localDiff(FOO), localDiff(BAR)));

            // then - Foo is missing B and C (oldest first); Bar is the same at A and D
            assertTrue(drift.isBaselineFound());
            assertEquals(Collections.singletonMap(FOO, behind(abbreviate(commitB), abbreviate(commitC))),
                    drift.getDriftByFetchKey());
        } finally {
            reader.close();
        }
    }

    @Test
    void workspaceAheadReportsTheCommitsTheMappingDoesNotHave() throws Exception {
        // given - the mapping is at A and the workspace is at C; B edited Foo and C edited Bar
        String storedCommit = commitFiles("A", FOO, "foo-a", BAR, "bar-a");
        String commitB = commitFiles("B", FOO, "foo-b");
        commitFiles("C", BAR, "bar-c");
        writeFile(FOO, "foo-local");

        GitReader reader = new GitReader(tempDir.getAbsolutePath());
        try {
            // when
            MappingDrift drift = reader.findMappingDrift(storedCommit,
                    Collections.singletonList(localDiff(FOO)));

            // then - only B touched Foo
            assertTrue(drift.isBaselineFound());
            assertEquals(Collections.singletonMap(FOO, ahead(abbreviate(commitB))), drift.getDriftByFetchKey());
        } finally {
            reader.close();
        }
    }

    @Test
    void workspaceAheadWithoutChangesToTheLocallyChangedFileHasNoDrift() throws Exception {
        // given - the mapping is at A and the workspace is at B, which edited only Bar
        String storedCommit = commitFiles("A", FOO, "foo-a", BAR, "bar-a");
        commitFiles("B", BAR, "bar-b");
        writeFile(FOO, "foo-local");

        GitReader reader = new GitReader(tempDir.getAbsolutePath());
        try {
            // when
            MappingDrift drift = reader.findMappingDrift(storedCommit,
                    Collections.singletonList(localDiff(FOO)));

            // then
            assertTrue(drift.isBaselineFound());
            assertTrue(drift.getDriftByFetchKey().isEmpty());
        } finally {
            reader.close();
        }
    }

    @Test
    void fileChangedAndRevertedSinceTheStoredCommitHasNoDrift() throws Exception {
        // given - the mapping is at A; B changed Foo and C changed it back
        String storedCommit = commitFiles("A", FOO, "foo-a");
        commitFiles("B", FOO, "foo-b");
        commitFiles("C", FOO, "foo-a");
        writeFile(FOO, "foo-local");

        GitReader reader = new GitReader(tempDir.getAbsolutePath());
        try {
            // when
            MappingDrift drift = reader.findMappingDrift(storedCommit,
                    Collections.singletonList(localDiff(FOO)));

            // then - Foo at HEAD is the mapped version, so its line numbers still line up
            assertTrue(drift.getDriftByFetchKey().isEmpty());
        } finally {
            reader.close();
        }
    }

    @Test
    void workspaceWithLocalCommitsOnAnOlderBaseReportsBothSides() throws Exception {
        // given - main: A adds Foo/Bar, B edits Foo (the mapping is at B). The workspace branched
        // at A and committed its own edit to Bar, then edited both files locally.
        String commitA = commitFiles("A", FOO, "foo-a", BAR, "bar-a");
        String storedCommit = commitFiles("B", FOO, "foo-b");
        git.checkout().setCreateBranch(true).setName("feature").setStartPoint(commitA).call();
        String localCommit = commitFiles("local", BAR, "bar-local-commit");
        writeFile(FOO, "foo-local");
        writeFile(BAR, "bar-local");

        GitReader reader = new GitReader(tempDir.getAbsolutePath());
        try {
            // when
            MappingDrift drift = reader.findMappingDrift(storedCommit,
                    Arrays.asList(localDiff(FOO), localDiff(BAR)));

            // then - Foo is missing B (upstream); Bar has the local commit the mapping doesn't
            Map<String, MappingDrift.FileDrift> expected = new LinkedHashMap<>();
            expected.put(FOO, behind(abbreviate(storedCommit)));
            expected.put(BAR, ahead(abbreviate(localCommit)));
            assertEquals(expected, drift.getDriftByFetchKey());
        } finally {
            reader.close();
        }
    }

    @Test
    void storedCommitNotInTheRepositoryIsReportedAsNotFound() throws Exception {
        // given - a stored commit id the local repository has never fetched
        commitFiles("A", FOO, "foo-a");
        writeFile(FOO, "foo-local");

        GitReader reader = new GitReader(tempDir.getAbsolutePath());
        try {
            // when
            MappingDrift drift = reader.findMappingDrift(
                    "0123456789abcdef0123456789abcdef01234567", Collections.singletonList(localDiff(FOO)));

            // then
            assertFalse(drift.isBaselineFound());
            assertTrue(drift.getDriftByFetchKey().isEmpty());
        } finally {
            reader.close();
        }
    }

    /**
     * Build a local MODIFY diff whose fetch key is the repo-relative path, as the analyzer builds it.
     *
     * @param path the repo-relative path.
     * @return the diff context.
     */
    private SourceFileDiffContext localDiff(String path) {
        String localPath = new File(tempDir, path).getAbsolutePath();
        SourceFileDiffContext diff = new SourceFileDiffContext(localPath, localPath, ChangeType.MODIFY);
        diff.setVcsFetchKey(path);
        return diff;
    }

    /**
     * @param commits the commits in the mapping the workspace doesn't have, oldest first.
     * @return a behind-only file drift.
     */
    private static MappingDrift.FileDrift behind(String... commits) {
        return new MappingDrift.FileDrift(Arrays.asList(commits), Collections.emptyList());
    }

    /**
     * @param commits the commits in the workspace the mapping doesn't have, oldest first.
     * @return an ahead-only file drift.
     */
    private static MappingDrift.FileDrift ahead(String... commits) {
        return new MappingDrift.FileDrift(Collections.emptyList(), Arrays.asList(commits));
    }

    /**
     * @param commitId a full commit id.
     * @return the abbreviated id the analyzer reports.
     */
    private static String abbreviate(String commitId) {
        return commitId.substring(0, 8);
    }

    /**
     * Write each (path, content) pair and commit them.
     *
     * @param message the commit message.
     * @param pathsAndContents alternating repo-relative paths and contents.
     * @return the SHA of the created commit.
     * @throws Exception if any write or JGit operation fails.
     */
    private String commitFiles(String message, String... pathsAndContents) throws Exception {
        List<String> args = Arrays.asList(pathsAndContents);
        for (int i = 0; i < args.size(); i += 2) {
            writeFile(args.get(i), args.get(i + 1));
        }
        git.add().addFilepattern(".").call();
        return git.commit().setMessage(message).setAuthor("tia", "tia@example.com")
                .setCommitter("tia", "tia@example.com").setSign(false).call().getName();
    }

    /**
     * Write {@code content} to {@code relativePath} under the repo, creating parent directories.
     *
     * @param relativePath the repo-relative file path.
     * @param content the file content to write.
     * @throws Exception if the write fails.
     */
    private void writeFile(String relativePath, String content) throws Exception {
        File file = new File(tempDir, relativePath);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}
