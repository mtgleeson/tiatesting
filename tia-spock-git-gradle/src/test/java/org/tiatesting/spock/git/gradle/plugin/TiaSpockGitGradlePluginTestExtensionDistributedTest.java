package org.tiatesting.spock.git.gradle.plugin;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.distributed.DistributedRunConfig;
import org.tiatesting.core.distributed.DistributedRunnerAssignment;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.gradle.plugin.TiaBasePlugin;
import org.tiatesting.gradle.plugin.TiaBaseTaskExtension;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the Gradle-side half of stage 9's task 2a: the {@code doFirst} action that now claims this
 * test task's share of a distributed run itself, in the daemon, instead of forwarding unclaimed
 * configuration for the forked test JVM to claim with. Before this stage the claim happened inside
 * the fork ({@code TiaSpockTestRunInitializer#claimDistributedRunGroup}, now removed); a build with
 * {@code maxParallelForks > 1} could claim several groups for one test task, and no daemon-side
 * finalizer could ever know which group a task's JVM held. These tests drive the claim through a
 * real embedded-H2 {@link JdbcDataStore}, the same fixture style {@code
 * AbstractTiaAgentMojoDistributedTest} uses on the Maven side, so what is under test is the actual
 * database row the claim writes - not a mock that would only assert this class calls the methods it
 * already knows it calls.
 *
 * <p>Driven through a real {@link Test} task built by {@link ProjectBuilder}, running the task
 * action the extension registers, because the claim happens in a task action and would otherwise
 * only be exercised by a full build.
 */
class TiaSpockGitGradlePluginTestExtensionDistributedTest {

    private static final String BRANCH = "main";
    private static final String PLAN_COMMIT = "commit-1";
    private static final String SHARED_DB_URL = "jdbc:h2:tcp://localhost:9092/tiadb";

    /**
     * Minimal concrete {@link TiaBasePlugin} for these tests: a {@link VCSReader} stubbed to a
     * fixed branch and workspace commit, and a datastore construction overridden to point at a
     * temp directory instead of the (deliberately fake, {@code SHARED_DB_URL}) configured
     * connection settings - the same substitution {@code AbstractTiaAgentMojoDistributedTest} makes
     * on the Maven side, keeping the shared-database precondition string check real while the
     * actual reads and writes go to a real embedded database a unit test can run.
     */
    static class TestPlugin extends TiaBasePlugin {
        private File dbDir;
        private String workspaceCommit = PLAN_COMMIT;

        /**
         * @param dbDir the temp directory {@link #buildDataStore} opens an embedded H2 database
         *              under, in place of the extension's configured connection settings
         */
        void setDbDir(final File dbDir) {
            this.dbDir = dbDir;
        }

        /**
         * @param workspaceCommit the commit the stubbed VCS reader reports this workspace is on
         */
        void setWorkspaceCommit(final String workspaceCommit) {
            this.workspaceCommit = workspaceCommit;
        }

        /**
         * @return a stub VCS reader reporting this test's branch and the configured workspace
         *         commit
         */
        @Override
        public VCSReader getVCSReader() {
            return new StubVCSReader(workspaceCommit);
        }

        /**
         * Build the datastore over this test's temp directory rather than the configured {@code
         * tiaDBUrl}, which exists only to satisfy the shared-database precondition string check.
         *
         * @param branch the VCS branch name whose schema the store selects
         * @return an embedded datastore the caller owns and closes
         */
        @Override
        public DataStore buildDataStore(final String branch) {
            return openStore(dbDir, branch);
        }
    }

    /**
     * Open an embedded-H2 datastore over the given directory, on the per-branch schema the given
     * branch resolves to - the same construction {@link TestPlugin#buildDataStore} performs, so a
     * test that seeds a plan directly and the plugin under test read the same rows.
     *
     * @param dbDir the embedded database directory
     * @param branch the VCS branch name whose schema the store selects
     * @return an open datastore the caller must close
     */
    private static JdbcDataStore openStore(final File dbDir, final String branch) {
        return new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(dbDir.getAbsolutePath())),
                BranchSchema.schemaName(branch));
    }

    /**
     * Create a fresh embedded-H2 directory under the given temp directory and bootstrap its
     * schema, so each test starts from an isolated store with no run planned.
     *
     * @param tempDir the test's temp directory
     * @return the new, schema-bootstrapped database directory
     */
    private static File newDbDir(final File tempDir) {
        File dbDir = new File(tempDir, "db");
        dbDir.mkdirs();
        try (DataStore dataStore = openStore(dbDir, BRANCH)) {
            dataStore.getTiaData(true);
        }
        return dbDir;
    }

    /**
     * Persist a run plan with an exact, caller-chosen suite-to-group assignment, so a test asserts
     * against the grouping it wrote rather than one a balancer chose.
     *
     * @param dbDir the embedded database directory to persist into
     * @param runId the run identifier to plan under
     * @param commitValue the VCS commit the plan is pinned to, which the claim checks this
     *                    workspace's commit against
     * @param suitesByGroup the suite names each group number owns; group numbers must run from 0
     *                      upwards with no gaps, since the group rows are derived from this map
     */
    private static void persistPlan(final File dbDir, final String runId, final String commitValue,
                                     final Map<Integer, List<String>> suitesByGroup) {
        List<DistributedRunGroup> groups = new ArrayList<>();
        for (int groupNumber = 0; groupNumber < suitesByGroup.size(); groupNumber++) {
            groups.add(DistributedRunGroup.pending(runId, groupNumber, 1000L));
        }
        DistributedRun run = DistributedRun.open(runId, BRANCH, commitValue, groups.size(), null,
                1000L * groups.size(), 5000L);
        try (DataStore dataStore = openStore(dbDir, BRANCH)) {
            dataStore.persistDistributedRunPlan(new DistributedRunPlan(run, groups, suitesByGroup, null));
        }
    }

    /**
     * Build the suite-to-group assignment most of these tests plan with: group 0 owns
     * {@code ATest} and {@code BTest}, group 1 owns {@code CTest}.
     *
     * @return a two-group suite assignment
     */
    private static Map<Integer, List<String>> twoGroupAssignment() {
        Map<Integer, List<String>> suitesByGroup = new HashMap<>();
        suitesByGroup.put(0, Arrays.asList("com.example.ATest", "com.example.BTest"));
        suitesByGroup.put(1, Collections.singletonList("com.example.CTest"));
        return suitesByGroup;
    }

    /**
     * Build a single-group suite assignment, for tests where a second, surplus runner is meant to
     * find every group already claimed.
     *
     * @return a one-group suite assignment
     */
    private static Map<Integer, List<String>> singleGroupAssignment() {
        Map<Integer, List<String>> suitesByGroup = new HashMap<>();
        suitesByGroup.put(0, Arrays.asList("com.example.ATest", "com.example.BTest"));
        return suitesByGroup;
    }

    /**
     * Build a Gradle project with the plugins the Tia test extension expects - {@code java}, {@code
     * jacoco} and a {@link TestPlugin} pointed at the given embedded database directory - a
     * {@code test} task, and apply the Tia test extension to that task. The returned task has not
     * run its actions yet.
     *
     * @param projectDir the temporary directory to root the project at
     * @param dbDir the embedded database directory {@link TestPlugin#buildDataStore} opens, or
     *              null for a test that must never reach a claim (an ordinary, non-distributed
     *              build)
     * @return the {@code test} task with the Tia extension applied
     */
    private static Test testTaskWithTiaApplied(final File projectDir, final File dbDir) {
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply("java");
        project.getPlugins().apply("jacoco");
        TestPlugin plugin = (TestPlugin) project.getPlugins().apply(TestPlugin.class);
        plugin.setDbDir(dbDir);
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
     * Configure the minimum an enabled Tia build needs so the extension's action reaches the claim
     * rather than short-circuiting on a disabled build. Every path-shaped property is given a
     * value because the action forwards each one to the test JVM as it stands.
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
     * configuration, claims a distributed run's group if this build is one, and sets the system
     * properties the forked test JVM is started with.
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
     * Verify the daemon claims this test task's group and forwards the run id, the claimed group
     * number, and - critically - the runner key the claim actually recorded, never a re-derived
     * one: a forwarded key that did not match the claimed row would let a later "this group is
     * finished" step (stage 9's task 2b) match no row and leave the group open forever.
     *
     * @param projectDir a temporary directory to root the Gradle project and the database at
     */
    @org.junit.jupiter.api.Test
    void shouldClaimOnceAndForwardTheClaimedRunnerKeyAndGroupNumber(@TempDir File projectDir) {
        // given
        File dbDir = newDbDir(projectDir);
        persistPlan(dbDir, "run-1", PLAN_COMMIT, twoGroupAssignment());
        Test testTask = testTaskWithTiaApplied(projectDir, dbDir);
        TiaBaseTaskExtension extension = projectExtension(testTask);
        enableTia(extension, projectDir);
        extension.setDbUrl(SHARED_DB_URL);
        extension.setDistributed(Boolean.TRUE);
        extension.setRunId("run-1");

        // when
        runTiaTaskAction(testTask);

        // then
        Map<String, Object> systemProperties = testTask.getSystemProperties();
        assertEquals("true", systemProperties.get("tiaDistributed"));
        assertEquals("run-1", systemProperties.get("tiaRunId"));
        assertEquals("0", systemProperties.get("tiaDistributedGroupNumber"));
        Object forwardedRunnerKey = systemProperties.get("tiaDistributedRunnerKey");
        assertNotNull(forwardedRunnerKey);
        try (DataStore dataStore = openStore(dbDir, BRANCH)) {
            assertEquals(forwardedRunnerKey,
                    dataStore.readDistributedRunGroups("run-1").get(0).getRunnerKey());
        }
    }

    /**
     * Verify a surplus runner - one whose test task claims after every group in the plan is
     * already taken, which a pipeline whose fan-out is wider than the plan's group count
     * legitimately produces - forwards no group number at all rather than one it does not own. A
     * forwarded group number it did not hold would have a later completion step report progress
     * for another runner's work.
     *
     * @param projectDir a temporary directory to root the Gradle project and the database at
     */
    @org.junit.jupiter.api.Test
    void shouldForwardNoGroupNumberForASurplusRunner(@TempDir File projectDir) {
        // given a single-group plan already claimed by another runner
        File dbDir = newDbDir(projectDir);
        persistPlan(dbDir, "run-2", PLAN_COMMIT, singleGroupAssignment());
        try (DataStore dataStore = openStore(dbDir, BRANCH)) {
            DistributedRunConfig priorConfig = DistributedRunConfig.forRunner("run-2", "runner-a");
            DistributedRunnerAssignment.claim(dataStore, priorConfig, PLAN_COMMIT, 1000L);
        }
        Test testTask = testTaskWithTiaApplied(projectDir, dbDir);
        TiaBaseTaskExtension extension = projectExtension(testTask);
        enableTia(extension, projectDir);
        extension.setDbUrl(SHARED_DB_URL);
        extension.setDistributed(Boolean.TRUE);
        extension.setRunId("run-2");
        extension.setDistributedRunnerKey("runner-b");

        // when
        runTiaTaskAction(testTask);

        // then
        Map<String, Object> systemProperties = testTask.getSystemProperties();
        assertEquals("true", systemProperties.get("tiaDistributed"));
        assertEquals("run-2", systemProperties.get("tiaRunId"));
        assertEquals("runner-b", systemProperties.get("tiaDistributedRunnerKey"));
        assertFalse(systemProperties.containsKey("tiaDistributedGroupNumber"), systemProperties.toString());
    }

    /**
     * Verify an ordinary, non-distributed Gradle build's test JVM is started with none of the
     * distributed properties, and that the daemon never even attempts a claim: no database
     * directory is configured for {@link TestPlugin#buildDataStore} here, so if the action
     * incorrectly tried to claim it would fail to open a store and this test would fail with that
     * exception instead of passing.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void shouldForwardNoDistributedPropertiesAndClaimNothingForANonDistributedBuild(@TempDir File projectDir) {
        // given - no database directory configured, and no plan persisted anywhere
        Test testTask = testTaskWithTiaApplied(projectDir, null);
        enableTia(projectExtension(testTask), projectDir);

        // when
        runTiaTaskAction(testTask);

        // then
        Map<String, Object> systemProperties = testTask.getSystemProperties();
        assertFalse(systemProperties.containsKey("tiaDistributed"), systemProperties.toString());
        assertFalse(systemProperties.containsKey("tiaRunId"), systemProperties.toString());
        assertFalse(systemProperties.containsKey("tiaDistributedRunnerKey"), systemProperties.toString());
        assertFalse(systemProperties.containsKey("tiaDistributedGroupNumber"), systemProperties.toString());
        // the ordinary properties are still forwarded exactly as before
        assertEquals(Boolean.TRUE, systemProperties.get("tiaEnabled"));
        assertEquals(Boolean.FALSE, systemProperties.get("tiaCheckLocalChanges"));
    }

    /**
     * Verify a distributed run configured on one test task rather than on the project is what the
     * claim uses, and that a task-level setting wins over the project's - a build with more than
     * one test task may want only one of them distributed. Also proves the claim reads the
     * task-level (already-merged) extension rather than the plugin's own project-level extension:
     * only the {@code "task-run"} id has a plan persisted under it, so a claim that read
     * {@code "project-run"} instead would fail this test with an unplanned-run exception.
     *
     * @param projectDir a temporary directory to root the Gradle project and the database at
     */
    @org.junit.jupiter.api.Test
    void shouldPreferTheTestTasksOwnDistributedRunOverTheProjects(@TempDir File projectDir) {
        // given
        File dbDir = newDbDir(projectDir);
        persistPlan(dbDir, "task-run", PLAN_COMMIT, twoGroupAssignment());
        Test testTask = testTaskWithTiaApplied(projectDir, dbDir);
        TiaBaseTaskExtension extension = projectExtension(testTask);
        enableTia(extension, projectDir);
        extension.setDbUrl(SHARED_DB_URL);
        extension.setDistributed(Boolean.TRUE);
        extension.setRunId("project-run");
        TiaBaseTaskExtension taskExtension = testTask.getExtensions().getByType(TiaBaseTaskExtension.class);
        taskExtension.setRunId("task-run");

        // when
        runTiaTaskAction(testTask);

        // then
        assertEquals("task-run", testTask.getSystemProperties().get("tiaRunId"));
    }

    /**
     * Verify the multi-project reactor rule fires at claim time, in the daemon, with this build's
     * real project count - the rule the old test-JVM claim could only ever pass a literal 1 for,
     * since it had no {@code Project} reference to count with at all. Applying Tia to one module of
     * a multi-project build must still be rejected: each module's claim would take its own group
     * from the same plan, and no runner would run the suites assigned to the group a rejected
     * module never claimed.
     *
     * @param rootDir a temporary directory to root the multi-project build at
     */
    @org.junit.jupiter.api.Test
    void shouldFailWhenTheReactorHasMoreThanOneProject(@TempDir File rootDir) {
        // given a two-module build with Tia applied to only one module
        Project root = ProjectBuilder.builder().withProjectDir(rootDir).build();
        Project moduleA = ProjectBuilder.builder().withParent(root).withName("module-a").build();
        ProjectBuilder.builder().withParent(root).withName("module-b").build();
        moduleA.getPlugins().apply("java");
        moduleA.getPlugins().apply("jacoco");
        TestPlugin plugin = (TestPlugin) moduleA.getPlugins().apply(TestPlugin.class);
        plugin.setDbDir(new File(rootDir, "db"));
        TiaBaseTaskExtension extension = moduleA.getExtensions().getByType(TiaBaseTaskExtension.class);
        enableTia(extension, moduleA.getProjectDir());
        extension.setDbUrl(SHARED_DB_URL);
        extension.setDistributed(Boolean.TRUE);
        extension.setRunId("run-reactor");
        Test testTask = (Test) moduleA.getTasks().getByName("test");
        new TiaSpockGitGradlePluginTestExtension().applyTo(testTask);

        // when
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> runTiaTaskAction(testTask));

        // then
        assertTrue(thrown.getMessage().contains("3 projects"), thrown.getMessage());
    }

    /**
     * Minimal VCS reader reporting a fixed branch and commit. A distributed daemon-side claim reads
     * only those two values; the diff methods are never reached, since claiming is what replaces
     * running the selection for a distributed build.
     */
    private static final class StubVCSReader implements VCSReader {

        private final String headCommit;

        /**
         * @param headCommit the commit this workspace is reported to be on
         */
        private StubVCSReader(final String headCommit) {
            this.headCommit = headCommit;
        }

        /**
         * @return the fixed branch these tests plan and claim against
         */
        @Override
        public String getBranchName() {
            return BRANCH;
        }

        /**
         * @return the workspace commit this reader was built with
         */
        @Override
        public String getHeadCommit() {
            return headCommit;
        }

        /**
         * Never called on the daemon-side claim path, which claims a planned group instead of
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
            throw new UnsupportedOperationException("a distributed claim must not diff");
        }

        /**
         * Never called on the daemon-side claim path.
         *
         * @param diffs ignored
         * @param baseChangeNum ignored
         * @param checkLocalChanges ignored
         */
        @Override
        public void loadContentForDiffs(final Collection<SourceFileDiffContext> diffs,
                                         final String baseChangeNum, final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("a distributed claim must not diff");
        }

        /**
         * Never called on the daemon-side claim path.
         *
         * @param baseChangeNum ignored
         * @param checkLocalChanges ignored
         * @return never returns
         */
        @Override
        public Set<String> getChangedFilePaths(final String baseChangeNum, final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("a distributed claim must not diff");
        }

        /**
         * No resources to release.
         */
        @Override
        public void close() {
        }
    }
}
