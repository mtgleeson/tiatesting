package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock the persist-time "seal-last" invariant: the stored commit value (written by
 * {@link TestRunnerService#persistTestRunData}) must only be advanced AFTER every mapping write
 * has completed for that run. A crash anywhere before the seal leaves the stored commit value
 * at its prior setting, so the next Tia run will diff against the older commit and re-do the
 * impacted work rather than under-select.
 *
 * <p>Tests wrap an {@link JdbcDataStore} in a recording decorator. Failure-case tests throw from
 * a specific {@code persistX} method and assert the stored commit value didn't move. The
 * happy-path test asserts that {@code persistSealedRunData} (the seal bundle) is invoked after
 * every mapping write that precedes it, and that it is the only write that reaches
 * {@code persistCoreData} - a mapping run never seals via a separate {@code persistCoreData} call.
 */
class TestRunnerServiceSealOrderTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-seal-order-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())), BranchSchema.schemaName("test"));
        dataStore.getTiaData(true);

        // Seed a known prior commit value so we can assert it survives mid-persist crashes.
        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("prior-commit");
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);
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
     * Crash during the test-suite mapping write (the first mapping write in the sequence) must
     * leave the stored commit value at the prior setting.
     */
    @Test
    void crashDuringTestSuiteMapping_storedCommitValueRemainsPriorValue() {
        // given - a decorator that throws on persistTestSuites
        RecordingDataStore spy = new RecordingDataStore(dataStore);
        spy.throwOnPersistTestSuites = true;
        TestRunnerService service = new TestRunnerService(spy);

        // when - persist with a new commit; the throw should propagate
        TestRunResult result = makeResult();
        assertThrows(RuntimeException.class, () -> service.persistTestRunData(
                true, false, false, "new-commit", "main", System.currentTimeMillis(), result));

        // then - stored commit value is unchanged
        TiaData reloaded = dataStore.getTiaData(true);
        assertEquals("prior-commit", reloaded.getCommitValue(),
                "commit value must not advance when a mapping write fails");
    }

    /**
     * Crash during the failed-tests write must leave the stored commit value at the prior
     * setting. On recovery, the stale failed-tests set just means previously-failing tests are
     * re-run on the next attempt - self-correcting.
     */
    @Test
    void crashDuringFailedTests_storedCommitValueRemainsPriorValue() {
        // given
        RecordingDataStore spy = new RecordingDataStore(dataStore);
        spy.throwOnPersistTestSuitesFailed = true;
        TestRunnerService service = new TestRunnerService(spy);

        // when
        TestRunResult result = makeResult();
        assertThrows(RuntimeException.class, () -> service.persistTestRunData(
                true, false, false, "new-commit", "main", System.currentTimeMillis(), result));

        // then
        TiaData reloaded = dataStore.getTiaData(true);
        assertEquals("prior-commit", reloaded.getCommitValue());
    }

    /**
     * A stats-only run ({@code updateDBMapping=false}) must never advance the stored commit value
     * and must never reach the seal bundle. {@code sealRun} short-circuits before building the
     * bundle on this path, writing only the core row via {@code persistCoreData} - there is no
     * catalogue rewrite and no drain cleanup, matching today's stats-only behaviour. A regression
     * that routed this path through {@code persistSealedRunData} would clear and re-insert the
     * whole {@code tia_source_method} catalogue on every stats-only build even though nothing
     * about the mapping changed.
     */
    @Test
    void statsOnlyRun_routesThroughPersistCoreDataNotSealBundle_andCommitValueRemainsPriorValue() {
        // given
        RecordingDataStore spy = new RecordingDataStore(dataStore);
        TestRunnerService service = new TestRunnerService(spy);

        // when
        service.persistTestRunData(false, true, false, "new-commit", "main",
                System.currentTimeMillis(), makeResult());

        // then - the seal bundle is never invoked; the core row goes through persistCoreData instead
        assertEquals(0, Collections.frequency(spy.callOrder, "persistSealedRunData"),
                "a stats-only run must not go through the seal bundle");
        assertEquals(1, Collections.frequency(spy.callOrder, "persistCoreData"),
                "a stats-only run must write its core row via persistCoreData");

        // and - the method catalogue is never rewritten either: neither call frequency alone
        // would catch a regression that wrote it directly on this path outside the seal bundle
        assertEquals(0, Collections.frequency(spy.callOrder, "persistSourceMethods"),
                "a stats-only run must not rewrite the method catalogue");

        // and - a non-mapping run must not seal a new commit value
        TiaData reloaded = dataStore.getTiaData(true);
        assertEquals("prior-commit", reloaded.getCommitValue(),
                "a non-mapping run must not advance the stored commit value");
    }

    /**
     * Happy path: with no injected failure, the stored commit value advances to the new value
     * AND {@code persistSealedRunData} (the seal bundle) is invoked after every write that
     * precedes it. This is the regression-proof for the reorder: if anyone moves the seal bundle
     * back to the front, this test breaks. It also pins the bundle as the ONLY seal write: on a
     * mapping run {@code persistSealedRunData} is invoked exactly once and {@code persistCoreData}
     * exactly zero times, so a regression that added a separate {@code persistCoreData} seal after
     * the bundle would be caught here rather than only on an aborting run.
     */
    @Test
    void happyPath_persistSealedRunDataInvokedAfterAllMappingWrites() {
        // given
        RecordingDataStore spy = new RecordingDataStore(dataStore);
        TestRunnerService service = new TestRunnerService(spy);

        // when
        service.persistTestRunData(true, false, false, "new-commit", "main",
                System.currentTimeMillis(), makeResult());

        // then - commit value is sealed to the new value
        TiaData reloaded = dataStore.getTiaData(true);
        assertEquals("new-commit", reloaded.getCommitValue(),
                "happy path must advance the stored commit value");

        // and - persistSealedRunData was invoked AFTER every write that precedes the seal
        int sealIdx = spy.callOrder.indexOf("persistSealedRunData");
        assertTrue(sealIdx >= 0, "persistSealedRunData must be invoked");

        int suitesIdx = spy.callOrder.indexOf("persistTestSuites");
        int failedIdx = spy.callOrder.indexOf("persistTestSuitesFailed");
        assertTrue(suitesIdx >= 0 && suitesIdx < sealIdx,
                "persistTestSuites must be invoked before the seal. Call order: " + spy.callOrder);
        assertTrue(failedIdx >= 0 && failedIdx < sealIdx,
                "persistTestSuitesFailed must be invoked before the seal. Call order: " + spy.callOrder);

        // and - the bundle is the single seal write: exactly one seal, zero separate core writes
        assertEquals(1, Collections.frequency(spy.callOrder, "persistSealedRunData"),
                "a mapping run must seal exactly once, via the bundle");
        assertEquals(0, Collections.frequency(spy.callOrder, "persistCoreData"),
                "a mapping run must not also seal via a separate persistCoreData call");
    }

    /**
     * The seal bundle ({@code persistSealedRunData}) is the single write that advances the
     * commit value. A failure inside the bundle must leave the prior commit value in place, and
     * the catalogue / commit writes must never reach the data store through the old separate
     * {@code persistCoreData} / {@code persistSourceMethods} entry points.
     */
    @Test
    void sealBundleIsTheSingleWriteThatAdvancesTheCommit() {
        // given - a spy that fails inside the seal bundle
        RecordingDataStore spy = new RecordingDataStore(dataStore);
        spy.failInSealBundle = true;
        TestRunnerService service = new TestRunnerService(spy);

        // when
        assertThrows(RuntimeException.class, () -> service.persistTestRunData(true, true, false,
                "new-commit", "main", System.currentTimeMillis(), makeResult()));

        // then - the prior commit survived and the seal ran as one call, not two
        assertEquals("prior-commit", dataStore.getTiaCore().getCommitValue());
        assertEquals(0, Collections.frequency(spy.callOrder, "persistCoreData"),
                "the seal must go through persistSealedRunData, not persistCoreData");
        assertEquals(0, Collections.frequency(spy.callOrder, "persistSourceMethods"),
                "the catalogue must go through persistSealedRunData, not persistSourceMethods");
    }

    /**
     * The suite mapping and the failed-suite set are written ahead of the seal bundle - both are
     * safe to be ahead of the commit value, so they must complete before
     * {@code persistSealedRunData} is invoked.
     */
    @Test
    void suiteMappingAndFailedSetAreWrittenBeforeTheSealBundle() {
        // given
        RecordingDataStore spy = new RecordingDataStore(dataStore);
        TestRunnerService service = new TestRunnerService(spy);

        // when
        service.persistTestRunData(true, true, false, "new-commit", "main",
                System.currentTimeMillis(), makeResult());

        // then
        int sealIdx = spy.callOrder.indexOf("persistSealedRunData");
        assertTrue(sealIdx >= 0, "persistSealedRunData must be invoked");
        assertTrue(spy.callOrder.indexOf("persistTestSuites") < sealIdx,
                "suite mapping must be written before the seal. Call order: " + spy.callOrder);
        assertTrue(spy.callOrder.indexOf("persistTestSuitesFailed") < sealIdx,
                "the failed set must be written before the seal. Call order: " + spy.callOrder);
    }

    /**
     * A run that reaches the seal clears the unsealed flag from every suite it flagged; a run
     * whose {@code persistSealedRunData} call never runs the real bundle - {@code failInSealBundle}
     * throws from the {@link RecordingDataStore} spy before delegating, so
     * {@code clearUnsealedTestSuites(connection)} inside the real
     * {@link JdbcDataStore#persistSealedRunData} never executes - leaves those suites flagged so a
     * later run force-selects them. This proves only that a seal that never ran didn't clear; it
     * does not exercise the in-transaction clear itself or its rollback behaviour, which
     * {@code JdbcDataStoreSealedRunDataTest} covers directly against the real bundle. Both
     * {@code persistTestRunData} calls write the same suite ({@code com.example.SomeTest} from
     * {@link #makeResultWithMapping()}, which carries one impacted class so the write actually
     * reaches {@code persistTestSuiteClasses} and flags the suite unsealed), so the first call's
     * clear and the second call's write are exercised against the same row.
     */
    @Test
    void aSealedRunClearsTheUnsealedFlagAndAnAbortedOneDoesNot() {
        // given
        RecordingDataStore spy = new RecordingDataStore(dataStore);
        TestRunnerService service = new TestRunnerService(spy);

        // when - a run that seals
        service.persistTestRunData(true, true, false, "sealedCommit", "main",
                System.currentTimeMillis(), makeResultWithMapping());

        // then
        for (TestSuiteTracker tracker : dataStore.getTestSuitesTracked().values()) {
            assertFalse(tracker.isUnsealed(), tracker.getName() + " must be cleared by the seal");
        }

        // when - a run that fails inside the seal bundle
        spy.failInSealBundle = true;
        assertThrows(RuntimeException.class, () -> service.persistTestRunData(true, true, false,
                "abortedCommit", "main", System.currentTimeMillis(), makeResultWithMapping()));

        // then - the suites that ran stay flagged for a forced re-run
        assertTrue(dataStore.getTestSuitesTracked().values().stream().anyMatch(TestSuiteTracker::isUnsealed),
                "an aborted run must leave the suites it ran flagged");
    }

    /**
     * Build a {@link TestRunResult} whose sole suite tracker carries one impacted class/method, so
     * a mapping-update persist actually reaches {@link JdbcDataStore#persistTestSuiteClasses} and
     * flags the suite unsealed - {@link #makeResult()}'s empty-mapping tracker never does, since
     * the unsealed write only fires for suites with non-empty coverage this run.
     *
     * @return a {@link TestRunResult} with one mapped suite, suitable for exercising the unsealed
     *         flag write and clear
     */
    private TestRunResult makeResultWithMapping() {
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        TestSuiteTracker tracker = new TestSuiteTracker("com.example.SomeTest");
        tracker.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker("com/example/Some.java", new HashSet<>(Collections.singletonList(1)))));
        trackers.put("com.example.SomeTest", tracker);
        return new TestRunResult(
                trackers, new HashSet<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>(), new TestStats(), null, 0, 1);
    }

    /**
     * Build a minimal {@link TestRunResult} with no drain, no mapped classes and a single suite.
     * Suitable for tests that only care about write ordering / commit-value movement, not about
     * the mapping edges themselves - see {@link #makeResultWithMapping()} for a tracker that
     * carries coverage.
     *
     * @return a sparsely-populated TestRunResult suitable for persist tests
     */
    private TestRunResult makeResult() {
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        trackers.put("com.example.SomeTest", new TestSuiteTracker("com.example.SomeTest"));
        return new TestRunResult(
                trackers, new HashSet<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>(), new TestStats(), null, 0, 1);
    }

    /**
     * Delegating {@link DataStore} that records the order of write-method invocations and can
     * be configured to throw from a chosen method. Reads are passed through untouched.
     */
    private static class RecordingDataStore implements DataStore {
        private final DataStore delegate;
        final List<String> callOrder = new ArrayList<>();
        boolean throwOnPersistTestSuites = false;
        boolean throwOnPersistTestSuitesFailed = false;
        boolean failInSealBundle;

        RecordingDataStore(DataStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public TiaData getTiaData(boolean readFromDisk) { return delegate.getTiaData(readFromDisk); }
        @Override
        public TiaData getTiaCore() { return delegate.getTiaCore(); }
        @Override
        public Map<String, TestSuiteTracker> getTestSuitesTracked() { return delegate.getTestSuitesTracked(); }
        @Override
        public Map<Integer, MethodImpactTracker> getMethodsTracked() { return delegate.getMethodsTracked(); }
        @Override
        public Set<Integer> getUniqueMethodIdsTracked() { return delegate.getUniqueMethodIdsTracked(); }
        @Override
        public Map<String, Map<Integer, MethodImpactTracker>> getMethodsTrackedForFiles(Set<String> sourceFilenames) { return delegate.getMethodsTrackedForFiles(sourceFilenames); }
        @Override
        public Map<Integer, Set<String>> getTestSuitesForMethods(Set<Integer> methodIds) { return delegate.getTestSuitesForMethods(methodIds); }
        @Override
        public int getNumTestSuites() { return delegate.getNumTestSuites(); }
        @Override
        public int getNumSourceMethods() { return delegate.getNumSourceMethods(); }
        @Override
        public Set<String> getTestSuitesFailed() { return delegate.getTestSuitesFailed(); }

        @Override
        public void persistCoreData(TiaData tiaData) {
            callOrder.add("persistCoreData");
            delegate.persistCoreData(tiaData);
        }
        @Override
        public void persistTestSuitesFailed(Set<String> testSuitesFailed) {
            callOrder.add("persistTestSuitesFailed");
            if (throwOnPersistTestSuitesFailed) {
                throw new RuntimeException("simulated failure in persistTestSuitesFailed");
            }
            delegate.persistTestSuitesFailed(testSuitesFailed);
        }
        @Override
        public void clearUnsealedTestSuites() {
            callOrder.add("clearUnsealedTestSuites");
            delegate.clearUnsealedTestSuites();
        }
        @Override
        public void persistSourceMethods(Map<Integer, MethodImpactTracker> methodsTracked) {
            callOrder.add("persistSourceMethods");
            delegate.persistSourceMethods(methodsTracked);
        }
        @Override
        public void persistSealedRunData(SealedRunData sealedRunData) {
            callOrder.add("persistSealedRunData");
            if (failInSealBundle) {
                throw new RuntimeException("simulated failure in persistSealedRunData");
            }
            delegate.persistSealedRunData(sealedRunData);
        }
        @Override
        public void persistTestSuites(Map<String, TestSuiteTracker> testSuites) {
            callOrder.add("persistTestSuites");
            if (throwOnPersistTestSuites) {
                throw new RuntimeException("simulated failure in persistTestSuites");
            }
            delegate.persistTestSuites(testSuites);
        }
        @Override
        public void persistTestSuiteStatsOnly(Map<String, TestSuiteTracker> testSuites) {
            callOrder.add("persistTestSuiteStatsOnly");
            delegate.persistTestSuiteStatsOnly(testSuites);
        }
        @Override
        public void deleteTestSuites(Set<String> testSuites) {
            callOrder.add("deleteTestSuites");
            delegate.deleteTestSuites(testSuites);
        }
        @Override
        public Map<String, TrackedLibrary> readTrackedLibraries() { return delegate.readTrackedLibraries(); }
        @Override
        public void persistTrackedLibrary(TrackedLibrary trackedLibrary) {
            callOrder.add("persistTrackedLibrary");
            delegate.persistTrackedLibrary(trackedLibrary);
        }
        @Override
        public void deleteTrackedLibrary(String groupArtifact) {
            callOrder.add("deleteTrackedLibrary");
            delegate.deleteTrackedLibrary(groupArtifact);
        }
        @Override
        public List<PendingLibraryImpactedMethod> readPendingLibraryImpactedMethods(String groupArtifact) {
            return delegate.readPendingLibraryImpactedMethods(groupArtifact);
        }
        @Override
        public List<LibraryPublish> readLibraryPublishes(String groupArtifact) {
            return delegate.readLibraryPublishes(groupArtifact);
        }
        @Override
        public List<LibraryPublish> readAllLibraryPublishes() {
            return delegate.readAllLibraryPublishes();
        }
        @Override
        public Map<Integer, MethodImpactTracker> getMethodsTrackedForIds(Set<Integer> methodIds) {
            return delegate.getMethodsTrackedForIds(methodIds);
        }
        @Override
        public long persistLibraryPublish(LibraryPublish publish, Set<Integer> impactedMethodIds,
                                          List<PendingLibraryForcedSelection> forcedSelections) {
            return delegate.persistLibraryPublish(publish, impactedMethodIds, forcedSelections);
        }
        @Override
        public LibraryPublish lookupLibraryPublish(String groupArtifact, String jarHash, String version) {
            return delegate.lookupLibraryPublish(groupArtifact, jarHash, version);
        }
        @Override
        public List<PendingLibraryImpactedMethod> readAllPendingLibraryImpactedMethods() {
            return delegate.readAllPendingLibraryImpactedMethods();
        }
        @Override
        public void persistPendingLibraryImpactedMethods(PendingLibraryImpactedMethod pending) {
            callOrder.add("persistPendingLibraryImpactedMethods");
            delegate.persistPendingLibraryImpactedMethods(pending);
        }
        @Override
        public void deletePendingLibraryImpactedMethods(String groupArtifact, long publishSeq) {
            callOrder.add("deletePendingLibraryImpactedMethods");
            delegate.deletePendingLibraryImpactedMethods(groupArtifact, publishSeq);
        }
        @Override
        public List<PendingLibraryForcedSelection> readAllPendingLibraryForcedSelections() {
            return delegate.readAllPendingLibraryForcedSelections();
        }
        @Override
        public List<PendingLibraryForcedSelection> readPendingLibraryForcedSelections(String groupArtifact) {
            return delegate.readPendingLibraryForcedSelections(groupArtifact);
        }
        @Override
        public void deletePendingLibraryForcedSelections(String groupArtifact, long publishSeq) {
            delegate.deletePendingLibraryForcedSelections(groupArtifact, publishSeq);
        }
        @Override
        public void persistTestRunHistoryEntry(TestRunHistoryEntry entry) {
            callOrder.add("persistTestRunHistoryEntry");
            delegate.persistTestRunHistoryEntry(entry);
        }
        @Override
        public List<TestRunHistoryEntry> readTestRunHistory() { return delegate.readTestRunHistory(); }

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
