package org.tiatesting.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the plugin wires Tia into every test task however the build is invoked - including test
 * tasks registered after the plugin is applied - and marks the tasks it adds work to as not
 * compatible with the configuration cache, so a {@code --configuration-cache} build runs instead of
 * failing.
 */
class TiaPluginTaskWiringTest {

    @org.junit.jupiter.api.Test
    void everyTestTaskGetsTheTiaExtensionWithoutBeingNamedOnTheCommandLine(@TempDir File projectDir) {
        // given - no task names on the start parameter
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply("java");
        project.getPlugins().apply(TiaPlugin.class);

        // when
        Test laterTask = project.getTasks().create("integrationTest", Test.class);

        // then
        assertTrue(project.getTasks().getByName("test").getExtensions().findByName("tia") instanceof TiaBaseTaskExtension);
        assertTrue(laterTask.getExtensions().findByName("tia") instanceof TiaBaseTaskExtension);
    }

    @org.junit.jupiter.api.Test
    void testTaskRunsWithoutTiaWhenTheProjectHasNoSupportedFramework(@TempDir File projectDir) {
        // given - Tia enabled, but no supported test framework declared
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply("java");
        project.getPlugins().apply(TiaPlugin.class);
        project.getDependencies().add("testImplementation", "junit:junit:4.13.2");
        project.getExtensions().getByType(TiaBaseTaskExtension.class).setEnabled(Boolean.TRUE);
        Test testTask = (Test) project.getTasks().getByName("test");

        // when
        testTask.getActions().get(0).execute(testTask);

        // then
        assertEquals(Boolean.FALSE, testTask.getSystemProperties().get("tiaEnabled"));
    }

    @org.junit.jupiter.api.Test
    void testAndTiaTasksAreMarkedNotConfigurationCacheCompatible(@TempDir File projectDir) {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply("java");
        project.getPlugins().apply(TiaPlugin.class);

        // when
        TaskInternal testTask = (TaskInternal) project.getTasks().getByName("test");
        TaskInternal historyTask = (TaskInternal) project.getTasks().getByName("tia-history");

        // then
        assertTrue(testTask.getReasonTaskIsIncompatibleWithConfigurationCache().isPresent());
        assertTrue(historyTask.getReasonTaskIsIncompatibleWithConfigurationCache().isPresent());
    }
}
