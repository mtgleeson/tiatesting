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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock the one thing that must happen once per test JVM rather than once per test plan: releasing
 * the barrier.
 *
 * <p>{@code persistTestRunData} runs on every {@code testPlanExecutionFinished}, and a Surefire
 * retry produces another test plan in the same JVM, so several persists per JVM is routine. The
 * mapping writes each of those persists makes are cumulative and safe, but completing the group is
 * not: it releases the barrier the sealer waits on, so a group completed after the first test plan
 * lets the build seal while this JVM is still executing tests - and the catalogue is then rebuilt
 * from an edge set missing everything the later test plans covered. That is silent under-selection,
 * the failure this whole feature exists to prevent.
 *
 * <p>These tests drive the completion directly rather than by exiting a JVM, since a test cannot
 * exit the JVM it runs in. What a real shutdown hook adds on top - that it runs at all, and that it
 * runs after the last test plan - is the JVM's contract, not this code's.
 *
 * <p>Runs against a real embedded H2 {@link JdbcDataStore} subclassed to record its write calls, so
 * "nothing completed the group yet" is asserted as an absent write rather than inferred.
 */
class DistributedRunCompletionTest {

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
        tempDir = File.createTempFile("tia-distributed-completion-", "");
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
     * Drop anything this test recorded for JVM exit before closing the store, so a test that
     * deliberately leaves a completion pending cannot carry it into the next test - or into the
     * real shutdown of the Gradle test JVM, where its store is long closed.
     */
    @AfterEach
    void tearDown() {
        DistributedRunCompletion.discardPendingCompletions();
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
     * The defect this stage fixes: the first of a JVM's test plans must not complete the group. Its
     * completion would release the barrier while this JVM is still running tests, letting another
     * runner seal a build whose coverage is not all written yet.
     */
    @Test
    void theFirstOfSeveralTestPlansDoesNotCompleteTheGroup() {
        // given
        persistPlan(RUN_ID, 2);
        DistributedRunnerContext context = claimGroup(RUN_ID, RUNNER_KEY);

        // when - one test plan finishes and persists, and the JVM keeps running
        service.persistTestRunData(true, true, true, PLAN_COMMIT, "main",
                System.currentTimeMillis(), resultFor(SUITE_FIRST_PLAN, METHOD_FIRST_PLAN, 2, 0),
                context);

        // then
        assertEquals(0, Collections.frequency(dataStore.callOrder, "completeGroup"),
                "a test plan finishing is not the JVM finishing, so nothing may release the "
                        + "barrier yet. Call order: " + dataStore.callOrder);
        assertEquals(DistributedRunGroupStatus.CLAIMED, readGroup(RUN_ID, 0).getStatus(),
                "the group must still be claimed while the runner's JVM is alive");
    }

    /**
     * Two test plans in one JVM complete the group exactly once, when the JVM exits. Completing it
     * twice would be a second guarded write against an already-completed row, and a second sealer
     * election with it.
     */
    @Test
    void twoTestPlansInOneJvmCompleteTheGroupExactlyOnceAtJvmExit() {
        // given
        persistPlan(RUN_ID, 2);
        DistributedRunnerContext context = claimGroup(RUN_ID, RUNNER_KEY);
        service.persistTestRunData(true, true, true, PLAN_COMMIT, "main",
                System.currentTimeMillis(), resultFor(SUITE_FIRST_PLAN, METHOD_FIRST_PLAN, 2, 0),
                context);
        service.persistTestRunData(true, true, true, PLAN_COMMIT, "main",
                System.currentTimeMillis(), resultFor(SUITE_SECOND_PLAN, METHOD_SECOND_PLAN, 1, 0),
                context);

        int writesBeforeExit = dataStore.callOrder.size();

        // when - the JVM exits
        DistributedRunCompletion.completePendingCompletions();

        // then
        List<String> writesAtExit = dataStore.callOrder.subList(writesBeforeExit,
                dataStore.callOrder.size());
        assertEquals(1, Collections.frequency(writesAtExit, "completeGroup"),
                "the JVM exit is what completes the group. Writes at exit: " + writesAtExit);
        assertEquals(1, Collections.frequency(dataStore.callOrder, "completeGroup"),
                "the group must be completed once for the JVM, not once per test plan. Call order: "
                        + dataStore.callOrder);
        assertEquals(DistributedRunGroupStatus.COMPLETED, readGroup(RUN_ID, 0).getStatus(),
                "the group must be complete once its runner's JVM has exited");
    }

    /**
     * The figures recorded on the completed group are the last test plan's, since the shared
     * per-JVM run data each persist reads from is cumulative - so the last one to run carries
     * everything the JVM did.
     */
    @Test
    void theCompletedGroupCarriesTheLastTestPlansFigures() {
        // given
        persistPlan(RUN_ID, 2);
        DistributedRunnerContext context = claimGroup(RUN_ID, RUNNER_KEY);
        service.persistTestRunData(true, true, true, PLAN_COMMIT, "main",
                System.currentTimeMillis() - 1000L,
                resultFor(SUITE_FIRST_PLAN, METHOD_FIRST_PLAN, 2, 0), context);

        // when - a retry adds a third suite and a failure, then the JVM exits
        service.persistTestRunData(true, true, true, PLAN_COMMIT, "main",
                System.currentTimeMillis() - 5000L,
                resultFor(SUITE_SECOND_PLAN, METHOD_SECOND_PLAN, 3, 1), context);
        DistributedRunCompletion.completePendingCompletions();

        // then
        DistributedRunGroup group = readGroup(RUN_ID, 0);
        assertEquals(3, group.getSuitesRan(),
                "the last test plan's cumulative suite count is the one the sealer aggregates");
        assertEquals(1, group.getSuitesFailed(),
                "the last test plan's failed count is the one the sealer aggregates");
        assertNotNull(group.getActualDurationMs());
        assertTrue(group.getActualDurationMs() >= 5000L,
                "the last test plan's duration must be the recorded one, was "
                        + group.getActualDurationMs());
    }

    /**
     * The bug the barrier move exists to fix, seen from the mapping side: a second test plan's
     * suite rows and staged trackers must still be written. With the group completed by the first
     * test plan, the second finds its claim dead and skips every write it had - so the coverage it
     * observed never reaches the edge table the sealer rebuilds the catalogue from.
     */
    @Test
    void aSecondTestPlansMappingWritesStillHappen() {
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
     * A surplus runner - one a pipeline fanned out wider than the plan's group count - has no group
     * to complete, so it records nothing for JVM exit and the exit does nothing at all.
     */
    @Test
    void aSurplusRunnerRecordsNoCompletionForJvmExit() {
        // given
        persistPlan(RUN_ID, 1);
        claimGroup(RUN_ID, "runner-b");
        DistributedRunnerContext context = DistributedRunnerContext.surplusRunner(RUN_ID, RUNNER_KEY);
        service.persistTestRunData(true, true, true, PLAN_COMMIT, "main",
                System.currentTimeMillis(), emptyResult(), context);
        dataStore.callOrder.clear();

        // when
        DistributedRunCompletion.completePendingCompletions();

        // then
        assertEquals(Collections.<String>emptyList(), dataStore.callOrder,
                "a runner with no group must have nothing to do when its JVM exits");
        assertEquals(DistributedRunGroupStatus.CLAIMED, readGroup(RUN_ID, 0).getStatus(),
                "a surplus runner must not complete the group another runner holds");
    }

    /**
     * A completion that fails at JVM exit must leave evidence and let the JVM go. Anything thrown
     * out of a shutdown hook vanishes without a trace, so the failure is caught, logged against the
     * run and group it belongs to, and swallowed - the build simply does not seal, which is the
     * safe direction.
     */
    @Test
    void aCompletionThatFailsAtJvmExitDoesNotEscapeTheHook() {
        // given
        persistPlan(RUN_ID, 2);
        DistributedRunnerContext context = claimGroup(RUN_ID, RUNNER_KEY);
        service.persistTestRunData(true, true, true, PLAN_COMMIT, "main",
                System.currentTimeMillis(), resultFor(SUITE_FIRST_PLAN, METHOD_FIRST_PLAN, 2, 0),
                context);
        dataStore.failCompleteGroup = true;
        int writesBeforeExit = dataStore.callOrder.size();

        // when / then
        assertDoesNotThrow(DistributedRunCompletion::completePendingCompletions,
                "a shutdown hook that throws kills the exit path and hides the cause");
        List<String> writesAtExit = dataStore.callOrder.subList(writesBeforeExit,
                dataStore.callOrder.size());
        assertTrue(writesAtExit.contains("completeGroup"),
                "the exit must actually have attempted the completion that failed. Writes at exit: "
                        + writesAtExit);
    }

    /**
     * A completion that failed is not left behind to be attempted again: the recording is taken
     * before it is run, so one exit means one attempt however it turns out.
     */
    @Test
    void aFailedCompletionIsNotRetriedOnALaterExit() {
        // given
        persistPlan(RUN_ID, 2);
        DistributedRunnerContext context = claimGroup(RUN_ID, RUNNER_KEY);
        service.persistTestRunData(true, true, true, PLAN_COMMIT, "main",
                System.currentTimeMillis(), resultFor(SUITE_FIRST_PLAN, METHOD_FIRST_PLAN, 2, 0),
                context);
        dataStore.failCompleteGroup = true;
        int writesBeforeExit = dataStore.callOrder.size();
        DistributedRunCompletion.completePendingCompletions();
        assertTrue(dataStore.callOrder.subList(writesBeforeExit, dataStore.callOrder.size())
                        .contains("completeGroup"),
                "the first exit must have attempted the completion. Call order: "
                        + dataStore.callOrder);
        dataStore.failCompleteGroup = false;
        dataStore.callOrder.clear();

        // when
        DistributedRunCompletion.completePendingCompletions();

        // then
        assertEquals(Collections.<String>emptyList(), dataStore.callOrder,
                "a completion is attempted once per recording, not retried. Call order: "
                        + dataStore.callOrder);
    }

    /**
     * Build and persist a distributed run plan for the test's runners to claim from.
     *
     * @param runId the run identifier to plan under
     * @param groupCount how many groups the plan is split into
     */
    private void persistPlan(final String runId, final int groupCount) {
        List<DistributedRunGroup> groups = new ArrayList<>();
        Map<Integer, List<String>> suites = new HashMap<>();
        for (int i = 0; i < groupCount; i++) {
            groups.add(DistributedRunGroup.pending(runId, i, 1000L));
            suites.put(i, Arrays.asList("com.example.Suite" + i + "Test"));
        }
        DistributedRun run = DistributedRun.open(runId, "main", PLAN_COMMIT, groupCount, null,
                1000L * groupCount, 1234L);
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
     * as discovered, since the runner discovers every test class whichever ones a given test plan
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
        Set<String> discovered = new HashSet<>(Arrays.asList(SUITE_FIRST_PLAN, SUITE_SECOND_PLAN));
        discovered.addAll(failed);

        return new TestRunResult(trackers, failed, discovered, new HashSet<>(discovered),
                methodTrackers, new TestStats(), null, 1, suitesRan);
    }

    /**
     * Build the result a runner that executed nothing reports - what a surplus runner produces.
     *
     * @return an empty result for the surplus-runner path
     */
    private TestRunResult emptyResult() {
        return new TestRunResult(new HashMap<String, TestSuiteTracker>(), new HashSet<String>(),
                new HashSet<String>(), new HashSet<String>(),
                new HashMap<Integer, MethodImpactTracker>(), new TestStats(), null, 0, 0);
    }

    /**
     * An embedded-H2 {@link JdbcDataStore} that records the write calls these tests assert on and
     * can be told to fail the completion, which is how the JVM-exit failure path is exercised
     * without a broken database.
     */
    private static final class RecordingDataStore extends JdbcDataStore {

        private final List<String> callOrder = new ArrayList<>();
        private boolean failCompleteGroup;

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
         * Record and delegate the group completion, or fail it outright when the test has asked for
         * the JVM-exit failure path.
         *
         * @param runId the run the group belongs to
         * @param groupNumber the group's zero-based index within the run
         * @param runnerKey the calling runner's identity
         * @param completedAtMs UTC epoch millis of the completion
         * @param actualDurationMs measured test-execution time of this group
         * @param suitesRan number of suites the runner executed
         * @param suitesFailed number of this runner's failed suites
         * @return the updated group, or null when the claim is no longer live
         */
        @Override
        public DistributedRunGroup completeGroup(final String runId, final int groupNumber,
                                                 final String runnerKey, final long completedAtMs,
                                                 final long actualDurationMs, final int suitesRan,
                                                 final int suitesFailed) {
            callOrder.add("completeGroup");
            if (failCompleteGroup) {
                throw new IllegalStateException("the database went away as the JVM was exiting");
            }
            return super.completeGroup(runId, groupNumber, runnerKey, completedAtMs,
                    actualDurationMs, suitesRan, suitesFailed);
        }

        /**
         * Record and delegate the sealer election, so a test can assert that a runner which never
         * completed its group never stood for it.
         *
         * @param runId the run to elect within
         * @param runnerKey the calling runner's identity
         * @param sealedAtMs UTC epoch millis to record as the election time
         * @return true when this runner won the election
         */
        @Override
        public boolean electSealer(final String runId, final String runnerKey, final long sealedAtMs) {
            callOrder.add("electSealer");
            return super.electSealer(runId, runnerKey, sealedAtMs);
        }
    }
}
