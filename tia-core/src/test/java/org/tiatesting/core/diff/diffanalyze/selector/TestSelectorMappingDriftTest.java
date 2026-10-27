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
import org.tiatesting.core.vcs.MappingDrift;
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
 * Verifies that in local-changes mode a locally changed file that has drifted from its mapped
 * version (behind or ahead of it) has every tracked method treated as impacted, instead of matching
 * its diff to tracked methods by line number. See the "Local changes and a workspace out of step
 * with the mapping" chapter in {@code WIKI.md}.
 */
class TestSelectorMappingDriftTest {

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
        tempDir = File.createTempFile("tia-mapping-drift-", "");
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
    void fileBehindTheMappingHasAllTrackedMethodsImpacted() {
        // given - Foo and Bar are both edited on line 5; the mapping has changes to Foo the workspace doesn't
        StubVCSReader reader = new StubVCSReader(
                driftOf(FOO_KEY, new MappingDrift.FileDrift(Arrays.asList("16", "18"), Collections.emptyList())),
                modifiedDiff(FOO_KEY), modifiedDiff(BAR_KEY));
        TestSelector selector = new TestSelector(dataStore, new File("."));

        // when
        TestSelectorResult result = selectLocal(selector, reader);

        // then - both Foo methods' suites run, though the edit only touches the top method's lines
        assertTrue(result.getTestsToRun().contains(FOO_TOP_SUITE));
        assertTrue(result.getTestsToRun().contains(FOO_BOTTOM_SUITE),
                "every tracked method in a drifted file must be treated as impacted");
        assertTrue(result.getTestsToRun().contains(BAR_SUITE), "Bar is still matched by line");

        // and - Foo's content is not loaded, because its line numbers aren't used
        assertEquals(Collections.singletonList(BAR_KEY), reader.contentLoadedForKeys());
        assertEquals(new HashSet<>(Arrays.asList(FOO_KEY, BAR_KEY)), new HashSet<>(reader.checkedKeys()));
    }

    @Test
    void fileAheadOfTheMappingHasAllTrackedMethodsImpacted() {
        // given - Foo is edited on line 5; the workspace has a change to Foo the mapping doesn't
        StubVCSReader reader = new StubVCSReader(
                driftOf(FOO_KEY, new MappingDrift.FileDrift(Collections.emptyList(), Collections.singletonList("19"))),
                modifiedDiff(FOO_KEY));
        TestSelector selector = new TestSelector(dataStore, new File("."));

        // when
        TestSelectorResult result = selectLocal(selector, reader);

        // then - both Foo methods' suites run and Foo's content is not loaded
        assertTrue(result.getTestsToRun().containsAll(Arrays.asList(FOO_TOP_SUITE, FOO_BOTTOM_SUITE)));
        assertTrue(reader.contentLoadedForKeys().isEmpty());
    }

    @Test
    void fileWithNoMappingDriftIsMatchedByLine() {
        // given - Foo is edited on line 5 and the workspace version of Foo is the mapped version
        StubVCSReader reader = new StubVCSReader(MappingDrift.none(), modifiedDiff(FOO_KEY));
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
        StubVCSReader reader = new StubVCSReader(MappingDrift.baselineNotFound(),
                modifiedDiff(FOO_KEY), modifiedDiff(BAR_KEY));
        TestSelector selector = new TestSelector(dataStore, new File("."));

        // when
        TestSelectorResult result = selectLocal(selector, reader);

        // then - every tracked method in both files is impacted and nothing is content-loaded
        assertTrue(result.getTestsToRun().containsAll(Arrays.asList(FOO_TOP_SUITE, FOO_BOTTOM_SUITE, BAR_SUITE)));
        assertTrue(reader.contentLoadedForKeys().isEmpty());
    }

    @Test
    void commitRangeModeDoesNotCheckForMappingDrift() {
        // given - a reader that would report Foo as drifted
        StubVCSReader reader = new StubVCSReader(
                driftOf(FOO_KEY, new MappingDrift.FileDrift(Collections.singletonList("16"), Collections.emptyList())),
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

    @Test
    void gitCommitIdIsAbbreviatedAndChangelistIsNot() {
        // given
        String gitCommit = "0123456789abcdef0123456789abcdef01234567";

        // when
        String abbreviatedGit = TestSelector.abbreviateCommit(gitCommit);
        String changelist = TestSelector.abbreviateCommit("1234");

        // then
        assertEquals("01234567", abbreviatedGit);
        assertEquals("1234", changelist);
    }

    /**
     * @param fetchKey the drifted file's fetch key
     * @param fileDrift its drift
     * @return a drift result for that one file
     */
    private static MappingDrift driftOf(String fetchKey, MappingDrift.FileDrift fileDrift) {
        return MappingDrift.of(Collections.singletonMap(fetchKey, fileDrift));
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
     * Stub reader returning a fixed diff set and a fixed mapping drift result. It
     * records the files the check and the content load are asked about. Loaded content changes
     * line 5, inside the 2-8 range of the top method in each file.
     */
    private static final class StubVCSReader implements VCSReader {
        private final MappingDrift drift;
        private final Set<SourceFileDiffContext> diffs;
        private final List<String> checkedKeys = new ArrayList<>();
        private final List<String> contentLoadedKeys = new ArrayList<>();

        /**
         * @param drift the result to return from the check
         * @param diffs the local diff
         */
        StubVCSReader(MappingDrift drift, SourceFileDiffContext... diffs) {
            this.drift = drift;
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
        public MappingDrift findMappingDrift(String baseChangeNum, Collection<SourceFileDiffContext> localDiffs) {
            for (SourceFileDiffContext diff : localDiffs) {
                checkedKeys.add(diff.getVcsFetchKey());
            }
            return drift;
        }

        @Override public void close() { }

        /** @return the fetch keys of the files checked for mapping drift */
        List<String> checkedKeys() { return checkedKeys; }

        /** @return the fetch keys of the files whose content was loaded, sorted */
        List<String> contentLoadedForKeys() {
            List<String> sorted = new ArrayList<>(contentLoadedKeys);
            Collections.sort(sorted);
            return sorted;
        }
    }
}
