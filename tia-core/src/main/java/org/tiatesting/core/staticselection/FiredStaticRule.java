package org.tiatesting.core.staticselection;

import org.tiatesting.core.staticselection.pom.PomChangeCategory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * A static test selection rule that fired for a diff, with what made it fire: the number of
 * changed files its path pattern matched and, for a POM-aware rule, the categories of the POM
 * changes it reacted to.
 */
public final class FiredStaticRule {

    private final StaticTestSelectionRule rule;
    private final int matchedFileCount;
    private final Set<PomChangeCategory> firedCategories;

    /**
     * Construct a fired rule.
     *
     * @param rule the rule.
     * @param matchedFileCount the number of changed files the rule's path pattern matched.
     * @param firedCategories the categories of the POM changes the rule reacted to; empty for a
     *                        plain path rule.
     */
    public FiredStaticRule(final StaticTestSelectionRule rule, final int matchedFileCount,
                           final Set<PomChangeCategory> firedCategories) {
        this.rule = rule;
        this.matchedFileCount = matchedFileCount;
        this.firedCategories = firedCategories.isEmpty()
                ? Collections.<PomChangeCategory>emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(firedCategories));
    }

    /**
     * @return the rule.
     */
    public StaticTestSelectionRule getRule() {
        return rule;
    }

    /**
     * @return the number of changed files the rule's path pattern matched.
     */
    public int getMatchedFileCount() {
        return matchedFileCount;
    }

    /**
     * @return the categories of the POM changes the rule reacted to; empty for a plain path rule.
     */
    public Set<PomChangeCategory> getFiredCategories() {
        return firedCategories;
    }

    /**
     * The name recorded on the run-history trigger: the rule name, followed by the fired POM
     * change categories for a POM-aware rule, e.g. {@code Functional POM changes [DEPENDENCY_VERSIONS]}.
     *
     * @return the trigger name.
     */
    public String getTriggerName() {
        return firedCategories.isEmpty() ? rule.getName() : rule.getName() + " " + firedCategories;
    }
}
