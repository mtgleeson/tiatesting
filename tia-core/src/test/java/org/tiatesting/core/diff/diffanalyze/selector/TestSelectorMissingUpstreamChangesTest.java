package org.tiatesting.core.diff.diffanalyze.selector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.SelectionMode;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.testrunner.TestSuiteRunnabilityClassifier;
import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.MissingUpstreamChanges;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Verifies that in local-changes mode a locally changed file missing upstream changes has every
 * tracked method treated as impacted, instead of matching its diff to tracked methods by line
 * number. See the "Local changes and a workspace behind the mapping" chapter in {@code WIKI.md}.
 */
class TestSelectorMissingUpstreamChangesTest {

    private static final String FOO_KEY = "com/example/Foo.java";
    private static final String BAR_KEY = "com/example/Bar.java";
    private static final String FOO_TOP_SUITE = "com.example.FooTopTest";
    private static final String FOO_BOTTOM_SUITE = "com.example.FooBottomTest";
    private static final String BAR_SUITE = "com.example.BarTest";
    private static final int FOO_TOP_METHOD = 101;
    private static final int FOO_BOTTOM_METHOD = 102;
    private static final int BAR_METHOD = 201;

    private JdbcDataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-missing-upstream-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
        dataStore.getTiaData();
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
    void fileMissingUpstreamChangesHasAllTrackedMethodsImpacted() {
        // given - Foo and Bar are both edited on line 5; Foo is missing an upstream change
        StubVCSReader reader = new StubVCSReader(
                MissingUpstreamChanges.of(Collections.singletonMap(FOO_KEY, Arrays.asList("16", "18"))),
                modifiedDiff(FOO_KEY), modifiedDiff(BAR_KEY));
        TestSelector selector = new TestSelector(dataStore, new File("."));

        // when
        TestSelectorResult result = selectLocal(selector, reader);

        // then - both Foo methods' suites run, though the edit only touches the top method's lines
        assertTrue(result.getTestsToRun().contains(FOO_TOP_SUITE));
        assertTrue(result.getTestsToRun().contains(FOO_BOTTOM_SUITE),
                "every tracked method in a file missing upstream changes must be treated as impacted");
        assertTrue(result.getTestsToRun().contains(BAR_SUITE), "Bar is still matched by line");

        // and - Foo's content is not loaded, because its line numbers aren't used
        assertEquals(Collections.singletonList(BAR_KEY), reader.contentLoadedForKeys());
        assertEquals(new HashSet<>(Arrays.asList(FOO_KEY, BAR_KEY)), new HashSet<>(reader.checkedKeys()));
    }

    @Test
    void fileWithNoMissingUpstreamChangesIsMatchedByLine() {
        // given - Foo is edited on line 5 and the workspace has every upstream change
        StubVCSReader reader = new StubVCSReader(MissingUpstreamChanges.none(), modifiedDiff(FOO_KEY));
        TestSelector selector = new TestSelector(dataStore, new File("."));

        // when
        TestSelectorResult result = selectLocal(selector, reader);

        // then - only the top method (lines 2-8) is impacted
        assertTrue(result.getTestsToRun().contains(FOO_TOP_SUITE));
        assertFalse(result.getTestsToRun().contains(FOO_BOTTOM_SUITE));
        assertEquals(Collections.singletonList(FOO_KEY), reader.contentLoadedForKeys());
    }

    @Test
    void baselineNotFoundTreatsEveryLocallyChangedFileAsImpacted() {
        // given - the stored mapping commit is not in the local repository
        StubVCSReader reader = new StubVCSReader(MissingUpstreamChanges.baselineNotFound(),
                modifiedDiff(FOO_KEY), modifiedDiff(BAR_KEY));
        TestSelector selector = new TestSelector(dataStore, new File("."));

        // when
        TestSelectorResult result = selectLocal(selector, reader);

        // then - every tracked method in both files is impacted and nothing is content-loaded
        assertTrue(result.getTestsToRun().containsAll(Arrays.asList(FOO_TOP_SUITE, FOO_BOTTOM_SUITE, BAR_SUITE)));
        assertTrue(reader.contentLoadedForKeys().isEmpty());
    }

    @Test
    void commitRangeModeDoesNotCheckForMissingUpstreamChanges() {
        // given - a reader that would report Foo as missing upstream changes
        StubVCSReader reader = new StubVCSReader(
                MissingUpstreamChanges.of(Collections.singletonMap(FOO_KEY, Collections.singletonList("16"))),
                modifiedDiff(FOO_KEY));
        TestSelector selector = new TestSelector(dataStore, new File("."));

        // when - a commit-range (not local-changes) selection
        TestSelectorResult result = selector.selectTestsToIgnore(reader,
                Collections.emptyList(), Collections.emptyList(),
                TestSuiteRunnabilityClassifier.disabled(),
                false, null, null, false, SelectionMode.SELECTIVE);

        // then - the check is skipped and Foo is matched by line
        assertTrue(reader.checkedKeys().isEmpty());
        assertTrue(result.getTestsToRun().contains(FOO_TOP_SUITE));
        assertFalse(result.getTestsToRun().contains(FOO_BOTTOM_SUITE));
    }

    /**
     * Run a local-changes selection with no source/test dirs, no library or static config.
     *
     * @param selector the selector under test
     * @param reader the stub reader
     * @return the selection result
     */
    private static TestSelectorResult selectLocal(TestSelector selector, StubVCSReader reader) {
        return selector.selectTestsToIgnore(reader,
                Collections.emptyList(), Collections.emptyList(),
                TestSuiteRunnabilityClassifier.disabled(),
                true, null, null, false, SelectionMode.SELECTIVE);
    }

    /**
     * Seed a sealed mapping: {@code Foo.java} has a top method (lines 2-8) covered by
     * {@code FooTopTest} and a bottom method (lines 20-30) covered by {@code FooBottomTest};
     * {@code Bar.java} has one method (lines 2-8) covered by {@code BarTest}.
     */
    private void seedMapping() {
        TiaData tiaData = dataStore.getTiaData();
        tiaData.setCommitValue("seed-commit");
        tiaData.setLastUpdated(Instant.now());

        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        methods.put(FOO_TOP_METHOD, new MethodImpactTracker("com/example/Foo.top.()V", 2, 8));
        methods.put(FOO_BOTTOM_METHOD, new MethodImpactTracker("com/example/Foo.bottom.()V", 20, 30));
        methods.put(BAR_METHOD, new MethodImpactTracker("com/example/Bar.method.()V", 2, 8));

        Map<String, TestSuiteTracker> testSuites = new HashMap<>();
        testSuites.put(FOO_TOP_SUITE, suite(FOO_TOP_SUITE, FOO_KEY, FOO_TOP_METHOD));
        testSuites.put(FOO_BOTTOM_SUITE, suite(FOO_BOTTOM_SUITE, FOO_KEY, FOO_BOTTOM_METHOD));
        testSuites.put(BAR_SUITE, suite(BAR_SUITE, BAR_KEY, BAR_METHOD));

        tiaData.setTestSuitesTracked(testSuites);
        tiaData.setMethodsTracked(methods);
        dataStore.persistCoreData(tiaData);
        dataStore.persistTestSuites(testSuites);
        dataStore.persistSourceMethods(methods);
        dataStore.clearUnsealedTestSuites();
    }

    /**
     * Build a suite covering one method in one source file.
     *
     * @param suiteName the suite name
     * @param fileKey the mapping key of the covered source file
     * @param methodId the covered method id
     * @return the suite tracker
     */
    private static TestSuiteTracker suite(String suiteName, String fileKey, int methodId) {
        TestSuiteTracker suite = new TestSuiteTracker(suiteName);
        suite.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker(fileKey, new HashSet<>(Collections.singletonList(methodId)))));
        return suite;
    }

    /**
     * Build a MODIFY diff for a mapping-key file, with the key as its fetch key. The leading slash
     * and empty source dirs make the selector's normalization yield the mapping key verbatim.
     *
     * @param mappingKey the stored mapping key
     * @return a content-less MODIFY diff context for that file
     */
    private static SourceFileDiffContext modifiedDiff(String mappingKey) {
        String path = "/" + mappingKey;
        SourceFileDiffContext diff = new SourceFileDiffContext(path, path, ChangeType.MODIFY);
        diff.setVcsFetchKey(mappingKey);
        return diff;
    }

    /**
     * Stub reader returning a fixed diff set and a fixed missing-upstream-changes result. It
     * records the files the check and the content load are asked about. Loaded content changes
     * line 5, inside the 2-8 range of the top method in each file.
     */
    private static final class StubVCSReader implements VCSReader {
        private final MissingUpstreamChanges missingUpstreamChanges;
        private final Set<SourceFileDiffContext> diffs;
        private final List<String> checkedKeys = new ArrayList<>();
        private final List<String> contentLoadedKeys = new ArrayList<>();

        /**
         * @param missingUpstreamChanges the result to return from the check
         * @param diffs the local diff
         */
        StubVCSReader(MissingUpstreamChanges missingUpstreamChanges, SourceFileDiffContext... diffs) {
            this.missingUpstreamChanges = missingUpstreamChanges;
            this.diffs = new HashSet<>(Arrays.asList(diffs));
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
            for (SourceFileDiffContext diff : diffsToLoad) {
                contentLoadedKeys.add(diff.getVcsFetchKey());
                diff.setSourceContentOriginal("l1\nl2\nl3\nl4\nl5-old\nl6\nl7\nl8\nl9\nl10\n");
                diff.setSourceContentNew("l1\nl2\nl3\nl4\nl5-new\nl6\nl7\nl8\nl9\nl10\n");
            }
        }

        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            return new HashSet<>();
        }

        /**
         * Content-by-path loading is not exercised by these tests.
         *
         * @param paths unused
         * @param baseChangeNum unused
         * @param checkLocalChanges unused
         * @return never returns
         */
        @Override
        public Map<String, FileContentPair> loadFileContents(Set<String> paths, String baseChangeNum,
                                                             boolean checkLocalChanges) {
            throw new UnsupportedOperationException("loadFileContents is not used by this test");
        }

        /**
         * Record the files checked and return the fixed result.
         *
         * @param baseChangeNum ignored
         * @param localDiffs the files to check
         * @return the fixed result
         */
        @Override
        public MissingUpstreamChanges findMissingUpstreamChanges(String baseChangeNum,
                                                                 Collection<SourceFileDiffContext> localDiffs) {
            for (SourceFileDiffContext diff : localDiffs) {
                checkedKeys.add(diff.getVcsFetchKey());
            }
            return missingUpstreamChanges;
        }

        @Override public void close() { }

        /** @return the fetch keys of the files checked for missing upstream changes */
        List<String> checkedKeys() { return checkedKeys; }

        /** @return the fetch keys of the files whose content was loaded, sorted */
        List<String> contentLoadedForKeys() {
            List<String> sorted = new ArrayList<>(contentLoadedKeys);
            Collections.sort(sorted);
            return sorted;
        }
    }
}
