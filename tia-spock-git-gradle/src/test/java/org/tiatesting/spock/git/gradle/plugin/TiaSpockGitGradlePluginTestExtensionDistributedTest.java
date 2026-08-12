package org.tiatesting.spock.git.gradle.plugin;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.gradle.plugin.TiaBaseTaskExtension;

import java.io.File;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Cover the Gradle-side half of the distributed handoff: the {@code doFirst} action that starts
 * the forked test JVM with the properties {@code DistributedRunSystemProperties} reads back over
 * there. Gradle/Spock claims a group inside the test JVM, so if these properties do not reach it
 * the JVM has no way to know it is a runner at all and quietly runs an ordinary selection - which
 * on a fanned-out pipeline means every runner runs the whole suite.
 *
 * <p>Driven through a real {@link Test} task built by {@link ProjectBuilder}, running the task
 * action the extension registers, because the property forwarding happens in a task action and
 * would otherwise only be exercised by a full build.
 */
class TiaSpockGitGradlePluginTestExtensionDistributedTest {

    /**
     * Build a Gradle project with the plugins the Tia test extension expects, a {@code test} task,
     * and a project-level {@code tia { ... }} extension, then apply the Tia test extension to that
     * task. The returned task has not run its actions yet.
     *
     * @param projectDir the temporary directory to root the project at
     * @return the {@code test} task with the Tia extension applied
     */
    private static Test testTaskWithTiaApplied(final File projectDir) {
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply("java");
        project.getPlugins().apply("jacoco");
        project.getExtensions().create("tia", TiaBaseTaskExtension.class);
        Test testTask = (Test) project.getTasks().getByName("test");
        new TiaSpockGitGradlePluginTestExtension().applyTo(testTask);
        return testTask;
    }

    /**
     * The project-level Tia extension of the given task's project, which is where a pipeline
     * configures a distributed run - the run id identifies the CI build, not one test task.
     *
     * @param testTask the test task whose project extension is wanted
     * @return the project-level Tia extension
     */
    private static TiaBaseTaskExtension projectExtension(final Test testTask) {
        return testTask.getProject().getExtensions().getByType(TiaBaseTaskExtension.class);
    }

    /**
     * Configure the minimum an enabled Tia build needs so the extension's action reaches the
     * property forwarding rather than short-circuiting on a disabled build. Every path-shaped
     * property is given a value because the action forwards each one to the test JVM as it stands.
     *
     * @param extension the project-level Tia extension to configure
     * @param projectDir the project directory to point the path-shaped properties at
     */
    private static void enableTia(final TiaBaseTaskExtension extension, final File projectDir) {
        extension.setEnabled(Boolean.TRUE);
        extension.setUpdateDBMapping(Boolean.FALSE);
        extension.setUpdateDBStats(Boolean.FALSE);
        extension.setCheckLocalChanges(Boolean.FALSE);
        extension.setProjectDir(projectDir.getAbsolutePath());
        extension.setDbFilePath(projectDir.getAbsolutePath());
        extension.setClassFilesDirs("build/classes");
        extension.setSourceFilesDirs("src/main/java");
        extension.setTestFilesDirs("src/test/groovy");
    }

    /**
     * Run the task action the Tia test extension registered, which is what resolves the Tia
     * configuration and sets the system properties the forked test JVM is started with.
     *
     * <p>Only that one action is run, not the task's whole action list: the last entry is the test
     * task's own execution action, and running it here would resolve the test runtime and fork a
     * JVM to run a test suite that does not exist. The Tia action is the first entry because the
     * extension registers it with {@code doFirst}, which prepends.
     *
     * @param testTask the task whose Tia action to run
     */
    private static void runTiaTaskAction(final Test testTask) {
        Action<? super Task> tiaAction = testTask.getActions().get(0);
        tiaAction.execute(testTask);
    }

    /**
     * Verify a project-level distributed run configuration reaches the forked test JVM. Both
     * values are needed there and neither can be worked out inside it: the run id names the plan
     * to claim from, and the runner key is the identity the claim is recorded under.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void shouldForwardTheDistributedRunToTheForkedTestJvm(@TempDir File projectDir) {
        // given
        Test testTask = testTaskWithTiaApplied(projectDir);
        TiaBaseTaskExtension extension = projectExtension(testTask);
        enableTia(extension, projectDir);
        extension.setDistributed(Boolean.TRUE);
        extension.setRunId("run-1");
        extension.setDistributedRunnerKey("runner-a");

        // when
        runTiaTaskAction(testTask);

        // then
        Map<String, Object> systemProperties = testTask.getSystemProperties();
        assertEquals("true", systemProperties.get("tiaDistributed"));
        assertEquals("run-1", systemProperties.get("tiaRunId"));
        assertEquals("runner-a", systemProperties.get("tiaDistributedRunnerKey"));
    }

    /**
     * Verify an ordinary Gradle build's test JVM is started with none of the distributed
     * properties, so it takes the selection path it always took. This is the guarantee that a
     * non-distributed Spock run is unchanged by this stage.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void shouldForwardNoDistributedPropertiesForAnOrdinaryBuild(@TempDir File projectDir) {
        // given
        Test testTask = testTaskWithTiaApplied(projectDir);
        enableTia(projectExtension(testTask), projectDir);

        // when
        runTiaTaskAction(testTask);

        // then
        Map<String, Object> systemProperties = testTask.getSystemProperties();
        assertFalse(systemProperties.containsKey("tiaDistributed"), systemProperties.toString());
        assertFalse(systemProperties.containsKey("tiaRunId"), systemProperties.toString());
        assertFalse(systemProperties.containsKey("tiaDistributedRunnerKey"), systemProperties.toString());
        // the ordinary properties are still forwarded exactly as before
        assertEquals(Boolean.TRUE, systemProperties.get("tiaEnabled"));
        assertEquals(Boolean.FALSE, systemProperties.get("tiaCheckLocalChanges"));
    }

    /**
     * Verify a runner with no configured identity forwards no runner key at all rather than the
     * string "null". A test JVM given that literal would claim under it, so every runner in the
     * pipeline would share one identity and the second claim would be read as the first runner's
     * job retrying instead of a new runner arriving.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void shouldForwardNoRunnerKeyWhenNoneIsConfigured(@TempDir File projectDir) {
        // given
        Test testTask = testTaskWithTiaApplied(projectDir);
        TiaBaseTaskExtension extension = projectExtension(testTask);
        enableTia(extension, projectDir);
        extension.setDistributed(Boolean.TRUE);
        extension.setRunId("run-1");

        // when
        runTiaTaskAction(testTask);

        // then
        Map<String, Object> systemProperties = testTask.getSystemProperties();
        assertEquals("true", systemProperties.get("tiaDistributed"));
        assertFalse(systemProperties.containsKey("tiaDistributedRunnerKey"), systemProperties.toString());
    }

    /**
     * Verify a distributed run configured on one test task rather than on the project reaches that
     * task's JVM, and that a task-level setting is what wins. A build with more than one test task
     * may want only one of them distributed.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void shouldPreferTheTestTasksOwnDistributedRunOverTheProjects(@TempDir File projectDir) {
        // given
        Test testTask = testTaskWithTiaApplied(projectDir);
        TiaBaseTaskExtension extension = projectExtension(testTask);
        enableTia(extension, projectDir);
        extension.setDistributed(Boolean.TRUE);
        extension.setRunId("project-run");
        TiaBaseTaskExtension taskExtension = testTask.getExtensions().getByType(TiaBaseTaskExtension.class);
        taskExtension.setRunId("task-run");

        // when
        runTiaTaskAction(testTask);

        // then
        assertEquals("task-run", testTask.getSystemProperties().get("tiaRunId"));
    }
}
