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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A forced mode ({@link SelectionMode#SELECT_ALL} / {@link SelectionMode#RESEED}) must override
 * selection: nothing ignored, every tracked suite the developer has not disabled selected, and the
 * VCS diff never consulted. See the "Forced runs and re-seed" chapter in {@code WIKI.md}.
 *
 * <p>Modeled on {@code TestSelectorUnsealedSuitesTest}: an embedded H2-backed
 * {@link JdbcDataStore} seeded through the persist methods, with a stub {@link VCSReader} that
 * counts how often it is asked for a diff.
 */
class TestSelectorForcedModeTest {

    private JdbcDataStore dataStore;
    private File tempDir;
    private CountingVCSReader vcsReader;

    /**
     * Create a temp-directory-backed embedded {@link JdbcDataStore}, bootstrap its schema, and a
     * fresh counting VCS reader, so each test starts from an empty, independent DB.
     *
     * @throws Exception if the temp directory or the underlying H2 database cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-forced-mode-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
        dataStore.getTiaData();
        vcsReader = new CountingVCSReader();
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
     * Select-all ignores nothing despite a stored mapping, selects every tracked suite the
     * developer has not disabled, carries its mode, and never consults the VCS diff.
     */
    @Test
    void selectAllReturnsAnEmptyIgnoreSetDespiteAStoredMapping() {
        // given
        seedTrackedSuite("com.example.ATest", 1, false);
        seedTrackedSuite("com.example.BTest", 2, true);

        // when
        TestSelectorResult result = select(SelectionMode.SELECT_ALL);

        // then
        assertTrue(result.getTestsToIgnore().isEmpty());
        assertEquals(Collections.singleton("com.example.ATest"), result.getTestsToRun());
        assertEquals(SelectionMode.SELECT_ALL, result.getSelectionMode());
        assertTrue(result.isRunAllTests());
        assertEquals(SelectionMode.SELECT_ALL, result.getSelectionDetails().getSelectionMode());
        assertEquals(0, vcsReader.diffRequests, "a forced run must not consult the VCS diff");
    }

    /**
     * A re-seed selects exactly like select-all; it differs only at seal.
     */
    @Test
    void reseedBehavesLikeSelectAllAtSelectionTime() {
        // given
        seedTrackedSuite("com.example.ATest", 1, false);

        // when
        TestSelectorResult result = select(SelectionMode.RESEED);

        // then
        assertTrue(result.getTestsToIgnore().isEmpty());
        assertEquals(Collections.singleton("com.example.ATest"), result.getTestsToRun());
        assertEquals(SelectionMode.RESEED, result.getSelectionMode());
        assertEquals(SelectionMode.RESEED, result.getSelectionDetails().getSelectionMode());
    }

    /**
     * A forced flag on a database with no stored mapping is simply a seed.
     */
    @Test
    void aForcedModeWithNoStoredMappingIsASeed() {
        // given - an empty database with no stored commit

        // when
        TestSelectorResult result = select(SelectionMode.RESEED);

        // then
        assertEquals(SelectionMode.SEED, result.getSelectionMode());
        assertTrue(result.getTestsToRun().isEmpty());
        assertTrue(result.getTestsToIgnore().isEmpty());
    }

    /**
     * Ordinary selection still diffs and ignores the unimpacted suite.
     */
    @Test
    void selectiveModeStillDiffs() {
        // given
        seedTrackedSuite("com.example.ATest", 1, false);

        // when
        TestSelectorResult result = select(SelectionMode.SELECTIVE);

        // then
        assertEquals(SelectionMode.SELECTIVE, result.getSelectionMode());
        assertTrue(result.getTestsToIgnore().contains("com.example.ATest"));
        assertTrue(vcsReader.diffRequests > 0, "a selective run must diff");
    }

    /**
     * Run selection as a mapping-owning build with the given mode.
     *
     * @param mode the requested selection mode
     * @return the selection result
     */
    private TestSelectorResult select(SelectionMode mode) {
        return new TestSelector(dataStore).selectTestsToIgnore(vcsReader, Collections.emptyList(),
                Collections.emptyList(), false, null, null, true, mode);
    }

    /**
     * Seed the DB with a stored commit and a tracked, sealed suite covering one method.
     *
     * @param suiteName the test suite name to register as tracked
     * @param methodId the impacted method id to attach, so the suite has non-empty coverage
     * @param developerDisabled whether the suite is flagged developer-disabled
     */
    private void seedTrackedSuite(String suiteName, int methodId, boolean developerDisabled) {
        TiaData tiaData = dataStore.getTiaData();
        tiaData.setCommitValue("abc123");
        tiaData.setLastUpdated(Instant.now());

        Map<Integer, MethodImpactTracker> methods = new HashMap<>(dataStore.getMethodsTracked());
        methods.put(methodId, new MethodImpactTracker(suiteName + ".someMethod", 1, 10));

        Map<String, TestSuiteTracker> testSuites = new HashMap<>(dataStore.getTestSuitesTracked());
        TestSuiteTracker tracker = new TestSuiteTracker(suiteName);
        tracker.setDeveloperDisabled(developerDisabled);
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
     * VCS reader that reports no changes and counts the diff and changed-path requests it gets,
     * so a test can assert whether selection consulted the VCS at all.
     */
    private static final class CountingVCSReader implements VCSReader {
        private int diffRequests;

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

        @Override
        public void close() {
        }
    }
}
