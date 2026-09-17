package org.tiatesting.core.staticselection;

import org.tiatesting.core.model.TestRunTrigger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Outcome of resolving the static test selection rules against a commit's changed paths: the union
 * of every forced suite name, and one {@link TestRunTrigger} per fired rule carrying that rule's
 * forced-suite count for the run-history breakdown.
 */
public final class StaticTestSelectionResult {
    private final Set<String> forcedSuites;
    private final List<TestRunTrigger> ruleTriggers;

    /**
     * Build the resolution outcome for one {@link StaticTestSelectionResolver#resolve(Set, java.util.Map)}
     * call, normalising null inputs to empty collections so callers never need to null-check the
     * getters.
     *
     * @param forcedSuites the union of all forced suite names; may be {@code null}, treated as empty
     * @param ruleTriggers one trigger per fired rule; may be {@code null}, treated as empty
     */
    public StaticTestSelectionResult(Set<String> forcedSuites, List<TestRunTrigger> ruleTriggers) {
        this.forcedSuites = forcedSuites == null ? Collections.<String>emptySet() : forcedSuites;
        this.ruleTriggers = ruleTriggers == null ? Collections.<TestRunTrigger>emptyList()
                : Collections.unmodifiableList(new ArrayList<TestRunTrigger>(ruleTriggers));
    }

    /**
     * The union of every forced suite name across all fired rules, deduplicated.
     *
     * @return the union of forced suite names; never {@code null}, may be empty
     */
    public Set<String> getForcedSuites() {
        return forcedSuites;
    }

    /**
     * The per-rule breakdown backing the run-history trigger display: one entry per rule that
     * fired (matched at least one changed path), carrying that rule's own forced-suite count.
     *
     * @return one trigger per fired rule (rule name + forced-suite count); never {@code null}, may be empty
     */
    public List<TestRunTrigger> getRuleTriggers() {
        return ruleTriggers;
    }
}
