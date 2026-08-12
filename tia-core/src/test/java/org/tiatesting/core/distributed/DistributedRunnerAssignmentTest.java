package org.tiatesting.core.distributed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover {@link DistributedRunnerAssignment}: the one decision both build tools make when they run
 * in distributed mode - claim a group, then work out the two suite lists that go into the ignore
 * and selected files. Maven claims in the build JVM before surefire forks and Gradle/Spock claims
 * inside the test JVM, so the only thing keeping the two from drifting on which suites a runner
 * skips is that they share this class; these tests are therefore the coverage of that decision for
 * both of them.
 *
 * <p>Uses a real embedded-H2 {@link JdbcDataStore} rather than a fake, following
 * {@link DistributedRunCoordinatorTest}'s fixture: the behaviour under test is what gets read back
 * from the shared plan tables, and a fake would only assert that this class calls the methods the
 * test already knows it calls.
 */
class DistributedRunnerAssignmentTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database in a new temp directory and bootstrap its schema, so
     * each test starts from an isolated store with no distributed run planned and no suite
     * tracked, and cannot see another test's claims.
     *
     * @throws Exception if the temp directory cannot be created or schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-distributed-assignment-", "");
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
     * @param commitValue the VCS commit the plan is pinned to, which a runner's workspace commit
     *                    is later checked against
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
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(run, groups, suitesByGroup, null));
    }

    /**
     * Record the given suite names as tracked by Tia, so the ignore list has a tracked set to
     * subtract this runner's group from.
     *
     * @param suiteNames the suite names to persist as tracked
     */
    private void persistTracked(String... suiteNames) {
        Map<String, TestSuiteTracker> trackers = new LinkedHashMap<>();
        for (String suiteName : suiteNames) {
            trackers.put(suiteName, new TestSuiteTracker(suiteName));
        }
        dataStore.persistTestSuites(trackers);
    }

    /**
     * Build the suite-to-group assignment used by most of these tests: group 0 owns {@code ATest}
     * and {@code BTest}, group 1 owns {@code CTest}.
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
     * Build a run configuration carrying the given run id and runner key, in the fixed-group-count
     * shape a runner's own properties would produce.
     *
     * @param runId the distributed run to work against
     * @param runnerKey the runner identity to claim with, or null to exercise the derived fallback
     * @return a validated run configuration
     */
    private static DistributedRunConfig config(String runId, String runnerKey) {
        return DistributedRunConfig.validated(runId, 2, null, null, runnerKey);
    }

    /**
     * Verify the runner that claims a group runs exactly that group's suites and skips everything
     * else: the selected list is the group's own suites and the ignore list is every other tracked
     * or planned suite. This is the pair of files the build tool writes, so getting the complement
     * wrong here is what would make two runners run the same suite twice or no runner run it at all.
     */
    @Test
    void shouldSelectTheClaimedGroupsSuitesAndIgnoreEveryOtherSuite() {
        // given
        persistPlan("run-claimed", "commit-1", twoGroupAssignment());
        persistTracked("com.example.ATest", "com.example.BTest", "com.example.CTest",
                "com.example.DTest");

        // when
        DistributedRunnerAssignment assignment = DistributedRunnerAssignment.claim(dataStore,
                config("run-claimed", "runner-a"), "commit-1", 4242L);

        // then
        assertTrue(assignment.isClaimed());
        assertEquals(Integer.valueOf(0), assignment.getGroupNumber());
        assertEquals("runner-a", assignment.getRunnerKey());
        assertEquals(new HashSet<>(Arrays.asList("com.example.ATest", "com.example.BTest")),
                assignment.getTestsToRun());
        assertEquals(new HashSet<>(Arrays.asList("com.example.CTest", "com.example.DTest")),
                assignment.getTestsToIgnore());
    }

    /**
     * Verify a surplus runner - one that arrives after every group has been claimed, which a
     * pipeline with a fixed fan-out wider than the plan's group count legitimately produces - runs
     * nothing at all: it ignores every planned and every tracked suite and selects none. Anything
     * less than "every suite" would have it duplicate work another runner already owns.
     */
    @Test
    void shouldIgnoreEverySuiteAndSelectNoneWhenEveryGroupIsAlreadyClaimed() {
        // given
        persistPlan("run-surplus", "commit-1", twoGroupAssignment());
        persistTracked("com.example.ATest", "com.example.BTest", "com.example.CTest",
                "com.example.DTest");
        DistributedRunnerAssignment.claim(dataStore, config("run-surplus", "runner-a"), "commit-1", 1L);
        DistributedRunnerAssignment.claim(dataStore, config("run-surplus", "runner-b"), "commit-1", 2L);

        // when
        DistributedRunnerAssignment surplus = DistributedRunnerAssignment.claim(dataStore,
                config("run-surplus", "runner-c"), "commit-1", 3L);

        // then
        assertFalse(surplus.isClaimed());
        assertNull(surplus.getGroupNumber());
        assertEquals("runner-c", surplus.getRunnerKey());
        assertTrue(surplus.getTestsToRun().isEmpty(), surplus.getTestsToRun().toString());
        assertEquals(new HashSet<>(Arrays.asList("com.example.ATest", "com.example.BTest",
                "com.example.CTest", "com.example.DTest")), surplus.getTestsToIgnore());
    }

    /**
     * Verify a planned suite Tia has no mapping for yet - a brand-new test class - is still
     * ignored by a surplus runner. The tracked set alone would not name it, and a surplus runner
     * that ran it would duplicate the runner that actually owns it.
     */
    @Test
    void shouldIgnoreAPlannedButUntrackedSuiteOnASurplusRunner() {
        // given
        Map<Integer, List<String>> suitesByGroup = new HashMap<>();
        suitesByGroup.put(0, Collections.singletonList("com.example.BrandNewTest"));
        persistPlan("run-surplus-new", "commit-1", suitesByGroup);
        DistributedRunnerAssignment.claim(dataStore, config("run-surplus-new", "runner-a"), "commit-1", 1L);

        // when
        DistributedRunnerAssignment surplus = DistributedRunnerAssignment.claim(dataStore,
                config("run-surplus-new", "runner-b"), "commit-1", 2L);

        // then
        assertFalse(surplus.isClaimed());
        assertEquals(new HashSet<>(Collections.singletonList("com.example.BrandNewTest")),
                surplus.getTestsToIgnore());
    }

    /**
     * Verify the runner key carried on the assignment is the derived one the claim was actually
     * recorded under, not the null the configuration supplied. The build tool hands this value to
     * the forked test JVM, which completes the group under it in a later stage - re-deriving it
     * there would produce a different key and orphan the claim.
     */
    @Test
    void shouldCarryTheDerivedRunnerKeyTheClaimWasRecordedUnder() {
        // given
        persistPlan("run-derived", "commit-1", twoGroupAssignment());

        // when
        DistributedRunnerAssignment assignment = DistributedRunnerAssignment.claim(dataStore,
                config("run-derived", null), "commit-1", 77L);

        // then
        assertNotNull(assignment.getRunnerKey());
        assertTrue(assignment.getRunnerKey().startsWith("run-derived-"), assignment.getRunnerKey());
        assertEquals(assignment.getRunnerKey(),
                dataStore.readDistributedRunGroups("run-derived").get(0).getRunnerKey());
    }

    /**
     * Verify a runner whose run id has no run row fails rather than producing an assignment. The
     * plan write clears the previous run's rows, so this is a straggler from a superseded build;
     * an empty ignore list would have it run every tracked suite and report a green build for a
     * plan that no longer exists.
     */
    @Test
    void shouldThrowRatherThanAssignWhenTheRunRowIsAbsent() {
        // given
        DistributedRunConfig config = config("run-missing", "runner-a");

        // when
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> DistributedRunnerAssignment.claim(dataStore, config, "commit-1", 1L));

        // then
        assertTrue(thrown.getMessage().contains("run-missing"), thrown.getMessage());
    }

    /**
     * Verify a runner whose workspace sits on a different commit than the plan was built for fails
     * rather than producing an assignment. The plan's suite lists were chosen by diffing the
     * planned commit, so this workspace would run a selection made for different code.
     */
    @Test
    void shouldThrowRatherThanAssignWhenTheWorkspaceCommitDiffersFromThePlan() {
        // given
        persistPlan("run-commit", "plan-commit", twoGroupAssignment());
        DistributedRunConfig config = config("run-commit", "runner-a");

        // when
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> DistributedRunnerAssignment.claim(dataStore, config, "workspace-commit", 1L));

        // then
        assertTrue(thrown.getMessage().contains("plan-commit"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("workspace-commit"), thrown.getMessage());
    }

    /**
     * Verify a seed run - one group with no suites in it, nothing tracked - assigns its single
     * runner an empty ignore list, so that runner executes the whole suite and records the mapping
     * the next build plans from.
     */
    @Test
    void shouldAssignAnEmptyIgnoreListForASeedRun() {
        // given
        Map<Integer, List<String>> suitesByGroup = new HashMap<>();
        suitesByGroup.put(0, Collections.<String>emptyList());
        persistPlan("run-seed", "commit-1", suitesByGroup);

        // when
        DistributedRunnerAssignment assignment = DistributedRunnerAssignment.claim(dataStore,
                config("run-seed", "runner-a"), "commit-1", 1L);

        // then
        assertTrue(assignment.isClaimed());
        assertTrue(assignment.getTestsToIgnore().isEmpty(), assignment.getTestsToIgnore().toString());
        assertTrue(assignment.getTestsToRun().isEmpty(), assignment.getTestsToRun().toString());
    }

    /**
     * Verify a claimed assignment converts into the context its persist is driven from, carrying
     * the identity the claim was actually recorded under and the group it won. A Gradle runner
     * claims and persists in the same JVM, so it converts the assignment it already holds rather
     * than claiming a second time - re-deriving here would take another group and leave the first
     * one open forever.
     */
    @Test
    void shouldConvertAClaimedAssignmentIntoTheContextItsPersistUses() {
        // given
        persistPlan("run-context", "commit-1", twoGroupAssignment());
        DistributedRunnerAssignment assignment = DistributedRunnerAssignment.claim(dataStore,
                config("run-context", "runner-a"), "commit-1", 4242L);

        // when
        DistributedRunnerContext context = assignment.toRunnerContext("run-context");

        // then
        assertTrue(context.isClaimed());
        assertEquals("run-context", context.getRunId());
        assertEquals("runner-a", context.getRunnerKey());
        assertEquals(assignment.getGroupNumber(), context.getGroupNumber());
    }

    /**
     * Verify a surplus runner's assignment converts into a context holding no group rather than
     * into no context at all. A null context would put it on the single-host persist, where it
     * would rebuild the catalogue and seal a build whose other runners are still going.
     */
    @Test
    void shouldConvertASurplusAssignmentIntoAGrouplessContext() {
        // given - both groups already claimed by other runners
        persistPlan("run-context-surplus", "commit-1", twoGroupAssignment());
        DistributedRunnerAssignment.claim(dataStore, config("run-context-surplus", "runner-a"),
                "commit-1", 1L);
        DistributedRunnerAssignment.claim(dataStore, config("run-context-surplus", "runner-b"),
                "commit-1", 2L);
        DistributedRunnerAssignment surplus = DistributedRunnerAssignment.claim(dataStore,
                config("run-context-surplus", "runner-c"), "commit-1", 3L);

        // when
        DistributedRunnerContext context = surplus.toRunnerContext("run-context-surplus");

        // then
        assertNotNull(context, "a surplus runner is still a distributed runner");
        assertFalse(context.isClaimed());
        assertEquals("runner-c", context.getRunnerKey());
    }
}
