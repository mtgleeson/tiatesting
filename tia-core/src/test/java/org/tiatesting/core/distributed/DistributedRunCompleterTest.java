package org.tiatesting.core.distributed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.testrunner.TestRunResult;
import org.tiatesting.core.testrunner.TestRunnerService;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover {@link DistributedRunCompleter#completeAndSeal}, the sequence that replaced the JVM shutdown
 * hook this project used to release the distributed barrier from. Every completion here is driven by
 * an explicit call, the way {@code AbstractTiaDistCompleteMojo} and {@code TiaDistCompleteTask} now
 * make it, rather than by simulating a JVM exit - a test cannot exit the JVM it runs in, and there is
 * no longer any pending state for it to simulate exiting into.
 *
 * <p>Two things this class exists to lock down that a plain "does it complete and seal" test would
 * not: a test plan finishing is not the same event as the explicit completion (several persists in
 * one JVM must not complete the group early, and the completion must still see everything those
 * persists wrote), and a failure is no longer swallowed the way the shutdown hook swallowed it - it
 * propagates, and {@link DistributedRunCompleter.SealFailedAfterCompletionException} is what tells a
 * caller which side of the barrier it happened on.
 *
 * <p>Runs against a real embedded H2 {@link JdbcDataStore} subclassed to record its write calls, so
 * "nothing completed the group yet" is asserted as an absent write rather than inferred.
 */
class DistributedRunCompleterTest {

    private static final String RUN_ID = "run-1";
    private static final String RUNNER_KEY = "runner-a";
    private static final String PLAN_COMMIT = "plan-commit";
    private static final String SUITE_FIRST_PLAN = "com.example.FirstPlanTest";
    private static final String SUITE_SECOND_PLAN = "com.example.SecondPlanTest";
    private static final int METHOD_FIRST_PLAN = 11;
    private static final int METHOD_SECOND_PLAN = 22;

    private RecordingDataStore dataStore;
    private TestRunnerService service;
    private File tempDir;

    /**
     * Create a fresh embedded H2 store in its own temp directory, seed a known prior commit value,
     * and build the service under test.
     *
     * @throws Exception if the temp directory cannot be created or the schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-distributed-completer-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new RecordingDataStore(tempDir);
        dataStore.getTiaData(true);

        TiaData tiaData = dataStore.getTiaCore();
        tiaData.setCommitValue("prior-commit");
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);
        dataStore.callOrder.clear();

        service = new TestRunnerService(dataStore);
    }

    /**
     * Close the store so embedded H2 releases its file lock, then remove the temp directory.
     */
    @AfterEach
    void tearDown() {
        if (dataStore != null) {
            dataStore.close();
        }
        if (tempDir != null && tempDir.exists()) {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            tempDir.delete();
        }
    }

    /**
     * The defect the barrier move fixed, seen from the new shared method's side: two test plans in
     * one JVM must not complete the group on their own, and the explicit completion the build tool
     * makes afterwards must be the only thing that does - once, not once per test plan.
     */
    @Test
    void twoTestPlansInOneJvmCompleteTheGroupExactlyOnceWhenExplicitlyCompleted() {
        // given
        persistPlan(RUN_ID, 2);
        DistributedRunnerContext context = claimGroup(RUN_ID, RUNNER_KEY);
        service.persistTestRunData(true, true, true, PLAN_COMMIT, "main",
                System.currentTimeMillis(), resultFor(SUITE_FIRST_PLAN, METHOD_FIRST_PLAN, 2, 0),
                context);
        service.persistTestRunData(true, true, true, PLAN_COMMIT, "main",
                System.currentTimeMillis(), resultFor(SUITE_SECOND_PLAN, METHOD_SECOND_PLAN, 1, 0),
                context);
        assertEquals(0, Collections.frequency(dataStore.callOrder, "completeGroup"),
                "neither test plan finishing may complete the group on its own. Call order: "
                        + dataStore.callOrder);

        // when
        DistributedRunCompleter.completeAndSeal(dataStore, context, true, true, true,
                System.currentTimeMillis());

        // then
        assertEquals(1, Collections.frequency(dataStore.callOrder, "completeGroup"),
                "the explicit completion must complete the group exactly once. Call order: "
                        + dataStore.callOrder);
        assertEquals(DistributedRunGroupStatus.COMPLETED, readGroup(RUN_ID, 0).getStatus(),
                "the group must be complete once the build tool has explicitly completed it");
    }

    /**
     * The bug the barrier move exists to fix, seen from the mapping side: a second test plan's suite
     * rows and staged trackers must still be written even though nothing has completed the group yet.
     * With the group completed by the first test plan (the old, wrong behaviour), the second would
     * find its claim dead and skip every write it had - so the coverage it observed would never reach
     * the edge table the sealer rebuilds the catalogue from.
     */
    @Test
    void aSecondTestPlansMappingWritesStillHappenBeforeTheExplicitCompletion() {
        // given
        persistPlan(RUN_ID, 2);
        DistributedRunnerContext context = claimGroup(RUN_ID, RUNNER_KEY);
        service.persistTestRunData(true, true, true, PLAN_COMMIT, "main",
                System.currentTimeMillis(), resultFor(SUITE_FIRST_PLAN, METHOD_FIRST_PLAN, 2, 0),
                context);

        // when - a second test plan covers a suite the first never saw
        service.persistTestRunData(true, true, true, PLAN_COMMIT, "main",
                System.currentTimeMillis(), resultFor(SUITE_SECOND_PLAN, METHOD_SECOND_PLAN, 1, 0),
                context);

        // then
        assertTrue(dataStore.getTestSuitesTracked().containsKey(SUITE_SECOND_PLAN),
                "the second test plan's suite mapping row must be written. Tracked: "
                        + dataStore.getTestSuitesTracked().keySet());
        assertTrue(dataStore.readStagedMethodTrackers(RUN_ID)
                        .containsKey(Integer.valueOf(METHOD_SECOND_PLAN)),
                "the second test plan's method tracker must be staged for the sealer. Staged: "
                        + dataStore.readStagedMethodTrackers(RUN_ID).keySet());
    }

    /**
     * {@code completeGroup} returning null - the claim died, or the group was already completed - is
     * a normal outcome, so a second call after a successful one must return quietly rather than throw
     * or attempt a second election.
     */
    @Test
    void aSecondCallAfterTheGroupIsAlreadyCompletedReturnsQuietlyWithoutAttemptingASecondElection() {
        // given
        persistPlan(RUN_ID, 1);
        DistributedRunnerContext context = claimGroup(RUN_ID, RUNNER_KEY);
        service.persistTestRunData(true, true, true, PLAN_COMMIT, "main",
                System.currentTimeMillis(), resultFor(SUITE_FIRST_PLAN, METHOD_FIRST_PLAN, 1, 0),
                context);
        DistributedRunCompleter.completeAndSeal(dataStore, context, true, true, true,
                System.currentTimeMillis());
        assertEquals(1, Collections.frequency(dataStore.callOrder, "electSealer"),
                "test setup expects the first call to have stood for election");
        dataStore.callOrder.clear();

        // when / then
        assertDoesNotThrow(() -> DistributedRunCompleter.completeAndSeal(dataStore, context, true,
                        true, true, System.currentTimeMillis()),
                "a rejected completion must not be reported as a failure");
        assertEquals(Collections.singletonList("completeGroup"), dataStore.callOrder,
                "the second call may attempt the guarded completion write, but that write must be "
                        + "the only thing it does - it must not re-run the election. Call order: "
                        + dataStore.callOrder);
    }

    /**
     * The requirement this class replaces the shutdown hook's swallow-everything behaviour with: a
     * failure completing the group must propagate rather than be caught and logged, since both
     * callers are now ordinary build steps that must fail loudly. The group itself is left exactly as
     * it was, since the failure happened before the completion took effect.
     */
    @Test
    void aFailureCompletingTheGroupPropagatesRatherThanBeingSwallowed() {
        // given
        persistPlan(RUN_ID, 1);
        DistributedRunnerContext context = claimGroup(RUN_ID, RUNNER_KEY);
        service.persistTestRunData(true, true, true, PLAN_COMMIT, "main",
                System.currentTimeMillis(), resultFor(SUITE_FIRST_PLAN, METHOD_FIRST_PLAN, 1, 0),
                context);
        dataStore.failCompleteGroup = true;

        // when / then
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> DistributedRunCompleter.completeAndSeal(dataStore, context, true, true, true,
                        System.currentTimeMillis()),
                "a failure completing the group must not be swallowed");
        assertTrue(thrown instanceof IllegalStateException,
                "the original failure must propagate unwrapped, was: " + thrown);
        assertEquals(DistributedRunGroupStatus.CLAIMED, readGroup(RUN_ID, 0).getStatus(),
                "the group must be left exactly as it was when completing it failed");
    }

    /**
     * The design decision this class documents: a failure electing or sealing, once the group has
     * already completed, is reported as {@link DistributedRunCompleter.SealFailedAfterCompletionException}
     * rather than a plain failure, so a caller catching it can tell "never completed" apart from
     * "completed, but the seal that followed it failed" - and the group really is left {@code
     * COMPLETED}, matching what the exception reports.
     */
    @Test
    void aFailureSealingAfterTheGroupCompletedIsReportedAsSealFailedAfterCompletion() {
        // given
        persistPlan(RUN_ID, 1);
        DistributedRunnerContext context = claimGroup(RUN_ID, RUNNER_KEY);
        service.persistTestRunData(true, true, true, PLAN_COMMIT, "main",
                System.currentTimeMillis(), resultFor(SUITE_FIRST_PLAN, METHOD_FIRST_PLAN, 1, 0),
                context);
        dataStore.failElectSealer = true;

        // when / then
        DistributedRunCompleter.SealFailedAfterCompletionException thrown = assertThrows(
                DistributedRunCompleter.SealFailedAfterCompletionException.class,
                () -> DistributedRunCompleter.completeAndSeal(dataStore, context, true, true, true,
                        System.currentTimeMillis()),
                "a failure sealing after the group completed must be reported distinctly");
        assertTrue(thrown.getCause() instanceof IllegalStateException,
                "the original failure must be preserved as the cause, was: " + thrown.getCause());
        assertEquals(DistributedRunGroupStatus.COMPLETED, readGroup(RUN_ID, 0).getStatus(),
                "the group must really be COMPLETED, matching what the exception reports - an "
                        + "operator must not go looking for a row that is still CLAIMED");
    }

    /**
     * Build and persist a distributed run plan for the test's runners to claim from. Group 0 - the
     * one every test in this class claims - is assigned exactly {@link #SUITE_FIRST_PLAN} and
     * {@link #SUITE_SECOND_PLAN}, the two suites {@link #resultFor} always reports as observed, so
     * the completeness guard's intersection against the group's own assignment is non-empty.
     *
     * @param runId the run identifier to plan under
     * @param groupCount how many groups the plan is split into
     */
    private void persistPlan(final String runId, final int groupCount) {
        List<DistributedRunGroup> groups = new ArrayList<>();
        Map<Integer, List<String>> suites = new HashMap<>();
        for (int i = 0; i < groupCount; i++) {
            groups.add(DistributedRunGroup.pending(runId, i, 1000L));
            if (i == 0) {
                suites.put(i, Arrays.asList(SUITE_FIRST_PLAN, SUITE_SECOND_PLAN));
            } else {
                suites.put(i, Arrays.asList("com.example.Suite" + i + "Test"));
            }
        }
        DistributedRun run = DistributedRun.open(runId, "main", PLAN_COMMIT, groupCount, null,
                1000L * groupCount, 1234L, false);
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(run, groups, suites, null));
    }

    /**
     * Claim a group for a runner key and wrap it in the context that runner's persists carry.
     *
     * @param runId the run to claim from
     * @param runnerKey the identity to claim under
     * @return the claimed runner's context
     */
    private DistributedRunnerContext claimGroup(final String runId, final String runnerKey) {
        DistributedRunGroup claimed = dataStore.claimNextPendingGroup(runId, runnerKey, 5000L);
        assertNotNull(claimed, "test setup expects a group to be available to claim");
        return DistributedRunnerContext.forClaimedGroup(runId, runnerKey, claimed.getGroupNumber());
    }

    /**
     * Read one group of a run back from the store.
     *
     * @param runId the run the group belongs to
     * @param groupNumber the group's zero-based index within the run
     * @return the stored group
     */
    private DistributedRunGroup readGroup(final String runId, final int groupNumber) {
        for (DistributedRunGroup group : dataStore.readDistributedRunGroups(runId)) {
            if (group.getGroupNumber() == groupNumber) {
                return group;
            }
        }
        throw new IllegalStateException("no group " + groupNumber + " in run " + runId);
    }

    /**
     * Build the result one test plan reports: one suite carrying coverage of one method, with the
     * counters that test plan would have accumulated. Both of the test's suites are always reported
     * as observed, since the runner observes every test class whichever ones a given test plan
     * executes, and a suite missing from that set would be treated as deleted.
     *
     * @param suiteName the suite this test plan executed
     * @param methodId the method it covered
     * @param suitesRan the cumulative suites-ran counter this test plan reports
     * @param suitesFailed how many of its suites failed
     * @return the result to hand to the persist
     */
    private TestRunResult resultFor(final String suiteName, final int methodId, final int suitesRan,
                                     final int suitesFailed) {
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        TestSuiteTracker tracker = new TestSuiteTracker(suiteName);
        tracker.setClassesImpacted(Collections.singletonList(new ClassImpactTracker(
                suiteName.replace('.', '/') + ".java",
                new HashSet<>(Collections.singletonList(Integer.valueOf(methodId))))));
        trackers.put(suiteName, tracker);

        Map<Integer, MethodImpactTracker> methodTrackers = new HashMap<>();
        methodTrackers.put(Integer.valueOf(methodId),
                new MethodImpactTracker("com/example/M" + methodId + ".m.()V", 1, 10));

        Set<String> failed = new HashSet<>();
        for (int i = 0; i < suitesFailed; i++) {
            failed.add("com.example.Failed" + i + "Test");
        }
        Set<String> observed = new HashSet<>(Arrays.asList(SUITE_FIRST_PLAN, SUITE_SECOND_PLAN));
        observed.addAll(failed);

        return new TestRunResult(trackers, failed, observed, observed, new HashSet<>(observed),
                methodTrackers, new TestStats(), null, 1, suitesRan);
    }

    /**
     * An embedded-H2 {@link JdbcDataStore} that records the write calls these tests assert on and
     * can be told to fail the completion or the election, which is how the failure-propagation and
     * seal-after-completion paths are exercised without a broken database.
     */
    private static final class RecordingDataStore extends JdbcDataStore {

        private final List<String> callOrder = new ArrayList<>();
        private boolean failCompleteGroup;
        private boolean failElectSealer;

        /**
         * Open an embedded H2 store under a test-owned directory, in the same per-branch schema the
         * other persistence tests use.
         *
         * @param databaseDir the directory to hold the embedded database files
         */
        RecordingDataStore(final File databaseDir) {
            super(new H2Dialect(),
                    new H2ConnectionProvider(H2ConnectionSettings.embedded(databaseDir.getAbsolutePath())),
                    BranchSchema.schemaName("test"));
        }

        /**
         * Record and delegate the full suite mapping write.
         *
         * @param testSuites the suites whose rows and edges to write
         */
        @Override
        public void persistTestSuites(final Map<String, TestSuiteTracker> testSuites) {
            callOrder.add("persistTestSuites");
            super.persistTestSuites(testSuites);
        }

        /**
         * Record and delegate the staging write a distributed runner makes in place of the
         * catalogue.
         *
         * @param runId the run to stage under
         * @param methodsTracked the trackers this runner observed
         */
        @Override
        public void persistStagedMethodTrackers(final String runId,
                                                final Map<Integer, MethodImpactTracker> methodsTracked) {
            callOrder.add("persistStagedMethodTrackers");
            super.persistStagedMethodTrackers(runId, methodsTracked);
        }

        /**
         * Delegate the progress report. Not recorded in {@code callOrder}: no test in this class
         * asserts on where it falls in the write order.
         *
         * @param runId the run the group belongs to
         * @param groupNumber the group's zero-based index within the run
         * @param runnerKey the calling runner's identity
         * @param actualDurationMs this call's measured test-execution time, added to the group
         * @param suitesRan number of suites this call's test plan executed, added to the group
         * @param suitesFailed number of suites currently failing, replacing what was stored
         * @param suitesObserved number of suites observed so far, replacing what was stored with
         *                       the greater of the two values
         * @return true when the guarded update applied, false when the claim is no longer live
         */
        @Override
        public boolean reportGroupProgress(final String runId, final int groupNumber,
                                           final String runnerKey, final long actualDurationMs,
                                           final int suitesRan, final int suitesFailed,
                                           final int suitesObserved) {
            return super.reportGroupProgress(runId, groupNumber, runnerKey, actualDurationMs,
                    suitesRan, suitesFailed, suitesObserved);
        }

        /**
         * Record and delegate the group completion, or fail it outright when the test has asked for
         * the completion-failure path.
         *
         * @param runId the run the group belongs to
         * @param groupNumber the group's zero-based index within the run
         * @param runnerKey the calling runner's identity
         * @param completedAtMs UTC epoch millis of the completion
         * @return the updated group, or null when the claim is no longer live
         */
        @Override
        public DistributedRunGroup completeGroup(final String runId, final int groupNumber,
                                                 final String runnerKey, final long completedAtMs) {
            callOrder.add("completeGroup");
            if (failCompleteGroup) {
                throw new IllegalStateException("the database went away while completing the group");
            }
            return super.completeGroup(runId, groupNumber, runnerKey, completedAtMs);
        }

        /**
         * Record and delegate the sealer election, or fail it outright when the test has asked for
         * the seal-after-completion failure path.
         *
         * @param runId the run to elect within
         * @param runnerKey the calling runner's identity
         * @param sealedAtMs UTC epoch millis to record as the election time
         * @return true when this runner won the election
         */
        @Override
        public boolean electSealer(final String runId, final String runnerKey, final long sealedAtMs) {
            callOrder.add("electSealer");
            if (failElectSealer) {
                throw new IllegalStateException("the database went away while electing the sealer");
            }
            return super.electSealer(runId, runnerKey, sealedAtMs);
        }
    }
}
