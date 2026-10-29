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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the selection of suites that use a changed test support class in {@link TestSelector}: a
 * modified, new (proven not a test) or deleted test file that isn't a tracked suite selects the
 * tracked suites whose sources refer to it, a changed test file no suite uses is logged, and a
 * change to tracked suites alone never reads the test sources. INFO output is captured from
 * {@code System.err}, where slf4j-simple, the test runtime, writes at its default INFO level.
 */
class TestSelectorTestSupportFilesTest {

    private static final String ORDER_TEST = "com.example.OrderTest";
    private static final String INVOICE_TEST = "com.example.InvoiceTest";
    private static final String OTHER_TEST = "com.example.OtherTest";
    private static final String SUPPORT_LOG = "test(s) to run from changed test support files";

    private JdbcDataStore dataStore;
    private File projectDir;
    private Path testDir;
    private Path testClassesDir;

    /**
     * Create an embedded H2 store with three tracked suites, and a project whose test sources show
     * which support classes each suite uses: OrderTest extends BaseTest, InvoiceTest imports
     * support.InvoiceHelper, and OtherTest uses neither.
     *
     * @param tempDir the JUnit temp dir everything lives under
     * @throws IOException if a directory or source file cannot be created
     */
    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        File dbDir = Files.createDirectories(tempDir.resolve("db")).toFile();
        projectDir = Files.createDirectories(tempDir.resolve("project")).toFile().getCanonicalFile();
        Files.createDirectories(projectDir.toPath().resolve("src/main/java"));
        testDir = Files.createDirectories(projectDir.toPath().resolve("src/test/java"));
        testClassesDir = Files.createDirectories(tempDir.resolve("test-classes"));
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(dbDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
        seedMapping();

        source("com.example.BaseTest", "package com.example;", "public abstract class BaseTest {}");
        source("com.example.support.InvoiceHelper", "package com.example.support;", "public class InvoiceHelper {}");
        source("com.example.support.UnusedHelper", "package com.example.support;", "public class UnusedHelper {}");
        source(ORDER_TEST, "package com.example;", "class OrderTest extends BaseTest {}");
        source(INVOICE_TEST, "package com.example;", "import com.example.support.InvoiceHelper;",
                "class InvoiceTest { InvoiceHelper helper; }");
        source(OTHER_TEST, "package com.example;", "class OtherTest {}");
    }

    /**
     * Close the store so embedded H2 releases its file lock.
     */
    @AfterEach
    void tearDown() {
        dataStore.close();
    }

    @Test
    void modifiedBaseClassSelectsTheSuitesExtendingIt() throws Exception {
        // given
        SourceFileDiffContext diff = diff("com.example.BaseTest", ChangeType.MODIFY);

        // when
        Selection selection = select(TestSuiteRunnabilityClassifier.disabled(), diff);

        // then
        assertEquals(Collections.singleton(ORDER_TEST), selection.result.getTestsToRun());
        assertEquals(1, selection.result.getSelectionDetails().getNumTestSupportDependants());
        assertTrue(selection.log.contains("Selected 1 " + SUPPORT_LOG + ": [" + ORDER_TEST + "]"),
                "expected the support-file selection line, got: " + selection.log);
    }

    @Test
    void modifiedHelperInAnotherPackageSelectsTheSuitesImportingIt() throws Exception {
        // given
        SourceFileDiffContext diff = diff("com.example.support.InvoiceHelper", ChangeType.MODIFY);

        // when
        Selection selection = select(TestSuiteRunnabilityClassifier.disabled(), diff);

        // then
        assertEquals(Collections.singleton(INVOICE_TEST), selection.result.getTestsToRun());
    }

    @Test
    void modifiedHelperNoSuiteUsesIsLoggedAndSelectsNothing() throws Exception {
        // given
        SourceFileDiffContext diff = diff("com.example.support.UnusedHelper", ChangeType.MODIFY);

        // when
        Selection selection = select(TestSuiteRunnabilityClassifier.disabled(), diff);

        // then
        assertEquals(Collections.emptySet(), selection.result.getTestsToRun());
        assertEquals(0, selection.result.getSelectionDetails().getNumTestSupportDependants());
        assertTrue(selection.log.contains(
                "Changed test file(s) not used by any tracked suite: [com.example.support.UnusedHelper]"),
                "expected the unused-file line, got: " + selection.log);
    }

    @Test
    void newAbstractBaseProvenNotATestSelectsTheSuitesUsingIt() throws Exception {
        // given - a new compiled abstract base class, already used by InvoiceTest's source
        source("com.example.NewBase", "package com.example;", "public abstract class NewBase {}");
        source(INVOICE_TEST, "package com.example;", "class InvoiceTest extends NewBase {}");
        ClassFileFixture.classIn(testClassesDir, "com.example.NewBase").access(Opcodes.ACC_ABSTRACT).write();

        // when
        Selection selection = select(TestSuiteRunnabilityClassifier.forDirs(
                Collections.singletonList(testClassesDir.toString())), diff("com.example.NewBase", ChangeType.ADD));

        // then - the base itself isn't selected, the suite using it is
        assertEquals(Collections.singleton(INVOICE_TEST), selection.result.getTestsToRun());
        assertEquals(0, selection.result.getSelectionDetails().getNumNewTestFiles());
    }

    @Test
    void deletedHelperSelectsTheSuitesStillReferringToIt() throws Exception {
        // given - the helper's source is gone, InvoiceTest still imports it
        Files.delete(testDir.resolve("com/example/support/InvoiceHelper.java"));

        // when
        Selection selection = select(TestSuiteRunnabilityClassifier.disabled(),
                diff("com.example.support.InvoiceHelper", ChangeType.DELETE));

        // then
        assertEquals(Collections.singleton(INVOICE_TEST), selection.result.getTestsToRun());
    }

    @Test
    void modifiedTrackedSuitesAloneNeverReadTheTestSources() throws Exception {
        // given
        SourceFileDiffContext diff = diff(ORDER_TEST, ChangeType.MODIFY);

        // when
        Selection selection = select(TestSuiteRunnabilityClassifier.disabled(), diff);

        // then - the suite is selected as a modified test file, and the support-file step returned early
        assertEquals(Collections.singleton(ORDER_TEST), selection.result.getTestsToRun());
        assertFalse(selection.log.contains(SUPPORT_LOG), "no support-file selection expected, got: " + selection.log);
        assertFalse(selection.log.contains("not used by any tracked suite"),
                "no unused-file line expected, got: " + selection.log);
    }

    @Test
    void supportFileChangedWithASuiteSelectsBoth() throws Exception {
        // given - the base class and an unrelated suite both changed
        SourceFileDiffContext baseDiff = diff("com.example.BaseTest", ChangeType.MODIFY);
        SourceFileDiffContext suiteDiff = diff(OTHER_TEST, ChangeType.MODIFY);

        // when
        Selection selection = select(TestSuiteRunnabilityClassifier.disabled(), baseDiff, suiteDiff);

        // then
        assertEquals(new HashSet<>(Arrays.asList(ORDER_TEST, OTHER_TEST)), selection.result.getTestsToRun());
        assertEquals(1, selection.result.getSelectionDetails().getNumModifiedTestFiles());
        assertEquals(1, selection.result.getSelectionDetails().getNumTestSupportDependants());
    }

    /**
     * Holder for one selection: its result and everything logged to {@code System.err} during it.
     */
    private static final class Selection {
        private final TestSelectorResult result;
        private final String log;

        /**
         * @param result the selection result
         * @param log the captured output
         */
        private Selection(final TestSelectorResult result, final String log) {
            this.result = result;
            this.log = log;
        }
    }

    /**
     * Run a selective selection over the given test-file diffs, capturing what is logged.
     *
     * @param classifier the runnability classifier to select with
     * @param diffs the changed test files
     * @return the result and the captured output
     * @throws IOException if the captured output can't be decoded
     */
    private Selection select(final TestSuiteRunnabilityClassifier classifier, final SourceFileDiffContext... diffs)
            throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8.name()));
        TestSelectorResult result;
        try {
            result = new TestSelector(dataStore, projectDir).selectTestsToIgnore(
                    new FixedDiffVCSReader(new HashSet<>(Arrays.asList(diffs))),
                    Collections.singletonList("src/main/java"), Collections.singletonList("src/test/java"),
                    classifier, false, null, null, false, SelectionMode.SELECTIVE);
        } finally {
            System.setErr(originalErr);
        }
        return new Selection(result, new String(captured.toByteArray(), StandardCharsets.UTF_8));
    }

    /**
     * Build the diff context for a changed test file: an added file has only a new path, a deleted
     * file only an old path, a modified file both.
     *
     * @param className the test class the file holds
     * @param changeType how the file changed
     * @return the diff context
     */
    private SourceFileDiffContext diff(final String className, final ChangeType changeType) {
        String path = testDir + "/" + className.replace('.', '/') + ".java";
        return new SourceFileDiffContext(changeType == ChangeType.ADD ? null : path,
                changeType == ChangeType.DELETE ? null : path, changeType);
    }

    /**
     * Write a test source file into the project's test dir.
     *
     * @param className the class the file holds
     * @param lines the file's lines
     * @throws IOException if the file can't be written
     */
    private void source(final String className, final String... lines) throws IOException {
        Path file = testDir.resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.write(file, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Store a mapping for the three tracked suites, so selection runs selectively against the diff.
     */
    private void seedMapping() {
        TiaData tiaData = dataStore.getTiaData();
        tiaData.setCommitValue("seed-commit");
        tiaData.setLastUpdated(Instant.now());
        Map<String, TestSuiteTracker> testSuites = new HashMap<>();
        for (String suite : Arrays.asList(ORDER_TEST, INVOICE_TEST, OTHER_TEST)) {
            testSuites.put(suite, new TestSuiteTracker(suite));
        }
        tiaData.setTestSuitesTracked(testSuites);
        dataStore.persistCoreData(tiaData);
        dataStore.persistTestSuites(testSuites);
        dataStore.clearUnsealedTestSuites();
    }

    /**
     * A VCS reader whose diff is a fixed set of changed files and which reports no other changes.
     */
    private static final class FixedDiffVCSReader implements VCSReader {
        private final Set<SourceFileDiffContext> diffs;

        /**
         * @param diffs the diff contexts to report
         */
        FixedDiffVCSReader(final Set<SourceFileDiffContext> diffs) {
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
        public MappingDrift findMappingDrift(String baseChangeNum, Collection<SourceFileDiffContext> localDiffs) {
            return MappingDrift.none();
        }

        @Override
        public void close() {
        }
    }
}
