package org.tiatesting.core.diff.diffanalyze.selector;

import org.tiatesting.core.model.SelectionMode;
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
import org.tiatesting.core.model.PendingLibraryForcedSelection;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.testrunner.TestSuiteRunnabilityClassifier;
import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.MappingDrift;
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
 * Integration coverage for the app-run library flow under publish-time stamping, driven through
 * the real {@code selectTestsToIgnore} path:
 * <ul>
 *   <li>a published library change (persisted ledger row + stamp) is drained into the selected
 *       tests once the app resolves a build that contains it - identically for preview and
 *       primary runs, with zero writes from either;</li>
 *   <li>an unpublished library change in the diff range is partitioned out and selects nothing -
 *       the resolved jar does not contain it, and its stamp will be written by the library's own
 *       publish, not by the app run;</li>
 *   <li>the app run never persists stamp rows, in either run mode;</li>
 *   <li>local-changes mode ({@code checkLocalChanges=true}) selects tests for local library
 *       edits directly (the partition is bypassed - the in-memory equivalent of stamp+drain)
 *       AND drains persisted stamps for published changes the local classpath contains, all
 *       with zero writes.</li>
 * </ul>
 * See the library publish-time stamping chapter in {@code WIKI.md}.
 */
class TestSelectorLibraryPreviewTest {

    private static final String LIB_COORD = "com.example:lib";
    private static final String LIB_PROJECT_DIR = "/projects/lib";
    private static final String LIB_SRC_DIR = "/projects/lib/src/main/java";
    private static final String LIB_FILE_KEY = "com/example/Lib.java";
    private static final String LIB_DIFF_PATH = LIB_SRC_DIR + "/" + LIB_FILE_KEY;
    private static final String LIB_TEST = "com.example.LibTest";
    private static final int METHOD_ID = 4242;

    private JdbcDataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-lib-preview-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())), BranchSchema.schemaName("test", null));
        dataStore.getTiaData();
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
     * A published library change is drained into the selected tests on a preview run: the ledger
     * row identifies the resolved build (release-version fallback here) and the covering test is
     * selected, with nothing written.
     */
    @Test
    void previewSelectsTestsForPublishedLibraryChange() {
        // given a tracked library with a published, stamped build the app resolves
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB_COORD, LIB_PROJECT_DIR, LIB_SRC_DIR));
        seedLibraryMethodMapping();
        dataStore.persistLibraryPublish(new LibraryPublish(LIB_COORD, "1.0.0", null, "lib-commit", 1000L),
                new HashSet<>(Collections.singletonList(METHOD_ID)),
                Collections.<PendingLibraryForcedSelection>emptyList());

        // when a preview build (updateDBMapping=false) runs selection with no app diffs
        TestSelector selector = new TestSelector(dataStore, new File("."));
        TestSelectorResult result = selector.selectTestsToIgnore(new StubVCSReader("head"),
                Collections.emptyList(), Collections.emptyList(),
                TestSuiteRunnabilityClassifier.disabled(),
                false, configResolving("1.0.0"), null, false, SelectionMode.SELECTIVE);

        // then the covering test is selected and the stamp remains pending (no cleanup on preview)
        assertTrue(result.getTestsToRun().contains(LIB_TEST),
                "Preview must select the test covering a published, resolvable library change.");
        assertTrue(dataStore.readPendingLibraryImpactedMethods(LIB_COORD).size() == 1,
                "Preview must not delete the drained stamp.");
    }

    /**
     * An unpublished library change appearing in the app's diff range is partitioned out of
     * source selection and selects nothing: the jar the app resolves does not contain the change,
     * so running its covering tests would prove nothing. The change is picked up later via the
     * library's own publish stamp.
     */
    @Test
    void unpublishedLibraryChangeInRangeSelectsNothing() {
        // given a tracked library whose source changed in the diff range but was never published
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB_COORD, LIB_PROJECT_DIR, LIB_SRC_DIR));
        seedLibraryMethodMapping();

        // when a preview run analyzes the library diff
        TestSelector selector = new TestSelector(dataStore, new File("."));
        TestSelectorResult result = selector.selectTestsToIgnore(new StubVCSReader("head", libraryDiff()),
                Collections.emptyList(), Collections.emptyList(),
                TestSuiteRunnabilityClassifier.disabled(),
                false, configResolving("1.0.0"), null, false, SelectionMode.SELECTIVE);

        // then no test is selected for the unpublished change and nothing was persisted
        assertFalse(result.getTestsToRun().contains(LIB_TEST),
                "An unpublished library change must not select tests - the resolved jar does not contain it.");
        assertTrue(dataStore.readPendingLibraryImpactedMethods(LIB_COORD).isEmpty(),
                "The app run must not stamp library changes.");
    }

    /**
     * The primary run behaves identically: a library diff in range produces no stamp rows -
     * stamping is owned by the library's publish task, not the app run.
     */
    @Test
    void primaryRunDoesNotStampLibraryDiffs() {
        // given a tracked library whose source changed in the diff range
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB_COORD, LIB_PROJECT_DIR, LIB_SRC_DIR));
        seedLibraryMethodMapping();

        // when a primary build (updateDBMapping=true) analyzes the library diff
        TestSelector selector = new TestSelector(dataStore, new File("."));
        TestSelectorResult result = selector.selectTestsToIgnore(new StubVCSReader("head", libraryDiff()),
                Collections.emptyList(), Collections.emptyList(),
                TestSuiteRunnabilityClassifier.disabled(),
                false, configResolving("1.0.0"), null, true, SelectionMode.SELECTIVE);

        // then no stamp rows exist and the library diff selected no tests directly
        assertTrue(dataStore.readPendingLibraryImpactedMethods(LIB_COORD).isEmpty(),
                "The primary app run must not stamp library changes - the publish task owns stamping.");
        assertFalse(result.getTestsToRun().contains(LIB_TEST),
                "Library diffs must not feed direct source selection.");
    }

    /**
     * The local dev flow: in {@code checkLocalChanges} mode a local (unpublished) library edit
     * bypasses the partition and selects its covering tests directly - the in-memory equivalent
     * of a stamp+drain, with zero DB writes. This is how a developer tests a library change
     * before review/commit.
     */
    @Test
    void localChangesRunSelectsTestsForLocalLibraryEditDirectly() {
        // given a tracked library whose source has a local edit in the working tree
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB_COORD, LIB_PROJECT_DIR, LIB_SRC_DIR));
        seedLibraryMethodMapping();

        // when a local-changes run (checkLocalChanges=true, non-primary) analyzes the edit
        TestSelector selector = new TestSelector(dataStore, new File("."));
        TestSelectorResult result = selector.selectTestsToIgnore(new StubVCSReader("head", libraryDiff()),
                Collections.emptyList(), Collections.emptyList(),
                TestSuiteRunnabilityClassifier.disabled(),
                true, configResolving("1.0.0"), null, false, SelectionMode.SELECTIVE);

        // then the covering test is selected directly and nothing was written
        assertTrue(result.getTestsToRun().contains(LIB_TEST),
                "Local-changes mode must select tests for a local library edit directly.");
        assertTrue(dataStore.readPendingLibraryImpactedMethods(LIB_COORD).isEmpty(),
                "The local flow must not persist any stamps.");
    }

    /**
     * Local-changes mode also drains persisted stamps: a published library change contained in
     * the build the dev's classpath resolves should be tested locally too. The drain is
     * read-only, so the stamp survives for the primary run's cleanup.
     */
    @Test
    void localChangesRunAlsoDrainsPersistedStamps() {
        // given a published, stamped build the local classpath resolves, and no local edits
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB_COORD, LIB_PROJECT_DIR, LIB_SRC_DIR));
        seedLibraryMethodMapping();
        dataStore.persistLibraryPublish(new LibraryPublish(LIB_COORD, "1.0.0", null, "lib-commit", 1000L),
                new HashSet<>(Collections.singletonList(METHOD_ID)),
                Collections.<PendingLibraryForcedSelection>emptyList());

        // when a local-changes run executes with no diffs of its own
        TestSelector selector = new TestSelector(dataStore, new File("."));
        TestSelectorResult result = selector.selectTestsToIgnore(new StubVCSReader("head"),
                Collections.emptyList(), Collections.emptyList(),
                TestSuiteRunnabilityClassifier.disabled(),
                true, configResolving("1.0.0"), null, false, SelectionMode.SELECTIVE);

        // then the published change's covering test is selected and the stamp remains pending
        assertTrue(result.getTestsToRun().contains(LIB_TEST),
                "Local-changes mode must drain persisted stamps the resolved build contains.");
        assertTrue(dataStore.readPendingLibraryImpactedMethods(LIB_COORD).size() == 1,
                "The local drain must not delete the stamp - cleanup belongs to the primary run.");
    }

    /**
     * Outside local-changes mode the app run does not diff the tracked library's source dirs:
     * library changes reach it through the publish stamp + drain, and a diffed library file would
     * only be partitioned out again, so its listing and content fetch would be wasted work.
     */
    @Test
    void nonLocalRunDoesNotDiffTrackedLibraryDirs() {
        // given a tracked library with a tracked method and a reader that reports a library diff
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB_COORD, LIB_PROJECT_DIR, LIB_SRC_DIR));
        seedLibraryMethodMapping();
        StubVCSReader reader = new StubVCSReader("head", libraryDiff());

        // when a primary (non-local) run selects tests
        TestSelector selector = new TestSelector(dataStore, new File("."));
        TestSelectorResult result = selector.selectTestsToIgnore(reader, Collections.emptyList(),
                Collections.emptyList(),
                TestSuiteRunnabilityClassifier.disabled(),
                false, configResolving("1.0.0"), null, true, SelectionMode.SELECTIVE);

        // then the library dir was not diffed, no library content was loaded and nothing was selected
        assertEquals(Collections.singletonList(Collections.<String>emptyList()), reader.getRequestedSourceDirs());
        assertTrue(reader.getContentLoadedDiffs().isEmpty(),
                "No library file content may be fetched outside local-changes mode.");
        assertFalse(result.getTestsToRun().contains(LIB_TEST));
    }

    /**
     * In local-changes mode the tracked library's source dirs are diffed, so local library edits
     * reach direct selection.
     */
    @Test
    void localChangesRunDiffsTrackedLibraryDirs() {
        // given a tracked library with a recorded source dir
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB_COORD, LIB_PROJECT_DIR, LIB_SRC_DIR));
        seedLibraryMethodMapping();
        StubVCSReader reader = new StubVCSReader("head", libraryDiff());

        // when a local-changes run selects tests
        TestSelector selector = new TestSelector(dataStore, new File("."));
        selector.selectTestsToIgnore(reader, Collections.emptyList(), Collections.emptyList(),
                TestSuiteRunnabilityClassifier.disabled(),
                true,
                configResolving("1.0.0"), null, false, SelectionMode.SELECTIVE);

        // then the library's source dir was part of the diff
        assertEquals(Collections.singletonList(Collections.singletonList(LIB_SRC_DIR)),
                reader.getRequestedSourceDirs());
    }

    /**
     * When the configured source dirs themselves cover a tracked library, its files are still in
     * a non-local run's diff - the partition stays as the guard that keeps them out of direct
     * selection.
     */
    @Test
    void nonLocalRunStillPartitionsLibraryFilesUnderConfiguredSourceDirs() throws Exception {
        // given a tracked library whose source dir is also listed in the app's configured source dirs
        File libSrcDir = new File(tempDir, "lib-src");
        assertTrue(libSrcDir.mkdirs());
        String libSrcPath = libSrcDir.getCanonicalPath();
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB_COORD, LIB_PROJECT_DIR, libSrcPath));
        seedLibraryMethodMapping();
        String diffPath = libSrcPath + "/" + LIB_FILE_KEY;
        StubVCSReader reader = new StubVCSReader("head",
                new SourceFileDiffContext(diffPath, diffPath, ChangeType.MODIFY));

        // when a preview (non-local) run selects tests - preview, so the reconcile does not reset
        // the tracked source dir to the stub metadata reader's
        TestSelector selector = new TestSelector(dataStore, new File("."));
        TestSelectorResult result = selector.selectTestsToIgnore(reader, Collections.singletonList(libSrcPath),
                Collections.emptyList(),
                TestSuiteRunnabilityClassifier.disabled(),
                false, configResolving("1.0.0"), null, false, SelectionMode.SELECTIVE);

        // then the tracked library file reached the partition (its content was loaded) but
        // selected no test
        assertEquals(1, reader.getContentLoadedDiffs().size());
        assertFalse(result.getTestsToRun().contains(LIB_TEST),
                "A library file under a configured source dir must still be partitioned out.");
    }

    /**
     * Seed a mapping where the library file {@code com/example/Lib.java} has one tracked method
     * spanning lines 2-8, covered by {@code com.example.LibTest}. The unsealed flag
     * {@link JdbcDataStore#persistTestSuites} sets as a side effect of the edge write is
     * immediately cleared, so the fixture models an already-sealed mapping - this class tests the
     * library publish/drain flow, not unsealed-suite force-selection.
     */
    private void seedLibraryMethodMapping() {
        TiaData tiaData = dataStore.getTiaData();
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
        dataStore.clearUnsealedTestSuites();
    }

    /**
     * Build a config whose metadata reader resolves the library at the given release version
     * (no jar available, so the drain identifies the build via the version fallback).
     *
     * @param resolvedVersion the version resolved on the app classpath
     * @return the library config
     */
    private LibraryImpactAnalysisConfig configResolving(String resolvedVersion) {
        Map<String, String> projectDirs = new HashMap<>();
        projectDirs.put(LIB_COORD, LIB_PROJECT_DIR);
        return new LibraryImpactAnalysisConfig(
                Collections.singletonList(LIB_COORD), projectDirs, "/projects/source",
                new StubMetadataReader(resolvedVersion));
    }

    /**
     * Build a MODIFY diff for the library source file.
     *
     * @return a content-less MODIFY diff context for the library file
     */
    private static SourceFileDiffContext libraryDiff() {
        return new SourceFileDiffContext(LIB_DIFF_PATH, LIB_DIFF_PATH, ChangeType.MODIFY);
    }

    /**
     * Stub reader returning a fixed diff set and, on content load, supplying original/changed
     * content that differs on line 5 - inside the seeded method's 2-8 range.
     */
    private static final class StubVCSReader implements VCSReader {
        private final String headCommit;
        private final Set<SourceFileDiffContext> diffs;
        private final List<List<String>> requestedSourceDirs = new ArrayList<>();
        private final List<SourceFileDiffContext> contentLoadedDiffs = new ArrayList<>();

        StubVCSReader(String headCommit, SourceFileDiffContext... diffs) {
            this.headCommit = headCommit;
            this.diffs = new HashSet<>(java.util.Arrays.asList(diffs));
        }

        @Override public String getBranchName() { return "test"; }
        @Override public String getHeadCommit() { return headCommit; }

        /**
         * Return the fixed diff set, recording the source dirs the diff was restricted to.
         *
         * @param baseChangeNum unused.
         * @param sourceFilesDirs the source dirs requested, recorded.
         * @param testFilesDirs unused.
         * @param checkLocalChanges unused.
         * @return the fixed diff set.
         */
        @Override
        public Set<SourceFileDiffContext> getDiffFiles(String baseChangeNum, List<String> sourceFilesDirs,
                                                       List<String> testFilesDirs, boolean checkLocalChanges) {
            requestedSourceDirs.add(new ArrayList<>(sourceFilesDirs));
            return diffs;
        }

        /**
         * Supply original/changed content differing on line 5 to each diff, recording which diffs
         * had content loaded.
         *
         * @param diffsToLoad the diffs to populate, recorded.
         * @param baseChangeNum unused.
         * @param checkLocalChanges unused.
         */
        @Override
        public void loadContentForDiffs(Collection<SourceFileDiffContext> diffsToLoad, String baseChangeNum,
                                        boolean checkLocalChanges) {
            contentLoadedDiffs.addAll(diffsToLoad);
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

        /**
         * Content-by-path loading is not exercised by these tests.
         *
         * @param paths unused.
         * @param baseChangeNum unused.
         * @param checkLocalChanges unused.
         * @return never returns.
         */
        @Override
        public Map<String, FileContentPair> loadFileContents(final Set<String> paths, final String baseChangeNum,
                                                             final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("loadFileContents is not used by this test");
        }

        /**
         * No locally changed file differs from its mapped version.
         *
         * @param baseChangeNum ignored
         * @param localDiffs ignored
         * @return {@link MappingDrift#none()}
         */
        @Override
        public MappingDrift findMappingDrift(String baseChangeNum,
                Collection<SourceFileDiffContext> localDiffs) {
            return MappingDrift.none();
        }

        @Override public void close() { }

        /**
         * Return the source dirs passed to each {@link #getDiffFiles} call, in call order.
         *
         * @return the requested source dirs per diff call.
         */
        List<List<String>> getRequestedSourceDirs() {
            return requestedSourceDirs;
        }

        /**
         * Return every diff passed to {@link #loadContentForDiffs}.
         *
         * @return the diffs whose content was loaded.
         */
        List<SourceFileDiffContext> getContentLoadedDiffs() {
            return contentLoadedDiffs;
        }
    }

    /**
     * Stub metadata reader resolving the library at a fixed version with no jar path.
     */
    private static final class StubMetadataReader implements LibraryMetadataReader {
        private final String resolvedVersion;

        StubMetadataReader(String resolvedVersion) {
            this.resolvedVersion = resolvedVersion;
        }

        @Override
        public List<LibraryBuildMetadata> readLibraryBuildMetadata(String libraryProjectDir, List<String> coordinates) {
            return Collections.emptyList();
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
            return Collections.singletonList(LIB_SRC_DIR);
        }
    }
}
