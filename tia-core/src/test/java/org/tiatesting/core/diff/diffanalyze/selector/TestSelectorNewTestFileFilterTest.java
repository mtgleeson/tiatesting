package org.tiatesting.core.diff.diffanalyze.selector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.model.SelectionMode;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.testrunner.ClassFileFixture;
import org.tiatesting.core.testrunner.TestSuiteRunnabilityClassifier;
import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.MappingDrift;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cover the plan-time filter on newly added test files in {@link TestSelector}: a new file whose
 * compiled class proves it is not a test (a helper with no test methods, an abstract base) is not
 * selected, while a new real test still is. Without compiled classes - the usual state when
 * selection runs before test compilation - every new file is kept, since a missing class file
 * proves nothing.
 */
class TestSelectorNewTestFileFilterTest {

    private static final String NEW_TEST = "com.example.NewTest";
    private static final String NEW_HELPER = "com.example.NewHelper";
    private static final String NEW_BASE = "com.example.AbstractNewBase";

    private JdbcDataStore dataStore;
    private File dbDir;
    private File projectDir;
    private Path testClassesDir;

    /**
     * Create an embedded H2 store with a stored mapping (so selection is selective, not a seed), a
     * project dir holding the configured test source dir, and an empty test-classes dir.
     *
     * @param tempDir the JUnit temp dir everything lives under
     * @throws IOException if a directory cannot be created
     */
    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        dbDir = Files.createDirectories(tempDir.resolve("db")).toFile();
        projectDir = Files.createDirectories(tempDir.resolve("project")).toFile().getCanonicalFile();
        Files.createDirectories(projectDir.toPath().resolve("src/main/java"));
        Files.createDirectories(projectDir.toPath().resolve("src/test/java"));
        testClassesDir = Files.createDirectories(tempDir.resolve("test-classes"));
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(dbDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
        seedMapping();
    }

    /**
     * Close the store so embedded H2 releases its file lock.
     */
    @AfterEach
    void tearDown() {
        dataStore.close();
    }

    @Test
    void newHelperAndAbstractBaseAreNotSelectedWhenCompiledClassesShowTheyAreNotTests() throws IOException {
        // given - three new files, compiled: a real test, a helper and an abstract base
        ClassFileFixture.classIn(testClassesDir, NEW_TEST).method("works", "org.junit.jupiter.api.Test").write();
        ClassFileFixture.classIn(testClassesDir, NEW_HELPER).method("fetch", null).write();
        ClassFileFixture.classIn(testClassesDir, NEW_BASE).access(Opcodes.ACC_ABSTRACT)
                .method("works", "org.junit.jupiter.api.Test").write();

        // when
        TestSelectorResult result = select(TestSuiteRunnabilityClassifier.forDirs(
                Collections.singletonList(testClassesDir.toString())));

        // then
        assertEquals(Collections.singleton(NEW_TEST), result.getTestsToRun());
        assertEquals(1, result.getSelectionDetails().getNumNewTestFiles());
    }

    @Test
    void allNewFilesAreSelectedWhenNoCompiledClassesExist() {
        // given - the test-classes dir exists but nothing has been compiled into it yet

        // when
        TestSelectorResult result = select(TestSuiteRunnabilityClassifier.forDirs(
                Collections.singletonList(testClassesDir.toString())));

        // then - a missing class file proves nothing, so every new file is kept
        assertEquals(new HashSet<>(Arrays.asList(NEW_TEST, NEW_HELPER, NEW_BASE)), result.getTestsToRun());
        assertEquals(3, result.getSelectionDetails().getNumNewTestFiles());
    }

    @Test
    void allNewFilesAreSelectedWithADisabledClassifier() throws IOException {
        // given - compiled classes exist, but the caller has no test-classes dir to give
        ClassFileFixture.classIn(testClassesDir, NEW_HELPER).method("fetch", null).write();

        // when
        TestSelectorResult result = select(TestSuiteRunnabilityClassifier.disabled());

        // then
        assertEquals(new HashSet<>(Arrays.asList(NEW_TEST, NEW_HELPER, NEW_BASE)), result.getTestsToRun());
    }

    @Test
    void aNewFileWhoseClassFileCannotBeReadIsKeptRatherThanFailingSelection() throws IOException {
        // given - the helper's class file is corrupt (an interrupted compile, or a too-new Java version)
        Path corrupt = testClassesDir.resolve(NEW_HELPER.replace('.', '/') + ".class");
        Files.createDirectories(corrupt.getParent());
        Files.write(corrupt, new byte[]{1, 2, 3});

        // when
        TestSelectorResult result = select(TestSuiteRunnabilityClassifier.forDirs(
                Collections.singletonList(testClassesDir.toString())));

        // then - selection completes, and a file Tia cannot judge is kept
        assertEquals(new HashSet<>(Arrays.asList(NEW_TEST, NEW_HELPER, NEW_BASE)), result.getTestsToRun());
    }

    /**
     * Run a selective selection over a diff that adds the three new test files.
     *
     * @param classifier the runnability classifier to select with
     * @return the selection result
     */
    private TestSelectorResult select(final TestSuiteRunnabilityClassifier classifier) {
        String testDir = new File(projectDir, "src/test/java").getAbsolutePath();
        Set<SourceFileDiffContext> diffs = new HashSet<>();
        for (String suite : Arrays.asList(NEW_TEST, NEW_HELPER, NEW_BASE)) {
            String path = testDir + "/" + suite.replace('.', '/') + ".java";
            diffs.add(new SourceFileDiffContext(null, path, ChangeType.ADD));
        }
        return new TestSelector(dataStore, projectDir).selectTestsToIgnore(new AddedFilesVCSReader(diffs),
                Collections.singletonList("src/main/java"), Collections.singletonList("src/test/java"),
                classifier, false, null, null, false, SelectionMode.SELECTIVE);
    }

    /**
     * Store a mapping for one existing suite, so selection runs selectively against the diff.
     */
    private void seedMapping() {
        TiaData tiaData = dataStore.getTiaData();
        tiaData.setCommitValue("seed-commit");
        tiaData.setLastUpdated(Instant.now());
        Map<String, TestSuiteTracker> testSuites = new HashMap<>();
        testSuites.put("com.example.ExistingTest", new TestSuiteTracker("com.example.ExistingTest"));
        tiaData.setTestSuitesTracked(testSuites);
        dataStore.persistCoreData(tiaData);
        dataStore.persistTestSuites(testSuites);
        dataStore.clearUnsealedTestSuites();
    }

    /**
     * A VCS reader whose diff is a fixed set of added files and which reports no other changes.
     */
    private static final class AddedFilesVCSReader implements VCSReader {
        private final Set<SourceFileDiffContext> diffs;

        /**
         * @param diffs the added-file diff contexts to report
         */
        AddedFilesVCSReader(final Set<SourceFileDiffContext> diffs) {
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
        public void loadContentForDiffs(Collection<SourceFileDiffContext> diffsToLoad, String baseChangeNum,
                                        boolean checkLocalChanges) {
            for (SourceFileDiffContext diff : diffsToLoad) {
                diff.setSourceContentOriginal("");
                diff.setSourceContentNew("");
            }
        }

        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            return Collections.emptySet();
        }

        /**
         * Not used by this test.
         *
         * @param paths ignored
         * @param baseChangeNum ignored
         * @param checkLocalChanges ignored
         * @return never returns
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

        @Override
        public void close() {
        }
    }
}
