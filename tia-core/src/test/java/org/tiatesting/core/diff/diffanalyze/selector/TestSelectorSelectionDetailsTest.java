package org.tiatesting.core.diff.diffanalyze.selector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestRunTrigger;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.staticselection.StaticTestSelectionRule;
import org.tiatesting.core.staticselection.StaticTestSelectionRuleMode;
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
 * Verifies that {@link TestSelector#selectTestsToIgnore} assembles a
 * {@link TestRunSelectionDetails} breakdown of what drove selection and carries it on
 * {@link TestSelectorResult#getSelectionDetails()}: one {@link TestRunTrigger} per impacted
 * source method (with its covering-suite count) and one per fired static rule, plus the scalar
 * source counters. See the "Run history details" chapter in {@code WIKI.md}.
 *
 * <p>Modeled on {@code TestSelectorTrackedFileFilterTest} (the tracked-method diff fixture) and
 * {@code TestSelectorStaticTestSelectionTest} (the static-rule fixture) in this package.
 */
class TestSelectorSelectionDetailsTest {

    private static final String TRACKED_FILE_KEY = "com/example/Foo.java";
    private static final int METHOD_ID = 4242;

    private JdbcDataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-selectiondetails-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())), BranchSchema.schemaName("test", null));
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
     * A diff that changes one tracked method covered by two suites, combined with a static rule
     * that also fires, must produce one SOURCE_METHOD trigger with testCount 2 and one
     * STATIC_RULE trigger, both carried on the result's {@link TestRunSelectionDetails}.
     */
    @Test
    void selectionDetailsCarryMethodAndRuleTriggers() {
        // given - Foo.java has one tracked method covered by two suites, plus a static rule that
        // fires on a changed .sql file
        seedMapping();
        StaticTestSelectionConfig staticConfig = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("sql-migrations", ".*\\.sql$",
                        StaticTestSelectionRuleMode.RUN_ALL, null)));
        CombinedVCSReader vcsReader = new CombinedVCSReader(modifiedDiff(TRACKED_FILE_KEY),
                Collections.singleton("src/main/resources/db/V001.sql"));
        TestSelector selector = new TestSelector(dataStore);

        // when
        TestSelectorResult result = selector.selectTestsToIgnore(vcsReader, Collections.emptyList(),
                Collections.emptyList(), false, null, staticConfig, false);

        // then
        TestRunSelectionDetails details = result.getSelectionDetails();
        assertEquals(1, details.getSourceMethodTriggers().size());
        assertEquals(2, details.getSourceMethodTriggers().get(0).getTestCount());
        assertEquals(TestRunTrigger.Type.SOURCE_METHOD, details.getSourceMethodTriggers().get(0).getType());

        assertEquals(1, details.getStaticRuleTriggers().size());
        assertEquals("sql-migrations", details.getStaticRuleTriggers().get(0).getName());
        assertEquals(TestRunTrigger.Type.STATIC_RULE, details.getStaticRuleTriggers().get(0).getType());
    }

    /**
     * A seed run (no stored mapping yet) selects everything and has nothing to attribute, so it
     * must carry an empty {@link TestRunSelectionDetails} rather than a partially-populated one.
     */
    @Test
    void seedRunYieldsEmptySelectionDetails() {
        // given - a fresh data store with no stored commit value, so hasStoredMapping is false
        VCSReader vcsReader = new CombinedVCSReader(null, Collections.emptySet());
        TestSelector selector = new TestSelector(dataStore);

        // when
        TestSelectorResult result = selector.selectTestsToIgnore(vcsReader, Collections.emptyList(),
                Collections.emptyList(), false, null, null, false);

        // then - equivalent to TestRunSelectionDetails.empty() by field value (the type has no
        // equals/hashCode override, so reference comparison would not be meaningful here)
        assertTrue(result.isRunAllTests());
        TestRunSelectionDetails details = result.getSelectionDetails();
        assertTrue(details.getTriggers().isEmpty());
        assertEquals(0, details.getNumModifiedTestFiles());
        assertEquals(0, details.getNumNewTestFiles());
        assertEquals(0, details.getNumPreviouslyFailed());
        assertEquals(0, details.getNumUnsealedMapping());
        assertEquals(0, details.getNumPendingLibrary());
    }

    /**
     * Seed a mapping where {@code com/example/Foo.java} has one tracked method (lines 2-8)
     * covered by two suites, so the method-trigger's covering-suite count is 2.
     */
    private void seedMapping() {
        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("seed-commit");
        tiaData.setLastUpdated(Instant.now());

        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        methods.put(METHOD_ID, new MethodImpactTracker("com/example/Foo.method.()V", 2, 8));

        TestSuiteTracker suiteOne = new TestSuiteTracker("com.example.FooTest");
        suiteOne.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker(TRACKED_FILE_KEY, new HashSet<>(Collections.singletonList(METHOD_ID)))));
        TestSuiteTracker suiteTwo = new TestSuiteTracker("com.example.FooOtherTest");
        suiteTwo.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker(TRACKED_FILE_KEY, new HashSet<>(Collections.singletonList(METHOD_ID)))));
        Map<String, TestSuiteTracker> testSuites = new HashMap<>();
        testSuites.put(suiteOne.getName(), suiteOne);
        testSuites.put(suiteTwo.getName(), suiteTwo);

        tiaData.setTestSuitesTracked(testSuites);
        tiaData.setMethodsTracked(methods);
        dataStore.persistCoreData(tiaData);
        dataStore.persistTestSuites(testSuites);
        dataStore.persistSourceMethods(methods);
        dataStore.clearUnsealedTestSuites();
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
     * Stub VCS reader that returns one changed-source diff (for the dynamic/method-trigger path)
     * and a fixed set of repo-relative changed file paths (for the static-rule path), and
     * populates the diff's content so it produces an impacted method when the recorded content
     * change (line 5) falls inside the seeded method's tracked line range (2-8).
     */
    private static final class CombinedVCSReader implements VCSReader {
        private final SourceFileDiffContext diff;
        private final Set<String> changedPaths;

        /**
         * @param diff the single modified-source diff to return from {@link #getDiffFiles}, or
         *             {@code null} to return no diffs
         * @param changedPaths the changed file paths to return from {@link #getChangedFilePaths}
         */
        CombinedVCSReader(SourceFileDiffContext diff, Set<String> changedPaths) {
            this.diff = diff;
            this.changedPaths = changedPaths;
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
            if (diff == null) {
                return Collections.emptySet();
            }
            Set<SourceFileDiffContext> diffs = new HashSet<>();
            diffs.add(diff);
            return diffs;
        }

        @Override
        public void loadContentForDiffs(Collection<SourceFileDiffContext> diffsToLoad, String baseChangeNum,
                                        boolean checkLocalChanges) {
            String original = "l1\nl2\nl3\nl4\nl5-old\nl6\nl7\nl8\nl9\nl10\n";
            String changed = "l1\nl2\nl3\nl4\nl5-new\nl6\nl7\nl8\nl9\nl10\n";
            for (SourceFileDiffContext d : diffsToLoad) {
                d.setSourceContentOriginal(original);
                d.setSourceContentNew(changed);
            }
        }

        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            return changedPaths;
        }

        @Override
        public void close() {
        }
    }
}
