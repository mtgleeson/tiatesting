package org.tiatesting.core.diff.diffanalyze.selector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An unsealed suite - one whose mapping rows were written by a run that never sealed its commit
 * value - must be force-selected, because those rows describe a later commit than the stored one.
 * See the "Persist flow and crash safety" chapter in {@code WIKI.md}.
 *
 * <p>Modeled on {@code TestSelectorUpdateDBMappingGateTest} and
 * {@code TestSelectorStaticTestSelectionTest} in this package: an embedded H2-backed
 * {@link JdbcDataStore} seeded directly via the persist methods, driven with a stub
 * {@link VCSReader} that reports no changes so the unsealed-flag behaviour is isolated from the
 * VCS-diff selection path.
 */
class TestSelectorUnsealedSuitesTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a temp-directory-backed embedded {@link JdbcDataStore} and bootstrap its schema, so
     * each test starts from an empty, independent DB rather than sharing state across test methods.
     *
     * @throws Exception if the temp directory or the underlying H2 database cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-unsealed-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())), BranchSchema.schemaName("test", null));
        dataStore.getTiaData(true);
    }

    /**
     * Close the data store and remove the temp directory created for it in {@link #setUp()}, so
     * repeated test runs do not leak an embedded-H2 connection or directory per run.
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
     * A suite flagged unsealed must be force-selected into the run set even though the VCS diff
     * implicates nothing, and a sealed suite with no impacting change must still be ignored -
     * proving the force-selection is driven by the flag, not a side effect of the diff being empty.
     */
    @Test
    void anUnsealedSuiteIsForceSelectedWithNoSourceChanges() {
        // given - no diff at all, one suite flagged unsealed
        seedTrackedSuite("com.example.SealedSpec", 1, false);
        seedTrackedSuite("com.example.UnsealedSpec", 2, true);

        // when
        TestSelectorResult result = new TestSelector(dataStore).selectTestsToIgnore(
                new StubVCSReader(), Collections.emptyList(), Collections.emptyList(),
                false, null, null, false);

        // then
        assertTrue(result.getTestsToRun().contains("com.example.UnsealedSpec"));
        assertFalse(result.getTestsToIgnore().contains("com.example.UnsealedSpec"));
        assertTrue(result.getTestsToIgnore().contains("com.example.SealedSpec"),
                "a sealed suite with no impacting change must still be ignored");
    }

    /**
     * The force-selection in {@link TestSelector#selectTestsToIgnore} is deliberately not gated
     * on {@code updateDBMapping} - an unsealed suite must be force-run on a preview
     * ({@code updateDBMapping=false}, covered above) just as much as on a primary build
     * ({@code updateDBMapping=true}), because either kind of run needs the recaptured coverage.
     * Together with the test above, this pins both polarities of {@code updateDBMapping} on the
     * force-selection call, so a regression that made it conditional on either value would fail
     * one of the two tests.
     */
    @Test
    void anUnsealedSuiteIsForceSelectedWhenUpdateDBMappingIsTrue() {
        // given - no diff at all, one suite flagged unsealed, a primary (mapping-owning) run
        seedTrackedSuite("com.example.SealedSpec", 1, false);
        seedTrackedSuite("com.example.UnsealedSpec", 2, true);

        // when
        TestSelectorResult result = new TestSelector(dataStore).selectTestsToIgnore(
                new StubVCSReader(), Collections.emptyList(), Collections.emptyList(),
                false, null, null, true);

        // then
        assertTrue(result.getTestsToRun().contains("com.example.UnsealedSpec"),
                "an unsealed suite must be force-selected on a mapping-owning run too");
        assertFalse(result.getTestsToIgnore().contains("com.example.UnsealedSpec"));
    }

    /**
     * Seed the DB with a tracked suite that has one impacted method, so the mapping row-write
     * path is exercised and {@link JdbcDataStore#persistTestSuites} flags the suite unsealed as
     * a side effect of writing its edges. When {@code unsealed} is {@code false}, immediately
     * clear the flag via {@link JdbcDataStore#clearUnsealedTestSuites()} to model a suite whose
     * owning run has already sealed.
     *
     * @param suiteName the test suite name to register as tracked
     * @param methodId the impacted method id to attach, so the suite has non-empty coverage
     * @param unsealed whether the suite should be left flagged unsealed after seeding
     */
    private void seedTrackedSuite(String suiteName, int methodId, boolean unsealed) {
        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("abc123");
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

        if (!unsealed) {
            dataStore.clearUnsealedTestSuites();
        }
    }

    /**
     * Stub VCS reader that reports no diffs and no changed paths, so dynamic and static test
     * selection both contribute nothing and only the unsealed-flag behaviour is exercised.
     */
    private static final class StubVCSReader implements VCSReader {
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
            return Collections.emptySet();
        }

        @Override
        public void loadContentForDiffs(Collection<SourceFileDiffContext> diffs, String baseChangeNum,
                                        boolean checkLocalChanges) {
            // no-op: this stub returns no diffs
        }

        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            return Collections.emptySet();
        }

        @Override
        public void close() {
        }
    }
}
