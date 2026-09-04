package org.tiatesting.spock.git.gradle.plugin;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.agent.ForkSystemProperties;
import org.tiatesting.gradle.plugin.TiaBaseTaskExtension;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Gradle plugin must tell the forked test JVM where the compiled test classes are.
 *
 * <p>Without them a fork answers "has this suite been deleted?" from the suites it happened to
 * observe, which is only correct when one JVM runs the whole project. Split the run
 * ({@code maxParallelForks > 1}, {@code forkEvery > 0}) and each fork is given a share of the
 * classes, so each concludes that every suite the others own has been deleted and removes its stored
 * mapping. The directories are identical for every fork, so answering from them makes the question
 * independent of how the run was split - which is why the Maven path, which has always forwarded
 * them, was never exposed to this.
 */
class TiaSpockGitGradlePluginTestClassesDirsTest {

    /**
     * The test task's own compiled output directories reach the fork.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void theTestClassesDirectoriesAreForwardedToTheFork(@TempDir File projectDir) {
        // given
        Test testTask = testTaskWithTiaApplied(projectDir);
        enableTia(projectExtension(testTask), projectDir);

        // when
        runTiaTaskAction(testTask);

        // then
        Object forwarded = testTask.getSystemProperties().get(ForkSystemProperties.PROP_TEST_CLASSES_DIRS);
        assertNotNull(forwarded, "the fork needs the test classes directories to tell a deleted "
                + "suite from one it did not run: " + testTask.getSystemProperties());
        assertTrue(String.valueOf(forwarded).contains("classes"), String.valueOf(forwarded));
    }

    /**
     * Gradle's {@code testClassesDirs} is a collection - a Groovy project has both the java and
     * groovy test outputs - so the forwarded value must carry all of them, comma separated, the way
     * every other multi-valued Tia setting is expressed.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void severalOutputDirectoriesAreForwardedCommaSeparated(@TempDir File projectDir) {
        // given
        Test testTask = testTaskWithTiaApplied(projectDir);
        enableTia(projectExtension(testTask), projectDir);
        testTask.setTestClassesDirs(testTask.getProject().files(
                new File(projectDir, "build/classes/java/test"),
                new File(projectDir, "build/classes/groovy/test")));

        // when
        runTiaTaskAction(testTask);

        // then
        String forwarded = String.valueOf(
                testTask.getSystemProperties().get(ForkSystemProperties.PROP_TEST_CLASSES_DIRS));
        assertTrue(forwarded.contains(","), forwarded);
        assertTrue(forwarded.contains("java"), forwarded);
        assertTrue(forwarded.contains("groovy"), forwarded);
    }

    /**
     * Build a project with the Tia plugin and test extension applied to its {@code test} task.
     *
     * @param projectDir the directory to root the project at
     * @return the applied test task, not yet run
     */
    private static Test testTaskWithTiaApplied(final File projectDir) {
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply("java");
        project.getPlugins().apply("jacoco");
        project.getPlugins().apply(TiaSpockGitGradlePluginSchemaSuffixTest.TestPlugin.class);
        Test testTask = (Test) project.getTasks().getByName("test");
        new TiaSpockGitGradlePluginTestExtension().applyTo(testTask);
        return testTask;
    }

    /**
     * @param testTask the task whose project extension is wanted
     * @return the project-level Tia extension
     */
    private static TiaBaseTaskExtension projectExtension(final Test testTask) {
        return testTask.getProject().getExtensions().getByType(TiaBaseTaskExtension.class);
    }

    /**
     * Configure the minimum an enabled Tia build needs so the task action runs through.
     *
     * @param extension the project-level Tia extension to configure
     * @param projectDir the directory to point the path-shaped properties at
     */
    private static void enableTia(final TiaBaseTaskExtension extension, final File projectDir) {
        extension.setEnabled(Boolean.TRUE);
        extension.setUpdateDBMapping(Boolean.FALSE);
        extension.setCheckLocalChanges(Boolean.FALSE);
        extension.setProjectDir(projectDir.getAbsolutePath());
        extension.setDbFilePath(projectDir.getAbsolutePath());
        extension.setClassFilesDirs("build/classes");
        extension.setSourceFilesDirs("src/main/java");
        extension.setTestFilesDirs("src/test/groovy");
    }

    /**
     * Run the task action the Tia test extension registered.
     *
     * @param testTask the task whose Tia action to run
     */
    private static void runTiaTaskAction(final Test testTask) {
        Action<? super Task> tiaAction = testTask.getActions().get(0);
        tiaAction.execute(testTask);
    }
}
