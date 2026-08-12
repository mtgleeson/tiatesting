package org.tiatesting.spock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.distributed.DistributedRunConfig;
import org.tiatesting.core.distributed.DistributedRunnerAssignment;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the distributed branch of {@link TiaSpockTestRunInitializer}: the Gradle/Spock runner's
 * entry point into a distributed test run. Unlike Maven, which claims in the build JVM before
 * surefire forks, Gradle selects inside the test JVM, so the claim happens here - but the decision
 * that follows from it must be the same one the Maven runner makes, which is why both call
 * {@link DistributedRunnerAssignment}.
 *
 * <p>Driven against a real embedded-H2 {@link JdbcDataStore} with a stubbed VCS reader, following
 * the fixture {@code DistributedRunnerAssignmentTest} uses: what these tests are checking is that
 * the runner reads its share out of the shared plan tables using this workspace's commit, and a
 * fake store would only assert that this class calls the methods the test already knows it calls.
 */
class TiaSpockTestRunInitializerDistributedTest {

    private static final String BRANCH = "main";
    private static final String PLAN_COMMIT = "commit-1";

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database in a new temp directory and bootstrap its schema, so
     * each test starts from an isolated store with no run planned and no suite tracked, and cannot
     * see another test's claims.
     *
     * @throws Exception if the temp directory cannot be created or schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-spock-distributed-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName(BRANCH));
        dataStore.getTiaData(true);
    }

    /**
     * Close the data store so its embedded H2 database releases its file lock.
     */
    @AfterEach
    void tearDown() {
        if (dataStore != null) {
            dataStore.close();
        }
    }

    /**
     * Persist a run plan with an exact, caller-chosen suite-to-group assignment, so a test asserts
     * against the grouping it wrote rather than one the balancer chose.
     *
     * @param runId the run identifier to plan under
     * @param commitValue the VCS commit the plan is pinned to, which a runner's workspace commit
     *                    is later checked against
     * @param suitesByGroup the suite names each group number owns; group numbers must run from 0
     *                      upwards with no gaps, since the group rows are derived from this map
     */
    private void persistPlan(final String runId, final String commitValue,
                              final Map<Integer, List<String>> suitesByGroup) {
        List<DistributedRunGroup> groups = new ArrayList<>();
        for (int groupNumber = 0; groupNumber < suitesByGroup.size(); groupNumber++) {
            groups.add(DistributedRunGroup.pending(runId, groupNumber, 1000L));
        }
        DistributedRun run = DistributedRun.open(runId, BRANCH, commitValue, groups.size(), null,
                1000L * groups.size(), 5000L);
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(run, groups, suitesByGroup, null));
    }

    /**
     * Record the given suite names as tracked by Tia, so a claimed runner's ignore list has a
     * tracked set to subtract its own group from.
     *
     * @param suiteNames the suite names to persist as tracked
     */
    private void persistTracked(final String... suiteNames) {
        Map<String, TestSuiteTracker> trackers = new LinkedHashMap<>();
        for (String suiteName : suiteNames) {
            trackers.put(suiteName, new TestSuiteTracker(suiteName));
        }
        dataStore.persistTestSuites(trackers);
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
     * Build the initializer under test over this test's datastore, with a VCS reader reporting the
     * given workspace commit.
     *
     * @param workspaceCommit the commit this runner's workspace is reported to be on
     * @return an initializer ready to claim
     */
    private TiaSpockTestRunInitializer initializer(final String workspaceCommit) {
        return new TiaSpockTestRunInitializer(new StubVCSReader(workspaceCommit), dataStore);
    }

    /**
     * Verify a runner that claims a group runs exactly that group's suites and skips every other
     * tracked or planned suite. Between the runners of one plan this complement is what makes each
     * suite run exactly once; the two sets go straight to the Spock extension's skip decision.
     */
    @Test
    void shouldClaimAGroupAndResolveItsSuitesAndTheComplement() {
        // given
        persistPlan("run-1", PLAN_COMMIT, twoGroupAssignment());
        persistTracked("com.example.ATest", "com.example.BTest", "com.example.CTest",
                "com.example.DTest");

        // when
        DistributedRunnerAssignment assignment = initializer(PLAN_COMMIT)
                .claimDistributedRunGroup(DistributedRunConfig.forRunner("run-1", "runner-a"));

        // then
        assertTrue(assignment.isClaimed());
        assertEquals(Integer.valueOf(0), assignment.getGroupNumber());
        assertEquals(new HashSet<>(Arrays.asList("com.example.ATest", "com.example.BTest")),
                assignment.getTestsToRun());
        assertEquals(new HashSet<>(Arrays.asList("com.example.CTest", "com.example.DTest")),
                assignment.getTestsToIgnore());
    }

    /**
     * Verify a surplus runner - one that starts after every group has been claimed, which a
     * pipeline whose fan-out is wider than the plan's group count legitimately produces - ignores
     * every suite and selects none, so it runs nothing rather than duplicating work another runner
     * already owns.
     */
    @Test
    void shouldIgnoreEverySuiteWhenEveryGroupIsAlreadyClaimed() {
        // given
        persistPlan("run-2", PLAN_COMMIT, twoGroupAssignment());
        persistTracked("com.example.ATest", "com.example.BTest", "com.example.CTest");
        initializer(PLAN_COMMIT).claimDistributedRunGroup(DistributedRunConfig.forRunner("run-2", "runner-a"));
        initializer(PLAN_COMMIT).claimDistributedRunGroup(DistributedRunConfig.forRunner("run-2", "runner-b"));

        // when
        DistributedRunnerAssignment surplus = initializer(PLAN_COMMIT)
                .claimDistributedRunGroup(DistributedRunConfig.forRunner("run-2", "runner-c"));

        // then
        assertFalse(surplus.isClaimed());
        assertTrue(surplus.getTestsToRun().isEmpty(), surplus.getTestsToRun().toString());
        assertEquals(new HashSet<>(Arrays.asList("com.example.ATest", "com.example.BTest",
                "com.example.CTest")), surplus.getTestsToIgnore());
    }

    /**
     * Verify the claim is recorded against the runner key the assignment reports, so the same test
     * JVM can complete its group later under the identity the row actually holds. A key derived
     * for the claim is not one the caller could reconstruct afterwards.
     */
    @Test
    void shouldRecordTheClaimUnderTheRunnerKeyItReports() {
        // given
        persistPlan("run-3", PLAN_COMMIT, twoGroupAssignment());

        // when
        DistributedRunnerAssignment assignment = initializer(PLAN_COMMIT)
                .claimDistributedRunGroup(DistributedRunConfig.forRunner("run-3", null));

        // then
        assertNotNull(assignment.getRunnerKey());
        assertEquals(assignment.getRunnerKey(),
                dataStore.readDistributedRunGroups("run-3").get(0).getRunnerKey());
    }

    /**
     * Verify the claim is made against this workspace's head commit, by checking that a workspace
     * on a different commit than the plan was built from fails naming both. The plan's suite lists
     * were chosen by diffing the planned commit, so this workspace would otherwise run a selection
     * made for different code and report green for it.
     */
    @Test
    void shouldFailWhenTheWorkspaceCommitDiffersFromThePlan() {
        // given
        persistPlan("run-4", PLAN_COMMIT, twoGroupAssignment());
        TiaSpockTestRunInitializer initializer = initializer("a-different-commit");
        DistributedRunConfig config = DistributedRunConfig.forRunner("run-4", "runner-a");

        // when
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> initializer.claimDistributedRunGroup(config));

        // then
        assertTrue(thrown.getMessage().contains(PLAN_COMMIT), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("a-different-commit"), thrown.getMessage());
    }

    /**
     * Verify a runner whose run id has no plan fails rather than resolving an empty ignore list.
     * This is a straggler from a superseded build, and an empty ignore list would have it run every
     * tracked suite and report a green build for a plan that no longer exists.
     */
    @Test
    void shouldFailWhenNoRunIsPlannedUnderTheRunId() {
        // given
        TiaSpockTestRunInitializer initializer = initializer(PLAN_COMMIT);
        DistributedRunConfig config = DistributedRunConfig.forRunner("run-missing", "runner-a");

        // when
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> initializer.claimDistributedRunGroup(config));

        // then
        assertTrue(thrown.getMessage().contains("run-missing"), thrown.getMessage());
    }

    /**
     * Minimal VCS reader reporting a fixed branch and commit. A distributed runner reads only
     * those two values: claiming a planned group is what replaces running the diff, so the diff
     * methods throw rather than return an empty answer that would hide a mistaken call.
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
         * Never called on the distributed runner path, which claims a planned group instead of
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
            throw new UnsupportedOperationException("a distributed runner must not diff");
        }

        /**
         * Never called on the distributed runner path.
         *
         * @param diffs ignored
         * @param baseChangeNum ignored
         * @param checkLocalChanges ignored
         */
        @Override
        public void loadContentForDiffs(final Collection<SourceFileDiffContext> diffs,
                                         final String baseChangeNum, final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("a distributed runner must not diff");
        }

        /**
         * Never called on the distributed runner path.
         *
         * @param baseChangeNum ignored
         * @param checkLocalChanges ignored
         * @return never returns
         */
        @Override
        public Set<String> getChangedFilePaths(final String baseChangeNum, final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("a distributed runner must not diff");
        }

        /**
         * No resources to release.
         */
        @Override
        public void close() {
        }
    }
}
