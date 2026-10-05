package org.tiatesting.gradle.plugin;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link TiaPlugin#configureTestRuntime()}: for a project with Tia enabled on a test task
 * it detects the framework and adds its Tia module to {@code testRuntimeOnly} at the plugin's
 * version, and applies jacoco when a test task updates the mapping - including when only the test
 * task's own extension says so, which used to leave jacoco unapplied and the task action failing.
 */
class TiaPluginTestRuntimeTest {

    @org.junit.jupiter.api.Test
    void enabledSpockProjectGetsTiaSpockAtThePluginVersion(@TempDir File projectDir) {
        // given
        Project project = projectWithSpock(projectDir);
        projectExtension(project).setEnabled(Boolean.TRUE);

        // when
        plugin(project).configureTestRuntime();

        // then
        Dependency tiaSpock = project.getConfigurations().getByName("testRuntimeOnly").getDependencies()
                .stream().filter(d -> "tia-spock".equals(d.getName())).findFirst().orElse(null);
        assertTrue(tiaSpock != null, "tia-spock was not added to testRuntimeOnly");
        assertEquals("org.tiatesting", tiaSpock.getGroup());
        assertEquals(TiaVersion.get(), tiaSpock.getVersion());
        assertEquals(SpockFrameworkAdapter.NAME, plugin(project).getTestFrameworkAdapter().name());
    }

    @org.junit.jupiter.api.Test
    void taskLevelUpdateDBMappingAppliesJacoco(@TempDir File projectDir) {
        // given - the project-level extension does not update the mapping; the test task does
        Project project = projectWithSpock(projectDir);
        projectExtension(project).setEnabled(Boolean.TRUE);
        projectExtension(project).setUpdateDBMapping(Boolean.FALSE);
        taskExtension(project).setUpdateDBMapping(Boolean.TRUE);

        // when
        plugin(project).configureTestRuntime();

        // then
        assertTrue(project.getPlugins().hasPlugin("jacoco"));
    }

    @org.junit.jupiter.api.Test
    void jacocoIsNotAppliedWhenNoTestTaskUpdatesTheMapping(@TempDir File projectDir) {
        // given
        Project project = projectWithSpock(projectDir);
        projectExtension(project).setEnabled(Boolean.TRUE);
        projectExtension(project).setUpdateDBMapping(Boolean.FALSE);

        // when
        plugin(project).configureTestRuntime();

        // then
        assertFalse(project.getPlugins().hasPlugin("jacoco"));
    }

    @org.junit.jupiter.api.Test
    void disabledProjectIsLeftAlone(@TempDir File projectDir) {
        // given
        Project project = projectWithSpock(projectDir);

        // when
        plugin(project).configureTestRuntime();

        // then
        assertFalse(project.getConfigurations().getByName("testRuntimeOnly").getDependencies().stream()
                .anyMatch(d -> "tia-spock".equals(d.getName())));
        assertThrows(IllegalStateException.class, () -> plugin(project).getTestFrameworkAdapter());
    }

    @org.junit.jupiter.api.Test
    void enabledProjectWithNoDetectableFrameworkFails(@TempDir File projectDir) {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply("java");
        project.getPlugins().apply(TiaPlugin.class);
        new TiaTestTaskConfigurer(SpockFrameworkAdapter::new).applyTo((Test) project.getTasks().getByName("test"));
        projectExtension(project).setEnabled(Boolean.TRUE);

        // when
        GradleException exception = assertThrows(GradleException.class,
                () -> plugin(project).configureTestRuntime());

        // then
        assertTrue(exception.getMessage().contains("testFramework"), exception.getMessage());
    }

    /**
     * @param projectDir the project directory
     * @return a Java project with the Tia plugin, Tia attached to its test task, and spock-core declared
     */
    private static Project projectWithSpock(final File projectDir) {
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply("java");
        project.getPlugins().apply(TiaPlugin.class);
        new TiaTestTaskConfigurer(SpockFrameworkAdapter::new).applyTo((Test) project.getTasks().getByName("test"));
        project.getDependencies().add("testImplementation", "org.spockframework:spock-core:2.3-groovy-3.0");
        return project;
    }

    /**
     * @param project the project
     * @return the applied Tia plugin
     */
    private static TiaPlugin plugin(final Project project) {
        return project.getPlugins().getPlugin(TiaPlugin.class);
    }

    /**
     * @param project the project
     * @return the project-level Tia extension
     */
    private static TiaBaseTaskExtension projectExtension(final Project project) {
        return project.getExtensions().getByType(TiaBaseTaskExtension.class);
    }

    /**
     * @param project the project
     * @return the test task's own Tia extension
     */
    private static TiaBaseTaskExtension taskExtension(final Project project) {
        return (TiaBaseTaskExtension) project.getTasks().getByName("test").getExtensions().getByName("tia");
    }
}
