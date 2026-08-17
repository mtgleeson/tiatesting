package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.LibraryPublish;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.PendingLibraryForcedSelection;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.SealedRunData;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;

import java.io.File;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that {@link TestRunnerService#persistTestRunData} routes test-suite persistence
 * correctly based on the {@code updateDBMapping} / {@code updateDBStats} flag combinations:
 *
 * <ul>
 *   <li><b>Both flags false</b> (history-only / SE-developer runs): neither
 *       {@link DataStore#persistTestSuites(Map)} nor
 *       {@link DataStore#persistTestSuiteStatsOnly(Map)} is invoked. The test-suite mapping
 *       table is left completely untouched.</li>
 *   <li><b>Stats-only</b> ({@code updateDBStats=true, updateDBMapping=false}):
 *       {@code persistTestSuiteStatsOnly} is invoked; {@code persistTestSuites} is NOT.
 *       The class/method-edge rows remain untouched.</li>
 *   <li><b>Primary build</b> ({@code updateDBMapping=true}): {@code persistTestSuites} is
 *       invoked (the full path including class/method-edge writes);
 *       {@code persistTestSuiteStatsOnly} is NOT.</li>
 * </ul>
 *
 * <p>The fix prevents needless delete-then-reinsert churn on the mapping rows during
 * non-mapping runs (history-only and stats-only).
 */
class TestRunnerServiceSuiteMappingPersistRoutingTest {

    private JdbcDataStore underlying;
    private CountingDataStore spy;
    private TestRunnerService service;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-routing-", "");
        tempDir.delete();
        tempDir.mkdirs();
        underlying = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())), BranchSchema.schemaName("test"));
        underlying.getTiaData(true);

        TiaData tiaData = underlying.getTiaData(true);
        tiaData.setCommitValue("prior");
        tiaData.setLastUpdated(Instant.now());
        underlying.persistCoreData(tiaData);

        spy = new CountingDataStore(underlying);
        service = new TestRunnerService(spy);
    }

    @AfterEach
    void tearDown() {
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * History-only runs (both flags false, history flag true) must not touch the test-suite
     * mapping table at all. Neither persist method should be invoked.
     */
    @Test
    void historyOnlyRun_neitherPersistMethodIsCalled() {
        // given - a run with both updateDBMapping and updateDBStats false, history on
        TestRunResult result = makeResultWithSuites("com.example.ATest", "com.example.BTest");

        // when
        service.persistTestRunData(false, false, true, "new-commit", "main",
                System.currentTimeMillis(), result, null);

        // then - neither persistTestSuites nor persistTestSuiteStatsOnly was invoked
        assertEquals(0, spy.persistTestSuitesCallCount,
                "history-only run must not call persistTestSuites");
        assertEquals(0, spy.persistTestSuiteStatsOnlyCallCount,
                "history-only run must not call persistTestSuiteStatsOnly either");
    }

    /**
     * Stats-only runs (updateDBStats=true, updateDBMapping=false) must invoke the
     * stats-only persist path so the mapping rows (tia_source_class /
     * tia_source_class_method) remain untouched.
     */
    @Test
    void statsOnlyRun_callsStatsOnlyPath_notFullPath() {
        // given
        TestRunResult result = makeResultWithSuites("com.example.ATest", "com.example.BTest");

        // when - stats on, mapping off
        service.persistTestRunData(false, true, false, "new-commit", "main",
                System.currentTimeMillis(), result, null);

        // then - stats-only path was used, full path was not
        assertEquals(1, spy.persistTestSuiteStatsOnlyCallCount,
                "stats-only run must call persistTestSuiteStatsOnly exactly once");
        assertEquals(0, spy.persistTestSuitesCallCount,
                "stats-only run must NOT call persistTestSuites (would rewrite mapping edges)");
    }

    /**
     * Primary-build runs (updateDBMapping=true) must invoke the full persist path so the
     * suite-to-source-class / method-edge tables get rewritten with this run's mapping.
     */
    @Test
    void primaryBuildRun_callsFullPath_notStatsOnly() {
        // given
        TestRunResult result = makeResultWithSuites("com.example.ATest", "com.example.BTest");

        // when - mapping on (stats can be either; here also on)
        service.persistTestRunData(true, true, false, "new-commit", "main",
                System.currentTimeMillis(), result, null);

        // then - full path was used, stats-only was not
        assertEquals(1, spy.persistTestSuitesCallCount,
                "primary-build run must call persistTestSuites exactly once");
        assertEquals(0, spy.persistTestSuiteStatsOnlyCallCount,
                "primary-build run must NOT call persistTestSuiteStatsOnly");
    }

    /**
     * Build a minimal {@link TestRunResult} carrying the named suites, no failures, no drain.
     *
     * @param suiteNames the suite names to add as trackers
     * @return a TestRunResult ready to feed into persistTestRunData
     */
    private TestRunResult makeResultWithSuites(String... suiteNames) {
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        for (String name : suiteNames) {
            trackers.put(name, new TestSuiteTracker(name));
        }
        return new TestRunResult(
                trackers, new HashSet<>(), new HashSet<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>(), new TestStats(), null, 0, suiteNames.length);
    }

    /**
     * Delegating {@link DataStore} that counts invocations of the two suite-persist methods.
     * All other methods pass through to the underlying H2 store.
     */
    private static class CountingDataStore implements DataStore {
        private final DataStore delegate;
        int persistTestSuitesCallCount = 0;
        int persistTestSuiteStatsOnlyCallCount = 0;

        CountingDataStore(DataStore delegate) {
            this.delegate = delegate;
        }

        @Override public TiaData getTiaData(boolean readFromDisk) { return delegate.getTiaData(readFromDisk); }
        @Override public TiaData getTiaCore() { return delegate.getTiaCore(); }
        @Override public Map<String, TestSuiteTracker> getTestSuitesTracked() { return delegate.getTestSuitesTracked(); }
        @Override public Map<Integer, MethodImpactTracker> getMethodsTracked() { return delegate.getMethodsTracked(); }
        @Override public Set<Integer> getUniqueMethodIdsTracked() { return delegate.getUniqueMethodIdsTracked(); }
        @Override public Map<String, Map<Integer, MethodImpactTracker>> getMethodsTrackedForFiles(Set<String> sourceFilenames) { return delegate.getMethodsTrackedForFiles(sourceFilenames); }
        @Override public Map<Integer, Set<String>> getTestSuitesForMethods(Set<Integer> methodIds) { return delegate.getTestSuitesForMethods(methodIds); }
        @Override public int getNumTestSuites() { return delegate.getNumTestSuites(); }
        @Override public int getNumSourceMethods() { return delegate.getNumSourceMethods(); }
        @Override public Set<String> getTestSuitesFailed() { return delegate.getTestSuitesFailed(); }
        @Override public void persistCoreData(TiaData tiaData) { delegate.persistCoreData(tiaData); }
        @Override public void persistTestSuitesFailed(Set<String> testSuitesFailed) { delegate.persistTestSuitesFailed(testSuitesFailed); }
        @Override public void clearUnsealedTestSuites() { delegate.clearUnsealedTestSuites(); }
        @Override public void persistSourceMethods(Map<Integer, MethodImpactTracker> methodsTracked) { delegate.persistSourceMethods(methodsTracked); }
        @Override public void persistSealedRunData(SealedRunData sealedRunData) { delegate.persistSealedRunData(sealedRunData); }

        @Override
        public void persistTestSuites(Map<String, TestSuiteTracker> testSuites) {
            persistTestSuitesCallCount++;
            delegate.persistTestSuites(testSuites);
        }

        @Override
        public void persistTestSuiteStatsOnly(Map<String, TestSuiteTracker> testSuites) {
            persistTestSuiteStatsOnlyCallCount++;
            delegate.persistTestSuiteStatsOnly(testSuites);
        }

        @Override public void deleteTestSuites(Set<String> testSuites) { delegate.deleteTestSuites(testSuites); }
        @Override public Map<String, TrackedLibrary> readTrackedLibraries() { return delegate.readTrackedLibraries(); }
        @Override public void persistTrackedLibrary(TrackedLibrary trackedLibrary) { delegate.persistTrackedLibrary(trackedLibrary); }
        @Override public void deleteTrackedLibrary(String groupArtifact) { delegate.deleteTrackedLibrary(groupArtifact); }
        @Override public List<LibraryPublish> readLibraryPublishes(String groupArtifact) { return delegate.readLibraryPublishes(groupArtifact); }
        @Override public List<LibraryPublish> readAllLibraryPublishes() { return delegate.readAllLibraryPublishes(); }
        @Override public Map<Integer, MethodImpactTracker> getMethodsTrackedForIds(Set<Integer> methodIds) { return delegate.getMethodsTrackedForIds(methodIds); }
        @Override public long persistLibraryPublish(LibraryPublish publish, Set<Integer> impactedMethodIds, List<PendingLibraryForcedSelection> forcedSelections) { return delegate.persistLibraryPublish(publish, impactedMethodIds, forcedSelections); }
        @Override public LibraryPublish lookupLibraryPublish(String groupArtifact, String jarHash, String version) { return delegate.lookupLibraryPublish(groupArtifact, jarHash, version); }
        @Override public List<PendingLibraryImpactedMethod> readPendingLibraryImpactedMethods(String groupArtifact) { return delegate.readPendingLibraryImpactedMethods(groupArtifact); }
        @Override public List<PendingLibraryImpactedMethod> readAllPendingLibraryImpactedMethods() { return delegate.readAllPendingLibraryImpactedMethods(); }
        @Override public void persistPendingLibraryImpactedMethods(PendingLibraryImpactedMethod pending) { delegate.persistPendingLibraryImpactedMethods(pending); }
        @Override public void deletePendingLibraryImpactedMethods(String groupArtifact, long publishSeq) { delegate.deletePendingLibraryImpactedMethods(groupArtifact, publishSeq); }
        @Override public List<PendingLibraryForcedSelection> readAllPendingLibraryForcedSelections() { return delegate.readAllPendingLibraryForcedSelections(); }
        @Override public List<PendingLibraryForcedSelection> readPendingLibraryForcedSelections(String groupArtifact) { return delegate.readPendingLibraryForcedSelections(groupArtifact); }
        @Override public void deletePendingLibraryForcedSelections(String groupArtifact, long publishSeq) { delegate.deletePendingLibraryForcedSelections(groupArtifact, publishSeq); }
        @Override public void persistTestRunHistoryEntry(TestRunHistoryEntry entry) { delegate.persistTestRunHistoryEntry(entry); }
        @Override public List<TestRunHistoryEntry> readTestRunHistory() { return delegate.readTestRunHistory(); }

        /**
         * Unsupported on this fake: this test suite never exercises distributed run plans, so a
         * silently-succeeding stub would let a future change start using distributed operations
         * from a non-distributed path without any test noticing.
         *
         * @param plan ignored
         * @throws UnsupportedOperationException always
         */
        @Override
        public void persistDistributedRunPlan(DistributedRunPlan plan) {
            throw new UnsupportedOperationException("not used by this test");
        }

        /**
         * Unsupported on this fake, for the same reason as {@link #persistDistributedRunPlan}.
         *
         * @param runId ignored
         * @return never returns
         * @throws UnsupportedOperationException always
         */
        @Override
        public DistributedRun readDistributedRun(String runId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        /**
         * Not used by this fake; distributed runs are not exercised here.
         *
         * @param runId ignored
         * @return always null
         */
        @Override
        public LibraryImpactDrainResult readDistributedRunDrainResult(String runId) { return null; }

        /**
         * Unsupported on this fake, for the same reason as {@link #persistDistributedRunPlan}.
         *
         * @param runId ignored
         * @return never returns
         * @throws UnsupportedOperationException always
         */
        @Override
        public List<DistributedRunGroup> readDistributedRunGroups(String runId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        /**
         * Unsupported on this fake, for the same reason as {@link #persistDistributedRunPlan}.
         *
         * @param runId ignored
         * @param groupNumber ignored
         * @return never returns
         * @throws UnsupportedOperationException always
         */
        @Override
        public List<String> readDistributedRunGroupSuites(String runId, int groupNumber) {
            throw new UnsupportedOperationException("not used by this test");
        }

        /**
         * Unsupported on this fake, for the same reason as {@link #persistDistributedRunPlan}.
         *
         * @return never returns
         * @throws UnsupportedOperationException always
         */
        @Override
        public List<DistributedRun> readAllDistributedRuns() {
            throw new UnsupportedOperationException("not used by this test");
        }

        /**
         * Unsupported on this fake, for the same reason as {@link #persistDistributedRunPlan}.
         *
         * @param runId ignored
         * @param runnerKey ignored
         * @param claimedAtMs ignored
         * @return never returns
         * @throws UnsupportedOperationException always
         */
        @Override
        public DistributedRunGroup claimNextPendingGroup(String runId, String runnerKey, long claimedAtMs) {
            throw new UnsupportedOperationException("not used by this test");
        }

        /**
         * Unsupported on this fake, for the same reason as {@link #persistDistributedRunPlan}.
         *
         * @param runId ignored
         * @param groupNumber ignored
         * @param runnerKey ignored
         * @param actualDurationMs ignored
         * @param suitesRan ignored
         * @param suitesFailed ignored
         * @param suitesObserved ignored
         * @return never returns
         * @throws UnsupportedOperationException always
         */
        @Override
        public boolean reportGroupProgress(String runId, int groupNumber, String runnerKey,
                                           long actualDurationMs, int suitesRan, int suitesFailed,
                                           int suitesObserved, long suitesDurationMs) {
            throw new UnsupportedOperationException("not used by this test");
        }

        /**
         * Unsupported on this fake, for the same reason as {@link #persistDistributedRunPlan}.
         *
         * @param runId ignored
         * @param groupNumber ignored
         * @param runnerKey ignored
         * @param completedAtMs ignored
         * @return never returns
         * @throws UnsupportedOperationException always
         */
        @Override
        public DistributedRunGroup completeGroup(String runId, int groupNumber, String runnerKey,
                                                 long completedAtMs) {
            throw new UnsupportedOperationException("not used by this test");
        }

        /**
         * Unsupported on this fake, for the same reason as {@link #persistDistributedRunPlan}.
         *
         * @param runId ignored
         * @param runnerKey ignored
         * @param sealedAtMs ignored
         * @return never returns
         * @throws UnsupportedOperationException always
         */
        @Override
        public boolean electSealer(String runId, String runnerKey, long sealedAtMs) {
            throw new UnsupportedOperationException("not used by this test");
        }

        /**
         * Unsupported on this fake, for the same reason as {@link #persistDistributedRunPlan}.
         *
         * @param runId ignored
         * @throws UnsupportedOperationException always
         */
        @Override
        public void markDistributedRunSealed(String runId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        /**
         * Unsupported on this fake, for the same reason as {@link #persistDistributedRunPlan}.
         *
         * @param runId ignored
         * @param methodsTracked ignored
         * @throws UnsupportedOperationException always
         */
        @Override
        public void persistStagedMethodTrackers(String runId, Map<Integer, MethodImpactTracker> methodsTracked) {
            throw new UnsupportedOperationException("not used by this test");
        }

        /**
         * Unsupported on this fake, for the same reason as {@link #persistDistributedRunPlan}.
         *
         * @param runId ignored
         * @return never returns
         * @throws UnsupportedOperationException always
         */
        @Override
        public Map<Integer, MethodImpactTracker> readStagedMethodTrackers(String runId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        /**
         * Unsupported on this fake, for the same reason as {@link #persistDistributedRunPlan}.
         *
         * @param runId ignored
         * @throws UnsupportedOperationException always
         */
        @Override
        public void deleteStagedMethodTrackers(String runId) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }
}
