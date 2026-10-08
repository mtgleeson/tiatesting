package org.tiatesting.gradle.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the configured source and test directories are resolved against the Gradle project
 * directory, never the daemon's working directory: relative entries (with or without a leading
 * {@code /}) become absolute paths under the project, and anything else is passed through.
 */
class ProjectRelativeDirsTest {

    @Test
    void relativeEntriesResolveUnderTheProjectDirectory(@TempDir File projectDir) {
        // given
        File mainDir = new File(projectDir, "src/main/java");
        File testDir = new File(projectDir, "src/test/java");
        assertTrue(mainDir.mkdirs() && testDir.mkdirs());

        // when
        List<String> resolved = ProjectRelativeDirs.resolve(projectDir, "/src/main/java, src/test/java");

        // then
        assertEquals(Arrays.asList(mainDir.getAbsolutePath(), testDir.getAbsolutePath()), resolved);
    }

    @Test
    void anAbsoluteEntryOutsideTheProjectIsKept(@TempDir File projectDir, @TempDir File otherDir) {
        // given
        String absolute = otherDir.getAbsolutePath();

        // when
        List<String> resolved = ProjectRelativeDirs.resolve(projectDir, absolute);

        // then
        assertEquals(Collections.singletonList(absolute), resolved);
    }

    @Test
    void anEntryMissingUnderTheProjectIsKeptAsConfigured(@TempDir File projectDir) {
        // given - nothing exists under the project

        // when
        List<String> resolved = ProjectRelativeDirs.resolve(projectDir, " /src/main/java ");

        // then - TestSelector warns about it rather than it being silently rewritten
        assertEquals(Collections.singletonList("/src/main/java"), resolved);
    }

    @Test
    void noConfiguredDirectoriesStayNull(@TempDir File projectDir) {
        // given
        String csv = null;

        // when
        List<String> resolved = ProjectRelativeDirs.resolve(projectDir, csv);

        // then
        assertNull(resolved);
    }
}
