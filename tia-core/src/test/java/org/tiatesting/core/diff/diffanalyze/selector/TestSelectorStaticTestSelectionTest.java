package org.tiatesting.core.diff.diffanalyze.selector;

import org.tiatesting.core.model.SelectionMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.library.LibraryMetadataReader;
import org.tiatesting.core.library.ResolvedSourceProjectLibrary;
import org.tiatesting.core.model.LibraryBuildMetadata;
import org.tiatesting.core.model.MethodImpactTracker;
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
import org.tiatesting.core.staticselection.pom.PomChangeCategory;
import org.tiatesting.core.testrunner.TestSuiteRunnabilityClassifier;
import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.MappingDrift;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
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

    private JdbcDataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-static-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())), BranchSchema.schemaName("test", null));
        dataStore.getTiaData();
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
                        StaticTestSelectionRuleMode.RUN_ALL, null, null)), null);
        VCSReader vcsReader = new StubVCSReader(Collections.singleton("src/main/resources/db/V001.sql"));

        // when
        TestSelectorResult result = new TestSelector(dataStore, new File(".")).selectTestsToIgnore(
                vcsReader, Collections.emptyList(), Collections.emptyList(),
                TestSuiteRunnabilityClassifier.disabled(),
                false, null, config, false, SelectionMode.SELECTIVE);

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
                        StaticTestSelectionRuleMode.RUN_ALL, null, null)), null);
        VCSReader vcsReader = new StubVCSReader(Collections.singleton("src/main/java/com/acme/Order.java"));

        // when
        TestSelectorResult result = new TestSelector(dataStore, new File(".")).selectTestsToIgnore(
                vcsReader, Collections.emptyList(), Collections.emptyList(),
                TestSuiteRunnabilityClassifier.disabled(),
                false, null, config, false, SelectionMode.SELECTIVE);

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
        TestSelectorResult result = new TestSelector(dataStore, new File(".")).selectTestsToIgnore(
                vcsReader, Collections.emptyList(), Collections.emptyList(),
                TestSuiteRunnabilityClassifier.disabled(),
                false, null, null, false, SelectionMode.SELECTIVE);

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
        TestSelectorResult result = new TestSelector(dataStore, new File(".")).selectTestsToIgnore(
                vcsReader, Collections.emptyList(), Collections.emptyList(),
                TestSuiteRunnabilityClassifier.disabled(),
                false, null, StaticTestSelectionConfig.EMPTY, false, SelectionMode.SELECTIVE);

        // then
        assertTrue(result.getTestsToRun().isEmpty());
        assertEquals(setOf("com.acme.OrderServiceIT"), result.getTestsToIgnore());
    }

    @Test
    void pomAwareRuleFiresForADependencyBumpAndRecordsTheCategoryOnTheTrigger() {
        // given - a FUNCTIONAL rule and a pom.xml whose external dependency version changed
        seedTrackedSuites("com.acme.OrderServiceIT", "com.acme.PaymentServiceIT");
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("pom-changes", null, StaticTestSelectionRuleMode.RUN_ALL, null,
                        EnumSet.of(PomChangeCategory.FUNCTIONAL))), null);
        StubVCSReader vcsReader = new StubVCSReader(Collections.singleton("pom.xml"),
                Collections.singletonMap("pom.xml", new FileContentPair(pom("1.0", "org.lib", "2.0"),
                        pom("1.0", "org.lib", "2.1"))));

        // when
        TestSelectorResult result = new TestSelector(dataStore, new File(".")).selectTestsToIgnore(
                vcsReader, Collections.emptyList(), Collections.emptyList(),
                TestSuiteRunnabilityClassifier.disabled(),
                false, null, config, false, SelectionMode.SELECTIVE);

        // then
        assertEquals(setOf("com.acme.OrderServiceIT", "com.acme.PaymentServiceIT"), result.getTestsToRun());
        assertEquals("pom-changes [DEPENDENCY_VERSIONS]",
                result.getSelectionDetails().getTriggers().get(0).getName());
    }

    @Test
    void pomAwareRuleDoesNotFireForAReleaseBump() {
        // given - only the project version changed
        seedTrackedSuites("com.acme.OrderServiceIT");
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("pom-changes", null, StaticTestSelectionRuleMode.RUN_ALL, null,
                        EnumSet.of(PomChangeCategory.FUNCTIONAL))), null);
        StubVCSReader vcsReader = new StubVCSReader(Collections.singleton("pom.xml"),
                Collections.singletonMap("pom.xml", new FileContentPair(pom("1.0", "org.lib", "2.0"),
                        pom("1.1", "org.lib", "2.0"))));

        // when
        TestSelectorResult result = new TestSelector(dataStore, new File(".")).selectTestsToIgnore(
                vcsReader, Collections.emptyList(), Collections.emptyList(),
                TestSuiteRunnabilityClassifier.disabled(),
                false, null, config, false, SelectionMode.SELECTIVE);

        // then
        assertTrue(result.getTestsToRun().isEmpty());
        assertEquals(setOf("com.acme.OrderServiceIT"), result.getTestsToIgnore());
    }

    @Test
    void pomAwareRuleTreatsConfiguredTrackedLibraryVersionsAsInert() {
        // given - the bumped dependency is a tracked library in the library impact config
        seedTrackedSuites("com.acme.OrderServiceIT");
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("pom-changes", null, StaticTestSelectionRuleMode.RUN_ALL, null,
                        EnumSet.of(PomChangeCategory.FUNCTIONAL))), null);
        StubVCSReader vcsReader = new StubVCSReader(Collections.singleton("pom.xml"),
                Collections.singletonMap("pom.xml", new FileContentPair(pom("1.0", "com.example", "2.0"),
                        pom("1.0", "com.example", "2.1"))));
        LibraryImpactAnalysisConfig libraryConfig = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), Collections.<String, String>emptyMap(), ".",
                new NoOpLibraryMetadataReader());

        // when
        TestSelectorResult result = new TestSelector(dataStore, new File(".")).selectTestsToIgnore(
                vcsReader, Collections.emptyList(), Collections.emptyList(),
                TestSuiteRunnabilityClassifier.disabled(),
                false, libraryConfig, config, false, SelectionMode.SELECTIVE);

        // then
        assertTrue(result.getTestsToRun().isEmpty());
    }

    @Test
    void plainPathRulesNeverLoadFileContent() {
        // given
        seedTrackedSuites("com.acme.OrderServiceIT");
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("poms", "pom\\.xml$", StaticTestSelectionRuleMode.RUN_ALL, null, null)), null);
        StubVCSReader vcsReader = new StubVCSReader(Collections.singleton("pom.xml"));

        // when
        TestSelectorResult result = new TestSelector(dataStore, new File(".")).selectTestsToIgnore(
                vcsReader, Collections.emptyList(), Collections.emptyList(),
                TestSuiteRunnabilityClassifier.disabled(),
                false, null, config, false, SelectionMode.SELECTIVE);

        // then
        assertEquals(setOf("com.acme.OrderServiceIT"), result.getTestsToRun());
        assertEquals(0, vcsReader.contentLoads);
    }

    /**
     * A jar POM {@code com.acme:app} with one dependency on {@code <groupId>:lib}.
     *
     * @param version the project version.
     * @param dependencyGroupId the dependency's groupId.
     * @param dependencyVersion the dependency's version.
     * @return the POM text.
     */
    private static String pom(String version, String dependencyGroupId, String dependencyVersion) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId><artifactId>app</artifactId>"
                + "<version>" + version + "</version><dependencies><dependency><groupId>" + dependencyGroupId
                + "</groupId><artifactId>lib</artifactId><version>" + dependencyVersion
                + "</version></dependency></dependencies></project>";
    }

    /**
     * Seed the test DB with the given suite names and a stored commit value so the
     * {@code hasStoredMapping} guard in {@link TestSelector} passes.
     *
     * @param suiteNames the test suite names to register as tracked.
     */
    private void seedTrackedSuites(String... suiteNames) {
        TiaData tiaData = dataStore.getTiaData();
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
        private final Map<String, FileContentPair> contents;
        private int contentLoads;

        /**
         * A reader with changed paths and no file content.
         *
         * @param changedPaths the changed paths to return.
         */
        StubVCSReader(Set<String> changedPaths) {
            this(changedPaths, Collections.<String, FileContentPair>emptyMap());
        }

        /**
         * A reader with changed paths and the before / after content of some of them.
         *
         * @param changedPaths the changed paths to return.
         * @param contents the content to serve by path.
         */
        StubVCSReader(Set<String> changedPaths, Map<String, FileContentPair> contents) {
            this.changedPaths = changedPaths;
            this.contents = contents;
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
            return Collections.emptySet();
        }

        @Override
        public void loadContentForDiffs(java.util.Collection<SourceFileDiffContext> diffs, String baseChangeNum,
                                        boolean checkLocalChanges) {
            // no-op: this stub returns no diffs
        }

        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            return changedPaths;
        }

        /**
         * Serve the configured content for the requested paths, counting the calls.
         *
         * @param paths the paths to load.
         * @param baseChangeNum unused.
         * @param checkLocalChanges unused.
         * @return the configured content of each requested path (absent paths have no content).
         */
        @Override
        public Map<String, FileContentPair> loadFileContents(final Set<String> paths, final String baseChangeNum,
                                                             final boolean checkLocalChanges) {
            contentLoads++;
            Map<String, FileContentPair> loaded = new HashMap<>();
            for (String path : paths) {
                loaded.put(path, contents.containsKey(path) ? contents.get(path) : new FileContentPair(null, null));
            }
            return loaded;
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
                java.util.Collection<SourceFileDiffContext> localDiffs) {
            return MappingDrift.none();
        }

        @Override
        public void close() {
        }
    }

    /**
     * Library metadata reader that resolves nothing: the selector only needs the configured
     * coordinates here, and an unresolvable library leaves the drain with nothing to apply.
     */
    private static final class NoOpLibraryMetadataReader implements LibraryMetadataReader {

        /**
         * @param libraryProjectDir unused.
         * @param coordinates unused.
         * @return an empty list.
         */
        @Override
        public List<LibraryBuildMetadata> readLibraryBuildMetadata(String libraryProjectDir, List<String> coordinates) {
            return Collections.emptyList();
        }

        /**
         * @param sourceProjectDir unused.
         * @param coordinates unused.
         * @return an empty list.
         */
        @Override
        public List<ResolvedSourceProjectLibrary> resolveLibrariesInSourceProject(String sourceProjectDir,
                                                                                  List<String> coordinates) {
            return Collections.emptyList();
        }

        /**
         * @param libraryProjectDir unused.
         * @return an empty list.
         */
        @Override
        public List<String> readSourceDirectories(String libraryProjectDir) {
            return Collections.emptyList();
        }
    }
}
