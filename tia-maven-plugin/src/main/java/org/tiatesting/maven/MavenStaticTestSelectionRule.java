package org.tiatesting.maven;

import java.util.List;

/**
 * Maven-side POJO carrying the configuration for a single static test selection rule.
 * Bound by Maven's plugin parameter injection from a nested
 * {@code <tiaStaticTestSelectionRule>} element inside
 * {@code <tiaStaticTestSelectionRules>}. The fields here are the Maven configuration
 * surface; {@link AbstractTiaMojo#buildStaticTestSelectionConfig()} converts these into the
 * core {@link org.tiatesting.core.staticselection.StaticTestSelectionRule} (compiling regexes
 * and validating mode-specific constraints).
 *
 * <p>Must have a public no-arg constructor and setters for every field so the Maven plugin
 * injector can bind nested XML elements.
 */
public class MavenStaticTestSelectionRule {

    /** Optional human-readable rule name used in log output. */
    private String name;

    /**
     * Regex matched against the repo-relative path of each changed file. Required, except for a
     * POM-aware rule ({@link #pomChanges} set), where it defaults to any {@code pom.xml}.
     */
    private String filePathPattern;

    /**
     * Selection mode: {@code RUN_ALL} or {@code SUITE_NAMES} (the {@code ANNOTATIONS_TAGS}
     * mode is reserved for a future stage and is rejected at build time).
     */
    private String mode;

    /**
     * Suite-name regex patterns matched against both the simple class name and the FQN of
     * each tracked test suite. Required for mode {@code SUITE_NAMES}; must be empty for other
     * modes.
     */
    private List<String> suiteNamePatterns;

    /**
     * Optional comma-separated POM change categories, e.g. {@code CODE_AND_TESTS}, {@code FUNCTIONAL} or
     * {@code DEPENDENCY_VERSIONS,TEST_RUNNER}. When set the rule is POM-aware: it fires only when a
     * matched POM's changes include one of these categories, and {@code filePathPattern} defaults
     * to any {@code pom.xml}.
     */
    private String pomChanges;

    /**
     * Optional comma-separated plugins whose changes are inert for this POM-aware rule, each an
     * {@code artifactId} (any groupId) or a {@code groupId:artifactId}, e.g. in-house deploy-only
     * plugins. Extends the built-in inert list for this rule only.
     */
    private String inertPlugins;

    /**
     * Optional comma-separated regexes matched against the whole profile id; every change inside a
     * matching profile is inert for this POM-aware rule, e.g. {@code Deploy.*}.
     */
    private String inertProfiles;

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getFilePathPattern() {
        return filePathPattern;
    }

    public void setFilePathPattern(final String filePathPattern) {
        this.filePathPattern = filePathPattern;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(final String mode) {
        this.mode = mode;
    }

    public List<String> getSuiteNamePatterns() {
        return suiteNamePatterns;
    }

    public void setSuiteNamePatterns(final List<String> suiteNamePatterns) {
        this.suiteNamePatterns = suiteNamePatterns;
    }

    /**
     * @return the raw comma-separated POM change categories, or {@code null} for a plain path rule.
     */
    public String getPomChanges() {
        return pomChanges;
    }

    /**
     * @param pomChanges the raw comma-separated POM change categories.
     */
    public void setPomChanges(final String pomChanges) {
        this.pomChanges = pomChanges;
    }

    /**
     * @return the raw comma-separated inert plugins, or {@code null} when not configured.
     */
    public String getInertPlugins() {
        return inertPlugins;
    }

    /**
     * @param inertPlugins the raw comma-separated inert plugins.
     */
    public void setInertPlugins(final String inertPlugins) {
        this.inertPlugins = inertPlugins;
    }

    /**
     * @return the raw comma-separated inert profile regexes, or {@code null} when not configured.
     */
    public String getInertProfiles() {
        return inertProfiles;
    }

    /**
     * @param inertProfiles the raw comma-separated inert profile regexes.
     */
    public void setInertProfiles(final String inertProfiles) {
        this.inertProfiles = inertProfiles;
    }
}
