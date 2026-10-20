package org.tiatesting.maven;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.staticselection.StaticTestSelectionRule;
import org.tiatesting.core.staticselection.pom.PomChangeCategory;
import org.tiatesting.core.staticselection.pom.PomInertOverrides;
import org.tiatesting.core.vcs.VCSReader;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover {@link AbstractTiaMojo#buildStaticTestSelectionConfig()} for POM-aware rules: parsing
 * {@code pomChanges} and the {@code inertPlugins} / {@code inertProfiles} overrides, defaulting
 * the file-path pattern, and naming the rule in config errors.
 */
class AbstractTiaMojoStaticRulesTest {

    /**
     * A rule with pomChanges and no file-path pattern becomes a POM-aware rule over any pom.xml.
     */
    @Test
    void pomChangesRuleIsPomAwareWithTheDefaultPattern() {
        // given
        TestMojo mojo = mojoWith(rule("pom-changes", null, "RUN_ALL", "functional, test_runner"));

        // when
        StaticTestSelectionConfig config = mojo.buildStaticTestSelectionConfig();

        // then
        StaticTestSelectionRule rule = config.getRules().get(0);
        assertTrue(rule.isPomAware());
        assertEquals(EnumSet.of(PomChangeCategory.FUNCTIONAL, PomChangeCategory.TEST_RUNNER), rule.getPomChanges());
        assertEquals(StaticTestSelectionRule.DEFAULT_POM_FILE_PATH_PATTERN, rule.getFilePathPattern().pattern());
    }

    /**
     * A rule without pomChanges stays a plain path rule.
     */
    @Test
    void ruleWithoutPomChangesIsAPlainPathRule() {
        // given
        TestMojo mojo = mojoWith(rule("sql", "\\.sql$", "RUN_ALL", null));

        // when
        StaticTestSelectionConfig config = mojo.buildStaticTestSelectionConfig();

        // then
        assertFalse(config.getRules().get(0).isPomAware());
    }

    /**
     * An unknown category fails the build, naming the rule.
     */
    @Test
    void unknownCategoryNamesTheRule() {
        // given
        TestMojo mojo = mojoWith(rule("pom-changes", null, "RUN_ALL", "FUNCTIONAL,DEPS"));

        // when
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                mojo::buildStaticTestSelectionConfig);

        // then
        assertTrue(error.getMessage().startsWith("Static test selection rule 'pom-changes': Unknown POM change"),
                error.getMessage());
    }

    /**
     * With no Maven session (as here) there is no local repository to read external POMs from.
     */
    @Test
    void noSessionMeansNoExternalPomSource() {
        // given
        TestMojo mojo = mojoWith(rule("pom-changes", null, "RUN_ALL", "FUNCTIONAL"));

        // when
        StaticTestSelectionConfig config = mojo.buildStaticTestSelectionConfig();

        // then
        assertNull(config.getExternalPomSource());
    }

    /**
     * The inert plugins and profiles are parsed onto the core rule.
     */
    @Test
    void inertOverridesAreParsedOntoTheRule() {
        // given
        TestMojo mojo = mojoWith(ruleWithOverrides("remote-maven-plugin, com.acme:upload", "Deploy.*", "FUNCTIONAL"));

        // when
        StaticTestSelectionConfig config = mojo.buildStaticTestSelectionConfig();

        // then
        PomInertOverrides overrides = config.getRules().get(0).getInertOverrides();
        assertEquals(new LinkedHashSet<>(Arrays.asList("remote-maven-plugin", "com.acme:upload")), overrides.getPlugins());
        assertEquals(Collections.singletonList("Deploy.*"), overrides.getProfilePatterns());
    }

    /**
     * An invalid profile regex fails the build, naming the rule.
     */
    @Test
    void invalidProfileRegexNamesTheRule() {
        // given
        TestMojo mojo = mojoWith(ruleWithOverrides(null, "Deploy(", "FUNCTIONAL"));

        // when
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                mojo::buildStaticTestSelectionConfig);

        // then
        assertTrue(error.getMessage().startsWith("Static test selection rule 'pom-changes': inertProfiles: invalid regex"),
                error.getMessage());
    }

    /**
     * Inert overrides on a rule without pomChanges fail the build, naming the rule.
     */
    @Test
    void inertOverridesWithoutPomChangesFail() {
        // given
        MavenStaticTestSelectionRule rule = rule("sql", "\\.sql$", "RUN_ALL", null);
        rule.setInertPlugins("upload-plugin");
        TestMojo mojo = mojoWith(rule);

        // when
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                mojo::buildStaticTestSelectionConfig);

        // then
        assertEquals("Static test selection rule 'sql': inertPlugins and inertProfiles need pomChanges.",
                error.getMessage());
    }

    /**
     * @param rule the single configured rule.
     * @return a mojo configured with it.
     */
    private static TestMojo mojoWith(MavenStaticTestSelectionRule rule) {
        TestMojo mojo = new TestMojo();
        mojo.tiaStaticTestSelectionRules = Collections.singletonList(rule);
        return mojo;
    }

    /**
     * @param name the rule name.
     * @param filePathPattern the file-path pattern; may be {@code null}.
     * @param mode the mode.
     * @param pomChanges the raw pomChanges setting; may be {@code null}.
     * @return the Maven-side rule.
     */
    private static MavenStaticTestSelectionRule rule(String name, String filePathPattern, String mode,
                                                     String pomChanges) {
        MavenStaticTestSelectionRule rule = new MavenStaticTestSelectionRule();
        rule.setName(name);
        rule.setFilePathPattern(filePathPattern);
        rule.setMode(mode);
        rule.setPomChanges(pomChanges);
        return rule;
    }

    /**
     * @param inertPlugins the raw inertPlugins setting; may be {@code null}.
     * @param inertProfiles the raw inertProfiles setting; may be {@code null}.
     * @param pomChanges the raw pomChanges setting; may be {@code null}.
     * @return a RUN_ALL rule named {@code pom-changes} with the default pattern and the overrides.
     */
    private static MavenStaticTestSelectionRule ruleWithOverrides(String inertPlugins, String inertProfiles,
                                                                  String pomChanges) {
        MavenStaticTestSelectionRule rule = rule("pom-changes", null, "RUN_ALL", pomChanges);
        rule.setInertPlugins(inertPlugins);
        rule.setInertProfiles(inertProfiles);
        return rule;
    }

    /**
     * Minimal concrete mojo: only the static rule configuration is exercised.
     */
    private static final class TestMojo extends AbstractTiaMojo {

        /**
         * @return never called by these tests
         */
        @Override
        public VCSReader getVCSReader() {
            throw new UnsupportedOperationException("these tests do not reach the VCS");
        }

        /**
         * Never called by these tests.
         */
        @Override
        public void execute() {
            throw new UnsupportedOperationException("these tests do not execute the mojo");
        }
    }
}
