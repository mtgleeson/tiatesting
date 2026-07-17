package org.tiatesting.core.diff.diffanalyze.selector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.library.LibraryMetadataReader;
import org.tiatesting.core.library.ResolvedSourceProjectLibrary;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.LibraryBuildMetadata;
import org.tiatesting.core.model.LibraryPublish;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link TestSelector#selectTestsToIgnore} only writes to the mapping DB when
 * {@code updateDBMapping=true}. Non-primary builds (a developer's local workspace, a CI job not
 * designated as the primary mapping owner) must run test selection without mutating the shared
 * {@code tia_library} or {@code tia_pending_library_impacted_method} tables.
 *
 * <p>Two gates are covered:
 * <ul>
 *     <li><b>Reconcile gate</b> - {@code TrackedLibraryReconciler} must not insert/update/delete
 *         {@code tia_library} rows when the run isn't the mapping owner.</li>
 *     <li><b>No app-side stamping</b> - the app run must never persist pending stamp rows;
 *         stamping is owned by the library's publish task under publish-time stamping.</li>
 * </ul>
 */
class TestSelectorUpdateDBMappingGateTest {

    private H2DataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-gate-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
        dataStore.getTiaData(true);
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
     * Non-primary build with a never-seen library declared in {@code tiaSourceLibs}: the reconciler
     * would normally insert a new {@code tia_library} row. Must not happen when updateDBMapping=false.
     */
    @Test
    void reconcileSkippedWhenUpdateDBMappingFalse() {
        LibraryImpactAnalysisConfig libraryConfig = libraryConfigFor("com.example:lib", "/projects/lib");

        TestSelector testSelector = new TestSelector(dataStore);
        testSelector.selectTestsToIgnore(emptyDiffsVcsReader(), Collections.emptyList(),
                Collections.emptyList(), false, libraryConfig, null, false);

        assertTrue(dataStore.readTrackedLibraries().isEmpty(),
                "Non-primary build must not insert tia_library rows.");
    }

    /**
     * Positive control: primary build with the same setup must reconcile and insert the row.
     */
    @Test
    void reconcileRunsWhenUpdateDBMappingTrue() {
        LibraryImpactAnalysisConfig libraryConfig = libraryConfigFor("com.example:lib", "/projects/lib");

        TestSelector testSelector = new TestSelector(dataStore);
        testSelector.selectTestsToIgnore(emptyDiffsVcsReader(), Collections.emptyList(),
                Collections.emptyList(), false, libraryConfig, null, true);

        assertTrue(dataStore.readTrackedLibraries().containsKey("com.example:lib"),
                "Primary build must insert the declared library on reconcile.");
    }

    /**
     * Non-primary build with a removed library (declared in DB but not in current config): the
     * reconciler would normally delete the row. Must not happen when updateDBMapping=false.
     */
    @Test
    void reconcileSkipsRemovalWhenUpdateDBMappingFalse() {
        TrackedLibrary preExisting = new TrackedLibrary("com.example:gone", "/projects/gone", null);
        dataStore.persistTrackedLibrary(preExisting);

        LibraryImpactAnalysisConfig libraryConfig = libraryConfigFor("com.example:lib", "/projects/lib");

        TestSelector testSelector = new TestSelector(dataStore);
        testSelector.selectTestsToIgnore(emptyDiffsVcsReader(), Collections.emptyList(),
                Collections.emptyList(), false, libraryConfig, null, false);

        assertTrue(dataStore.readTrackedLibraries().containsKey("com.example:gone"),
                "Non-primary build must not delete tia_library rows even when config drops a library.");
    }

    /**
     * A library diff on the app run must never produce a stamp - stamping is owned by the
     * library's publish task under publish-time stamping. Verified for a non-primary run via a
     * {@link CountingDataStore}; the primary-run counterpart lives in
     * {@code TestSelectorLibraryPreviewTest}.
     */
    @Test
    void stampPersistSkippedWhenUpdateDBMappingFalse() {
        TrackedLibrary tracked = new TrackedLibrary("com.example:lib", "/projects/lib",
                "/projects/lib/src/main/java");
        dataStore.persistTrackedLibrary(tracked);
        seedStoredCommit("abc123");

        CountingDataStore counting = new CountingDataStore(dataStore);
        LibraryImpactAnalysisConfig libraryConfig = libraryConfigFor("com.example:lib", "/projects/lib");

        TestSelector testSelector = new TestSelector(counting);
        testSelector.selectTestsToIgnore(libraryDiffVcsReader(), Collections.emptyList(),
                Collections.emptyList(), false, libraryConfig, null, false);

        assertEquals(0, counting.persistPendingCalls.get(),
                "Non-primary build must not call persistPendingLibraryImpactedMethods.");
        assertEquals(0, counting.persistTrackedLibraryCalls.get(),
                "Non-primary build must not call persistTrackedLibrary (no reconcile).");
        assertEquals(0, counting.deleteTrackedLibraryCalls.get(),
                "Non-primary build must not call deleteTrackedLibrary.");
    }

    /**
     * Preview path (Maven {@code tia-select-tests} mojo / Gradle {@code tia-select-tests} task):
     * {@code selectTestsToIgnore} is called with a real {@link LibraryImpactAnalysisConfig} and
     * {@code updateDBMapping=false}. A pending stamp contained in the build the app resolves
     * (identified via the publish ledger) must be drained into {@code testsToRun}, but the drain
     * must not mutate the DB - post-test cleanup is the primary build's job.
     */
    @Test
    void previewDrainsPendingBatchIntoTestsToRunWithoutDbWrites() {
        TrackedLibrary tracked = new TrackedLibrary("com.example:lib", "/projects/lib",
                "/projects/lib/src/main/java");
        dataStore.persistTrackedLibrary(tracked);
        seedStoredCommitWithTestMapping("abc123", 42, "com.example.LibTest");
        // Publish-time stamp: ledger row at version 1.0.0 with method 42 stamped. The stub
        // metadata reader resolves the library at 1.0.0 with no jar, so the drain identifies the
        // build via the release-version fallback.
        dataStore.persistLibraryPublish(new LibraryPublish("com.example:lib", "1.0.0", null,
                "lib-commit", 1000L), new HashSet<>(Collections.singletonList(42)));

        CountingDataStore counting = new CountingDataStore(dataStore);
        LibraryImpactAnalysisConfig libraryConfig = libraryConfigFor("com.example:lib", "/projects/lib");

        TestSelector testSelector = new TestSelector(counting);
        TestSelectorResult result = testSelector.selectTestsToIgnore(emptyDiffsVcsReader(),
                Collections.emptyList(), Collections.emptyList(), false, libraryConfig, null, false);

        assertTrue(result.getTestsToRun().contains("com.example.LibTest"),
                "Preview must surface tests resolved from drained pending batches.");

        assertEquals(0, counting.persistPendingCalls.get(),
                "Preview must not write pending rows.");
        assertEquals(0, counting.persistTrackedLibraryCalls.get(),
                "Preview must not advance tracked-library state.");
        assertEquals(0, counting.deleteTrackedLibraryCalls.get(),
                "Preview must not delete tracked-library rows.");
        assertEquals(0, counting.deletePendingCalls.get(),
                "Preview must not delete drained pending rows - that's the primary build's job.");

        assertEquals(1, dataStore.readPendingLibraryImpactedMethods("com.example:lib").size(),
                "Pending row must remain in DB after preview.");
        assertNull(dataStore.readTrackedLibraries().get("com.example:lib").getLastAppliedSeq(),
                "Tracked library applied seq must remain unchanged after preview.");
    }

    private LibraryImpactAnalysisConfig libraryConfigFor(String coordinate, String projectDir) {
        Map<String, String> projectDirs = new HashMap<>();
        projectDirs.put(coordinate, projectDir);
        return new LibraryImpactAnalysisConfig(
                Collections.singletonList(coordinate),
                projectDirs,
                "/projects/source",
                new StubMetadataReader());
    }

    private void seedStoredCommit(String commit) {
        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue(commit);
        tiaData.setLastUpdated(Instant.now());
        Map<String, TestSuiteTracker> testSuites = new HashMap<>();
        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        tiaData.setTestSuitesTracked(testSuites);
        tiaData.setMethodsTracked(methods);
        dataStore.persistCoreData(tiaData);
        dataStore.persistTestSuites(testSuites);
        dataStore.persistSourceMethods(methods);
    }

    private void seedStoredCommitWithTestMapping(String commit, int methodId, String testSuiteName) {
        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue(commit);
        tiaData.setLastUpdated(Instant.now());

        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        methods.put(methodId, new MethodImpactTracker("com.example.Method" + methodId, 1, 10));

        Map<String, TestSuiteTracker> testSuites = new HashMap<>();
        TestSuiteTracker tracker = new TestSuiteTracker(testSuiteName);
        tracker.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker("com/example/Lib.java",
                        new HashSet<>(Collections.singletonList(methodId)))));
        testSuites.put(testSuiteName, tracker);

        tiaData.setTestSuitesTracked(testSuites);
        tiaData.setMethodsTracked(methods);
        dataStore.persistCoreData(tiaData);
        dataStore.persistTestSuites(testSuites);
        dataStore.persistSourceMethods(methods);
    }

    private VCSReader emptyDiffsVcsReader() {
        return new StubVCSReader(Collections.emptySet());
    }

    private VCSReader libraryDiffVcsReader() {
        SourceFileDiffContext diff = new SourceFileDiffContext(
                "/projects/lib/src/main/java/com/example/Lib.java",
                "/projects/lib/src/main/java/com/example/Lib.java",
                ChangeType.MODIFY);
        Set<SourceFileDiffContext> diffs = new HashSet<>();
        diffs.add(diff);
        return new StubVCSReader(diffs);
    }

    private static class StubVCSReader implements VCSReader {
        private final Set<SourceFileDiffContext> diffs;

        StubVCSReader(Set<SourceFileDiffContext> diffs) {
            this.diffs = diffs;
        }

        @Override
        public String getBranchName() {
            return "test";
        }

        @Override
        public String getHeadCommit() {
            return "head";
        }

        @Override
        public Set<SourceFileDiffContext> getDiffFiles(String baseChangeNum, List<String> sourceFilesDirs,
                                                       List<String> testFilesDirs, boolean checkLocalChanges) {
            return diffs;
        }

        @Override
        public void loadContentForDiffs(java.util.Collection<SourceFileDiffContext> diffsToLoad, String baseChangeNum,
                                        boolean checkLocalChanges) {
            // no-op: this stub's diffs carry whatever content the test needs already
        }

        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            return new HashSet<>();
        }

        @Override
        public void close() {
        }
    }

    /**
     * Stub reader: returns "1.0.0" as both declared and resolved version, no JAR path. Sufficient
     * for reconcile (insert path reads declared version under BUMP_AFTER_RELEASE only - no-op there)
     * and for the stamp recorder's declared-version read.
     */
    private static class StubMetadataReader implements LibraryMetadataReader {
        @Override
        public List<LibraryBuildMetadata> readLibraryBuildMetadata(String libraryProjectDir, List<String> coordinates) {
            List<LibraryBuildMetadata> result = new ArrayList<>();
            for (String coord : coordinates) {
                result.add(new LibraryBuildMetadata(coord, "1.0.0"));
            }
            return result;
        }

        @Override
        public List<ResolvedSourceProjectLibrary> resolveLibrariesInSourceProject(String sourceProjectDir,
                                                                                  List<String> coordinates) {
            List<ResolvedSourceProjectLibrary> result = new ArrayList<>();
            for (String coord : coordinates) {
                result.add(new ResolvedSourceProjectLibrary(coord, "1.0.0", null));
            }
            return result;
        }

        @Override
        public List<String> readSourceDirectories(String libraryProjectDir) {
            return Collections.emptyList();
        }
    }

    /**
     * Delegating {@link DataStore} that counts mutating calls relevant to the gate. All other
     * calls pass straight through to the wrapped store so {@link TestSelector} sees realistic data.
     */
    private static class CountingDataStore implements DataStore {
        private final DataStore delegate;
        final AtomicInteger persistTrackedLibraryCalls = new AtomicInteger();
        final AtomicInteger deleteTrackedLibraryCalls = new AtomicInteger();
        final AtomicInteger persistPendingCalls = new AtomicInteger();
        final AtomicInteger deletePendingCalls = new AtomicInteger();

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
        @Override public void persistSourceMethods(Map<Integer, MethodImpactTracker> methodsTracked) { delegate.persistSourceMethods(methodsTracked); }
        @Override public void persistTestSuites(Map<String, TestSuiteTracker> testSuites) { delegate.persistTestSuites(testSuites); }
        @Override public void persistTestSuiteStatsOnly(Map<String, TestSuiteTracker> testSuites) { delegate.persistTestSuiteStatsOnly(testSuites); }
        @Override public void deleteTestSuites(Set<String> testSuites) { delegate.deleteTestSuites(testSuites); }
        @Override public Map<String, TrackedLibrary> readTrackedLibraries() { return delegate.readTrackedLibraries(); }
        @Override public List<LibraryPublish> readLibraryPublishes(String groupArtifact) { return delegate.readLibraryPublishes(groupArtifact); }
        @Override public List<LibraryPublish> readAllLibraryPublishes() { return delegate.readAllLibraryPublishes(); }
        @Override public Map<Integer, MethodImpactTracker> getMethodsTrackedForIds(Set<Integer> methodIds) { return delegate.getMethodsTrackedForIds(methodIds); }
        @Override public long persistLibraryPublish(LibraryPublish publish, Set<Integer> impactedMethodIds) { return delegate.persistLibraryPublish(publish, impactedMethodIds); }
        @Override public LibraryPublish lookupLibraryPublish(String groupArtifact, String jarHash, String version) { return delegate.lookupLibraryPublish(groupArtifact, jarHash, version); }
        @Override public List<PendingLibraryImpactedMethod> readPendingLibraryImpactedMethods(String groupArtifact) { return delegate.readPendingLibraryImpactedMethods(groupArtifact); }
        @Override public List<PendingLibraryImpactedMethod> readAllPendingLibraryImpactedMethods() { return delegate.readAllPendingLibraryImpactedMethods(); }
        @Override
        public void deletePendingLibraryImpactedMethods(String groupArtifact, long publishSeq) {
            deletePendingCalls.incrementAndGet();
            delegate.deletePendingLibraryImpactedMethods(groupArtifact, publishSeq);
        }

        @Override
        public void persistTrackedLibrary(TrackedLibrary trackedLibrary) {
            persistTrackedLibraryCalls.incrementAndGet();
            delegate.persistTrackedLibrary(trackedLibrary);
        }

        @Override
        public void deleteTrackedLibrary(String groupArtifact) {
            deleteTrackedLibraryCalls.incrementAndGet();
            delegate.deleteTrackedLibrary(groupArtifact);
        }

        @Override
        public void persistPendingLibraryImpactedMethods(PendingLibraryImpactedMethod pending) {
            persistPendingCalls.incrementAndGet();
            delegate.persistPendingLibraryImpactedMethods(pending);
        }

        @Override
        public void persistTestRunHistoryEntry(TestRunHistoryEntry entry) {
            delegate.persistTestRunHistoryEntry(entry);
        }

        @Override
        public List<TestRunHistoryEntry> readTestRunHistory() {
            return delegate.readTestRunHistory();
        }
    }
}
