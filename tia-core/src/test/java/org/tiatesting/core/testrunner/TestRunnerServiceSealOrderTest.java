package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.h2.H2DataStore;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock the persist-time "seal-last" invariant: the stored commit value (written by
 * {@link TestRunnerService#persistTestRunData}) must only be advanced AFTER every mapping write
 * has completed for that run. A crash anywhere before the seal leaves the stored commit value
 * at its prior setting, so the next Tia run will diff against the older commit and re-do the
 * impacted work rather than under-select.
 *
 * <p>Tests wrap an {@link H2DataStore} in a recording decorator. Failure-case tests throw from
 * a specific {@code persistX} method and assert the stored commit value didn't move. The
 * happy-path test asserts that {@code persistCoreData} is invoked after every mapping write.
 */
class TestRunnerServiceSealOrderTest {

    private H2DataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-seal-order-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(tempDir.getAbsolutePath(), "test");
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
     * Crash during the methods-tracked write (the second mapping write) must leave the stored
     * commit value at the prior setting. The suite mapping has already updated at this point;
     * the orphan-skip logic at {@code TestRunnerService.updateMethodTracker} handles the partial
     * state on the next read.
     */
    @Test
    void crashDuringMethodsTracked_storedCommitValueRemainsPriorValue() {
        // given
        RecordingDataStore spy = new RecordingDataStore(dataStore);
        spy.throwOnPersistSourceMethods = true;
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
     * Crash during the library-impact drain (the last mapping-adjacent write before the seal)
     * must still leave the stored commit value at the prior setting. The pending library rows
     * remain in the DB; the next run re-drains them - idempotent.
     */
    @Test
    void crashDuringLibraryDrain_storedCommitValueRemainsPriorValue() {
        // given - seed a tracked library + a pending row so the drain has work to do
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, "0.9.0", null);
        dataStore.persistTrackedLibrary(lib);
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0.0", null, new HashSet<>(java.util.Arrays.asList(10))));

        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDrainedBatch("com.example:lib", "1.0.0");
        drainResult.setObservedLibraryState("com.example:lib", "1.0.0", null);

        RecordingDataStore spy = new RecordingDataStore(dataStore);
        spy.throwOnDeletePendingLibraryImpactedMethods = true;
        TestRunnerService service = new TestRunnerService(spy);

        // when
        TestRunResult result = makeResult(drainResult);
        assertThrows(RuntimeException.class, () -> service.persistTestRunData(
                true, false, false, "new-commit", "main", System.currentTimeMillis(), result));

        // then
        TiaData reloaded = dataStore.getTiaData(true);
        assertEquals("prior-commit", reloaded.getCommitValue());
    }

    /**
     * Happy path: with no injected failure, the stored commit value advances to the new value
     * AND {@code persistCoreData} (the seal) is invoked after every mapping write. This is the
     * regression-proof for the reorder: if anyone moves {@code updateTiaCoreData} back to the
     * front, this test breaks.
     */
    @Test
    void happyPath_persistCoreDataInvokedAfterAllMappingWrites() {
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

        // and - persistCoreData was invoked AFTER every mapping write
        int sealIdx = spy.callOrder.indexOf("persistCoreData");
        assertTrue(sealIdx >= 0, "persistCoreData must be invoked");

        int suitesIdx = spy.callOrder.indexOf("persistTestSuites");
        int methodsIdx = spy.callOrder.indexOf("persistSourceMethods");
        int failedIdx = spy.callOrder.indexOf("persistTestSuitesFailed");
        assertTrue(suitesIdx >= 0 && suitesIdx < sealIdx,
                "persistTestSuites must be invoked before the seal. Call order: " + spy.callOrder);
        assertTrue(methodsIdx >= 0 && methodsIdx < sealIdx,
                "persistSourceMethods must be invoked before the seal. Call order: " + spy.callOrder);
        assertTrue(failedIdx >= 0 && failedIdx < sealIdx,
                "persistTestSuitesFailed must be invoked before the seal. Call order: " + spy.callOrder);
    }

    /**
     * Build a minimal {@link TestRunResult} with no drain. Internal tests use
     * {@link #makeResult(LibraryImpactDrainResult)} when they need to exercise the drain path.
     *
     * @return a sparsely-populated TestRunResult suitable for persist tests
     */
    private TestRunResult makeResult() {
        return makeResult(null);
    }

    /**
     * Build a minimal {@link TestRunResult} with the provided drain result.
     *
     * @param drainResult the drain result to attach (or null for none)
     * @return a sparsely-populated TestRunResult suitable for persist tests
     */
    private TestRunResult makeResult(LibraryImpactDrainResult drainResult) {
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        trackers.put("com.example.SomeTest", new TestSuiteTracker("com.example.SomeTest"));
        return new TestRunResult(
                trackers, new HashSet<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>(), new TestStats(), drainResult, 0, 1);
    }

    /**
     * Delegating {@link DataStore} that records the order of write-method invocations and can
     * be configured to throw from a chosen method. Reads are passed through untouched.
     */
    private static class RecordingDataStore implements DataStore {
        private final DataStore delegate;
        final List<String> callOrder = new ArrayList<>();
        boolean throwOnPersistTestSuites = false;
        boolean throwOnPersistSourceMethods = false;
        boolean throwOnPersistTestSuitesFailed = false;
        boolean throwOnDeletePendingLibraryImpactedMethods = false;

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
        public void persistSourceMethods(Map<Integer, MethodImpactTracker> methodsTracked) {
            callOrder.add("persistSourceMethods");
            if (throwOnPersistSourceMethods) {
                throw new RuntimeException("simulated failure in persistSourceMethods");
            }
            delegate.persistSourceMethods(methodsTracked);
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
        public List<PendingLibraryImpactedMethod> readAllPendingLibraryImpactedMethods() {
            return delegate.readAllPendingLibraryImpactedMethods();
        }
        @Override
        public void persistPendingLibraryImpactedMethods(PendingLibraryImpactedMethod pending) {
            callOrder.add("persistPendingLibraryImpactedMethods");
            delegate.persistPendingLibraryImpactedMethods(pending);
        }
        @Override
        public void deletePendingLibraryImpactedMethods(String groupArtifact, String stampVersion) {
            callOrder.add("deletePendingLibraryImpactedMethods");
            if (throwOnDeletePendingLibraryImpactedMethods) {
                throw new RuntimeException("simulated failure in deletePendingLibraryImpactedMethods");
            }
            delegate.deletePendingLibraryImpactedMethods(groupArtifact, stampVersion);
        }
        @Override
        public void persistTestRunHistoryEntry(TestRunHistoryEntry entry) {
            callOrder.add("persistTestRunHistoryEntry");
            delegate.persistTestRunHistoryEntry(entry);
        }
        @Override
        public List<TestRunHistoryEntry> readTestRunHistory() { return delegate.readTestRunHistory(); }
    }
}
