package org.tiatesting.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies the plugin helpers Tia's task classes use instead of reading their own project while they
 * execute, which Gradle 10 refuses.
 */
class TiaPluginProjectAccessTest {

    @TempDir
    File projectDir;

    @Test
    void resolveProjectDirResolvesARelativeDirAgainstTheGradleProject() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        TiaPlugin plugin = project.getPlugins().apply(TiaPlugin.class);
        project.getExtensions().getByType(TiaBaseTaskExtension.class).setProjectDir("sub");

        // when
        File resolved = plugin.resolveProjectDir();

        // then
        assertEquals(new File(project.getProjectDir(), "sub").getAbsoluteFile(), resolved.getAbsoluteFile());
    }

    @Test
    void resolveProjectDirIsTheGradleProjectWhenUnset() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        TiaPlugin plugin = project.getPlugins().apply(TiaPlugin.class);

        // when
        File resolved = plugin.resolveProjectDir();

        // then
        assertEquals(project.getProjectDir().getAbsoluteFile(), resolved.getAbsoluteFile());
    }

    @Test
    void distributedClaimRegistryIsTheBuildsRegistry() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        TiaPlugin plugin = project.getPlugins().apply(TiaPlugin.class);

        // when
        DistributedClaimRegistry registry = plugin.distributedClaimRegistry();

        // then
        assertSame(DistributedClaimRegistry.forBuild(project.getGradle()), registry);
    }

    @Test
    void sourceProjectDirResolvesARelativeDirAgainstTheGradleProject() {
        // given - a Gradle daemon's working directory is the daemon's own, not the project's
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        TiaPlugin plugin = project.getPlugins().apply(TiaPlugin.class);
        project.getExtensions().getByType(TiaBaseTaskExtension.class).setSourceProjectDir("../app");

        // when
        String resolved = plugin.getSourceProjectDir();

        // then
        assertEquals(new File(project.getProjectDir().getAbsoluteFile(), "../app").getPath(), resolved);
    }

    @Test
    void sourceProjectDirIsTheResolvedProjectDirWhenUnset() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        TiaPlugin plugin = project.getPlugins().apply(TiaPlugin.class);
        project.getExtensions().getByType(TiaBaseTaskExtension.class).setProjectDir(".");

        // when
        String resolved = plugin.getSourceProjectDir();

        // then
        assertEquals(new File(project.getProjectDir().getAbsoluteFile(), ".").getPath(), resolved);
    }
}
