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
 * Verifies {@link GitReader#getWorkspaceRoot}: the repository's work tree, which the changed paths
 * are relative to, even when the reader is opened on a project below it.
 */
class GitReaderWorkspaceRootTest {

    private File tempDir;
    private Git git;

    /**
     * Create a repository with one commit, holding a project directory below the root.
     *
     * @throws Exception if the repository can't be created
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("tia-git-root-").toFile();
        git = Git.init().setDirectory(tempDir).setInitialBranch("main").call();
        File projectDir = new File(tempDir, "app");
        projectDir.mkdirs();
        Files.write(new File(projectDir, "pom.xml").toPath(), "<project/>".getBytes(StandardCharsets.UTF_8));
        git.add().addFilepattern(".").call();
        git.commit().setMessage("init").setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
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
    void workspaceRootIsTheWorkTreeWhenOpenedOnASubProject() throws Exception {
        // given a reader opened on the project directory below the repository root
        GitReader reader = new GitReader(new File(tempDir, "app").getAbsolutePath());

        // when the workspace root is asked for
        String root;
        try {
            root = reader.getWorkspaceRoot();
        } finally {
            reader.close();
        }

        // then it is the repository's work tree
        assertEquals(tempDir.getCanonicalPath(), new File(root).getCanonicalPath());
    }
}
