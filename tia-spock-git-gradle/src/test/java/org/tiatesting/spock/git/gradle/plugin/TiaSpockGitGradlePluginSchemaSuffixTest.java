package org.tiatesting.spock.git.gradle.plugin;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.persistence.DataStoreFactory;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.gradle.plugin.TiaBasePlugin;
import org.tiatesting.gradle.plugin.TiaBaseTaskExtension;

import org.tiatesting.core.diff.SourceFileDiffContext;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schema suffix, and the guard that makes declaring it safe to leave optional.
 *
 * <p>Two Tia-enabled test tasks sharing a datastore corrupt each other: each sees only its own
 * source set, so each deletes the other's tracked suites, and they share the one {@code tia_core}
 * row and therefore the one stored commit value. Neither failure fails a build - the first costs all
 * selectivity, the second silently under-selects - so the only thing that makes an opt-in setting
 * safe is refusing the colliding configuration outright.
 */
class TiaSpockGitGradlePluginSchemaSuffixTest {

    /**
     * Minimal concrete {@link TiaBasePlugin} with a stubbed VCS reader, so the guard can resolve a
     * branch without a real repository.
     */
    static class TestPlugin extends TiaBasePlugin {
        @Override
        public VCSReader getVCSReader() {
            return new StubVCSReader();
        }
    }

    /**
     * An unset suffix must forward no property at all, so the fork resolves the plain
     * {@code tia_<branch>} schema Tia has always used rather than one named "null".
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void anUndeclaredSuffixForwardsNothing(@TempDir File projectDir) {
        // given
        Test testTask = testTaskWithTiaApplied(projectDir);
        enableTia(projectExtension(testTask), projectDir);

        // when
        runTiaTaskAction(testTask);

        // then
        Map<String, Object> systemProperties = testTask.getSystemProperties();
        assertFalse(systemProperties.containsKey(DataStoreFactory.PROP_DB_SCHEMA_SUFFIX),
                systemProperties.toString());
    }

    /**
     * A declared suffix reaches the forked test JVM, which is where the schema is resolved.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void aDeclaredSuffixIsForwardedToTheFork(@TempDir File projectDir) {
        // given
        Test testTask = testTaskWithTiaApplied(projectDir);
        enableTia(projectExtension(testTask), projectDir);
        taskExtension(testTask).setSchemaSuffix("unit");

        // when
        runTiaTaskAction(testTask);

        // then
        assertEquals("unit",
                testTask.getSystemProperties().get(DataStoreFactory.PROP_DB_SCHEMA_SUFFIX));
    }

    /**
     * A suffix declared once at the project level reaches a test task that does not declare its own,
     * for the project that wants every task in one named schema rather than the unsuffixed default.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void aProjectLevelSuffixMergesIntoATestTask(@TempDir File projectDir) {
        // given
        Test testTask = testTaskWithTiaApplied(projectDir);
        TiaBaseTaskExtension projectExtension = projectExtension(testTask);
        enableTia(projectExtension, projectDir);
        projectExtension.setSchemaSuffix("shared");

        // when
        runTiaTaskAction(testTask);

        // then
        assertEquals("shared",
                testTask.getSystemProperties().get(DataStoreFactory.PROP_DB_SCHEMA_SUFFIX));
    }

    /**
     * The regression this stage exists for: two mapping-owning test tasks resolving to one schema
     * must fail the build rather than silently delete each other's suites and share a commit value.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void twoMappingTasksSharingASchemaAreRefused(@TempDir File projectDir) {
        // given - both tasks mapping-owning, neither declaring a suffix
        Test testTask = testTaskWithTiaApplied(projectDir);
        TiaBaseTaskExtension projectExtension = projectExtension(testTask);
        enableTia(projectExtension, projectDir);
        projectExtension.setUpdateDBMapping(Boolean.TRUE);
        secondTestTaskWithTiaApplied(testTask, "integrationTest");

        // when / then
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> runTiaTaskAction(testTask));
        assertTrue(thrown.getMessage().contains(":test"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(":integrationTest"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("schemaSuffix"), thrown.getMessage());
    }

    /**
     * Declaring a suffix on each is the fix the refusal points at, so it has to actually work.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void distinctSuffixesAreAccepted(@TempDir File projectDir) {
        // given
        Test testTask = testTaskWithTiaApplied(projectDir);
        TiaBaseTaskExtension projectExtension = projectExtension(testTask);
        enableTia(projectExtension, projectDir);
        projectExtension.setUpdateDBMapping(Boolean.TRUE);
        Test integrationTest = secondTestTaskWithTiaApplied(testTask, "integrationTest");

        taskExtension(testTask).setSchemaSuffix("unit");
        taskExtension(integrationTest).setSchemaSuffix("integration");

        // when / then - no refusal, and each task carries its own suffix to its fork
        runTiaTaskAction(testTask);
        assertEquals("unit",
                testTask.getSystemProperties().get(DataStoreFactory.PROP_DB_SCHEMA_SUFFIX));
    }

    /**
     * A task that does not own mapping updates writes no mapping and deletes nothing, so it cannot
     * collide with anything. Failing a build over it would refuse a configuration that is perfectly
     * safe - a preview or history-only task alongside the mapping-owning one.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void aNonMappingTaskDoesNotCollide(@TempDir File projectDir) {
        // given - the second task is enabled but does not own the mapping
        Test testTask = testTaskWithTiaApplied(projectDir);
        TiaBaseTaskExtension projectExtension = projectExtension(testTask);
        enableTia(projectExtension, projectDir);
        projectExtension.setUpdateDBMapping(Boolean.FALSE);
        Test previewTest = secondTestTaskWithTiaApplied(testTask, "previewTest");
        taskExtension(testTask).setUpdateDBMapping(Boolean.TRUE);
        taskExtension(previewTest).setUpdateDBMapping(Boolean.FALSE);

        // when / then - the mapping-owning task is the only writer, so nothing is refused
        runTiaTaskAction(testTask);
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
        project.getPlugins().apply(TestPlugin.class);
        Test testTask = (Test) project.getTasks().getByName("test");
        new TiaSpockGitGradlePluginTestExtension().applyTo(testTask);
        return testTask;
    }

    /**
     * Register a second test task on an existing project and apply the Tia extension to it - the
     * shape of a build with both {@code test} and {@code integrationTest}.
     *
     * @param firstTestTask a task whose project the new one is added to
     * @param taskName the name of the new test task
     * @return the new test task, with the extension applied and not yet run
     */
    private static Test secondTestTaskWithTiaApplied(final Test firstTestTask, final String taskName) {
        Test testTask = firstTestTask.getProject().getTasks().create(taskName, Test.class);
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
     * @param testTask the task whose own extension is wanted
     * @return the task-level Tia extension
     */
    private static TiaBaseTaskExtension taskExtension(final Test testTask) {
        return testTask.getExtensions().getByType(TiaBaseTaskExtension.class);
    }

    /**
     * Configure the minimum an enabled Tia build needs so the extension's action runs through.
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

    /** VCS reader stubbed to a fixed branch, so the guard can resolve a schema name. */
    private static final class StubVCSReader implements VCSReader {

        /** @return the fixed branch these tests resolve schemas against */
        @Override
        public String getBranchName() {
            return "main";
        }

        /** @return the fixed workspace commit */
        @Override
        public String getHeadCommit() {
            return "commit-1";
        }

        /**
         * Never called: these tests run the task action, which resolves configuration rather than
         * diffing.
         *
         * @param baseChangeNum ignored
         * @param sourceFilesDirs ignored
         * @param testFilesDirs ignored
         * @param checkLocalChanges ignored
         * @return never returns
         */
        @Override
        public Set<SourceFileDiffContext> getDiffFiles(final String baseChangeNum,
                                                       final List<String> sourceFilesDirs,
                                                       final List<String> testFilesDirs,
                                                       final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("the schema guard must not diff");
        }

        /**
         * Never called.
         *
         * @param diffs ignored
         * @param baseChangeNum ignored
         * @param checkLocalChanges ignored
         */
        @Override
        public void loadContentForDiffs(final Collection<SourceFileDiffContext> diffs,
                                        final String baseChangeNum, final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("the schema guard must not diff");
        }

        /**
         * Never called.
         *
         * @param baseChangeNum ignored
         * @param checkLocalChanges ignored
         * @return never returns
         */
        @Override
        public Set<String> getChangedFilePaths(final String baseChangeNum, final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("the schema guard must not diff");
        }

        /** No resource to release. */
        @Override
        public void close() {
        }
    }
}
