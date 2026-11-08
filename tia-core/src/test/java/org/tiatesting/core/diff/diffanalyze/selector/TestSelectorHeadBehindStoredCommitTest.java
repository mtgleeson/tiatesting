package org.tiatesting.core.diff.diffanalyze.selector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run that updates the mapping from a workspace whose head is behind the stored commit is
 * refused before anything is diffed or written, unless it is a re-seed. Runs that don't update the
 * mapping are never refused and don't pay for the check. See the "Persist flow and crash safety"
 * chapter in {@code WIKI.md}.
 *
 * <p>Modeled on {@code TestSelectorForcedModeTest}: an embedded H2-backed {@link JdbcDataStore}
 * seeded through the persist methods, with a stub {@link VCSReader} whose head-behind answer is
 * fixed and which counts the diff requests and head-behind checks it gets.
 */
class TestSelectorHeadBehindStoredCommitTest {

    private static final String STORED_COMMIT = "1010";
    private static final String HEAD_COMMIT = "1005";

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a temp-directory-backed embedded {@link JdbcDataStore} and bootstrap its schema, so
     * each test starts from an empty, independent DB.
     *
     * @throws Exception if the temp directory or the underlying H2 database cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-head-behind-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
        dataStore.getTiaData();
    }

    /**
     * Close the data store and remove the temp directory created in {@link #setUp()}.
     */
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
     * A mapping run whose head is behind the stored commit is refused before anything is diffed,
     * with an error naming both commits and the two ways forward.
     */
    @Test
    void mappingRunBehindStoredCommit_isRefused() {
        // given
        seedTrackedSuite("com.example.ATest", 1);
        HeadBehindVCSReader reader = new HeadBehindVCSReader(true);

        // when
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> select(reader, true, SelectionMode.SELECTIVE));

        // then
        assertTrue(error.getMessage().contains(HEAD_COMMIT));
        assertTrue(error.getMessage().contains(STORED_COMMIT));
        assertTrue(error.getMessage().contains("tiaUpdateDBMapping=false"));
        assertTrue(error.getMessage().contains("tiaReseed=true"));
        assertEquals(0, reader.diffRequests);
    }

    /**
     * Select-all updates the mapping in place and is refused like a selective mapping run.
     */
    @Test
    void selectAllBehindStoredCommit_isRefused() {
        // given
        seedTrackedSuite("com.example.ATest", 1);
        HeadBehindVCSReader reader = new HeadBehindVCSReader(true);

        // when
        Runnable selectAll = () -> select(reader, true, SelectionMode.SELECT_ALL);

        // then
        assertThrows(IllegalStateException.class, selectAll::run);
    }

    /**
     * A re-seed rebuilds the whole mapping, so it may run at an older commit.
     */
    @Test
    void reseedBehindStoredCommit_isAllowed() {
        // given
        seedTrackedSuite("com.example.ATest", 1);
        HeadBehindVCSReader reader = new HeadBehindVCSReader(true);

        // when
        TestSelectorResult result = select(reader, true, SelectionMode.RESEED);

        // then
        assertEquals(SelectionMode.RESEED, result.getSelectionMode());
        assertEquals(Collections.singleton("com.example.ATest"), result.getTestsToRun());
        assertEquals(1, reader.behindChecks);
    }

    /**
     * A run that doesn't update the mapping is never refused, still diffs, and doesn't pay for the
     * check.
     */
    @Test
    void nonMappingRunBehindStoredCommit_selectsAsUsual() {
        // given
        seedTrackedSuite("com.example.ATest", 1);
        HeadBehindVCSReader reader = new HeadBehindVCSReader(true);

        // when
        TestSelectorResult result = select(reader, false, SelectionMode.SELECTIVE);

        // then
        assertEquals(SelectionMode.SELECTIVE, result.getSelectionMode());
        assertTrue(reader.diffRequests > 0);
        assertEquals(0, reader.behindChecks, "a non-mapping run must not pay for the check");
    }

    /**
     * A mapping run at or ahead of the stored commit selects as usual.
     */
    @Test
    void mappingRunNotBehind_selectsAsUsual() {
        // given
        seedTrackedSuite("com.example.ATest", 1);
        HeadBehindVCSReader reader = new HeadBehindVCSReader(false);

        // when
        TestSelectorResult result = select(reader, true, SelectionMode.SELECTIVE);

        // then
        assertEquals(SelectionMode.SELECTIVE, result.getSelectionMode());
        assertTrue(reader.diffRequests > 0);
        assertEquals(1, reader.behindChecks);
    }

    /**
     * With no stored commit there is nothing to be behind: the seed path runs without the check.
     */
    @Test
    void noStoredCommit_isASeedWithoutTheCheck() {
        // given - an empty database
        HeadBehindVCSReader reader = new HeadBehindVCSReader(true);

        // when
        TestSelectorResult result = select(reader, true, SelectionMode.SELECTIVE);

        // then
        assertEquals(SelectionMode.SEED, result.getSelectionMode());
        assertEquals(0, reader.behindChecks);
    }

    /**
     * Run selection with the given reader, mapping flag and mode.
     *
     * @param reader the VCS reader
     * @param updateDBMapping whether the run owns the mapping
     * @param mode the requested selection mode
     * @return the selection result
     */
    private TestSelectorResult select(VCSReader reader, boolean updateDBMapping, SelectionMode mode) {
        return new TestSelector(dataStore, new File(".")).selectTestsToIgnore(reader, Collections.emptyList(),
                Collections.emptyList(), TestSuiteRunnabilityClassifier.disabled(),
                false, null, null, updateDBMapping, mode);
    }

    /**
     * Seed the DB with the stored commit and a tracked, sealed suite covering one method.
     *
     * @param suiteName the test suite name to register as tracked
     * @param methodId the impacted method id to attach, so the suite has non-empty coverage
     */
    private void seedTrackedSuite(String suiteName, int methodId) {
        TiaData tiaData = dataStore.getTiaData();
        tiaData.setCommitValue(STORED_COMMIT);
        tiaData.setLastUpdated(Instant.now());

        Map<Integer, MethodImpactTracker> methods = new HashMap<>(dataStore.getMethodsTracked());
        methods.put(methodId, new MethodImpactTracker(suiteName + ".someMethod", 1, 10));

        Map<String, TestSuiteTracker> testSuites = new HashMap<>(dataStore.getTestSuitesTracked());
        TestSuiteTracker tracker = new TestSuiteTracker(suiteName);
        tracker.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker("com/example/" + suiteName + "Source.java",
                        new HashSet<>(Collections.singletonList(methodId)))));
        testSuites.put(suiteName, tracker);

        tiaData.setTestSuitesTracked(testSuites);
        tiaData.setMethodsTracked(methods);
        dataStore.persistCoreData(tiaData);
        dataStore.persistTestSuites(testSuites);
        dataStore.persistSourceMethods(methods);
        dataStore.clearUnsealedTestSuites();
    }

    /**
     * VCS reader at {@link #HEAD_COMMIT} that reports no changes, gives a fixed answer to
     * {@link #isHeadBehind}, and counts the diff requests and head-behind checks it gets.
     */
    private static final class HeadBehindVCSReader implements VCSReader {
        private final boolean behind;
        private int diffRequests;
        private int behindChecks;

        /**
         * @param behind what {@link #isHeadBehind} answers
         */
        HeadBehindVCSReader(boolean behind) {
            this.behind = behind;
        }

        @Override
        public String getBranchName() {
            return "test";
        }

        @Override
        public String getHeadCommit() {
            return HEAD_COMMIT;
        }

        @Override
        public boolean isHeadBehind(String commit) {
            behindChecks++;
            return behind;
        }

        @Override
        public Set<SourceFileDiffContext> getDiffFiles(String baseChangeNum, List<String> sourceFilesDirs,
                                                       List<String> testFilesDirs, boolean checkLocalChanges) {
            diffRequests++;
            return Collections.emptySet();
        }

        @Override
        public void loadContentForDiffs(Collection<SourceFileDiffContext> diffs, String baseChangeNum,
                                        boolean checkLocalChanges) {
            // no-op: this stub returns no diffs
        }

        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            diffRequests++;
            return Collections.emptySet();
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

        @Override
        public void close() {
        }
    }
}
