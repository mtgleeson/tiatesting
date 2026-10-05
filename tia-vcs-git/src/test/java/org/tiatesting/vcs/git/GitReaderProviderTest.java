package org.tiatesting.vcs.git;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.core.vcs.VCSReaderFactory;
import org.tiatesting.core.vcs.VcsSettings;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link GitReaderProvider} is registered for {@code ServiceLoader} and builds a working
 * {@link GitReader} from the settings' project dir.
 */
class GitReaderProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void factoryFindsGitProviderAndOpensTheProjectRepository() throws Exception {
        // given
        try (Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            git.commit().setMessage("init").setAuthor("tia", "tia@example.com")
                    .setCommitter("tia", "tia@example.com").setSign(false).call();
        }
        VcsSettings settings = VcsSettings.builder().projectDir(tempDir.toString()).build();

        // when
        Optional<VCSReader> reader = VCSReaderFactory.create(settings, getClass().getClassLoader());

        // then
        try {
            assertEquals(GitReader.class, reader.get().getClass());
            assertEquals("main", reader.get().getBranchName());
        } finally {
            reader.get().close();
        }
    }

    @Test
    void nameIsGit() {
        // given
        GitReaderProvider provider = new GitReaderProvider();

        // when
        String name = provider.name();

        // then
        assertEquals("git", name);
    }
}
