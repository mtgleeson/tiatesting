package org.tiatesting.core.staticselection;

/**
 * The selection mode for a {@link StaticTestSelectionRule}. Determines how the rule resolves
 * the set of test suites to force-run when at least one changed file path matches the rule's
 * file-path regex.
 */
public enum StaticTestSelectionRuleMode {

    /**
     * Force-run every test suite tracked by Tia.
     */
    RUN_ALL,

    /**
     * Force-run every tracked test suite whose simple class name or fully qualified name
     * matches at least one of the rule's suite-name regex patterns.
     */
    SUITE_NAMES,

    /**
     * Reserved for a future stage. Will force-run every tracked test suite whose persisted
     * annotations or tags match at least one of the rule's annotation/tag regex patterns.
     * Not yet supported — passing this mode to {@link StaticTestSelectionRule} throws an
     * {@link UnsupportedOperationException}.
     */
    ANNOTATIONS_TAGS
}
