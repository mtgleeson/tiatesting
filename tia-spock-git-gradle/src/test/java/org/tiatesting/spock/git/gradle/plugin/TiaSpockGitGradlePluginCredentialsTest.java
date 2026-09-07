package org.tiatesting.spock.git.gradle.plugin;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.persistence.CredentialResolver;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.gradle.plugin.TiaBasePlugin;
import org.tiatesting.gradle.plugin.TiaBaseTaskExtension;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the Gradle plugin hands the database password to the test worker.
 *
 * <p>Gradle turns {@code Test.systemProperty} into a {@code -D} on the worker's command line, which
 * any local user can read out of the process table. {@code Test.environment} does not appear there,
 * and Gradle's own JUnit XML - unlike Surefire's - carries no system properties either. So the
 * password travels in the worker's environment, and only a path ever travels as a property.
 */
class TiaSpockGitGradlePluginCredentialsTest {

    private static final String SECRET = "hunter2-super-secret";

    /**
     * Minimal concrete {@link TiaBasePlugin} with a stubbed VCS reader, so the plugin can resolve a
     * branch without a real repository.
     */
    static class TestPlugin extends TiaBasePlugin {
        @Override
        public VCSReader getVCSReader() {
            return new StubVCSReader();
        }
    }

    /**
     * The core guarantee on the Gradle side: the password is in the worker's environment, and is
     * not a system property, because a system property becomes a visible command-line argument.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void thePasswordTravelsInTheWorkerEnvironmentNotAsASystemProperty(@TempDir File projectDir) {
        // given
        Test testTask = testTaskWithTiaApplied(projectDir);
        TiaBaseTaskExtension extension = projectExtension(testTask);
        enableTia(extension, projectDir);
        extension.setDbPassword(SECRET);

        // when
        runTiaTaskAction(testTask);

        // then
        assertEquals(SECRET, testTask.getEnvironment().get(CredentialResolver.ENV_DB_PASSWORD));
        Map<String, Object> systemProperties = testTask.getSystemProperties();
        assertFalse(systemProperties.containsKey("tiaDBPassword"),
                "the password must not be a system property: " + systemProperties.keySet());
        assertFalse(systemProperties.toString().contains(SECRET),
                "no system property may carry the password: " + systemProperties);
    }

    /**
     * A password file is referenced by path, so the secret stays in the file the user owns and Tia
     * forwards nothing sensitive. A path is safe as a system property.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void aPasswordFileIsForwardedAsAPathAndNoEnvironmentValue(@TempDir File projectDir) {
        // given
        Test testTask = testTaskWithTiaApplied(projectDir);
        TiaBaseTaskExtension extension = projectExtension(testTask);
        enableTia(extension, projectDir);
        extension.setDbPasswordFile("/run/secrets/tia-db-password");

        // when
        runTiaTaskAction(testTask);

        // then
        assertEquals("/run/secrets/tia-db-password",
                testTask.getSystemProperties().get(CredentialResolver.PROP_DB_PASSWORD_FILE));
        assertFalse(testTask.getEnvironment().containsKey(CredentialResolver.ENV_DB_PASSWORD),
                "a password file needs no environment value forwarded");
    }

    /**
     * With nothing configured, the worker inherits the daemon's environment and resolves
     * {@value CredentialResolver#ENV_DB_PASSWORD} for itself, so the plugin must forward neither an
     * environment entry nor a path.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void nothingIsForwardedWhenNoPasswordIsConfigured(@TempDir File projectDir) {
        // given
        Test testTask = testTaskWithTiaApplied(projectDir);
        enableTia(projectExtension(testTask), projectDir);

        // when
        runTiaTaskAction(testTask);

        // then
        assertFalse(testTask.getEnvironment().containsKey(CredentialResolver.ENV_DB_PASSWORD));
        assertFalse(testTask.getSystemProperties()
                .containsKey(CredentialResolver.PROP_DB_PASSWORD_FILE));
    }

    /**
     * An explicit empty password means "this database has no password" and must reach the worker as
     * a set-but-empty value, bypassing the environment fallback exactly as it does in the daemon.
     * Forwarding nothing would let a {@value CredentialResolver#ENV_DB_PASSWORD} that happened to
     * be set in the daemon's environment win in the worker instead.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void anExplicitlyEmptyPasswordIsStillForwarded(@TempDir File projectDir) {
        // given
        Test testTask = testTaskWithTiaApplied(projectDir);
        TiaBaseTaskExtension extension = projectExtension(testTask);
        enableTia(extension, projectDir);
        extension.setDbPassword("");

        // when
        runTiaTaskAction(testTask);

        // then
        assertTrue(testTask.getEnvironment().containsKey(CredentialResolver.ENV_DB_PASSWORD));
        assertEquals("", testTask.getEnvironment().get(CredentialResolver.ENV_DB_PASSWORD));
    }

    /**
     * Build a Gradle project with the Tia plugin applied and its test task extended.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     * @return the project's {@code test} task with the Tia action attached
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
     * @param testTask the test task whose project holds the extension
     * @return the project-level Tia extension
     */
    private static TiaBaseTaskExtension projectExtension(final Test testTask) {
        return testTask.getProject().getExtensions().getByType(TiaBaseTaskExtension.class);
    }

    /**
     * Configure the minimum that makes the plugin consider Tia enabled for this task.
     *
     * @param extension  the Tia extension to populate
     * @param projectDir the project directory the paths resolve against
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
     * Run the Tia action the plugin attached to the test task, which is what performs the
     * forwarding under test.
     *
     * @param testTask the test task to run the action against
     */
    private static void runTiaTaskAction(final Test testTask) {
        Action<? super Task> tiaAction = testTask.getActions().get(0);
        tiaAction.execute(testTask);
    }

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
