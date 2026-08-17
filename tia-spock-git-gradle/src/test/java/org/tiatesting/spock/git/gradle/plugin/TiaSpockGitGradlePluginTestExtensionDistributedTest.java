package org.tiatesting.spock.git.gradle.plugin;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.distributed.DistributedRunConfig;
import org.tiatesting.core.distributed.DistributedRunnerAssignment;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
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
import org.tiatesting.gradle.plugin.TiaDistCompleteTask;

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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                1000L * groups.size(), 5000L, false);
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
     * Register a second {@link Test} task on a project a prior call to {@link
     * #testTaskWithTiaApplied} already built, and apply the Tia test extension to it too - the
     * shape of a build with two test tasks (e.g. {@code test} and {@code integrationTest}), both
     * sharing the one {@link org.gradle.api.invocation.Gradle} instance {@link
     * org.tiatesting.gradle.plugin.DistributedClaimRegistry#forBuild} keys its registry by.
     *
     * @param firstTestTask a test task {@link #testTaskWithTiaApplied} already built and applied
     *                      the extension to, whose project the new task is added to
     * @param taskName the name of the new test task
     * @return the new test task, with the Tia extension applied and not yet run
     */
    private static Test secondTestTaskWithTiaApplied(final Test firstTestTask, final String taskName) {
        Test testTask = firstTestTask.getProject().getTasks().create(taskName, Test.class);
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
     * finished" step (stage 9's task 2b) match no row and leave the group open forever. Also
     * verifies the plan's other group is untouched - still {@code PENDING}, with no runner key or
     * claim time - so this test does not merely catch a double claim incidentally were one to
     * happen: it asserts directly that a single test-task claim takes exactly the one group it
     * claimed and leaves the rest of the plan alone.
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
            List<DistributedRunGroup> groups = dataStore.readDistributedRunGroups("run-1");
            assertEquals(forwardedRunnerKey, groups.get(0).getRunnerKey());
            DistributedRunGroup untouchedGroup = groups.get(1);
            assertEquals(DistributedRunGroupStatus.PENDING, untouchedGroup.getStatus());
            assertNull(untouchedGroup.getRunnerKey());
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
     * Verify the shared-database precondition fires from the daemon's claim, not only from the
     * (deleted) fork-side re-check the old test-JVM claim used to make. No {@code tiaDBUrl} is
     * configured here, so the resolved datastore is embedded H2 - each runner would get its own
     * private copy and no runner would ever see another's group claims.
     *
     * @param projectDir a temporary directory to root the Gradle project and the database at
     */
    @org.junit.jupiter.api.Test
    void shouldFailWhenTheDatabaseIsEmbedded(@TempDir File projectDir) {
        // given a distributed build with no tiaDBUrl configured, so the datastore resolves embedded
        File dbDir = newDbDir(projectDir);
        persistPlan(dbDir, "run-embedded", PLAN_COMMIT, singleGroupAssignment());
        Test testTask = testTaskWithTiaApplied(projectDir, dbDir);
        TiaBaseTaskExtension extension = projectExtension(testTask);
        enableTia(extension, projectDir);
        extension.setDistributed(Boolean.TRUE);
        extension.setRunId("run-embedded");

        // when
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> runTiaTaskAction(testTask));

        // then
        assertTrue(thrown.getMessage().contains("shared database"), thrown.getMessage());
    }

    /**
     * Verify the local-changes precondition fires from the daemon's claim. A distributed run
     * requires every runner to diff the same committed baseline; {@code tiaCheckLocalChanges}
     * enabled would let this runner's uncommitted edits compute different line numbers than the
     * plan was built from.
     *
     * @param projectDir a temporary directory to root the Gradle project and the database at
     */
    @org.junit.jupiter.api.Test
    void shouldFailWhenCheckLocalChangesIsEnabled(@TempDir File projectDir) {
        // given a distributed build with tiaCheckLocalChanges enabled
        File dbDir = newDbDir(projectDir);
        persistPlan(dbDir, "run-local-changes", PLAN_COMMIT, singleGroupAssignment());
        Test testTask = testTaskWithTiaApplied(projectDir, dbDir);
        TiaBaseTaskExtension extension = projectExtension(testTask);
        enableTia(extension, projectDir);
        extension.setDbUrl(SHARED_DB_URL);
        extension.setDistributed(Boolean.TRUE);
        extension.setRunId("run-local-changes");
        extension.setCheckLocalChanges(Boolean.TRUE);

        // when
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> runTiaTaskAction(testTask));

        // then
        assertTrue(thrown.getMessage().contains("tiaCheckLocalChanges"), thrown.getMessage());
    }

    /**
     * Verify a distributed test task with {@code maxParallelForks > 1} is refused rather than left
     * to hang. The claim is made once, here in the daemon, and its group number and runner key are
     * forwarded to every worker JVM Gradle starts, while Gradle splits the group's suites across
     * those workers - so no single worker observes the whole group, the completion's
     * suites-observed guard is never satisfied, the group never completes and the run never seals,
     * on a build that still exits green.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void shouldFailWhenTheTestTaskRunsMoreThanOneForkInParallel(@TempDir File projectDir) {
        // given a distributed test task configured to run several forks at once
        Test testTask = testTaskWithTiaApplied(projectDir, null);
        TiaBaseTaskExtension extension = projectExtension(testTask);
        enableTia(extension, projectDir);
        extension.setDbUrl(SHARED_DB_URL);
        extension.setDistributed(Boolean.TRUE);
        extension.setRunId("run-parallel-forks");
        testTask.setMaxParallelForks(4);

        // when
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> runTiaTaskAction(testTask));

        // then the failure names the setting it found and the rule it breaks
        assertTrue(thrown.getMessage().contains("maxParallelForks = 4"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("one JVM per group"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("never seal"), thrown.getMessage());
    }

    /**
     * Verify a distributed test task with {@code forkEvery > 0} is refused for the same reason as
     * {@code maxParallelForks > 1}: it too gives the one claimed group more than one JVM, each
     * reporting its own partial observed set under the same runner key.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void shouldFailWhenTheTestTaskRestartsItsForkPeriodically(@TempDir File projectDir) {
        // given a distributed test task configured to start a fresh JVM every few classes
        Test testTask = testTaskWithTiaApplied(projectDir, null);
        TiaBaseTaskExtension extension = projectExtension(testTask);
        enableTia(extension, projectDir);
        extension.setDbUrl(SHARED_DB_URL);
        extension.setDistributed(Boolean.TRUE);
        extension.setRunId("run-fork-every");
        testTask.setForkEvery(10L);

        // when
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> runTiaTaskAction(testTask));

        // then the failure names the setting it found and the rule it breaks
        assertTrue(thrown.getMessage().contains("forkEvery = 10"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("one JVM per group"), thrown.getMessage());
    }

    /**
     * Verify the forking guard refuses only what it must: a distributed test task left on Gradle's
     * one-JVM defaults claims its group exactly as before. A guard that fired on the default
     * configuration would make distributed runs impossible rather than safe.
     *
     * @param projectDir a temporary directory to root the Gradle project and the database at
     */
    @org.junit.jupiter.api.Test
    void shouldClaimNormallyWhenTheTestTaskRunsASingleFork(@TempDir File projectDir) {
        // given a distributed test task explicitly on the one-JVM settings
        File dbDir = newDbDir(projectDir);
        persistPlan(dbDir, "run-single-fork", PLAN_COMMIT, singleGroupAssignment());
        Test testTask = testTaskWithTiaApplied(projectDir, dbDir);
        TiaBaseTaskExtension extension = projectExtension(testTask);
        enableTia(extension, projectDir);
        extension.setDbUrl(SHARED_DB_URL);
        extension.setDistributed(Boolean.TRUE);
        extension.setRunId("run-single-fork");
        testTask.setMaxParallelForks(1);
        testTask.setForkEvery(0L);

        // when
        runTiaTaskAction(testTask);

        // then the claim went through and the group number reached the fork
        assertEquals("0", testTask.getSystemProperties().get("tiaDistributedGroupNumber"));
        try (DataStore dataStore = openStore(dbDir, BRANCH)) {
            assertEquals(DistributedRunGroupStatus.CLAIMED,
                    dataStore.readDistributedRunGroups("run-single-fork").get(0).getStatus());
        }
    }

    /**
     * Verify a second test task claiming in the same build is refused loudly instead of the two
     * test tasks' claims silently colliding. The derived runner key is {@code runId + hostname +
     * pid}, and the pid is the daemon's, shared by every test task in this one JVM, so the second
     * task's underlying claim actually re-claims the first task's group via {@code
     * claimNextPendingGroup}'s "already holds a group" step rather than failing on its own - it is
     * {@link org.tiatesting.gradle.plugin.DistributedClaimRegistry#recordClaim} that must be what
     * catches this, by refusing to record a claim under a second test task path in the same build.
     *
     * @param projectDir a temporary directory to root the Gradle project and the database at
     */
    @org.junit.jupiter.api.Test
    void shouldFailWhenASecondTestTaskClaimsInTheSameBuild(@TempDir File projectDir) {
        // given a two-group plan and two distributed test tasks in the same project
        File dbDir = newDbDir(projectDir);
        persistPlan(dbDir, "run-two-tasks", PLAN_COMMIT, twoGroupAssignment());
        Test firstTestTask = testTaskWithTiaApplied(projectDir, dbDir);
        TiaBaseTaskExtension extension = projectExtension(firstTestTask);
        enableTia(extension, projectDir);
        extension.setDbUrl(SHARED_DB_URL);
        extension.setDistributed(Boolean.TRUE);
        extension.setRunId("run-two-tasks");
        Test secondTestTask = secondTestTaskWithTiaApplied(firstTestTask, "integrationTest");

        // when the first test task claims successfully
        runTiaTaskAction(firstTestTask);
        // and the second test task attempts to claim too
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> runTiaTaskAction(secondTestTask));

        // then the failure names both test tasks and the one-test-task-per-runner rule
        assertTrue(thrown.getMessage().contains(firstTestTask.getPath()), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(secondTestTask.getPath()), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("exactly one test task per runner"), thrown.getMessage());
    }

    /**
     * Force the project's queued {@code afterEvaluate} blocks to run, the way a real Gradle
     * invocation would once the build script finishes - including the {@code
     * tia-dist-complete}-wiring block {@link TiaSpockGitGradlePluginTestExtension#applyTo}
     * registers. {@link ProjectBuilder}-built projects never reach this point on their own, since
     * nothing in these tests runs a real build; the cast to {@link ProjectInternal} is what exposes
     * {@code evaluate()} - not part of the public {@link Project} API - to trigger it directly.
     *
     * @param testTask a test task whose project's queued {@code afterEvaluate} blocks should run
     */
    private static void evaluate(final Test testTask) {
        ((ProjectInternal) testTask.getProject()).evaluate();
    }

    /**
     * Verify that a distributed build - {@code tia.distributed = true} - gets a {@code
     * tia-dist-complete} task of type {@link TiaDistCompleteTask}, and that the test task is
     * finalized by it, once the project's {@code afterEvaluate} blocks run. Without the production
     * wiring, {@code tia-dist-complete} would not exist at all, and a runner whose tests failed
     * would never get the chance to complete its group - the run would never seal.
     *
     * @param projectDir a temporary directory to root the Gradle project and the database at
     */
    @org.junit.jupiter.api.Test
    void shouldRegisterDistCompleteTaskAndFinalizeTheTestTaskForADistributedBuild(@TempDir File projectDir) {
        // given a distributed build (no plan or claim needed - only the configuration-time wiring
        // is under test here)
        Test testTask = testTaskWithTiaApplied(projectDir, null);
        TiaBaseTaskExtension extension = projectExtension(testTask);
        enableTia(extension, projectDir);
        extension.setDbUrl(SHARED_DB_URL);
        extension.setDistributed(Boolean.TRUE);
        extension.setRunId("run-wiring");

        // when the project's afterEvaluate blocks run
        evaluate(testTask);

        // then the tia-dist-complete task exists and finalizes the test task
        Task completeTask = testTask.getProject().getTasks().findByName("tia-dist-complete");
        assertNotNull(completeTask, "tia-dist-complete should be registered for a distributed build");
        assertInstanceOf(TiaDistCompleteTask.class, completeTask);
        assertTrue(testTask.getFinalizedBy().getDependencies(testTask).contains(completeTask),
                "the test task should be finalized by tia-dist-complete");
    }

    /**
     * Verify that a second distributed test task in the same build fails at configuration time with
     * the one-test-task-per-runner explanation, not with Gradle's own duplicate-task-name error.
     * Both test tasks would register a finalizer under the same {@code tia-dist-complete} name, and
     * Gradle's message for that says nothing about why two distributed test tasks cannot work. The
     * registry's own refusal is not reachable here either: it fires at execution time, after this
     * configuration-time registration would already have failed.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void shouldFailWhenASecondTestTaskIsAlsoDistributed(@TempDir File projectDir) {
        // given two test tasks in one project, both covered by a distributed project extension
        Test firstTestTask = testTaskWithTiaApplied(projectDir, null);
        TiaBaseTaskExtension extension = projectExtension(firstTestTask);
        enableTia(extension, projectDir);
        extension.setDbUrl(SHARED_DB_URL);
        extension.setDistributed(Boolean.TRUE);
        extension.setRunId("run-two-finalizers");
        Test secondTestTask = secondTestTaskWithTiaApplied(firstTestTask, "integrationTest");

        // when the project's afterEvaluate blocks run, wiring a finalizer for each test task
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> evaluate(firstTestTask));

        // then the failure explains the rule rather than reporting a duplicate task name
        String message = rootCauseMessage(thrown);
        assertTrue(message.contains(secondTestTask.getPath()), message);
        assertTrue(message.contains("exactly one test task per runner"), message);
    }

    /**
     * Verify that a build with Tia switched off is inert, however much distributed configuration it
     * carries: two distributed test tasks configure cleanly, no {@code tia-dist-complete} task is
     * registered and no finalizer is wired. Disabled Tia must add nothing to a build and must
     * certainly not fail one at configuration time over a run it was never going to make - the same
     * rule the Maven side keeps by short-circuiting {@code AbstractTiaDistCompleteMojo.execute} on
     * {@code !isTiaEnabled()} as its very first statement.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void shouldWireNothingWhenTiaIsDisabledEvenWithTwoDistributedTestTasks(@TempDir File projectDir) {
        // given two distributed test tasks in a build that has Tia switched off
        Test firstTestTask = testTaskWithTiaApplied(projectDir, null);
        TiaBaseTaskExtension extension = projectExtension(firstTestTask);
        enableTia(extension, projectDir);
        extension.setEnabled(Boolean.FALSE);
        extension.setDbUrl(SHARED_DB_URL);
        extension.setDistributed(Boolean.TRUE);
        extension.setRunId("run-tia-disabled");
        Test secondTestTask = secondTestTaskWithTiaApplied(firstTestTask, "integrationTest");

        // when the project's afterEvaluate blocks run
        evaluate(firstTestTask);

        // then the build configured cleanly and gained nothing at all
        assertNull(firstTestTask.getProject().getTasks().findByName("tia-dist-complete"),
                "a disabled build must register no completion task");
        assertTrue(firstTestTask.getFinalizedBy().getDependencies(firstTestTask).isEmpty(),
                "a disabled build's test task must have no finalizer");
        assertTrue(secondTestTask.getFinalizedBy().getDependencies(secondTestTask).isEmpty(),
                "and neither must the second one");
    }

    /**
     * Unwrap the message of a failure's root cause. Gradle wraps a failure thrown from an {@code
     * afterEvaluate} block in a project-configuration exception, so the assertion has to look at
     * what was actually thrown rather than at the wrapper's own message.
     *
     * @param thrown the exception caught from the configuration-time call
     * @return the root cause's message, or the top-level message when there is no cause
     */
    private static String rootCauseMessage(final Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    /**
     * Verify that an ordinary, non-distributed build - {@code tia.distributed} left unset - gets
     * neither a {@code tia-dist-complete} task nor a finalizer on the test task, once the project's
     * {@code afterEvaluate} blocks run. A non-distributed Gradle build must gain no task and no
     * finalizer at all.
     *
     * @param projectDir a temporary directory to root the Gradle project at
     */
    @org.junit.jupiter.api.Test
    void shouldRegisterNeitherTaskNorFinalizerForANonDistributedBuild(@TempDir File projectDir) {
        // given an ordinary build with tia.distributed left unset
        Test testTask = testTaskWithTiaApplied(projectDir, null);
        enableTia(projectExtension(testTask), projectDir);

        // when the project's afterEvaluate blocks run
        evaluate(testTask);

        // then no tia-dist-complete task was registered, and the test task has no finalizer
        assertNull(testTask.getProject().getTasks().findByName("tia-dist-complete"));
        assertTrue(testTask.getFinalizedBy().getDependencies(testTask).isEmpty(),
                "a non-distributed build's test task should have no finalizer");
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
