package org.tiatesting.vcs.git;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.MissingUpstreamChanges;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link GitReader#findMissingUpstreamChanges} (delegating to {@link GitDiffAnalyzer})
 * against a throwaway on-disk Git repository: a workspace at or ahead of the stored commit has
 * nothing missing, a workspace behind it reports the commits that touched each locally changed
 * file, and a stored commit not in the repository is reported as not found.
 */
class GitReaderMissingUpstreamChangesTest {

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
        tempDir = Files.createTempDirectory("tia-git-upstream-").toFile();
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
            MissingUpstreamChanges missing = reader.findMissingUpstreamChanges(storedCommit,
                    Arrays.asList(localDiff(FOO), localDiff(BAR)));

            // then - Foo is missing B and C (oldest first); Bar changed in none of the missing commits
            assertTrue(missing.isBaselineFound());
            assertEquals(Collections.singletonMap(FOO, Arrays.asList(abbreviate(commitB), abbreviate(commitC))),
                    missing.getChangesByFetchKey());
        } finally {
            reader.close();
        }
    }

    @Test
    void workspaceAheadOfTheStoredCommitHasNothingMissing() throws Exception {
        // given - the mapping is at A and the workspace is at B, which edited Foo
        String storedCommit = commitFiles("A", FOO, "foo-a");
        commitFiles("B", FOO, "foo-b");
        writeFile(FOO, "foo-local");

        GitReader reader = new GitReader(tempDir.getAbsolutePath());
        try {
            // when
            MissingUpstreamChanges missing = reader.findMissingUpstreamChanges(storedCommit,
                    Collections.singletonList(localDiff(FOO)));

            // then
            assertTrue(missing.isBaselineFound());
            assertTrue(missing.getChangesByFetchKey().isEmpty());
        } finally {
            reader.close();
        }
    }

    @Test
    void workspaceWithLocalCommitsOnAnOlderBaseReportsOnlyTheUpstreamCommits() throws Exception {
        // given - main: A adds Foo/Bar, B edits Foo (the mapping is at B). The workspace branched
        // at A and committed its own edit to Bar, then edited both files locally.
        String commitA = commitFiles("A", FOO, "foo-a", BAR, "bar-a");
        String storedCommit = commitFiles("B", FOO, "foo-b");
        git.checkout().setCreateBranch(true).setName("feature").setStartPoint(commitA).call();
        commitFiles("local", BAR, "bar-local-commit");
        writeFile(FOO, "foo-local");
        writeFile(BAR, "bar-local");

        GitReader reader = new GitReader(tempDir.getAbsolutePath());
        try {
            // when
            MissingUpstreamChanges missing = reader.findMissingUpstreamChanges(storedCommit,
                    Arrays.asList(localDiff(FOO), localDiff(BAR)));

            // then - only B (upstream, touching Foo) is missing; the local commit is not upstream
            assertEquals(Collections.singletonMap(FOO, Collections.singletonList(abbreviate(storedCommit))),
                    missing.getChangesByFetchKey());
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
            MissingUpstreamChanges missing = reader.findMissingUpstreamChanges(
                    "0123456789abcdef0123456789abcdef01234567", Collections.singletonList(localDiff(FOO)));

            // then
            assertFalse(missing.isBaselineFound());
            assertTrue(missing.getChangesByFetchKey().isEmpty());
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
