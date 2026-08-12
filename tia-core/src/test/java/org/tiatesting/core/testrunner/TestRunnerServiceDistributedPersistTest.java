package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.distributed.DistributedRunCompletion;
import org.tiatesting.core.distributed.DistributedRunnerContext;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.SealedRunData;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock the distributed runner's half of {@link TestRunnerService#persistTestRunData}: a runner in a
 * distributed build writes its own suites' mapping rows, stages the method trackers it observed,
 * updates the failed set and then marks its group complete - and does none of the whole-build work
 * (no method catalogue, no seal, no commit advance, no history row), which belongs to whichever
 * runner finishes last.
 *
 * <p>Two properties here are correctness, not tidiness, and each has a test whose failure would be
 * a silent under-selection on the next build:
 * <ul>
 *   <li>the claim is re-verified before any write, so a runner from a superseded build writes
 *       nothing rather than leaving rows from an old commit under the commit a newer build has
 *       already sealed;</li>
 *   <li>{@code completeGroup} is the last write the runner makes, because it is what releases the
 *       barrier the sealer's catalogue rebuild waits on.</li>
 * </ul>
 *
 * <p>Tests run against a real embedded H2 {@link JdbcDataStore} subclassed to record the order of
 * its write calls, so both the ordering assertions and the persisted state are checked against the
 * same store the production code writes to.
 */
class TestRunnerServiceDistributedPersistTest {

    private static final String RUN_ID = "run-1";
    private static final String RUNNER_KEY = "runner-a";
    private static final String PLAN_COMMIT = "plan-commit";

    private RecordingDataStore dataStore;
    private TestRunnerService service;
    private File tempDir;

    /**
     * Create a fresh embedded H2 store in its own temp directory, seed a known prior commit value
     * so tests can assert a distributed runner never advances it, and build the service under test.
     *
     * @throws Exception if the temp directory cannot be created or the schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-distributed-persist-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new RecordingDataStore(tempDir);
        dataStore.getTiaData(true);

        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("prior-commit");
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);
        dataStore.callOrder.clear();

        service = new TestRunnerService(dataStore);
    }

    /**
     * Drop anything a test recorded for its runner's JVM exit, then close the store so embedded H2
     * releases its file lock, then remove the temp directory. A test process drives many runners
     * through one JVM, so a recording left pending would otherwise outlive the store it names.
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
     * A distributed runner stages the method trackers it observed rather than rebuilding the
     * method catalogue: the catalogue is rebuilt wholesale from the edge table and would drop
     * every method reachable only from a group that has not finished yet, so only the run's sealer
     * may write it, after the barrier.
     */
    @Test
    void distributedRunnerStagesItsMethodTrackersInsteadOfWritingTheCatalogue() {
        // given
        persistPlan(RUN_ID, 2);
        DistributedRunnerContext context = claimGroup(RUN_ID, RUNNER_KEY);

        // when
        service.persistTestRunData(true, true, true, "new-commit", "main",
                System.currentTimeMillis(), makeResult(), context);

        // then
        assertEquals(1, Collections.frequency(dataStore.callOrder, "persistStagedMethodTrackers"),
                "a distributed runner must stage the trackers it observed exactly once");
        Map<Integer, MethodImpactTracker> staged = dataStore.readStagedMethodTrackers(RUN_ID);
        assertEquals(1, staged.size(), "the run's staged trackers must carry this runner's method");
        assertEquals("com/example/Some.someMethod.()V", staged.get(42).getMethodName());

        // and - nothing wrote the catalogue
        assertEquals(0, Collections.frequency(dataStore.callOrder, "persistSourceMethods"),
                "a distributed runner must not rewrite the method catalogue");
        assertEquals(0, Collections.frequency(dataStore.callOrder, "persistSealedRunData"),
                "a distributed runner must not seal");
    }

    /**
     * A distributed runner neither seals nor advances the stored commit value, and never writes
     * the core row at all: the commit stamp describes the whole build, and the Tia-level run stats
     * are aggregated by the sealer from every group rather than incremented once per runner.
     */
    @Test
    void distributedRunnerDoesNotSealAndLeavesTheStoredCommitValueAlone() {
        // given
        persistPlan(RUN_ID, 2);
        DistributedRunnerContext context = claimGroup(RUN_ID, RUNNER_KEY);

        // when
        service.persistTestRunData(true, true, true, "new-commit", "main",
                System.currentTimeMillis(), makeResult(), context);

        // then
        assertEquals(0, Collections.frequency(dataStore.callOrder, "persistSealedRunData"),
                "only the run's sealer may write the seal bundle");
        assertEquals(0, Collections.frequency(dataStore.callOrder, "persistCoreData"),
                "a distributed runner must not write the core row");
        assertEquals("prior-commit", dataStore.getTiaData(true).getCommitValue(),
                "a distributed runner must not advance the stored commit value");
    }

    /**
     * A distributed runner writes no history row even when history logging is on: one build
     * produces one aggregated row, written by the sealer, rather than one row per runner.
     */
    @Test
    void distributedRunnerWritesNoHistoryRow() {
        // given
        persistPlan(RUN_ID, 2);
        DistributedRunnerContext context = claimGroup(RUN_ID, RUNNER_KEY);

        // when
        service.persistTestRunData(true, true, true, "new-commit", "main",
                System.currentTimeMillis(), makeResult(), context);

        // then
        assertEquals(0, Collections.frequency(dataStore.callOrder, "persistTestRunHistoryEntry"),
                "a distributed runner must not write a per-runner history row");
        assertTrue(dataStore.readTestRunHistory().isEmpty(),
                "the history table must be empty until the sealer writes the build's row");
    }

    /**
     * A distributed runner still writes the two things that are safe ahead of the stored commit:
     * its own suites' mapping rows and the incrementally-maintained failed-suite set.
     */
    @Test
    void distributedRunnerWritesItsSuiteMappingAndFailedSet() {
        // given
        persistPlan(RUN_ID, 2);
        DistributedRunnerContext context = claimGroup(RUN_ID, RUNNER_KEY);

        // when
        service.persistTestRunData(true, true, true, "new-commit", "main",
                System.currentTimeMillis(), makeResult(), context);

        // then
        assertTrue(dataStore.getTestSuitesTracked().containsKey("com.example.SomeTest"),
                "the runner's suite mapping row must be written");
        assertTrue(dataStore.getTestSuitesFailed().contains("com.example.FailedTest"),
                "the runner's failed suites must be added to the stored failed set");
    }

    /**
     * {@code completeGroup} is the last write the runner makes. Completing the group is what
     * releases the barrier, so a group marked complete before its mapping rows land would let the
     * sealer rebuild the catalogue from an edge set still missing them - silent under-selection.
     * It comes from the runner's JVM exit rather than from the persist, so the run of writes it has
     * to follow is every test plan's, not just the last one's.
     */
    @Test
    void distributedRunnerCompletesItsGroupAfterEveryMappingWrite() {
        // given
        persistPlan(RUN_ID, 2);
        DistributedRunnerContext context = claimGroup(RUN_ID, RUNNER_KEY);

        // when
        service.persistTestRunData(true, true, true, "new-commit", "main",
                System.currentTimeMillis(), makeResult(), context);
        DistributedRunCompletion.completePendingCompletions();

        // then
        int completeIdx = dataStore.callOrder.indexOf("completeGroup");
        assertTrue(completeIdx >= 0, "the runner must complete its group. Call order: " + dataStore.callOrder);
        assertEquals(dataStore.callOrder.size() - 1, completeIdx,
                "completeGroup must be the runner's last write. Call order: " + dataStore.callOrder);
        assertTrue(dataStore.callOrder.indexOf("persistTestSuites") < completeIdx,
                "the suite mapping must be written before the group is completed. Call order: " + dataStore.callOrder);
        assertTrue(dataStore.callOrder.indexOf("persistTestSuitesFailed") < completeIdx,
                "the failed set must be written before the group is completed. Call order: " + dataStore.callOrder);
        assertTrue(dataStore.callOrder.indexOf("persistStagedMethodTrackers") < completeIdx,
                "the trackers must be staged before the group is completed. Call order: " + dataStore.callOrder);
    }

    /**
     * The completed group carries the measurements the sealer aggregates into the build's single
     * history row: this runner's test-execution duration and its ran / failed suite counters.
     */
    @Test
    void completedGroupRecordsTheRunnersDurationAndCounters() {
        // given
        persistPlan(RUN_ID, 2);
        DistributedRunnerContext context = claimGroup(RUN_ID, RUNNER_KEY);
        long runStart = System.currentTimeMillis() - 4000L;

        // when
        service.persistTestRunData(true, true, true, "new-commit", "main", runStart,
                makeResult(), context);
        DistributedRunCompletion.completePendingCompletions();

        // then
        DistributedRunGroup group = readGroup(RUN_ID, 0);
        assertEquals(DistributedRunGroupStatus.COMPLETED, group.getStatus());
        assertEquals(RUNNER_KEY, group.getRunnerKey());
        assertNotNull(group.getActualDurationMs());
        assertTrue(group.getActualDurationMs() >= 4000L,
                "the recorded duration must be this runner's test-execution time, was "
                        + group.getActualDurationMs());
        assertEquals(2, group.getSuitesRan(), "the group must record the suites this runner ran");
        assertEquals(1, group.getSuitesFailed(), "the group must record this runner's failed suites");
    }

    /**
     * The straggler protection: a runner whose plan rows a newer build's plan write has already
     * cleared writes nothing at all - no suite rows, no staged trackers, no failed set and no
     * completion. Persisting anyway would leave mapping rows from the superseded build's commit
     * under the commit the newer build seals.
     */
    @Test
    void runnerWhoseClaimIsGoneWritesNothing() {
        // given - the claim is taken, then a newer build's plan supersedes this run
        persistPlan(RUN_ID, 2);
        DistributedRunnerContext context = claimGroup(RUN_ID, RUNNER_KEY);
        persistPlan("run-2", 1);
        dataStore.callOrder.clear();

        // when
        service.persistTestRunData(true, true, true, "new-commit", "main",
                System.currentTimeMillis(), makeResult(), context);

        // then
        assertEquals(Collections.<String>emptyList(), dataStore.callOrder,
                "a runner whose claim is gone must make no writes at all");
        assertFalse(dataStore.getTestSuitesTracked().containsKey("com.example.SomeTest"),
                "no suite mapping row may be written for a superseded run");
        assertTrue(dataStore.readStagedMethodTrackers(RUN_ID).isEmpty(),
                "no trackers may be staged for a superseded run");
        assertTrue(dataStore.getTestSuitesFailed().isEmpty(),
                "the failed set must not be touched by a superseded run");
    }

    /**
     * A runner whose group is now held by another runner key - the same claim being dead, seen
     * from the other direction - also writes nothing, since the guard is on the claim's identity
     * and not merely on the run existing.
     */
    @Test
    void runnerWhoseGroupIsHeldByAnotherRunnerWritesNothing() {
        // given - the context names a group that runner-b holds
        persistPlan(RUN_ID, 2);
        claimGroup(RUN_ID, "runner-b");
        DistributedRunnerContext context = DistributedRunnerContext.forClaimedGroup(RUN_ID, RUNNER_KEY, 0);
        dataStore.callOrder.clear();

        // when
        service.persistTestRunData(true, true, true, "new-commit", "main",
                System.currentTimeMillis(), makeResult(), context);

        // then
        assertEquals(Collections.<String>emptyList(), dataStore.callOrder,
                "a runner that does not hold the group must make no writes at all");
        assertEquals(DistributedRunGroupStatus.CLAIMED, readGroup(RUN_ID, 0).getStatus(),
                "the holder's claim must be left untouched");
    }

    /**
     * A surplus runner - one a pipeline fanned out wider than the plan's group count, which
     * claimed no group and therefore ran nothing - writes nothing. It has no group to complete and
     * no coverage worth merging, and persisting the tracked-suite map it read would rewrite rows
     * it did not produce.
     */
    @Test
    void surplusRunnerWritesNothing() {
        // given
        persistPlan(RUN_ID, 1);
        claimGroup(RUN_ID, "runner-b");
        DistributedRunnerContext context = DistributedRunnerContext.surplusRunner(RUN_ID, RUNNER_KEY);
        dataStore.callOrder.clear();

        // when
        service.persistTestRunData(true, true, true, "new-commit", "main",
                System.currentTimeMillis(), makeEmptyResult(), context);

        // then
        assertEquals(Collections.<String>emptyList(), dataStore.callOrder,
                "a surplus runner must make no writes at all");
    }

    /**
     * The single-host flow is unchanged by the distributed branch: a null context still seals in
     * one bundle, advances the stored commit value and writes its own history row. A regression
     * that routed ordinary builds down the runner path would show up here as an unadvanced commit.
     */
    @Test
    void nonDistributedRunStillSealsAndWritesItsHistoryRow() {
        // given - no distributed context at all

        // when
        service.persistTestRunData(true, true, true, "new-commit", "main",
                System.currentTimeMillis(), makeResult(), null);

        // then
        assertEquals(1, Collections.frequency(dataStore.callOrder, "persistSealedRunData"),
                "a single-host run must still seal exactly once");
        assertEquals("new-commit", dataStore.getTiaData(true).getCommitValue(),
                "a single-host run must still advance the stored commit value");
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
        assertEquals(1, history.size(), "a single-host run must still write its own history row");
        assertEquals(0, Collections.frequency(dataStore.callOrder, "persistStagedMethodTrackers"),
                "a single-host run must not stage trackers");
        assertEquals(0, Collections.frequency(dataStore.callOrder, "completeGroup"),
                "a single-host run has no group to complete");
    }

    /**
     * Build and persist a distributed run plan, which also clears any previously planned run - the
     * supersession a straggler runner has to survive.
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
     * Claim a group for a runner key and wrap the result in the context a runner hands to the
     * persist, so tests start from the state a real runner reaches before it runs its tests.
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
     * Build a run result shaped like one group's worth of work: one mapped suite carrying coverage
     * (so the mapping write actually reaches the edge tables), one method tracker to stage, one
     * failed suite, and two suites reported as run this attempt.
     *
     * @return a populated result for the distributed persist path
     */
    private TestRunResult makeResult() {
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        TestSuiteTracker tracker = new TestSuiteTracker("com.example.SomeTest");
        tracker.setClassesImpacted(Collections.singletonList(new ClassImpactTracker(
                "com/example/Some.java", new HashSet<>(Collections.singletonList(42)))));
        trackers.put("com.example.SomeTest", tracker);

        Map<Integer, MethodImpactTracker> methodTrackers = new HashMap<>();
        methodTrackers.put(42, new MethodImpactTracker("com/example/Some.someMethod.()V", 1, 10));

        Set<String> failed = new HashSet<>(Collections.singletonList("com.example.FailedTest"));
        Set<String> runnerSuites = new HashSet<>(Arrays.asList("com.example.SomeTest",
                "com.example.FailedTest"));
        Set<String> selected = new HashSet<>(Arrays.asList("com.example.SomeTest",
                "com.example.FailedTest"));

        return new TestRunResult(trackers, failed, runnerSuites, selected, methodTrackers,
                new TestStats(), null, 3, 2);
    }

    /**
     * Build the result a runner that executed nothing produces - what a surplus runner reports.
     *
     * @return an empty result for the surplus-runner path
     */
    private TestRunResult makeEmptyResult() {
        return new TestRunResult(new HashMap<String, TestSuiteTracker>(), new HashSet<String>(),
                new HashSet<String>(), new HashSet<String>(),
                new HashMap<Integer, MethodImpactTracker>(), new TestStats(), null, 0, 0);
    }

    /**
     * An embedded-H2 {@link JdbcDataStore} that records the order of the write calls this test
     * asserts on. Subclassing rather than decorating keeps every other operation - including the
     * distributed plan, claim and staging reads these tests depend on - working against the real
     * store with no delegation boilerplate to drift out of date.
     */
    private static final class RecordingDataStore extends JdbcDataStore {

        private final List<String> callOrder = new ArrayList<>();

        /**
         * Open an embedded H2 store under a test-owned directory, in the same per-branch schema
         * the other persistence tests use.
         *
         * @param databaseDir the directory to hold the embedded database files
         */
        RecordingDataStore(final File databaseDir) {
            super(new H2Dialect(),
                    new H2ConnectionProvider(H2ConnectionSettings.embedded(databaseDir.getAbsolutePath())),
                    BranchSchema.schemaName("test"));
        }

        /**
         * Record and delegate the core-row write.
         *
         * @param tiaData the core data to write
         */
        @Override
        public void persistCoreData(final TiaData tiaData) {
            callOrder.add("persistCoreData");
            super.persistCoreData(tiaData);
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
         * Record and delegate the stats-only suite write.
         *
         * @param testSuites the suites whose stats columns to write
         */
        @Override
        public void persistTestSuiteStatsOnly(final Map<String, TestSuiteTracker> testSuites) {
            callOrder.add("persistTestSuiteStatsOnly");
            super.persistTestSuiteStatsOnly(testSuites);
        }

        /**
         * Record and delegate the failed-suite set write.
         *
         * @param testSuitesFailed the failed suite names to store
         */
        @Override
        public void persistTestSuitesFailed(final Set<String> testSuitesFailed) {
            callOrder.add("persistTestSuitesFailed");
            super.persistTestSuitesFailed(testSuitesFailed);
        }

        /**
         * Record and delegate the method catalogue write, which no distributed runner may make.
         *
         * @param methodsTracked the catalogue to write
         */
        @Override
        public void persistSourceMethods(final Map<Integer, MethodImpactTracker> methodsTracked) {
            callOrder.add("persistSourceMethods");
            super.persistSourceMethods(methodsTracked);
        }

        /**
         * Record and delegate the seal bundle, which only a single-host run or a run's elected
         * sealer may write.
         *
         * @param sealedRunData the bundle to write
         */
        @Override
        public void persistSealedRunData(final SealedRunData sealedRunData) {
            callOrder.add("persistSealedRunData");
            super.persistSealedRunData(sealedRunData);
        }

        /**
         * Record and delegate the history-row write.
         *
         * @param entry the history entry to write
         */
        @Override
        public void persistTestRunHistoryEntry(final TestRunHistoryEntry entry) {
            callOrder.add("persistTestRunHistoryEntry");
            super.persistTestRunHistoryEntry(entry);
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
         * Record and delegate the group completion, the write that must come last.
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
            return super.completeGroup(runId, groupNumber, runnerKey, completedAtMs,
                    actualDurationMs, suitesRan, suitesFailed);
        }

        /**
         * Record and delegate the deletion of suites the runner no longer discovers.
         *
         * @param testSuites the suite names to delete
         */
        @Override
        public void deleteTestSuites(final Set<String> testSuites) {
            callOrder.add("deleteTestSuites");
            super.deleteTestSuites(testSuites);
        }
    }
}
