package org.tiatesting.core.diff.diffanalyze.selector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.staticselection.StaticTestSelectionRule;
import org.tiatesting.core.staticselection.StaticTestSelectionRuleMode;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link TestSelector#selectTestsToIgnore} unions the suites forced by static
 * test selection rules into the dynamic test selection (stage 2: RUN_ALL mode).
 */
class TestSelectorStaticTestSelectionTest {

    private H2DataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-static-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
        dataStore.getTiaData(true);
    }

    @AfterEach
    void tearDown() {
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    @Test
    void runAllRuleForcesAllTrackedSuitesIntoRunSet() {
        // given
        seedTrackedSuites("com.acme.OrderServiceIT", "com.acme.PaymentServiceIT");
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("sql-migrations", ".*\\.sql$",
                        StaticTestSelectionRuleMode.RUN_ALL, null)));
        VCSReader vcsReader = new StubVCSReader(Collections.singleton("src/main/resources/db/V001.sql"));

        // when
        TestSelectorResult result = new TestSelector(dataStore).selectTestsToIgnore(
                vcsReader, Collections.emptyList(), Collections.emptyList(),
                false, null, config, false);

        // then
        assertEquals(setOf("com.acme.OrderServiceIT", "com.acme.PaymentServiceIT"), result.getTestsToRun());
        assertTrue(result.getTestsToIgnore().isEmpty(),
                "Every tracked suite is forced to run, so the ignore set must be empty.");
    }

    @Test
    void runAllRuleIsNoOpWhenChangedPathDoesNotMatchFilePattern() {
        // given - the only changed path is .java, not .sql; the dynamic path returns no diffs either
        seedTrackedSuites("com.acme.OrderServiceIT");
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("sql-migrations", ".*\\.sql$",
                        StaticTestSelectionRuleMode.RUN_ALL, null)));
        VCSReader vcsReader = new StubVCSReader(Collections.singleton("src/main/java/com/acme/Order.java"));

        // when
        TestSelectorResult result = new TestSelector(dataStore).selectTestsToIgnore(
                vcsReader, Collections.emptyList(), Collections.emptyList(),
                false, null, config, false);

        // then
        assertTrue(result.getTestsToRun().isEmpty());
        assertEquals(setOf("com.acme.OrderServiceIT"), result.getTestsToIgnore());
    }

    @Test
    void nullStaticConfigKeepsExistingDynamicBehaviour() {
        // given - no static config; dynamic selection sees no diffs and selects no tests
        seedTrackedSuites("com.acme.OrderServiceIT");
        VCSReader vcsReader = new StubVCSReader(Collections.singleton("src/main/resources/db/V001.sql"));

        // when
        TestSelectorResult result = new TestSelector(dataStore).selectTestsToIgnore(
                vcsReader, Collections.emptyList(), Collections.emptyList(),
                false, null, null, false);

        // then
        assertTrue(result.getTestsToRun().isEmpty());
        assertEquals(setOf("com.acme.OrderServiceIT"), result.getTestsToIgnore());
    }

    @Test
    void disabledStaticConfigKeepsExistingDynamicBehaviour() {
        // given - empty static config (isEnabled=false) is also a no-op
        seedTrackedSuites("com.acme.OrderServiceIT");
        VCSReader vcsReader = new StubVCSReader(Collections.singleton("src/main/resources/db/V001.sql"));

        // when
        TestSelectorResult result = new TestSelector(dataStore).selectTestsToIgnore(
                vcsReader, Collections.emptyList(), Collections.emptyList(),
                false, null, StaticTestSelectionConfig.EMPTY, false);

        // then
        assertTrue(result.getTestsToRun().isEmpty());
        assertEquals(setOf("com.acme.OrderServiceIT"), result.getTestsToIgnore());
    }

    /**
     * Seed the test DB with the given suite names and a stored commit value so the
     * {@code hasStoredMapping} guard in {@link TestSelector} passes.
     *
     * @param suiteNames the test suite names to register as tracked.
     */
    private void seedTrackedSuites(String... suiteNames) {
        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("abc123");
        tiaData.setLastUpdated(Instant.now());
        Map<String, TestSuiteTracker> tracked = new HashMap<>();
        for (String name : suiteNames) {
            tracked.put(name, new TestSuiteTracker(name));
        }
        tiaData.setTestSuitesTracked(tracked);
        tiaData.setMethodsTracked(new HashMap<>());
        dataStore.persistCoreData(tiaData);
        dataStore.persistTestSuites(tracked);
        dataStore.persistSourceMethods(new HashMap<Integer, MethodImpactTracker>());
    }

    private static Set<String> setOf(String... values) {
        Set<String> set = new HashSet<>();
        Collections.addAll(set, values);
        return set;
    }

    /**
     * Stub VCS reader that returns a fixed set of repo-relative changed file paths and no
     * source-file diffs. The {@link TestSelector} dynamic path consumes the empty diff set
     * (so dynamic selection picks nothing) while the static path receives the configured
     * changed paths.
     */
    private static final class StubVCSReader implements VCSReader {
        private final Set<String> changedPaths;

        StubVCSReader(Set<String> changedPaths) {
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
        public Set<SourceFileDiffContext> buildDiffFilesContext(String baseChangeNum, List<String> sourceFilesDirs,
                                                                List<String> testFilesDirs, boolean checkLocalChanges) {
            return Collections.emptySet();
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
