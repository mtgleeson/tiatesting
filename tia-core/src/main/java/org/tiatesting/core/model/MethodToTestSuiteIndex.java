package org.tiatesting.core.model;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Lazy, cached reverse-index from source method id to the set of test suites that exercise it,
 * derived from {@link TiaData#getTestSuitesTracked()}. Built once per Tia run and reused by
 * both the test-selector path (resolving impacted source files to tests) and the library drain
 * path (resolving pending-method ids to tests).
 *
 * <p>For a project at the 5.6M-edge reference scale this build is ~1.5–2s of CPU; sharing it
 * between the two consumers avoids paying that twice. The first call to {@link #getMap()}
 * builds the map; subsequent calls return the cached reference.
 *
 * <p>Not thread-safe — Tia's select-tests flow is single-threaded on the orchestrating thread
 * and the index is created per run, so concurrent access isn't a concern.
 */
public final class MethodToTestSuiteIndex {

    private final TiaData tiaData;
    private Map<Integer, Set<String>> cached;

    /**
     * @param tiaData the loaded Tia data whose {@code testSuitesTracked} mapping will be indexed
     *                on first {@link #getMap()} call
     */
    public MethodToTestSuiteIndex(TiaData tiaData) {
        this.tiaData = tiaData;
    }

    /**
     * Return the reverse-index, building and caching it on first call.
     *
     * @return a map from source method id to the set of test suite names that exercise it.
     *         Never null; empty if {@link TiaData#getTestSuitesTracked()} is null or empty.
     *         The returned map is the live cache — callers must not mutate it.
     */
    public Map<Integer, Set<String>> getMap() {
        if (cached == null) {
            cached = build(tiaData);
        }
        return cached;
    }

    /**
     * Stateless single-pass build of the reverse index. Exposed so callers that already know
     * the index won't be reused can skip the holder. {@link LinkedHashSet} is used for the
     * per-method test-suite collection to keep iteration order deterministic across runs
     * (matches the previous {@code PendingLibraryImpactedMethodsDrainer} implementation; the
     * previous {@code TestSelector} implementation used a {@link java.util.HashSet} which gave
     * non-deterministic order — the determinism is preferred).
     *
     * @param tiaData the Tia data to index
     * @return a fresh reverse index from method id → set of test suite names
     */
    public static Map<Integer, Set<String>> build(TiaData tiaData) {
        Map<Integer, Set<String>> map = new HashMap<>();
        if (tiaData == null || tiaData.getTestSuitesTracked() == null) {
            return map;
        }
        tiaData.getTestSuitesTracked().forEach((testSuiteName, testSuiteTracker) -> {
            for (ClassImpactTracker classImpacted : testSuiteTracker.getClassesImpacted()) {
                for (Integer methodId : classImpacted.getMethodsImpacted()) {
                    map.computeIfAbsent(methodId, k -> new LinkedHashSet<>()).add(testSuiteName);
                }
            }
        });
        return map;
    }
}
