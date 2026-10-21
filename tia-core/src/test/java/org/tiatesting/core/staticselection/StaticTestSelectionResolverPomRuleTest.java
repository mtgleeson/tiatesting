package org.tiatesting.core.staticselection;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.staticselection.pom.PomChangeAnalyzer;
import org.tiatesting.core.staticselection.pom.PomChangeCategory;
import org.tiatesting.core.staticselection.pom.PomChangeClassifier;
import org.tiatesting.core.staticselection.pom.PomInertOverrides;
import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.VCSReader;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for POM-aware rules in {@link StaticTestSelectionResolver}: rules fire only for
 * covered POM change categories, matched POMs are fetched once across rules, and plain rules keep
 * firing on any matching change.
 */
class StaticTestSelectionResolverPomRuleTest {

    private static final String ROOT_POM = "pom.xml";
    private static final String MODULE_POM = "module-a/pom.xml";

    @Test
    void functionalRuleFiresForADependencyBumpWithTheCategoryInTheTriggerName() {
        // given
        ContentVCSReader reader = new ContentVCSReader(Collections.singletonMap(ROOT_POM,
                new FileContentPair(pom("1.0", "2.0"), pom("1.0", "2.1"))));
        StaticTestSelectionConfig config = configWith(pomRule("poms", null, PomChangeCategory.FUNCTIONAL));

        // when
        StaticTestSelectionResult result = new StaticTestSelectionResolver(config)
                .resolve(setOf(ROOT_POM), tracked("com.acme.AIT", "com.acme.BIT"), analyzer(reader, setOf(ROOT_POM)));

        // then
        assertEquals(setOf("com.acme.AIT", "com.acme.BIT"), result.getForcedSuites());
        assertEquals("poms [DEPENDENCY_VERSIONS]", result.getRuleTriggers().get(0).getName());
    }

    @Test
    void ruleDoesNotFireWhenEveryChangeIsInert() {
        // given - a release bump only
        ContentVCSReader reader = new ContentVCSReader(Collections.singletonMap(ROOT_POM,
                new FileContentPair(pom("1.0", "2.0"), pom("1.1", "2.0"))));
        StaticTestSelectionConfig config = configWith(pomRule("poms", null, PomChangeCategory.FUNCTIONAL));

        // when
        StaticTestSelectionResult result = new StaticTestSelectionResolver(config)
                .resolve(setOf(ROOT_POM), tracked("com.acme.AIT"), analyzer(reader, setOf(ROOT_POM)));

        // then
        assertTrue(result.getForcedSuites().isEmpty());
        assertTrue(result.getRuleTriggers().isEmpty());
    }

    @Test
    void ruleDoesNotFireForAFunctionalChangeOutsideItsCategories() {
        // given - a dependency bump, but the rule only reacts to test runner changes
        ContentVCSReader reader = new ContentVCSReader(Collections.singletonMap(ROOT_POM,
                new FileContentPair(pom("1.0", "2.0"), pom("1.0", "2.1"))));
        StaticTestSelectionConfig config = configWith(pomRule("runner", null, PomChangeCategory.TEST_RUNNER));

        // when
        StaticTestSelectionResult result = new StaticTestSelectionResolver(config)
                .resolve(setOf(ROOT_POM), tracked("com.acme.AIT"), analyzer(reader, setOf(ROOT_POM)));

        // then
        assertTrue(result.getForcedSuites().isEmpty());
    }

    @Test
    void matchedPomsAreFetchedOnceAcrossRules() {
        // given - two POM-aware rules matching overlapping POMs, plus a plain .sql rule
        Map<String, FileContentPair> contents = new HashMap<>();
        contents.put(ROOT_POM, new FileContentPair(pom("1.0", "2.0"), pom("1.0", "2.1")));
        contents.put(MODULE_POM, new FileContentPair(pom("1.0", "2.0"), pom("1.0", "2.0")));
        ContentVCSReader reader = new ContentVCSReader(contents);
        StaticTestSelectionConfig config = configWith(
                pomRule("all-poms", null, PomChangeCategory.FUNCTIONAL),
                pomRule("module-poms", "module-a/pom\\.xml$", PomChangeCategory.DEPENDENCY_VERSIONS),
                new StaticTestSelectionRule("sql", "\\.sql$", StaticTestSelectionRuleMode.RUN_ALL, null, null, null));

        // when
        List<FiredStaticRule> fired = new StaticTestSelectionResolver(config)
                .evaluateRules(setOf(ROOT_POM, MODULE_POM, "db/V1.sql"), analyzer(reader, setOf(ROOT_POM, MODULE_POM, "db/V1.sql")));

        // then - one fetch covering both POMs; the module POM is unchanged so its rule does not fire
        assertEquals(1, reader.loads);
        assertEquals(setOf(ROOT_POM, MODULE_POM), reader.requested);
        assertEquals(Arrays.asList("all-poms", "sql"), ruleNames(fired));
    }

    @Test
    void pomAwareSuiteNamesRuleForcesOnlyItsSuites() {
        // given
        ContentVCSReader reader = new ContentVCSReader(Collections.singletonMap(ROOT_POM,
                new FileContentPair(pom("1.0", "2.0"), pom("1.0", "2.1"))));
        StaticTestSelectionRule rule = new StaticTestSelectionRule("dep-its", null,
                StaticTestSelectionRuleMode.SUITE_NAMES, Collections.singletonList("IT$"),
                EnumSet.of(PomChangeCategory.DEPENDENCY_VERSIONS), null);

        // when
        StaticTestSelectionResult result = new StaticTestSelectionResolver(configWith(rule))
                .resolve(setOf(ROOT_POM), tracked("com.acme.OrderIT", "com.acme.OrderTest"), analyzer(reader, setOf(ROOT_POM)));

        // then
        assertEquals(setOf("com.acme.OrderIT"), result.getForcedSuites());
    }

    @Test
    void noContentIsFetchedWhenNoPomAwareRuleMatches() {
        // given - a POM-aware rule and a change to a non-POM file only
        ContentVCSReader reader = new ContentVCSReader(Collections.<String, FileContentPair>emptyMap());
        StaticTestSelectionConfig config = configWith(pomRule("poms", null, PomChangeCategory.FUNCTIONAL));

        // when
        List<FiredStaticRule> fired = new StaticTestSelectionResolver(config)
                .evaluateRules(setOf("src/main/java/A.java"), analyzer(reader, setOf("src/main/java/A.java")));

        // then
        assertTrue(fired.isEmpty());
        assertEquals(0, reader.loads);
    }

    @Test
    void missingAnalyzerFailsFastWhenAPomAwareRuleMatches() {
        // given
        StaticTestSelectionConfig config = configWith(pomRule("poms", null, PomChangeCategory.FUNCTIONAL));
        StaticTestSelectionResolver resolver = new StaticTestSelectionResolver(config);

        // when
        // then
        assertThrows(IllegalStateException.class, () -> resolver.evaluateRules(setOf(ROOT_POM), null));
    }

    /**
     * Verify a rule scoped to a new module's directory fires when the module is registered by an
     * aggregator outside the rule's pattern, and does not fire for an unreferenced added POM.
     */
    @Test
    void scopedRuleFiresForAnAddedModuleRegisteredOutsideItsPattern() {
        // given
        Map<String, FileContentPair> contents = new HashMap<>();
        contents.put(ROOT_POM, new FileContentPair(
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId><artifactId>root</artifactId>"
                        + "<version>1.0</version><packaging>pom</packaging><modules><module>module-a</module></modules></project>",
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId><artifactId>root</artifactId>"
                        + "<version>1.0</version><packaging>pom</packaging><modules><module>module-a</module>"
                        + "<module>services/new</module></modules></project>"));
        contents.put("services/new/pom.xml", new FileContentPair(null,
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId><artifactId>new</artifactId>"
                        + "<version>1.0</version></project>"));
        contents.put("services/copy/pom.xml", new FileContentPair(null,
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId><artifactId>copy</artifactId>"
                        + "<version>1.0</version></project>"));
        ContentVCSReader reader = new ContentVCSReader(contents);
        StaticTestSelectionConfig config = configWith(
                pomRule("new-service", "^services/new/pom\\.xml$", PomChangeCategory.FUNCTIONAL),
                pomRule("copy-service", "^services/copy/pom\\.xml$", PomChangeCategory.FUNCTIONAL));
        Set<String> changedPaths = setOf(ROOT_POM, "services/new/pom.xml", "services/copy/pom.xml");

        // when
        List<FiredStaticRule> fired = new StaticTestSelectionResolver(config)
                .evaluateRules(changedPaths, analyzer(reader, changedPaths));

        // then
        assertEquals(1, fired.size());
        assertEquals("new-service", fired.get(0).getRule().getName());
    }

    /**
     * Verify the build 358 shape: a deploy plugin's configuration change and a plugin change in a
     * deploy profile do not fire a rule that declares them inert, while a rule without overrides
     * still fires on the same shared report.
     */
    @Test
    void inertOverridesApplyPerRule() {
        // given
        String pom = "<project><groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>"
                + "<build><plugins><plugin><groupId>com.acme.rs</groupId><artifactId>remote-maven-plugin</artifactId>"
                + "<configuration><host>%s</host></configuration></plugin></plugins></build>"
                + "<profiles><profile><id>DeployToGCS</id><build><plugins><plugin><groupId>com.acme.fut</groupId>"
                + "<artifactId>gcs_upload_m2-maven-plugin</artifactId><configuration><bucket>%s</bucket>"
                + "</configuration></plugin></plugins></build></profile></profiles></project>";
        ContentVCSReader reader = new ContentVCSReader(Collections.singletonMap(ROOT_POM,
                new FileContentPair(String.format(pom, "a", "a"), String.format(pom, "b", "b"))));
        StaticTestSelectionConfig config = configWith(
                new StaticTestSelectionRule("with-overrides", null, StaticTestSelectionRuleMode.RUN_ALL, null,
                        EnumSet.of(PomChangeCategory.FUNCTIONAL),
                        PomInertOverrides.parse("remote-maven-plugin", "DeployToGCS")),
                pomRule("plain", null, PomChangeCategory.FUNCTIONAL));

        // when
        List<FiredStaticRule> fired = new StaticTestSelectionResolver(config)
                .evaluateRules(setOf(ROOT_POM), analyzer(reader, setOf(ROOT_POM)));

        // then
        assertEquals(Collections.singletonList("plain"), ruleNames(fired));
    }

    /**
     * Verify a CODE_AND_TESTS rule ignores a configuration change to a project-specific plugin
     * that is not on the built-in lists, which a FUNCTIONAL rule fires for.
     */
    @Test
    void codeAndTestsRuleDoesNotFireForAnUnknownPluginChange() {
        // given
        String pom = "<project><groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>"
                + "<build><plugins><plugin><groupId>com.acme.rs</groupId><artifactId>remote-maven-plugin</artifactId>"
                + "<configuration><host>%s</host></configuration></plugin></plugins></build></project>";
        ContentVCSReader reader = new ContentVCSReader(Collections.singletonMap(ROOT_POM,
                new FileContentPair(String.format(pom, "a"), String.format(pom, "b"))));
        StaticTestSelectionConfig config = configWith(
                pomRule("code-and-tests", null, PomChangeCategory.CODE_AND_TESTS),
                pomRule("functional", null, PomChangeCategory.FUNCTIONAL));

        // when
        List<FiredStaticRule> fired = new StaticTestSelectionResolver(config)
                .evaluateRules(setOf(ROOT_POM), analyzer(reader, setOf(ROOT_POM)));

        // then
        assertEquals(Collections.singletonList("functional"), ruleNames(fired));
    }

    /**
     * Verify a CODE_AND_TESTS rule ignores an OTHER change (a packaging change), which a
     * FUNCTIONAL rule fires for.
     */
    @Test
    void codeAndTestsRuleDoesNotFireForAnOtherChange() {
        // given
        String pom = "<project><groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>"
                + "<packaging>%s</packaging></project>";
        ContentVCSReader reader = new ContentVCSReader(Collections.singletonMap(ROOT_POM,
                new FileContentPair(String.format(pom, "jar"), String.format(pom, "war"))));
        StaticTestSelectionConfig config = configWith(
                pomRule("code-and-tests", null, PomChangeCategory.CODE_AND_TESTS),
                pomRule("functional", null, PomChangeCategory.FUNCTIONAL));

        // when
        List<FiredStaticRule> fired = new StaticTestSelectionResolver(config)
                .evaluateRules(setOf(ROOT_POM), analyzer(reader, setOf(ROOT_POM)));

        // then
        assertEquals(Collections.singletonList("functional"), ruleNames(fired));
    }

    /**
     * Verify a CODE_AND_TESTS rule fires for a dependency added inside a profile, and the trigger
     * names the change's own category rather than CODE_AND_TESTS.
     */
    @Test
    void codeAndTestsRuleFiresForADependencyAddedInAProfile() {
        // given
        String pom = "<project><groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>"
                + "<profiles><profile><id>extra</id><dependencies>%s</dependencies></profile></profiles></project>";
        String dependency = "<dependency><groupId>org.lib</groupId><artifactId>lib</artifactId>"
                + "<version>1.0</version></dependency>";
        ContentVCSReader reader = new ContentVCSReader(Collections.singletonMap(ROOT_POM,
                new FileContentPair(String.format(pom, ""), String.format(pom, dependency))));
        StaticTestSelectionConfig config = configWith(pomRule("poms", null, PomChangeCategory.CODE_AND_TESTS));

        // when
        StaticTestSelectionResult result = new StaticTestSelectionResolver(config)
                .resolve(setOf(ROOT_POM), tracked("com.acme.AIT"), analyzer(reader, setOf(ROOT_POM)));

        // then
        assertEquals(setOf("com.acme.AIT"), result.getForcedSuites());
        assertEquals("poms [DEPENDENCY_SET]", result.getRuleTriggers().get(0).getName());
    }

    /**
     * Verify a CODE_AND_TESTS rule fires for a surefire configuration change.
     */
    @Test
    void codeAndTestsRuleFiresForASurefireChange() {
        // given
        String pom = "<project><groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>"
                + "<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>"
                + "<artifactId>maven-surefire-plugin</artifactId><configuration><forkCount>%s</forkCount>"
                + "</configuration></plugin></plugins></build></project>";
        ContentVCSReader reader = new ContentVCSReader(Collections.singletonMap(ROOT_POM,
                new FileContentPair(String.format(pom, "1"), String.format(pom, "2"))));
        StaticTestSelectionConfig config = configWith(pomRule("poms", null, PomChangeCategory.CODE_AND_TESTS));

        // when
        StaticTestSelectionResult result = new StaticTestSelectionResolver(config)
                .resolve(setOf(ROOT_POM), tracked("com.acme.AIT"), analyzer(reader, setOf(ROOT_POM)));

        // then
        assertEquals(setOf("com.acme.AIT"), result.getForcedSuites());
        assertEquals("poms [TEST_RUNNER]", result.getRuleTriggers().get(0).getName());
    }

    /**
     * @param reader the reader serving POM content.
     * @param changedPaths every changed path in the diff.
     * @return an analyzer over the reader with no tracked libraries.
     */
    private static PomChangeAnalyzer analyzer(VCSReader reader, Set<String> changedPaths) {
        return new PomChangeAnalyzer(reader, "base", false, changedPaths,
                new PomChangeClassifier(Collections.<String>emptyList()), null);
    }

    /**
     * @param name the rule name.
     * @param filePattern the file-path pattern, or {@code null} for the default {@code pom.xml} pattern.
     * @param category the rule's single POM change category.
     * @return a RUN_ALL POM-aware rule.
     */
    private static StaticTestSelectionRule pomRule(String name, String filePattern, PomChangeCategory category) {
        return new StaticTestSelectionRule(name, filePattern, StaticTestSelectionRuleMode.RUN_ALL, null,
                EnumSet.of(category), null);
    }

    /**
     * @param version the project version.
     * @param libVersion the version of the {@code org.lib:lib} dependency.
     * @return a jar POM text.
     */
    private static String pom(String version, String libVersion) {
        return "<project><groupId>com.acme</groupId><artifactId>app</artifactId><version>" + version
                + "</version><dependencies><dependency><groupId>org.lib</groupId><artifactId>lib</artifactId>"
                + "<version>" + libVersion + "</version></dependency></dependencies></project>";
    }

    /**
     * @param fired the fired rules.
     * @return their names, in order.
     */
    private static List<String> ruleNames(List<FiredStaticRule> fired) {
        String[] names = new String[fired.size()];
        for (int i = 0; i < fired.size(); i++) {
            names[i] = fired.get(i).getRule().getName();
        }
        return Arrays.asList(names);
    }

    /**
     * @param rules the rules.
     * @return a config holding them.
     */
    private static StaticTestSelectionConfig configWith(StaticTestSelectionRule... rules) {
        return new StaticTestSelectionConfig(Arrays.asList(rules), null);
    }

    /**
     * @param suiteNames the tracked suite names.
     * @return the tracked suites keyed by name.
     */
    private static Map<String, TestSuiteTracker> tracked(String... suiteNames) {
        Map<String, TestSuiteTracker> tracked = new LinkedHashMap<>();
        for (String name : suiteNames) {
            tracked.put(name, new TestSuiteTracker(name));
        }
        return tracked;
    }

    /**
     * @param values the members.
     * @return a mutable set.
     */
    private static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    /**
     * VCS reader serving fixed file content and recording each content load.
     */
    private static final class ContentVCSReader implements VCSReader {
        private final Map<String, FileContentPair> contents;
        private final Set<String> requested = new HashSet<>();
        private int loads;

        /**
         * @param contents the content to serve by path.
         */
        ContentVCSReader(Map<String, FileContentPair> contents) {
            this.contents = contents;
        }

        /**
         * @return a fixed branch name.
         */
        @Override
        public String getBranchName() {
            return "main";
        }

        /**
         * @return a fixed head commit.
         */
        @Override
        public String getHeadCommit() {
            return "head";
        }

        /**
         * @param baseChangeNum unused.
         * @param sourceFilesDirs unused.
         * @param testFilesDirs unused.
         * @param checkLocalChanges unused.
         * @return never returns.
         */
        @Override
        public Set<SourceFileDiffContext> getDiffFiles(String baseChangeNum, List<String> sourceFilesDirs,
                                                       List<String> testFilesDirs, boolean checkLocalChanges) {
            throw new UnsupportedOperationException("not used by this test");
        }

        /**
         * @param diffs unused.
         * @param baseChangeNum unused.
         * @param checkLocalChanges unused.
         */
        @Override
        public void loadContentForDiffs(Collection<SourceFileDiffContext> diffs, String baseChangeNum,
                                        boolean checkLocalChanges) {
            throw new UnsupportedOperationException("not used by this test");
        }

        /**
         * @param baseChangeNum unused.
         * @param checkLocalChanges unused.
         * @return never returns.
         */
        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            throw new UnsupportedOperationException("not used by this test");
        }

        /**
         * Serve the configured content and record the request.
         *
         * @param paths the requested paths.
         * @param baseChangeNum unused.
         * @param checkLocalChanges unused.
         * @return the content of each requested path; absent paths have no content on either side.
         */
        @Override
        public Map<String, FileContentPair> loadFileContents(Set<String> paths, String baseChangeNum,
                                                             boolean checkLocalChanges) {
            loads++;
            requested.addAll(paths);
            Map<String, FileContentPair> loaded = new HashMap<>();
            for (String path : paths) {
                loaded.put(path, contents.containsKey(path) ? contents.get(path) : new FileContentPair(null, null));
            }
            return loaded;
        }

        /**
         * Nothing to release.
         */
        @Override
        public void close() {
        }
    }
}
