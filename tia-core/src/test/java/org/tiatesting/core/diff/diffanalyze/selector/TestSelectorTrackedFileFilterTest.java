package org.tiatesting.core.diff.diffanalyze.selector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the tracked-file content-fetch optimisation in {@link TestSelector#selectTestsToIgnore}:
 * file content is fetched only for changed source files that are tracked in the mapping. A changed
 * source file with no tracked coverage is dropped before the content fetch, and selection is
 * unchanged (it contributes nothing either way).
 */
class TestSelectorTrackedFileFilterTest {

    private static final String TRACKED_FILE_KEY = "com/example/Foo.java";
    private static final String UNTRACKED_FILE_KEY = "com/example/Bar.java";
    private static final String SUITE_NAME = "com.example.FooTest";
    private static final int METHOD_ID = 4242;

    private H2DataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-trackedfilter-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
        dataStore.getTiaData(true);
        // Foo.java is tracked (one method, lines 2-8, covered by FooTest); Bar.java is not tracked.
        seedMapping();
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

    @Test
    void loadsContentForOnlyTheTrackedChangedFile() {
        // given - a diff touching one tracked (Foo) and one untracked (Bar) source file
        RecordingVCSReader reader = new RecordingVCSReader(
                modifiedDiff(TRACKED_FILE_KEY), modifiedDiff(UNTRACKED_FILE_KEY));
        TestSelector selector = new TestSelector(dataStore);

        // when
        TestSelectorResult result = selector.selectTestsToIgnore(reader,
                Collections.emptyList(), Collections.emptyList(), false, null, null, false);

        // then - content was fetched for ONLY the tracked file
        assertEquals(Collections.singletonList(TRACKED_FILE_KEY), reader.contentLoadedForKeys(),
                "content must be loaded for only the tracked changed file");

        // and - the untracked file's diff was never given content
        assertNull(reader.diffFor(UNTRACKED_FILE_KEY).getSourceContentOriginal(),
                "untracked file must not be content-loaded");
        assertNull(reader.diffFor(UNTRACKED_FILE_KEY).getSourceContentNew(),
                "untracked file must not be content-loaded");

        // and - selection is correct: the suite covering the tracked file's changed method runs,
        // and the presence of the untracked file changed nothing.
        assertTrue(result.getTestsToRun().contains(SUITE_NAME),
                "the suite covering the tracked changed method must be selected");
    }

    @Test
    void selectionUnchangedWhenOnlyUntrackedFilesChange() {
        // given - a diff touching only the untracked file
        RecordingVCSReader reader = new RecordingVCSReader(modifiedDiff(UNTRACKED_FILE_KEY));
        TestSelector selector = new TestSelector(dataStore);

        // when
        TestSelectorResult result = selector.selectTestsToIgnore(reader,
                Collections.emptyList(), Collections.emptyList(), false, null, null, false);

        // then - nothing is content-loaded and no tests are selected from source changes
        assertTrue(reader.contentLoadedForKeys().isEmpty(), "no tracked files means no content fetch");
        assertTrue(result.getTestsToRun().isEmpty(), "an untracked-only change selects no tests");
    }

    /**
     * The result carries the base estimate (pure per-suite time) and the mapping overhead as
     * separate figures. The overhead is always computed (it's the caller's choice, at display time,
     * whether to add it) so the base stays the bare per-suite sum.
     */
    @Test
    void resultCarriesBaseEstimateAndMappingOverheadSeparately() {
        // given - FooTest avg 100ms, full-suite baseline 500ms => overhead (500-100)/1 = 400ms/suite
        seedStats(100L, 500L);
        RecordingVCSReader reader = new RecordingVCSReader(modifiedDiff(TRACKED_FILE_KEY));
        TestSelector selector = new TestSelector(dataStore);

        // when
        TestSelectorResult result = selector.selectTestsToIgnore(reader,
                Collections.emptyList(), Collections.emptyList(), false, null, null, false);

        // then - base is the per-suite sum (no overhead folded in); overhead is reported separately
        assertTrue(result.getTestsToRun().contains(SUITE_NAME));
        assertEquals(100L, result.getEstimatedRunTimeMs());
        assertEquals(400L, result.getMappingOverheadMs());
    }

    /**
     * Overlay run-time stats onto the seeded mapping: set {@code FooTest}'s {@code avgRunTime} and
     * the Tia-level all-tests baseline so the estimate and its overhead are exercised.
     *
     * @param suiteAvgMs the {@code avgRunTime} (ms) to store for {@code FooTest}
     * @param allTestsRunTimeMs the full-suite baseline (ms) to store on the core row
     */
    private void seedStats(long suiteAvgMs, long allTestsRunTimeMs) {
        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.getTestStats().setAllTestsRunTime(allTestsRunTimeMs);
        dataStore.persistCoreData(tiaData);
        tiaData.getTestSuitesTracked().get(SUITE_NAME).getTestStats().setAvgRunTime(suiteAvgMs);
        dataStore.persistTestSuites(tiaData.getTestSuitesTracked());
    }

    /**
     * Seed a mapping where {@code com/example/Foo.java} has one tracked method (lines 2-8) covered
     * by {@code com.example.FooTest}. {@code com/example/Bar.java} is left untracked.
     */
    private void seedMapping() {
        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("seed-commit");
        tiaData.setLastUpdated(Instant.now());

        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        methods.put(METHOD_ID, new MethodImpactTracker("com/example/Foo.method.()V", 2, 8));

        TestSuiteTracker suite = new TestSuiteTracker(SUITE_NAME);
        suite.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker(TRACKED_FILE_KEY, new HashSet<>(Collections.singletonList(METHOD_ID)))));
        Map<String, TestSuiteTracker> testSuites = new HashMap<>();
        testSuites.put(SUITE_NAME, suite);

        tiaData.setTestSuitesTracked(testSuites);
        tiaData.setMethodsTracked(methods);
        dataStore.persistCoreData(tiaData);
        dataStore.persistTestSuites(testSuites);
        dataStore.persistSourceMethods(methods);
    }

    /**
     * Build a MODIFY diff for a mapping-key file. The leading slash + empty source dirs means the
     * selector's normalization ({@code substring(1)}) yields the mapping key verbatim.
     *
     * @param mappingKey the stored mapping key (e.g. {@code com/example/Foo.java})
     * @return a content-less MODIFY diff context for that file
     */
    private static SourceFileDiffContext modifiedDiff(String mappingKey) {
        String path = "/" + mappingKey;
        return new SourceFileDiffContext(path, path, ChangeType.MODIFY);
    }

    /**
     * Stub reader that returns a fixed diff set without content, and records which diffs
     * {@link #loadContentForDiffs} is asked to populate. The recorded content changes line 5, which
     * falls inside the seeded method's 2-8 range, so a fetched file produces an impacted method.
     */
    private static final class RecordingVCSReader implements VCSReader {
        private final Set<SourceFileDiffContext> diffs;
        private final List<SourceFileDiffContext> contentLoadedFor = new ArrayList<>();

        RecordingVCSReader(SourceFileDiffContext... diffs) {
            this.diffs = new HashSet<>(java.util.Arrays.asList(diffs));
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
                contentLoadedFor.add(diff);
                diff.setSourceContentOriginal(original);
                diff.setSourceContentNew(changed);
            }
        }

        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            return new HashSet<>();
        }

        @Override public void close() { }

        /** @return the mapping keys (sorted) the content load was invoked for */
        List<String> contentLoadedForKeys() {
            return contentLoadedFor.stream()
                    .map(d -> d.getOldFilePath().substring(1))
                    .sorted()
                    .collect(Collectors.toList());
        }

        /**
         * @param mappingKey the mapping key to find the diff for
         * @return the diff context whose old path matches the key
         */
        SourceFileDiffContext diffFor(String mappingKey) {
            return diffs.stream().filter(d -> d.getOldFilePath().equals("/" + mappingKey))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("no diff for " + mappingKey));
        }
    }
}
