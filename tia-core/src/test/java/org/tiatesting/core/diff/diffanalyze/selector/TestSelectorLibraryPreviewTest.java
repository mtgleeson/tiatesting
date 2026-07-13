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
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 3 integration coverage for the {@code select-tests} library preview: a library source
 * change that appears for the first time in the analyzed range (and so has never been recorded as a
 * persisted pending batch) must still be surfaced by a preview build ({@code updateDBMapping=false})
 * so the selected-tests set - and the runtime estimate derived from it - is accurate. The preview
 * must achieve this without writing anything, whereas the primary build ({@code updateDBMapping=true})
 * persists the same change as a pending batch as before.
 *
 * <p>Drives the real {@code selectTestsToIgnore} diff-analysis path end to end: the stub VCS reader
 * supplies content whose changed line falls inside the tracked library method's line range, so
 * {@code findMethodsImpacted} produces a genuine impacted method that flows into the synthetic batch.
 */
class TestSelectorLibraryPreviewTest {

    private static final String LIB_COORD = "com.example:lib";
    private static final String LIB_PROJECT_DIR = "/projects/lib";
    private static final String LIB_SRC_DIR = "/projects/lib/src/main/java";
    private static final String LIB_FILE_KEY = "com/example/Lib.java";
    private static final String LIB_DIFF_PATH = LIB_SRC_DIR + "/" + LIB_FILE_KEY;
    private static final String LIB_TEST = "com.example.LibTest";
    private static final int METHOD_ID = 4242;

    private H2DataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-lib-preview-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
        dataStore.getTiaData(true);
    }

    @AfterEach
    void tearDown() {
        dataStore.close();
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * A first-seen library change under a preview build must surface the covering test from an
     * in-memory synthetic batch (resolved version 1.0.0 is at/above the stamp and differs from the
     * tracked 0.9.0, so it drains), while persisting nothing and leaving tracked-library state alone.
     */
    @Test
    void previewSurfacesFirstSeenLibraryChangeWithoutDbWrites() {
        // given a tracked library at 0.9.0 with a mapped method/test and NO persisted pending batch
        TrackedLibrary lib = new TrackedLibrary(LIB_COORD, LIB_PROJECT_DIR, LIB_SRC_DIR, "0.9.0", null);
        dataStore.persistTrackedLibrary(lib);
        seedLibraryMethodMapping();
        LibraryImpactAnalysisConfig config = libraryConfig();

        // when a preview build (updateDBMapping=false) analyzes a diff to the library source file
        TestSelector selector = new TestSelector(dataStore);
        TestSelectorResult result = selector.selectTestsToIgnore(libraryDiffReader(),
                Collections.emptyList(), Collections.emptyList(), false, config, null, false);

        // then the covering test is surfaced from the in-memory synthetic batch...
        assertTrue(result.getTestsToRun().contains(LIB_TEST),
                "Preview must surface the test covering a first-seen library change.");
        // ...and nothing was written: no pending row was persisted, tracked version is unchanged
        assertTrue(dataStore.readPendingLibraryImpactedMethods(LIB_COORD).isEmpty(),
                "Preview must not persist a pending batch for the first-seen change.");
        assertEquals("0.9.0", dataStore.readTrackedLibraries().get(LIB_COORD).getLastSourceProjectVersion(),
                "Preview must not advance tracked-library state.");
    }

    /**
     * "What would run now" semantics at the integration level: when the resolved version has not yet
     * reached the change's stamp, the preview holds the synthetic batch back and selects no test for
     * it, so the estimate does not count a test this build will not run.
     */
    @Test
    void previewHoldsFirstSeenLibraryChangeWhenResolvedVersionBelowStamp() {
        // given the source project still resolves 0.9.0 - below the library's declared/stamp 1.0.0
        TrackedLibrary lib = new TrackedLibrary(LIB_COORD, LIB_PROJECT_DIR, LIB_SRC_DIR, "0.9.0", null);
        dataStore.persistTrackedLibrary(lib);
        seedLibraryMethodMapping();
        LibraryImpactAnalysisConfig config = libraryConfig("1.0.0", "0.9.0");

        // when a preview build analyzes the library diff
        TestSelector selector = new TestSelector(dataStore);
        TestSelectorResult result = selector.selectTestsToIgnore(libraryDiffReader(),
                Collections.emptyList(), Collections.emptyList(), false, config, null, false);

        // then the change is held - the covering test is not selected
        assertFalse(result.getTestsToRun().contains(LIB_TEST),
                "Preview must hold a change whose stamp is above the resolved version.");
    }

    /**
     * Contrast/parity with the primary build: the same first-seen library diff under
     * {@code updateDBMapping=true} is stamped as a persisted pending batch (the pre-existing
     * behaviour), rather than being handled in memory only.
     */
    @Test
    void primaryBuildPersistsFirstSeenLibraryChange() {
        // given a tracked library at 0.9.0 with a mapped method/test and NO persisted pending batch
        TrackedLibrary lib = new TrackedLibrary(LIB_COORD, LIB_PROJECT_DIR, LIB_SRC_DIR, "0.9.0", null);
        dataStore.persistTrackedLibrary(lib);
        seedLibraryMethodMapping();
        LibraryImpactAnalysisConfig config = libraryConfig();

        // when a primary build (updateDBMapping=true) analyzes the library diff
        TestSelector selector = new TestSelector(dataStore);
        selector.selectTestsToIgnore(libraryDiffReader(),
                Collections.emptyList(), Collections.emptyList(), false, config, null, true);

        // then the change is stamped as a persisted pending batch at the declared version
        List<PendingLibraryImpactedMethod> pending = dataStore.readPendingLibraryImpactedMethods(LIB_COORD);
        assertEquals(1, pending.size(), "Primary build must persist the first-seen library change.");
        assertEquals("1.0.0", pending.get(0).getStampVersion());
        assertTrue(pending.get(0).getSourceMethodIds().contains(METHOD_ID));
    }

    /**
     * Seed a mapping where the library file {@code com/example/Lib.java} has one tracked method
     * spanning lines 2-8, covered by {@code com.example.LibTest}.
     */
    private void seedLibraryMethodMapping() {
        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("seed-commit");
        tiaData.setLastUpdated(Instant.now());

        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        methods.put(METHOD_ID, new MethodImpactTracker("com/example/Lib.method.()V", 2, 8));

        TestSuiteTracker suite = new TestSuiteTracker(LIB_TEST);
        suite.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker(LIB_FILE_KEY, new HashSet<>(Collections.singletonList(METHOD_ID)))));
        Map<String, TestSuiteTracker> testSuites = new HashMap<>();
        testSuites.put(LIB_TEST, suite);

        tiaData.setTestSuitesTracked(testSuites);
        tiaData.setMethodsTracked(methods);
        dataStore.persistCoreData(tiaData);
        dataStore.persistTestSuites(testSuites);
        dataStore.persistSourceMethods(methods);
    }

    private LibraryImpactAnalysisConfig libraryConfig() {
        return libraryConfig("1.0.0", "1.0.0");
    }

    private LibraryImpactAnalysisConfig libraryConfig(String declaredVersion, String resolvedVersion) {
        Map<String, String> projectDirs = new HashMap<>();
        projectDirs.put(LIB_COORD, LIB_PROJECT_DIR);
        return new LibraryImpactAnalysisConfig(
                Collections.singletonList(LIB_COORD), projectDirs, "/projects/source",
                new StubMetadataReader(declaredVersion, resolvedVersion));
    }

    private VCSReader libraryDiffReader() {
        return new ContentProvidingVCSReader(new SourceFileDiffContext(
                LIB_DIFF_PATH, LIB_DIFF_PATH, ChangeType.MODIFY));
    }

    /**
     * Stub reader returning a single MODIFY diff and, on content load, supplying original/changed
     * content that differs on line 5 - inside the seeded method's 2-8 range - so method-impact
     * analysis yields the tracked method.
     */
    private static final class ContentProvidingVCSReader implements VCSReader {
        private final Set<SourceFileDiffContext> diffs;

        ContentProvidingVCSReader(SourceFileDiffContext diff) {
            this.diffs = new HashSet<>(Collections.singletonList(diff));
        }

        @Override public String getBranchName() { return "test"; }
        @Override public String getHeadCommit() { return "head"; }

        @Override
        public Set<SourceFileDiffContext> getDiffFiles(String baseChangeNum, List<String> sourceFilesDirs,
                                                       List<String> testFilesDirs, boolean checkLocalChanges) {
            return diffs;
        }

        @Override
        public void loadContentForDiffs(Collection<SourceFileDiffContext> diffsToLoad, String baseChangeNum,
                                        boolean checkLocalChanges) {
            String original = "l1\nl2\nl3\nl4\nl5-old\nl6\nl7\nl8\nl9\nl10\n";
            String changed = "l1\nl2\nl3\nl4\nl5-new\nl6\nl7\nl8\nl9\nl10\n";
            for (SourceFileDiffContext diff : diffsToLoad) {
                diff.setSourceContentOriginal(original);
                diff.setSourceContentNew(changed);
            }
        }

        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            return new HashSet<>();
        }

        @Override public void close() { }
    }

    /**
     * Stub metadata reader returning a fixed declared version (used for the pending stamp) and a
     * fixed resolved version (used by the drain preview to decide eligibility).
     */
    private static final class StubMetadataReader implements LibraryMetadataReader {
        private final String declaredVersion;
        private final String resolvedVersion;

        StubMetadataReader(String declaredVersion, String resolvedVersion) {
            this.declaredVersion = declaredVersion;
            this.resolvedVersion = resolvedVersion;
        }

        @Override
        public List<LibraryBuildMetadata> readLibraryBuildMetadata(String libraryProjectDir, List<String> coordinates) {
            List<LibraryBuildMetadata> result = new ArrayList<>();
            for (String coord : coordinates) {
                result.add(new LibraryBuildMetadata(coord, declaredVersion));
            }
            return result;
        }

        @Override
        public List<ResolvedSourceProjectLibrary> resolveLibrariesInSourceProject(String sourceProjectDir,
                                                                                  List<String> coordinates) {
            List<ResolvedSourceProjectLibrary> result = new ArrayList<>();
            for (String coord : coordinates) {
                result.add(new ResolvedSourceProjectLibrary(coord, resolvedVersion, null));
            }
            return result;
        }

        @Override
        public List<String> readSourceDirectories(String libraryProjectDir) {
            // Return the tracked source dir so the primary-build reconcile is a no-op for this
            // library (keeps sourceDirsCsv stable, so diff-path normalization stays consistent).
            return Collections.singletonList(LIB_SRC_DIR);
        }
    }
}
