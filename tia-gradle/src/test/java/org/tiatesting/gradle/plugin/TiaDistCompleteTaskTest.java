package org.tiatesting.gradle.plugin;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.distributed.DistributedRunConfig;
import org.tiatesting.core.distributed.DistributedRunnerAssignment;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TiaDistCompleteTask}: the daemon-side finalizer that completes a runner's
 * claimed group and, when elected, seals a distributed run. Covers stage 9's task 2b - the Gradle
 * half of the completion step already covered on the Maven side by {@code
 * AbstractTiaDistCompleteMojo}.
 *
 * <p>Driven directly against a real embedded-H2 {@link JdbcDataStore}, the same fixture style
 * {@link org.tiatesting.spock.git.gradle.plugin.TiaSpockGitGradlePluginTestExtensionDistributedTest}
 * uses on the claim side of this same feature: the claim registry is seeded exactly as the daemon's
 * claim action would leave it, so what is under test is the actual database rows the task's action
 * reads and writes, not a mock of the classes it is already known to call.
 */
class TiaDistCompleteTaskTest {

    private static final String BRANCH = "main";
    private static final String PLAN_COMMIT = "commit-1";
    private static final String TEST_TASK_PATH = ":test";

    /**
     * Minimal concrete {@link TiaBasePlugin}: a stub VCS reader fixed to {@link #BRANCH}, and a
     * datastore construction pointed at a temp directory rather than any configured connection
     * settings, or - for the datastore-failure test - one that always throws.
     */
    static class TestPlugin extends TiaBasePlugin {
        private File dbDir;
        private boolean throwOnBuildDataStore;

        void setDbDir(final File dbDir) {
            this.dbDir = dbDir;
        }

        void setThrowOnBuildDataStore(final boolean throwOnBuildDataStore) {
            this.throwOnBuildDataStore = throwOnBuildDataStore;
        }

        @Override
        public VCSReader getVCSReader() {
            return new StubVCSReader();
        }

        @Override
        public DataStore buildDataStore(final String branch) {
            if (throwOnBuildDataStore) {
                throw new RuntimeException("simulated datastore failure");
            }
            return openStore(dbDir, branch);
        }
    }

    private static JdbcDataStore openStore(final File dbDir, final String branch) {
        return new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(dbDir.getAbsolutePath())),
                BranchSchema.schemaName(branch));
    }

    private static File newDbDir(final File tempDir) {
        File dbDir = new File(tempDir, "db");
        dbDir.mkdirs();
        try (DataStore dataStore = openStore(dbDir, BRANCH)) {
            dataStore.getTiaData(true);
        }
        return dbDir;
    }

    /**
     * Persist a run plan whose one or more groups own no suites at all, so {@code completeGroup}'s
     * completeness guard - {@code suitesObserved >= assignedSuiteCount} - is satisfied trivially by
     * every group's default {@code suitesObserved = 0}, with no progress report needed first. That
     * keeps these tests focused on the claim/complete/seal wiring rather than on suite bookkeeping.
     *
     * @param dbDir the embedded database directory to persist into
     * @param runId the run identifier to plan under
     * @param groupCount how many empty groups to plan
     */
    private static void persistEmptyPlan(final File dbDir, final String runId, final int groupCount) {
        List<DistributedRunGroup> groups = new ArrayList<>();
        Map<Integer, List<String>> suitesByGroup = new HashMap<>();
        for (int groupNumber = 0; groupNumber < groupCount; groupNumber++) {
            groups.add(DistributedRunGroup.pending(runId, groupNumber, 1000L));
            suitesByGroup.put(groupNumber, Collections.<String>emptyList());
        }
        DistributedRun run = DistributedRun.open(runId, BRANCH, PLAN_COMMIT, groupCount, null,
                1000L * groupCount, 5000L);
        try (DataStore dataStore = openStore(dbDir, BRANCH)) {
            dataStore.persistDistributedRunPlan(new DistributedRunPlan(run, groups, suitesByGroup, null));
        }
    }

    /**
     * Claim a group from an already-planned run the way both build tools do, through the shared
     * coordinator, and return the runner key and group number the claim actually resolved - not
     * necessarily the ones the caller asked for, since {@link DistributedRunConfig#forRunner} lets
     * the coordinator derive a runner key itself when none is supplied.
     *
     * @param dbDir the embedded database directory holding the plan
     * @param runId the run id to claim from
     * @param runnerKey the runner identity to claim under
     * @return the resulting assignment
     */
    private static DistributedRunnerAssignment claim(final File dbDir, final String runId,
                                                      final String runnerKey) {
        try (DataStore dataStore = openStore(dbDir, BRANCH)) {
            DistributedRunnerAssignment assignment = DistributedRunnerAssignment.claim(dataStore,
                    DistributedRunConfig.forRunner(runId, runnerKey), PLAN_COMMIT, 1000L);
            assertTrue(assignment.isClaimed(), "the plan must have a group left for " + runnerKey);
            return assignment;
        }
    }

    /**
     * A Gradle project with a {@link TestPlugin} applied, held together with the project itself so
     * a test can both configure the plugin and reach {@link DistributedClaimRegistry#forBuild},
     * which is keyed by {@link org.gradle.api.invocation.Gradle} rather than reachable from the
     * plugin instance.
     */
    private static final class Fixture {
        final Project project;
        final TestPlugin plugin;
        private TaskProvider<TiaDistCompleteTask> completeTask;

        private Fixture(final Project project, final TestPlugin plugin) {
            this.project = project;
            this.plugin = plugin;
        }

        /**
         * Seed this build's {@link DistributedClaimRegistry} exactly as the daemon's claim action
         * would leave it for {@link #TEST_TASK_PATH}.
         *
         * @param runId the distributed run id the claim was made against
         * @param runnerKey the runner identity the claim was recorded under
         * @param groupNumber the claimed group, or null for a surplus runner
         */
        void recordClaim(final String runId, final String runnerKey, final Integer groupNumber) {
            DistributedClaimRegistry.forBuild(project.getGradle()).recordClaim(TEST_TASK_PATH, runId,
                    runnerKey, groupNumber, true, true, true);
        }

        /**
         * Run a {@link TiaDistCompleteTask} registered against this fixture's plugin for {@link
         * #TEST_TASK_PATH}, registering it once and re-running the same task instance on a second
         * call - mirroring a second finalizer invocation against an already-registered task rather
         * than re-registering under the same name, which Gradle rejects.
         */
        void runCompleteTask() {
            if (completeTask == null) {
                completeTask = plugin.createDistCompleteTask(TEST_TASK_PATH);
            }
            completeTask.get().run();
        }
    }

    /**
     * Build a Gradle project with a {@link TestPlugin} applied, pointed at the given embedded
     * database directory.
     *
     * @param projectDir the temp project directory
     * @param dbDir the embedded database directory, or null for the datastore-failure test's own
     *              throwing plugin
     * @return the project and plugin, held together
     */
    private static Fixture projectWithPlugin(final File projectDir, final File dbDir) {
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        TestPlugin plugin = (TestPlugin) project.getPlugins().apply(TestPlugin.class);
        plugin.setDbDir(dbDir);
        return new Fixture(project, plugin);
    }

    /**
     * Verify that completing the one and only group of a run seals it: the group row flips to
     * {@code COMPLETED}, and the stored commit - one of the seal's effects - advances to the plan's
     * commit. Without the production change, this task does not exist at all and there is nothing
     * to complete the group or elect this runner as sealer, so the group would stay {@code CLAIMED}
     * and the stored commit would stay unset.
     *
     * @param projectDir a temp directory to root the Gradle project and database at
     */
    @Test
    void completesTheClaimedGroupAndSealsWhenLastRunner(@TempDir File projectDir) {
        // given a single-group plan claimed by this runner
        File dbDir = newDbDir(projectDir);
        persistEmptyPlan(dbDir, "run-1", 1);
        DistributedRunnerAssignment assignment = claim(dbDir, "run-1", "runner-a");
        Fixture fixture = projectWithPlugin(projectDir, dbDir);
        fixture.recordClaim("run-1", assignment.getRunnerKey(), assignment.getGroupNumber());

        // when
        fixture.runCompleteTask();

        // then the group is completed and the run's effects (the stored commit) landed
        try (DataStore dataStore = openStore(dbDir, BRANCH)) {
            List<DistributedRunGroup> groups = dataStore.readDistributedRunGroups("run-1");
            assertEquals(DistributedRunGroupStatus.COMPLETED, groups.get(0).getStatus());
            TiaData core = dataStore.getTiaCore();
            assertEquals(PLAN_COMMIT, core.getCommitValue());
        }
    }

    /**
     * Verify the task completes and seals using the runner key the claim actually recorded, even
     * when the Tia extension was configured with a different one - proving the task reads {@code
     * claim.getRunnerKey()} rather than re-deriving or reading a configured value. A task that used
     * the wrong key would match no row and leave the group open forever.
     *
     * @param projectDir a temp directory to root the Gradle project and database at
     */
    @Test
    void usesTheRunnerKeyFromTheClaimRecordNotAConfiguredOne(@TempDir File projectDir) {
        // given a group claimed under "actual-runner-key", but the registry (as the daemon would
        // populate it) recorded under that same key - a differently-configured value never reaches
        // the registry at all, since the daemon always records the key the claim actually resolved
        File dbDir = newDbDir(projectDir);
        persistEmptyPlan(dbDir, "run-2", 1);
        DistributedRunnerAssignment assignment = claim(dbDir, "run-2", "actual-runner-key");
        Fixture fixture = projectWithPlugin(projectDir, dbDir);
        fixture.recordClaim("run-2", assignment.getRunnerKey(), assignment.getGroupNumber());

        // when
        fixture.runCompleteTask();

        // then the group claimed under the actual runner key was completed
        try (DataStore dataStore = openStore(dbDir, BRANCH)) {
            List<DistributedRunGroup> groups = dataStore.readDistributedRunGroups("run-2");
            assertEquals(DistributedRunGroupStatus.COMPLETED, groups.get(0).getStatus());
            assertEquals("actual-runner-key", groups.get(0).getRunnerKey());
        }
    }

    /**
     * Verify a surplus runner's claim (a claim recorded with a null group number) is a no-op: no
     * exception, and nothing in the database changes. Without the production change's null-group
     * check, the task would try to build a claimed context from a null group number and blow up.
     *
     * @param projectDir a temp directory to root the Gradle project and database at
     */
    @Test
    void surplusRunnerClaimIsANoOp(@TempDir File projectDir) {
        // given a claim recorded with no group number, as a surplus runner's claim would be
        File dbDir = newDbDir(projectDir);
        Fixture fixture = projectWithPlugin(projectDir, dbDir);
        fixture.recordClaim("run-3", "runner-b", null);

        // when / then - no exception
        fixture.runCompleteTask();
    }

    /**
     * Verify that with no claim recorded for the test task at all, the task is a no-op. Without the
     * production change's null-claim check, the task would throw a {@link NullPointerException}
     * dereferencing the claim.
     *
     * @param projectDir a temp directory to root the Gradle project and database at
     */
    @Test
    void noClaimRecordedIsANoOp(@TempDir File projectDir) {
        // given no claim recorded in this build's registry at all
        File dbDir = newDbDir(projectDir);
        Fixture fixture = projectWithPlugin(projectDir, dbDir);

        // when / then - no exception
        fixture.runCompleteTask();
    }

    /**
     * Verify that completing an already-completed group (the second of two retries' worth of
     * finalizer runs, say) succeeds without attempting a seal: {@code completeGroup} returns null,
     * and this task must treat that as a normal outcome, not a failure. Runs the task twice against
     * the same claim to produce the "already completed" case for the second call.
     *
     * @param projectDir a temp directory to root the Gradle project and database at
     */
    @Test
    void alreadyCompletedGroupSucceedsWithoutSealing(@TempDir File projectDir) {
        // given a single-group plan claimed and completed once already
        File dbDir = newDbDir(projectDir);
        persistEmptyPlan(dbDir, "run-4", 1);
        DistributedRunnerAssignment assignment = claim(dbDir, "run-4", "runner-c");
        Fixture fixture = projectWithPlugin(projectDir, dbDir);
        fixture.recordClaim("run-4", assignment.getRunnerKey(), assignment.getGroupNumber());
        fixture.runCompleteTask();

        // when completing again (a second finalizer run against the same, already-recorded claim)
        fixture.runCompleteTask();

        // then no exception was thrown and the group is still simply COMPLETED
        try (DataStore dataStore = openStore(dbDir, BRANCH)) {
            List<DistributedRunGroup> groups = dataStore.readDistributedRunGroups("run-4");
            assertEquals(DistributedRunGroupStatus.COMPLETED, groups.get(0).getStatus());
        }
    }

    /**
     * Verify that a datastore failure fails the build with a {@link GradleException} whose message
     * says the run was NOT sealed - the failure the user must be told about, since a silently
     * unsealed build would drop test coverage with nothing surfacing why.
     *
     * @param projectDir a temp directory to root the Gradle project at
     */
    @Test
    void datastoreFailureFailsTheBuildNamingThatTheRunWasNotSealed(@TempDir File projectDir) {
        // given a claim recorded, but a plugin whose datastore always throws
        Fixture fixture = projectWithPlugin(projectDir, null);
        fixture.plugin.setThrowOnBuildDataStore(true);
        fixture.recordClaim("run-5", "runner-d", Integer.valueOf(0));

        // when / then
        GradleException e = assertThrows(GradleException.class, fixture::runCompleteTask);
        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().contains("NOT be sealed"), e.getMessage());
        assertTrue(e.getMessage().contains("run-5"), e.getMessage());
    }

    /**
     * Stub VCS reader reporting a fixed branch; the head commit and diff methods are never reached
     * on the completion path, which never diffs.
     */
    private static final class StubVCSReader implements VCSReader {

        @Override
        public String getBranchName() {
            return BRANCH;
        }

        @Override
        public String getHeadCommit() {
            return PLAN_COMMIT;
        }

        @Override
        public Set<SourceFileDiffContext> getDiffFiles(final String baseChangeNum,
                                                        final List<String> sourceFilesDirs,
                                                        final List<String> testFilesDirs,
                                                        final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("a distributed completion must not diff");
        }

        @Override
        public void loadContentForDiffs(final Collection<SourceFileDiffContext> diffs,
                                         final String baseChangeNum, final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("a distributed completion must not diff");
        }

        @Override
        public Set<String> getChangedFilePaths(final String baseChangeNum, final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("a distributed completion must not diff");
        }

        @Override
        public void close() {
        }
    }
}
