package org.tiatesting.core.staticselection;

import org.tiatesting.core.staticselection.pom.ExternalPomSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Configuration for static test selection. Carries an immutable list of
 * {@link StaticTestSelectionRule}s that supplement the dynamic (coverage-derived) selection
 * performed by {@link org.tiatesting.core.diff.diffanalyze.selector.TestSelector}.
 *
 * <p>Static rules are <em>additive only</em>: any suites they resolve are unioned into the
 * dynamic selection. A rule can cause additional tests to run; it can never cause a test to
 * be skipped that the dynamic selection would otherwise have run.
 *
 * <p>Mirrors the shape of {@link org.tiatesting.core.library.LibraryImpactAnalysisConfig}:
 * immutable, {@code isEnabled()} false when no rules are configured.
 */
public class StaticTestSelectionConfig {

    /**
     * Singleton empty/disabled config. Useful for callers that want a non-null placeholder
     * when the feature is not configured.
     */
    public static final StaticTestSelectionConfig EMPTY =
            new StaticTestSelectionConfig(Collections.emptyList(), null);

    private final List<StaticTestSelectionRule> rules;

    /**
     * Reads POMs outside the repository (external parents, imported BOMs) for POM-aware rules;
     * {@code null} when the build tool provides none.
     */
    private final ExternalPomSource externalPomSource;

    /**
     * Construct a static test selection config from a list of pre-built rules.
     *
     * @param rules the rules to apply during test selection; {@code null} is treated as empty.
     * @param externalPomSource reads POMs outside the repository so POM-aware rules can classify
     *                          external parent and BOM version bumps by what they change;
     *                          {@code null} when not available.
     */
    public StaticTestSelectionConfig(final List<StaticTestSelectionRule> rules,
                                     final ExternalPomSource externalPomSource) {
        this.rules = (rules != null && !rules.isEmpty())
                ? Collections.unmodifiableList(new ArrayList<>(rules))
                : Collections.emptyList();
        this.externalPomSource = externalPomSource;
    }

    /**
     * @return the reader for POMs outside the repository, or {@code null} when not available.
     */
    public ExternalPomSource getExternalPomSource() {
        return externalPomSource;
    }

    /**
     * @return the configured rules; never {@code null}, may be empty.
     */
    public List<StaticTestSelectionRule> getRules() {
        return rules;
    }

    /**
     * Returns {@code true} when at least one rule is configured and static test selection
     * should be applied during test selection.
     *
     * @return whether static test selection is active.
     */
    public boolean isEnabled() {
        return !rules.isEmpty();
    }
}
