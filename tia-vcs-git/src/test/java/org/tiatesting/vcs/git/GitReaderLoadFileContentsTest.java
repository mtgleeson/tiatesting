package org.tiatesting.vcs.git;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.vcs.FileContentPair;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link GitReader#loadFileContents} (delegating to {@link GitDiffAnalyzer}) reads the
 * before / after content of arbitrary paths - changed or not, any file type - over a commit range
 * and for local changes. Builds a throwaway on-disk Git repository per test via JGit.
 */
class GitReaderLoadFileContentsTest {

    private static final String ROOT_POM = "pom.xml";
    private static final String MODULE_POM = "module-a/pom.xml";

    private File tempDir;
    private Git git;

    /**
     * Create an empty repository in a fresh temp directory.
     *
     * @throws Exception if the repository can't be created.
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("tia-git-content-").toFile();
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
    void commitRangeReturnsOldAndNewContentForChangedAndUnchangedPaths() throws Exception {
        // given - two POMs committed, then only the module POM changed
        writeFile(ROOT_POM, "<project>root</project>");
        writeFile(MODULE_POM, "<project>v1</project>");
        String baseCommit = commitAll("add poms");
        writeFile(MODULE_POM, "<project>v2</project>");
        commitAll("edit module pom");

        GitReader reader = new GitReader(tempDir.getAbsolutePath());
        try {
            // when
            Map<String, FileContentPair> contents = reader.loadFileContents(
                    new HashSet<>(Arrays.asList(ROOT_POM, MODULE_POM)), baseCommit, false);

            // then - the changed file has both versions, the unchanged one has equal sides
            assertEquals(new FileContentPair("<project>v1</project>", "<project>v2</project>"),
                    contents.get(MODULE_POM));
            assertEquals(new FileContentPair("<project>root</project>", "<project>root</project>"),
                    contents.get(ROOT_POM));
        } finally {
            reader.close();
        }
    }

    @Test
    void commitRangeReturnsNullSidesForAddedDeletedAndMissingPaths() throws Exception {
        // given - one file deleted and another added after the base commit
        writeFile(ROOT_POM, "<project>root</project>");
        String baseCommit = commitAll("add root pom");
        new File(tempDir, ROOT_POM).delete();
        writeFile(MODULE_POM, "<project>new</project>");
        commitAll("delete root, add module");

        GitReader reader = new GitReader(tempDir.getAbsolutePath());
        try {
            // when
            Map<String, FileContentPair> contents = reader.loadFileContents(
                    new HashSet<>(Arrays.asList(ROOT_POM, MODULE_POM, "missing/pom.xml")), baseCommit, false);

            // then - every requested path has an entry; absent sides are null
            assertEquals(new FileContentPair("<project>root</project>", null), contents.get(ROOT_POM));
            assertEquals(new FileContentPair(null, "<project>new</project>"), contents.get(MODULE_POM));
            assertEquals(new FileContentPair(null, null), contents.get("missing/pom.xml"));
        } finally {
            reader.close();
        }
    }

    @Test
    void directoryPathIsTreatedAsAbsent() throws Exception {
        // given - a committed file inside module-a, then a path naming the directory itself
        writeFile(MODULE_POM, "<project>v1</project>");
        String baseCommit = commitAll("add module pom");

        GitReader reader = new GitReader(tempDir.getAbsolutePath());
        try {
            // when
            Map<String, FileContentPair> contents = reader.loadFileContents(
                    Collections.singleton("module-a"), baseCommit, false);

            // then
            assertEquals(new FileContentPair(null, null), contents.get("module-a"));
        } finally {
            reader.close();
        }
    }

    @Test
    void localChangesCompareHeadAgainstWorkingCopy() throws Exception {
        // given - committed POMs, then an uncommitted edit, an uncommitted delete and a new file
        writeFile(ROOT_POM, "<project>root</project>");
        writeFile(MODULE_POM, "<project>v1</project>");
        String headCommit = commitAll("add poms");
        writeFile(MODULE_POM, "<project>v1-local</project>");
        new File(tempDir, ROOT_POM).delete();
        writeFile("module-b/pom.xml", "<project>b</project>");

        GitReader reader = new GitReader(tempDir.getAbsolutePath());
        try {
            // when
            Map<String, FileContentPair> contents = reader.loadFileContents(
                    new HashSet<>(Arrays.asList(ROOT_POM, MODULE_POM, "module-b/pom.xml")), headCommit, true);

            // then - "before" is head, "after" is the working copy
            assertEquals(new FileContentPair("<project>v1</project>", "<project>v1-local</project>"),
                    contents.get(MODULE_POM));
            assertEquals(new FileContentPair("<project>root</project>", null), contents.get(ROOT_POM));
            assertEquals(new FileContentPair(null, "<project>b</project>"), contents.get("module-b/pom.xml"));
        } finally {
            reader.close();
        }
    }

    @Test
    void emptyPathSetReturnsEmptyMap() throws Exception {
        // given
        writeFile(ROOT_POM, "<project>root</project>");
        String baseCommit = commitAll("add root pom");

        GitReader reader = new GitReader(tempDir.getAbsolutePath());
        try {
            // when
            Map<String, FileContentPair> contents = reader.loadFileContents(
                    Collections.emptySet(), baseCommit, false);

            // then
            assertTrue(contents.isEmpty());
        } finally {
            reader.close();
        }
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

    /**
     * Stage every addition, modification and deletion in the working copy and commit it.
     *
     * @param message the commit message.
     * @return the SHA of the created commit.
     * @throws Exception if any JGit operation fails.
     */
    private String commitAll(String message) throws Exception {
        git.add().addFilepattern(".").call();
        git.add().setUpdate(true).addFilepattern(".").call();
        return git.commit().setMessage(message).setAuthor("tia", "tia@example.com")
                .setCommitter("tia", "tia@example.com").setSign(false).call().getName();
    }
}
