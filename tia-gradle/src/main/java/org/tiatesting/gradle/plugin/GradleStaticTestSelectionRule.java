package org.tiatesting.gradle.plugin;

import java.util.List;

/**
 * Gradle-side POJO carrying the configuration for a single static test selection rule.
 * Bound by the Tia Gradle extension from a nested element in the
 * {@code staticTestSelectionRules} list inside the {@code tia} extension block.
 *
 * <p>The fields here are the Gradle configuration surface;
 * {@link TiaBasePlugin#buildStaticTestSelectionConfig()} converts these into the core
 * {@link org.tiatesting.core.staticselection.StaticTestSelectionRule} (compiling regexes and
 * validating mode-specific constraints).
 */
public class GradleStaticTestSelectionRule {

    /** Optional human-readable rule name used in log output. */
    private String name;

    /** Regex matched against the repo-relative path of each changed file. Required. */
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
}
