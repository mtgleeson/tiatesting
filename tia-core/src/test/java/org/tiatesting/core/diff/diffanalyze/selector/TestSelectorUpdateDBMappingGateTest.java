package org.tiatesting.core.diff.diffanalyze.selector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.library.LibraryMetadataReader;
import org.tiatesting.core.library.ResolvedSourceProjectLibrary;
import org.tiatesting.core.model.LibraryBuildMetadata;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.DataStore;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link TestSelector#selectTestsToIgnore} only writes to the mapping DB when
 * {@code updateDBMapping=true}. Non-primary builds (a developer's local workspace, a CI job not
 * designated as the primary mapping owner) must run test selection without mutating the shared
 * {@code tia_library} or {@code tia_pending_library_impacted_method} tables.
 *
 * <p>Two gates are covered:
 * <ul>
 *     <li><b>Reconcile gate</b> — {@code TrackedLibraryReconciler} must not insert/update/delete
 *         {@code tia_library} rows when the run isn't the mapping owner.</li>
 *     <li><b>Stamp gate</b> — {@code PendingLibraryImpactedMethodsRecorder} must not persist
 *         pending rows nor advance the BUMP_AT_RELEASE high water mark on a non-primary run.</li>
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
        dataStore = new H2DataStore(tempDir.getAbsolutePath(), "test");
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
                Collections.emptyList(), false, libraryConfig, false);

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
                Collections.emptyList(), false, libraryConfig, true);

        assertTrue(dataStore.readTrackedLibraries().containsKey("com.example:lib"),
                "Primary build must insert the declared library on reconcile.");
    }

    /**
     * Non-primary build with a removed library (declared in DB but not in current config): the
     * reconciler would normally delete the row. Must not happen when updateDBMapping=false.
     */
    @Test
    void reconcileSkipsRemovalWhenUpdateDBMappingFalse() {
        TrackedLibrary preExisting = new TrackedLibrary("com.example:gone", "/projects/gone", null, "1.0.0", null);
        dataStore.persistTrackedLibrary(preExisting);

        LibraryImpactAnalysisConfig libraryConfig = libraryConfigFor("com.example:lib", "/projects/lib");

        TestSelector testSelector = new TestSelector(dataStore);
        testSelector.selectTestsToIgnore(emptyDiffsVcsReader(), Collections.emptyList(),
                Collections.emptyList(), false, libraryConfig, false);

        assertTrue(dataStore.readTrackedLibraries().containsKey("com.example:gone"),
                "Non-primary build must not delete tia_library rows even when config drops a library.");
    }

    /**
     * Non-primary build with a library diff that would normally be stamped as pending: the
     * recorder must not be invoked. Verified via a {@link CountingDataStore} so the test does not
     * depend on whether method-impact analysis produces a non-empty set for the synthetic diff.
     */
    @Test
    void stampPersistSkippedWhenUpdateDBMappingFalse() {
        TrackedLibrary tracked = new TrackedLibrary("com.example:lib", "/projects/lib",
                "/projects/lib/src/main/java", "1.0.0", null);
        dataStore.persistTrackedLibrary(tracked);
        seedStoredCommit("abc123");

        CountingDataStore counting = new CountingDataStore(dataStore);
        LibraryImpactAnalysisConfig libraryConfig = libraryConfigFor("com.example:lib", "/projects/lib");

        TestSelector testSelector = new TestSelector(counting);
        testSelector.selectTestsToIgnore(libraryDiffVcsReader(), Collections.emptyList(),
                Collections.emptyList(), false, libraryConfig, false);

        assertEquals(0, counting.persistPendingCalls.get(),
                "Non-primary build must not call persistPendingLibraryImpactedMethods.");
        assertEquals(0, counting.persistTrackedLibraryCalls.get(),
                "Non-primary build must not call persistTrackedLibrary (no reconcile, no HWM advance).");
        assertEquals(0, counting.deleteTrackedLibraryCalls.get(),
                "Non-primary build must not call deleteTrackedLibrary.");
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
        public Set<SourceFileDiffContext> buildDiffFilesContext(String baseChangeNum, List<String> sourceFilesDirs,
                                                                List<String> testFilesDirs, boolean checkLocalChanges) {
            return diffs;
        }

        @Override
        public void close() {
        }
    }

    /**
     * Stub reader: returns "1.0.0" as both declared and resolved version, no JAR path. Sufficient
     * for reconcile (insert path reads declared version under BUMP_AFTER_RELEASE only — no-op there)
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

        CountingDataStore(DataStore delegate) {
            this.delegate = delegate;
        }

        @Override public TiaData getTiaData(boolean readFromDisk) { return delegate.getTiaData(readFromDisk); }
        @Override public TiaData getTiaCore() { return delegate.getTiaCore(); }
        @Override public Map<String, TestSuiteTracker> getTestSuitesTracked() { return delegate.getTestSuitesTracked(); }
        @Override public Map<Integer, MethodImpactTracker> getMethodsTracked() { return delegate.getMethodsTracked(); }
        @Override public Set<Integer> getUniqueMethodIdsTracked() { return delegate.getUniqueMethodIdsTracked(); }
        @Override public int getNumTestSuites() { return delegate.getNumTestSuites(); }
        @Override public int getNumSourceMethods() { return delegate.getNumSourceMethods(); }
        @Override public Set<String> getTestSuitesFailed() { return delegate.getTestSuitesFailed(); }
        @Override public void persistCoreData(TiaData tiaData) { delegate.persistCoreData(tiaData); }
        @Override public void persistTestSuitesFailed(Set<String> testSuitesFailed) { delegate.persistTestSuitesFailed(testSuitesFailed); }
        @Override public void persistSourceMethods(Map<Integer, MethodImpactTracker> methodsTracked) { delegate.persistSourceMethods(methodsTracked); }
        @Override public void persistTestSuites(Map<String, TestSuiteTracker> testSuites) { delegate.persistTestSuites(testSuites); }
        @Override public void deleteTestSuites(Set<String> testSuites) { delegate.deleteTestSuites(testSuites); }
        @Override public Map<String, TrackedLibrary> readTrackedLibraries() { return delegate.readTrackedLibraries(); }
        @Override public List<PendingLibraryImpactedMethod> readPendingLibraryImpactedMethods(String groupArtifact) { return delegate.readPendingLibraryImpactedMethods(groupArtifact); }
        @Override public List<PendingLibraryImpactedMethod> readAllPendingLibraryImpactedMethods() { return delegate.readAllPendingLibraryImpactedMethods(); }
        @Override public void deletePendingLibraryImpactedMethods(String groupArtifact, String stampVersion) { delegate.deletePendingLibraryImpactedMethods(groupArtifact, stampVersion); }

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
    }
}
