package org.tiatesting.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the configured project root is taken from the build tool's project directory, never the
 * JVM's working directory.
 */
class ProjectDirsTest {

    @Test
    void anUnsetProjectDirIsTheBuildProjectDirectory(@TempDir File buildProjectDir) {
        // given
        String configured = null;

        // when
        File resolved = ProjectDirs.resolve(buildProjectDir, configured);

        // then
        assertEquals(buildProjectDir.getAbsoluteFile(), resolved);
    }

    @Test
    void aRelativeProjectDirIsTakenFromTheBuildProjectDirectory(@TempDir File buildProjectDir) {
        // given
        String configured = "../source-project";

        // when
        File resolved = ProjectDirs.resolve(buildProjectDir, configured);

        // then
        assertEquals(new File(buildProjectDir.getAbsoluteFile(), "../source-project"), resolved);
    }

    @Test
    void anAbsoluteProjectDirIsUsedAsIs(@TempDir File buildProjectDir, @TempDir File elsewhere) {
        // given
        String configured = elsewhere.getAbsolutePath();

        // when
        File resolved = ProjectDirs.resolve(buildProjectDir, configured);

        // then
        assertEquals(elsewhere.getAbsoluteFile(), resolved);
    }
}
