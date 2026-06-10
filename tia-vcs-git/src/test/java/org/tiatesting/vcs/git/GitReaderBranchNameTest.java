package org.tiatesting.vcs.git;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that {@link GitReader#getBranchName()} reports the short VCS branch name (e.g. {@code main}
 * / {@code feature/foo}) rather than the full {@code refs/heads/...} ref, and the commit SHA when
 * HEAD is detached. Builds a throwaway on-disk Git repository per test via JGit.
 */
class GitReaderBranchNameTest {

    private File tempDir;
    private Git git;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("tia-git-test-").toFile();
        git = Git.init().setDirectory(tempDir).setInitialBranch("main").call();
        commitSomething("init");
    }

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

    /**
     * Stage a file and create a commit so HEAD points at a real branch tip.
     *
     * @param message the commit message
     * @throws Exception if any JGit operation fails
     */
    private void commitSomething(final String message) throws Exception {
        File file = new File(tempDir, "file-" + message + ".txt");
        Files.write(file.toPath(), message.getBytes(StandardCharsets.UTF_8));
        git.add().addFilepattern(".").call();
        git.commit().setMessage(message).setAuthor("tia", "tia@example.com")
                .setCommitter("tia", "tia@example.com").setSign(false).call();
    }

    /**
     * Read the branch name through a fresh {@link GitReader} over the test repo, ensuring the
     * reader's repository handle is released afterwards.
     *
     * @return the value of {@link GitReader#getBranchName()} for the repo's current HEAD
     */
    private String readBranchName() {
        GitReader reader = new GitReader(tempDir.getAbsolutePath());
        try {
            return reader.getBranchName();
        } finally {
            reader.close();
        }
    }

    @Test
    void getBranchNameReturnsShortNameOnSimpleBranch() {
        // given
        // repo initialised on branch "main" with one commit (see setUp)

        // when
        String branch = readBranchName();

        // then
        assertEquals("main", branch);
    }

    @Test
    void getBranchNameKeepsSlashOnNestedBranch() throws Exception {
        // given
        git.checkout().setCreateBranch(true).setName("feature/foo").call();

        // when
        String branch = readBranchName();

        // then
        // the short name retains the slash; sanitization happens where the H2 URL is built
        assertEquals("feature/foo", branch);
    }

    @Test
    void getBranchNameReturnsCommitShaWhenHeadDetached() throws Exception {
        // given
        String headSha = git.getRepository().resolve("HEAD").getName();
        git.checkout().setName(headSha).call(); // detach HEAD onto the commit

        // when
        String branch = readBranchName();

        // then
        assertEquals(headSha, branch);
    }
}
