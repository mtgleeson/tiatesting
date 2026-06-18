package org.tiatesting.vcs.git;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that {@link GitReader#getDiffFiles} + {@link GitReader#loadContentForDiffs} (which
 * delegate to {@link GitDiffAnalyzer}) populate both the before and after content of a changed
 * source file over a commit range, after the split of list-build vs content-load. Builds a
 * throwaway on-disk Git repository per test via JGit.
 */
class GitReaderDiffContentTest {

    private static final String SOURCE_DIR = "src/main/java";
    private static final String SOURCE_FILE = SOURCE_DIR + "/com/example/Foo.java";

    private File tempDir;
    private Git git;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("tia-git-diff-").toFile();
        git = Git.init().setDirectory(tempDir).setInitialBranch("main").call();
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

    @Test
    void buildsDiffContextWithBeforeAndAfterContentOverCommitRange() throws Exception {
        // given - a source file committed, then modified and committed again
        String baseCommit = commitFile(SOURCE_FILE, "class Foo { int v = 1; }", "add Foo");
        commitFile(SOURCE_FILE, "class Foo { int v = 2; }", "edit Foo");

        GitReader reader = new GitReader(tempDir.getAbsolutePath());
        try {
            // when - analyse the range from the first commit to HEAD via the two-step API
            Set<SourceFileDiffContext> diffs = reader.getDiffFiles(baseCommit,
                    Collections.singletonList(SOURCE_DIR), Collections.emptyList(), false);
            reader.loadContentForDiffs(diffs, baseCommit, false);

            // then - one diff context, with both versions' content loaded through the new
            // list-build-then-content-load path
            assertEquals(1, diffs.size(), "expected one changed source file");
            SourceFileDiffContext foo = diffs.iterator().next();
            assertEquals(ChangeType.MODIFY, foo.getChangeType());
            assertEquals(SOURCE_FILE, foo.getVcsFetchKey(),
                    "fetch key should be the repo-relative path");
            assertNotNull(foo.getSourceContentOriginal(), "before content must be populated");
            assertNotNull(foo.getSourceContentNew(), "after content must be populated");
            assertEquals("class Foo { int v = 1; }", foo.getSourceContentOriginal().trim());
            assertEquals("class Foo { int v = 2; }", foo.getSourceContentNew().trim());
        } finally {
            reader.close();
        }
    }

    /**
     * Write {@code content} to {@code relativePath} under the repo, stage and commit it.
     *
     * @param relativePath the repo-relative file path
     * @param content the file content to write
     * @param message the commit message
     * @return the SHA of the created commit
     * @throws Exception if any JGit / IO operation fails
     */
    private String commitFile(String relativePath, String content, String message) throws Exception {
        File file = new File(tempDir, relativePath);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        git.add().addFilepattern(".").call();
        return git.commit().setMessage(message).setAuthor("tia", "tia@example.com")
                .setCommitter("tia", "tia@example.com").setSign(false).call().getName();
    }
}
