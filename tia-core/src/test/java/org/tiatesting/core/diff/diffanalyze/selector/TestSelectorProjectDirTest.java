package org.tiatesting.core.diff.diffanalyze.selector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the configured source and test directories are resolved against the project directory
 * the selector was given - never the JVM's working directory, which in a long-lived Gradle daemon
 * or a Maven multi-module build is not the project's.
 */
class TestSelectorProjectDirTest {

    @Test
    void relativeEntriesResolveUnderTheProjectDirectory(@TempDir File projectDir) throws IOException {
        // given
        File mainDir = new File(projectDir, "src/main/java");
        File testDir = new File(projectDir, "src/test/java");
        assertTrue(mainDir.mkdirs() && testDir.mkdirs());
        TestSelector selector = new TestSelector(null, projectDir);

        // when
        List<String> resolved = selector.getFullFilePaths(Arrays.asList("/src/main/java", "src/test/java"));

        // then
        assertEquals(Arrays.asList(mainDir.getCanonicalPath(), testDir.getCanonicalPath()), resolved);
    }

    @Test
    void anAbsoluteEntryOutsideTheProjectIsKept(@TempDir File projectDir, @TempDir File otherDir) throws IOException {
        // given
        TestSelector selector = new TestSelector(null, projectDir);

        // when
        List<String> resolved = selector.getFullFilePaths(Collections.singletonList(otherDir.getAbsolutePath()));

        // then
        assertEquals(Collections.singletonList(otherDir.getCanonicalPath()), resolved);
    }

    @Test
    void aRelativeEntryMissingUnderTheProjectIsNotResolvedAgainstTheWorkingDirectory(@TempDir File projectDir) {
        // given - "src" exists under the working directory (the module directory) but not the project
        assertTrue(new File("src").exists());
        TestSelector selector = new TestSelector(null, projectDir);

        // when
        List<String> resolved = selector.getFullFilePaths(Collections.singletonList("src"));

        // then - skipped with a warning, never the working directory's "src"
        assertTrue(resolved.isEmpty(), resolved.toString());
    }
}
