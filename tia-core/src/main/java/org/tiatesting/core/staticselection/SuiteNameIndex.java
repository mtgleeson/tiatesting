package org.tiatesting.core.staticselection;

import org.tiatesting.core.model.TestSuiteTracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lazy, cached index over the tracked test suite names, supporting fast regex evaluation for
 * static test selection rules in mode {@link StaticTestSelectionRuleMode#SUITE_NAMES}.
 *
 * <p>Two structures are exposed:
 * <ul>
 *   <li>A map from <em>simple class name</em> (the substring after the last {@code '.'} of the
 *       FQN) to the list of FQNs sharing that simple name. Two suites in different packages can
 *       share a simple name; both will be returned for a simple-name match.</li>
 *   <li>The set of all tracked suite FQNs, so a pattern can be matched against the full name
 *       independently of the simple-name lookup.</li>
 * </ul>
 *
 * <p>Built once per Tia run. The build is O(n) over tracked suite names - suite-count scale
 * (thousands), not coverage-edge scale, so it stays cheap on the targeted select-tests read
 * path which deliberately never loads the edge data.
 *
 * <p>Not thread-safe — Tia's select-tests flow is single-threaded on the orchestrating thread
 * and the index is created per run, so concurrent access is not a concern.
 */
public final class SuiteNameIndex {

    private final Map<String, TestSuiteTracker> testSuitesTracked;
    private Map<String, List<String>> simpleNameToFqns;
    private Set<String> fqns;

    /**
     * @param testSuitesTracked the tracked test suites keyed by suite name, whose keys will be
     *                          indexed on first {@link #getSimpleNameToFqns()} / {@link #getFqns()}
     *                          call.
     */
    public SuiteNameIndex(final Map<String, TestSuiteTracker> testSuitesTracked) {
        this.testSuitesTracked = testSuitesTracked;
    }

    /**
     * Return the simple-name lookup map, building and caching it on first call.
     *
     * @return a map from simple class name to the list of fully qualified suite names sharing
     *         that simple name. Never {@code null}; empty when there are no tracked suites.
     *         The returned map is the live cache — callers must not mutate it.
     */
    public Map<String, List<String>> getSimpleNameToFqns() {
        ensureBuilt();
        return simpleNameToFqns;
    }

    /**
     * Return the set of all tracked FQN suite names, building and caching it on first call.
     *
     * @return the live, unmodifiable set of tracked FQNs. Never {@code null}; empty when
     *         there are no tracked suites.
     */
    public Set<String> getFqns() {
        ensureBuilt();
        return fqns;
    }

    /**
     * Build the index lazily on first access; subsequent calls return the cached references.
     */
    private void ensureBuilt() {
        if (simpleNameToFqns != null) {
            return;
        }
        if (testSuitesTracked == null) {
            this.simpleNameToFqns = Collections.emptyMap();
            this.fqns = Collections.emptySet();
            return;
        }

        Map<String, List<String>> simpleMap = new HashMap<>();
        Set<String> fqnSet = new LinkedHashSet<>();
        for (String fqn : testSuitesTracked.keySet()) {
            if (fqn == null || fqn.isEmpty()) {
                continue;
            }
            fqnSet.add(fqn);
            String simpleName = simpleNameOf(fqn);
            simpleMap.computeIfAbsent(simpleName, k -> new ArrayList<>()).add(fqn);
        }
        this.simpleNameToFqns = simpleMap;
        this.fqns = fqnSet;
    }

    /**
     * Derive a simple class name from a fully qualified suite name by stripping everything up
     * to and including the last {@code '.'}. When the FQN has no dot the full name is returned.
     *
     * @param fqn the fully qualified suite name.
     * @return the simple class name.
     */
    public static String simpleNameOf(final String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return (lastDot < 0) ? fqn : fqn.substring(lastDot + 1);
    }
}
