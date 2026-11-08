package org.tiatesting.vcs.git;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link GitReader#isHeadBehind} against a throwaway repository: the head is behind a
 * commit only when it is a strict ancestor of it. Equal, ahead, diverged and unknown commits are
 * not behind.
 */
class GitReaderHeadBehindTest {

    private File tempDir;
    private Git git;

    /**
     * Create an empty repository in a fresh temp directory.
     *
     * @throws Exception if the repository can't be created
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("tia-git-behind-").toFile();
        git = Git.init().setDirectory(tempDir).setInitialBranch("main").call();
    }

    /**
     * Close the repository and delete the temp directory.
     *
     * @throws Exception if cleanup fails
     */
    @AfterEach
    void tearDown() throws Exception {
        git.getRepository().close();
        git.close();
        FileUtils.deleteDirectory(tempDir);
    }

    @Test
    void headAncestorOfCommit_isBehind() throws Exception {
        // given - the head is checked out at A, the stored commit is B on top of A
        String commitA = commit("Foo.java", "a");
        String commitB = commit("Foo.java", "b");
        git.checkout().setName(commitA).call();

        // when
        boolean behind = isHeadBehind(commitB);

        // then
        assertTrue(behind);
    }

    @Test
    void headEqualToCommit_isNotBehind() throws Exception {
        // given
        String commitA = commit("Foo.java", "a");

        // when
        boolean behind = isHeadBehind(commitA);

        // then
        assertFalse(behind);
    }

    @Test
    void headAheadOfCommit_isNotBehind() throws Exception {
        // given
        String commitA = commit("Foo.java", "a");
        commit("Foo.java", "b");

        // when
        boolean behind = isHeadBehind(commitA);

        // then
        assertFalse(behind);
    }

    @Test
    void headDivergedFromCommit_isNotBehind() throws Exception {
        // given - B on main, C on a branch from A; the head is C
        String commitA = commit("Foo.java", "a");
        String commitB = commit("Foo.java", "b");
        git.checkout().setCreateBranch(true).setName("other").setStartPoint(commitA).call();
        commit("Bar.java", "c");

        // when
        boolean behind = isHeadBehind(commitB);

        // then
        assertFalse(behind);
    }

    @Test
    void unknownCommit_isNotBehind() throws Exception {
        // given
        commit("Foo.java", "a");

        // when
        boolean behind = isHeadBehind("0123456789abcdef0123456789abcdef01234567");

        // then
        assertFalse(behind);
    }

    /**
     * Open a reader on the repository and ask whether its head is behind the commit.
     *
     * @param commit the commit to compare the head with
     * @return the reader's answer
     */
    private boolean isHeadBehind(String commit) {
        GitReader reader = new GitReader(tempDir.getAbsolutePath());
        try {
            return reader.isHeadBehind(commit);
        } finally {
            reader.close();
        }
    }

    /**
     * Write a file and commit it.
     *
     * @param path the repository-relative path
     * @param content the file content
     * @return the new commit's id
     * @throws Exception if the write or commit fails
     */
    private String commit(String path, String content) throws Exception {
        File file = new File(tempDir, path);
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        git.add().addFilepattern(path).call();
        return git.commit().setMessage(content).setAuthor("tia", "tia@example.com")
                .setCommitter("tia", "tia@example.com").call().getName();
    }
}
