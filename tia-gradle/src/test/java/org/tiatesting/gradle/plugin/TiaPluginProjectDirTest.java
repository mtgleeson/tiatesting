package org.tiatesting.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the configured {@code projectDir} - the base the source and test directories are resolved
 * against - is taken from the Gradle project's directory, never the daemon's working directory.
 */
class TiaPluginProjectDirTest {

    @Test
    void anUnsetProjectDirIsTheGradleProjectsDirectory(@TempDir File projectDir) {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();

        // when
        File resolved = TiaPlugin.resolveProjectDir(project, null);

        // then
        assertEquals(project.getProjectDir().getAbsoluteFile(), resolved);
    }

    @Test
    void aRelativeProjectDirIsTakenFromTheGradleProjectsDirectory(@TempDir File projectDir) {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();

        // when
        File resolved = TiaPlugin.resolveProjectDir(project, "../source-project");

        // then
        assertEquals(new File(project.getProjectDir(), "../source-project"), resolved);
    }

    @Test
    void anAbsoluteProjectDirIsUsedAsIs(@TempDir File projectDir, @TempDir File elsewhere) {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();

        // when
        File resolved = TiaPlugin.resolveProjectDir(project, elsewhere.getAbsolutePath());

        // then
        assertEquals(elsewhere.getAbsoluteFile(), resolved);
    }
}
