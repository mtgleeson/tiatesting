package org.tiatesting.core.staticselection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A single static-test-selection rule. When any of the changed file paths in a Tia run matches
 * {@link #getFilePathPattern()}, the rule force-runs a set of test suites determined by
 * {@link #getMode()}.
 *
 * <p>Patterns are pre-compiled at construction so the hot read path in test selection only
 * pays for matching, not compilation.
 *
 * <p>Static rules are <em>additive only</em>: their resolved suite set is unioned into the
 * dynamic selection from method-impact analysis. A rule can only cause more tests to run; it
 * can never cause a test to be skipped.
 */
public class StaticTestSelectionRule {

    /**
     * Optional user-supplied name for this rule, used in log messages. When {@code null} or
     * blank, defaults to the file-path pattern's regex string.
     */
    private final String name;

    /**
     * The compiled regex matched against each changed file's repo-relative, forward-slash-normalised
     * path. Uses {@link java.util.regex.Matcher#find()} semantics: any substring match counts. Users
     * wanting strict anchoring can write {@code ^...$} themselves.
     */
    private final Pattern filePathPattern;

    /**
     * The selection mode that determines how this rule resolves suites to force-run.
     */
    private final StaticTestSelectionRuleMode mode;

    /**
     * Compiled regex patterns matched against test suite names. Only meaningful when
     * {@link #mode} is {@link StaticTestSelectionRuleMode#SUITE_NAMES}; empty otherwise.
     * Each pattern is matched against both the simple class name and the fully qualified
     * name; either match counts as a hit.
     */
    private final List<Pattern> suiteNamePatterns;

    /**
     * Construct a static test selection rule from raw regex strings, compiling each one at
     * construction time. Validates mode-specific constraints and rejects modes that are not
     * yet implemented.
     *
     * @param name optional human-readable rule name used in log messages; may be {@code null}
     *             or blank, in which case the file-path pattern is used as the rule's display name.
     * @param filePathPatternRegex the regex matched against changed file paths; must be non-null
     *                             and non-blank.
     * @param mode the selection mode; must be non-null. {@link StaticTestSelectionRuleMode#ANNOTATIONS_TAGS}
     *             is reserved for a future stage and is rejected here.
     * @param suiteNamePatternRegexes the suite-name regex patterns; required and non-empty when
     *                                {@code mode} is {@link StaticTestSelectionRuleMode#SUITE_NAMES},
     *                                must be empty (or {@code null}) for other modes.
     * @throws IllegalArgumentException if validation fails or any regex fails to compile.
     * @throws UnsupportedOperationException if {@code mode} is {@link StaticTestSelectionRuleMode#ANNOTATIONS_TAGS}.
     */
    public StaticTestSelectionRule(final String name, final String filePathPatternRegex,
                                   final StaticTestSelectionRuleMode mode,
                                   final List<String> suiteNamePatternRegexes) {
        if (filePathPatternRegex == null || filePathPatternRegex.trim().isEmpty()) {
            throw new IllegalArgumentException("Static test selection rule: file path pattern is required.");
        }
        if (mode == null) {
            throw new IllegalArgumentException("Static test selection rule '" + filePathPatternRegex
                    + "': mode is required (one of RUN_ALL, SUITE_NAMES).");
        }
        if (mode == StaticTestSelectionRuleMode.ANNOTATIONS_TAGS) {
            throw new UnsupportedOperationException("Static test selection rule '" + filePathPatternRegex
                    + "': mode ANNOTATIONS_TAGS is not yet supported. Use RUN_ALL or SUITE_NAMES.");
        }

        this.filePathPattern = compilePattern(filePathPatternRegex, "file path pattern");
        this.mode = mode;
        this.suiteNamePatterns = compileSuiteNamePatternsForMode(mode, suiteNamePatternRegexes, filePathPatternRegex);
        this.name = (name != null && !name.trim().isEmpty()) ? name.trim() : filePathPatternRegex;
    }

    /**
     * Compile and validate the suite-name patterns for the given mode.
     *
     * @param mode the rule mode.
     * @param suiteNamePatternRegexes the user-supplied regex strings; may be {@code null}.
     * @param filePathPatternRegex used in error messages to identify the offending rule.
     * @return the compiled list (empty for modes that do not use suite-name patterns).
     */
    private static List<Pattern> compileSuiteNamePatternsForMode(final StaticTestSelectionRuleMode mode,
                                                                 final List<String> suiteNamePatternRegexes,
                                                                 final String filePathPatternRegex) {
        if (mode == StaticTestSelectionRuleMode.SUITE_NAMES) {
            if (suiteNamePatternRegexes == null || suiteNamePatternRegexes.isEmpty()) {
                throw new IllegalArgumentException("Static test selection rule '" + filePathPatternRegex
                        + "': mode SUITE_NAMES requires at least one suite-name pattern.");
            }
            List<Pattern> compiled = new ArrayList<>(suiteNamePatternRegexes.size());
            for (String regex : suiteNamePatternRegexes) {
                if (regex == null || regex.trim().isEmpty()) {
                    throw new IllegalArgumentException("Static test selection rule '" + filePathPatternRegex
                            + "': suite-name pattern is blank.");
                }
                compiled.add(compilePattern(regex, "suite-name pattern"));
            }
            return Collections.unmodifiableList(compiled);
        }

        if (suiteNamePatternRegexes != null && !suiteNamePatternRegexes.isEmpty()) {
            throw new IllegalArgumentException("Static test selection rule '" + filePathPatternRegex
                    + "': suite-name patterns are only valid for mode SUITE_NAMES.");
        }
        return Collections.emptyList();
    }

    /**
     * Compile a single regex with a descriptive error when the pattern is malformed.
     *
     * @param regex the regex string.
     * @param description short description of the pattern for the error message.
     * @return the compiled {@link Pattern}.
     */
    private static Pattern compilePattern(final String regex, final String description) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Static test selection rule: failed to compile "
                    + description + " '" + regex + "': " + e.getDescription(), e);
        }
    }

    /**
     * @return the display name for this rule (user-supplied or, if blank, the file-path regex).
     */
    public String getName() {
        return name;
    }

    /**
     * @return the compiled file-path regex.
     */
    public Pattern getFilePathPattern() {
        return filePathPattern;
    }

    /**
     * @return the selection mode.
     */
    public StaticTestSelectionRuleMode getMode() {
        return mode;
    }

    /**
     * @return the compiled suite-name patterns; empty for modes other than {@link StaticTestSelectionRuleMode#SUITE_NAMES}.
     */
    public List<Pattern> getSuiteNamePatterns() {
        return suiteNamePatterns;
    }
}
