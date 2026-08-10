package org.tiatesting.core.distributed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover {@link DistributedRunCoordinator}: the runner-side half of a distributed run, which claims
 * one group of an already-planned run and works out which suites that runner must therefore skip.
 * Uses a real embedded-H2 {@link JdbcDataStore} rather than a fake, following
 * {@link DistributedRunPlannerTest}'s fixture, because the behaviour under test is entirely about
 * what the coordinator reads back from and writes to the shared plan tables - a fake would only
 * assert that the coordinator called the methods this test already knows it calls.
 *
 * <p>Plans are persisted directly through {@link JdbcDataStore#persistDistributedRunPlan} rather
 * than through {@link DistributedRunPlanner}, so each test pins exactly which suite lands in which
 * group. Routing through the planner would make the assignment a function of the balancer's
 * weighting, and a balancing change would then break tests that are not about balancing.
 */
class DistributedRunCoordinatorTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database in a new temp directory and bootstrap its schema, so
     * each test starts from an isolated store with no distributed runs planned and cannot see
     * another test's claims.
     *
     * @throws Exception if the temp directory cannot be created or schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-distributed-coordinator-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test"));
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
     * @param commitValue the VCS commit the plan is pinned to; the value a runner's workspace
     *                    commit is later checked against
     * @param suitesByGroup the suite names each group number owns; group numbers must run from 0
     *                      upwards with no gaps, since the group rows are derived from this map
     */
    private void persistPlan(String runId, String commitValue, Map<Integer, List<String>> suitesByGroup) {
        List<DistributedRunGroup> groups = new ArrayList<>();
        for (int groupNumber = 0; groupNumber < suitesByGroup.size(); groupNumber++) {
            groups.add(DistributedRunGroup.pending(runId, groupNumber, 1000L));
        }
        DistributedRun run = DistributedRun.open(runId, "main", commitValue, groups.size(), null,
                1000L * groups.size(), 5000L);
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(run, groups, suitesByGroup));
    }

    /**
     * Build the suite-to-group assignment used by most of the derivation tests: group 0 owns
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
     * Build a coordinator bound to this test's datastore and a run configuration carrying the
     * given run id and runner key.
     *
     * @param runId the distributed run the coordinator works against
     * @param runnerKey the runner identity to claim with, or null to exercise the derived fallback
     * @return a coordinator ready to claim
     */
    private DistributedRunCoordinator coordinator(String runId, String runnerKey) {
        return new DistributedRunCoordinator(dataStore,
                DistributedRunConfig.validated(runId, 2, null, null, runnerKey));
    }

    /**
     * Verify a claim against a freshly planned run takes the lowest-numbered group and writes the
     * configured runner key and claim time onto the stored row - asserted from the datastore, not
     * only from the returned outcome, since a runner that crashes after claiming is identified by
     * what landed in the table.
     */
    @Test
    void shouldClaimTheLowestPendingGroupAndRecordTheRunnerKey() {
        // given
        persistPlan("run-claim", "commit-1", twoGroupAssignment());
        DistributedRunCoordinator coordinator = coordinator("run-claim", "runner-a");

        // when
        ClaimOutcome outcome = coordinator.claim("commit-1", 4242L);

        // then
        assertTrue(outcome.isClaimed());
        assertEquals(0, outcome.getGroup().getGroupNumber());
        assertEquals("runner-a", outcome.getRunnerKey());

        List<DistributedRunGroup> groups = dataStore.readDistributedRunGroups("run-claim");
        assertEquals(DistributedRunGroupStatus.CLAIMED, groups.get(0).getStatus());
        assertEquals("runner-a", groups.get(0).getRunnerKey());
        assertEquals(Long.valueOf(4242L), groups.get(0).getClaimedAtMs());
        assertEquals(DistributedRunGroupStatus.PENDING, groups.get(1).getStatus());
    }

    /**
     * Verify a surplus runner - one that started outside the plan's fan-out, so every group is
     * already claimed by the time it arrives - gets a no-op outcome rather than an exception. This
     * is the legitimate half of the pair the coordinator must keep apart: nothing is wrong, there
     * is simply no work for this runner.
     */
    @Test
    void shouldReturnANoOpOutcomeWhenEveryGroupIsAlreadyClaimed() {
        // given
        Map<Integer, List<String>> suitesByGroup = new HashMap<>();
        suitesByGroup.put(0, Collections.singletonList("com.example.ATest"));
        persistPlan("run-surplus", "commit-1", suitesByGroup);
        coordinator("run-surplus", "runner-a").claim("commit-1", 1L);

        // when
        ClaimOutcome outcome = coordinator("run-surplus", "runner-b").claim("commit-1", 2L);

        // then
        assertFalse(outcome.isClaimed());
        assertNull(outcome.getGroup());
        assertEquals("runner-b", outcome.getRunnerKey());
        assertEquals("runner-a", dataStore.readDistributedRunGroups("run-surplus").get(0).getRunnerKey());
    }

    /**
     * Verify a runner whose run id has no run row fails loudly. The plan write clears the previous
     * run's rows, so this is a straggler from a build that has been superseded; exiting quietly
     * would report a green build that ran no tests at all, which is the exact silent failure the
     * whole claim protocol exists to prevent.
     */
    @Test
    void shouldThrowWhenTheRunRowIsAbsent() {
        // given
        DistributedRunCoordinator coordinator = coordinator("run-missing", "runner-a");

        // when
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> coordinator.claim("commit-1", 1L));

        // then
        assertTrue(thrown.getMessage().contains("run-missing"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("tiaRunId"), thrown.getMessage());
    }

    /**
     * Verify a runner whose workspace sits on a different commit than the plan was built for fails
     * loudly, with both commits named. Its suite list was chosen against other code, so running it
     * would test the wrong tree while reporting on this one.
     */
    @Test
    void shouldThrowWhenTheWorkspaceCommitDiffersFromThePlanCommit() {
        // given
        persistPlan("run-commit", "plan-commit", twoGroupAssignment());
        DistributedRunCoordinator coordinator = coordinator("run-commit", "runner-a");

        // when
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> coordinator.claim("workspace-commit", 1L));

        // then
        assertTrue(thrown.getMessage().contains("plan-commit"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("workspace-commit"), thrown.getMessage());
        assertEquals(DistributedRunGroupStatus.PENDING,
                dataStore.readDistributedRunGroups("run-commit").get(0).getStatus());
    }

    /**
     * Verify that with no {@code tiaDistributedRunnerKey} configured the coordinator derives a key
     * from the run id, hostname and process id, and claims with it - so both build tools get the
     * same fallback without either having to build one.
     */
    @Test
    void shouldDeriveARunnerKeyFromRunIdHostAndPidWhenNoneIsConfigured() {
        // given
        persistPlan("run-derived", "commit-1", twoGroupAssignment());
        DistributedRunCoordinator coordinator = coordinator("run-derived", null);
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];

        // when
        ClaimOutcome outcome = coordinator.claim("commit-1", 77L);

        // then
        assertNotNull(outcome.getRunnerKey());
        assertTrue(outcome.getRunnerKey().startsWith("run-derived-"), outcome.getRunnerKey());
        assertTrue(outcome.getRunnerKey().endsWith("-" + pid), outcome.getRunnerKey());
        assertEquals(outcome.getRunnerKey(),
                dataStore.readDistributedRunGroups("run-derived").get(0).getRunnerKey());
    }

    /**
     * Verify the ignore list is every tracked suite except the ones this runner's group owns, for
     * both groups of the same plan - so between them the two runners run each suite exactly once
     * and a tracked suite in no group ({@code DTest}) is skipped by both.
     */
    @Test
    void shouldIgnoreEveryTrackedSuiteOutsideMyGroup() {
        // given
        persistPlan("run-derive", "commit-1", twoGroupAssignment());
        DistributedRunCoordinator coordinator = coordinator("run-derive", "runner-a");
        Set<String> tracked = new HashSet<>(Arrays.asList("com.example.ATest", "com.example.BTest",
                "com.example.CTest", "com.example.DTest"));

        // when
        Set<String> ignoredByGroupZero = coordinator.deriveTestsToIgnore(0, tracked);
        Set<String> ignoredByGroupOne = coordinator.deriveTestsToIgnore(1, tracked);

        // then
        assertEquals(new HashSet<>(Arrays.asList("com.example.CTest", "com.example.DTest")),
                ignoredByGroupZero);
        assertEquals(new HashSet<>(Arrays.asList("com.example.ATest", "com.example.BTest",
                "com.example.DTest")), ignoredByGroupOne);
    }

    /**
     * Verify a suite that is in the plan but not yet tracked - a brand-new test class Tia has no
     * mapping for - is ignored by every runner that did not get it. Without the union of the plan's
     * own suites it would be missing from every runner's ignore list and every runner would run it,
     * turning one new suite into N duplicate executions.
     */
    @Test
    void shouldIgnoreAPlannedButUntrackedSuiteOnTheRunnersThatDidNotGetIt() {
        // given
        Map<Integer, List<String>> suitesByGroup = new HashMap<>();
        suitesByGroup.put(0, Collections.singletonList("com.example.ATest"));
        suitesByGroup.put(1, Collections.singletonList("com.example.BrandNewTest"));
        persistPlan("run-untracked", "commit-1", suitesByGroup);
        DistributedRunCoordinator coordinator = coordinator("run-untracked", "runner-a");
        Set<String> tracked = new HashSet<>(Collections.singletonList("com.example.ATest"));

        // when
        Set<String> ignoredByGroupZero = coordinator.deriveTestsToIgnore(0, tracked);
        Set<String> ignoredByGroupOne = coordinator.deriveTestsToIgnore(1, tracked);

        // then
        assertEquals(new HashSet<>(Collections.singletonList("com.example.BrandNewTest")),
                ignoredByGroupZero);
        assertEquals(new HashSet<>(Collections.singletonList("com.example.ATest")),
                ignoredByGroupOne);
    }

    /**
     * Verify a seed run - one group, no suites, nothing tracked - derives an empty ignore list, so
     * its single runner runs the entire suite and records the mapping the next build will plan
     * from. This falls out of the general rule with no special case for it.
     */
    @Test
    void shouldDeriveAnEmptyIgnoreListForASeedRun() {
        // given
        Map<Integer, List<String>> suitesByGroup = new HashMap<>();
        suitesByGroup.put(0, Collections.<String>emptyList());
        persistPlan("run-seed", "commit-1", suitesByGroup);
        DistributedRunCoordinator coordinator = coordinator("run-seed", "runner-a");

        // when
        Set<String> ignored = coordinator.deriveTestsToIgnore(0, Collections.<String>emptySet());

        // then
        assertTrue(ignored.isEmpty(), ignored.toString());
    }

    /**
     * Verify the caller's tracked-suite set is not mutated while deriving the ignore list, by
     * handing in an unmodifiable set - the tracked names come from the datastore's own cached
     * mapping, and quietly removing this runner's suites from it would corrupt every later read in
     * the same build.
     */
    @Test
    void shouldNotMutateTheSuppliedTrackedSuiteSet() {
        // given
        persistPlan("run-immutable", "commit-1", twoGroupAssignment());
        DistributedRunCoordinator coordinator = coordinator("run-immutable", "runner-a");
        Set<String> tracked = Collections.unmodifiableSet(new HashSet<>(
                Arrays.asList("com.example.ATest", "com.example.BTest", "com.example.CTest")));

        // when
        Set<String> ignored = coordinator.deriveTestsToIgnore(0, tracked);

        // then
        assertEquals(new HashSet<>(Collections.singletonList("com.example.CTest")), ignored);
        assertEquals(3, tracked.size());
    }

    /**
     * Verify deriving an ignore list for a run that has no run row throws rather than returning a
     * list. The same straggler that must not claim quietly must not silently derive an ignore list
     * either - with no plan to read, the union would be empty and the runner would run everything
     * tracked, duplicating the whole suite.
     */
    @Test
    void shouldThrowWhenDerivingForAnAbsentRun() {
        // given
        DistributedRunCoordinator coordinator = coordinator("run-absent", "runner-a");

        // when
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> coordinator.deriveTestsToIgnore(0, Collections.<String>emptySet()));

        // then
        assertTrue(thrown.getMessage().contains("run-absent"), thrown.getMessage());
    }

    /**
     * Verify deriving for a group number the plan does not contain throws. Left unchecked it would
     * subtract nothing, so the runner would ignore every suite in the plan and run none of them
     * while still reporting success.
     */
    @Test
    void shouldThrowWhenDerivingForAGroupNumberThePlanDoesNotHave() {
        // given
        persistPlan("run-badgroup", "commit-1", twoGroupAssignment());
        DistributedRunCoordinator coordinator = coordinator("run-badgroup", "runner-a");

        // when
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> coordinator.deriveTestsToIgnore(7, Collections.<String>emptySet()));

        // then
        assertTrue(thrown.getMessage().contains("7"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("run-badgroup"), thrown.getMessage());
    }
}
