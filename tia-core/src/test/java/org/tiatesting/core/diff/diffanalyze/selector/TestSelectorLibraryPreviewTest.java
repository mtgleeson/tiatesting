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
 *   <li>the app run never persists stamp rows, in either run mode.</li>
 * </ul>
 * See {@code DESIGN-publish-time-stamping.md} sections 2-3.
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
                new HashSet<>(Collections.singletonList(METHOD_ID)));

        // when a preview build (updateDBMapping=false) runs selection with no app diffs
        TestSelector selector = new TestSelector(dataStore);
        TestSelectorResult result = selector.selectTestsToIgnore(new StubVCSReader("head"),
                Collections.emptyList(), Collections.emptyList(), false, configResolving("1.0.0"), null, false);

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
        TestSelector selector = new TestSelector(dataStore);
        TestSelectorResult result = selector.selectTestsToIgnore(new StubVCSReader("head", libraryDiff()),
                Collections.emptyList(), Collections.emptyList(), false, configResolving("1.0.0"), null, false);

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
        TestSelector selector = new TestSelector(dataStore);
        TestSelectorResult result = selector.selectTestsToIgnore(new StubVCSReader("head", libraryDiff()),
                Collections.emptyList(), Collections.emptyList(), false, configResolving("1.0.0"), null, true);

        // then no stamp rows exist and the library diff selected no tests directly
        assertTrue(dataStore.readPendingLibraryImpactedMethods(LIB_COORD).isEmpty(),
                "The primary app run must not stamp library changes - the publish task owns stamping.");
        assertFalse(result.getTestsToRun().contains(LIB_TEST),
                "Library diffs must not feed direct source selection.");
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

        StubVCSReader(String headCommit, SourceFileDiffContext... diffs) {
            this.headCommit = headCommit;
            this.diffs = new HashSet<>(java.util.Arrays.asList(diffs));
        }

        @Override public String getBranchName() { return "test"; }
        @Override public String getHeadCommit() { return headCommit; }

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
