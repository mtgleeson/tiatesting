package org.tiatesting.core.staticselection;

import org.tiatesting.core.model.TiaData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lazy, cached index over the tracked test suite names in a {@link TiaData}, supporting fast
 * regex evaluation for static test selection rules in mode
 * {@link StaticTestSelectionRuleMode#SUITE_NAMES}.
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
 * <p>Built once per Tia run. For a project at the 5.6M-edge reference scale the build is O(n)
 * over tracked suite names; far below the cost of {@link org.tiatesting.core.model.MethodToTestSuiteIndex}
 * which iterates classes and method ids.
 *
 * <p>Not thread-safe — Tia's select-tests flow is single-threaded on the orchestrating thread
 * and the index is created per run, so concurrent access is not a concern.
 */
public final class SuiteNameIndex {

    private final TiaData tiaData;
    private Map<String, List<String>> simpleNameToFqns;
    private Set<String> fqns;

    /**
     * @param tiaData the loaded Tia data whose tracked test suite names will be indexed on
     *                first {@link #getSimpleNameToFqns()} / {@link #getFqns()} call.
     */
    public SuiteNameIndex(final TiaData tiaData) {
        this.tiaData = tiaData;
    }

    /**
     * Return the simple-name lookup map, building and caching it on first call.
     *
     * @return a map from simple class name to the list of fully qualified suite names sharing
     *         that simple name. Never {@code null}; empty when the Tia data has no tracked
     *         suites. The returned map is the live cache — callers must not mutate it.
     */
    public Map<String, List<String>> getSimpleNameToFqns() {
        ensureBuilt();
        return simpleNameToFqns;
    }

    /**
     * Return the set of all tracked FQN suite names, building and caching it on first call.
     *
     * @return the live, unmodifiable set of tracked FQNs. Never {@code null}; empty when the
     *         Tia data has no tracked suites.
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
        if (tiaData == null || tiaData.getTestSuitesTracked() == null) {
            this.simpleNameToFqns = Collections.emptyMap();
            this.fqns = Collections.emptySet();
            return;
        }

        Map<String, List<String>> simpleMap = new HashMap<>();
        Set<String> fqnSet = new LinkedHashSet<>();
        for (String fqn : tiaData.getTestSuitesTracked().keySet()) {
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
