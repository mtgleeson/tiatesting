package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link MethodToTestSuiteIndex} — the shared lazy reverse-index used by
 * {@code TestSelector} and {@code PendingLibraryImpactedMethodsDrainer}.
 */
class MethodToTestSuiteIndexTest {

    /**
     * Empty / null inputs return an empty map, never throwing — both call sites can hit this
     * shape (e.g. a fresh DB with no tracked suites).
     */
    @Test
    void emptyOrNullTiaData_returnsEmptyMap() {
        // given
        TiaData emptyData = new TiaData();
        emptyData.setTestSuitesTracked(new HashMap<>());

        // when
        Map<Integer, Set<String>> empty = MethodToTestSuiteIndex.build(emptyData);
        Map<Integer, Set<String>> nullCase = MethodToTestSuiteIndex.build(null);

        // then
        assertNotNull(empty);
        assertTrue(empty.isEmpty(), "Empty testSuitesTracked must produce empty index");
        assertNotNull(nullCase);
        assertTrue(nullCase.isEmpty(), "Null TiaData must produce empty index");
    }

    /**
     * A single suite that exercises three methods produces three entries, each mapping back
     * to the suite name.
     */
    @Test
    void singleSuite_indexesAllItsMethods() {
        // given
        TiaData tiaData = tiaDataWith(suite("com.example.AlphaTest",
                cls(/*methods=*/ 10, 20, 30)));

        // when
        Map<Integer, Set<String>> index = MethodToTestSuiteIndex.build(tiaData);

        // then
        assertEquals(3, index.size());
        assertEquals(Collections.singleton("com.example.AlphaTest"), index.get(10));
        assertEquals(Collections.singleton("com.example.AlphaTest"), index.get(20));
        assertEquals(Collections.singleton("com.example.AlphaTest"), index.get(30));
    }

    /**
     * Two suites sharing one method produce a single index entry whose value contains both
     * suite names — this is the reverse-lookup case the index exists for.
     */
    @Test
    void overlappingSuites_collectAllExercisersPerMethod() {
        // given
        TiaData tiaData = tiaDataWith(
                suite("com.example.AlphaTest", cls(10, 20)),
                suite("com.example.BetaTest", cls(20, 30)));

        // when
        Map<Integer, Set<String>> index = MethodToTestSuiteIndex.build(tiaData);

        // then
        assertEquals(3, index.size());
        assertEquals(Collections.singleton("com.example.AlphaTest"), index.get(10));
        assertEquals(Collections.singleton("com.example.BetaTest"), index.get(30));
        Set<String> shared = index.get(20);
        assertTrue(shared.contains("com.example.AlphaTest"));
        assertTrue(shared.contains("com.example.BetaTest"));
        assertEquals(2, shared.size());
    }

    /**
     * A suite with multiple impacted classes contributes each class's methods to the index —
     * confirms the inner loop over {@code ClassImpactTracker}s isn't skipping anything.
     */
    @Test
    void multipleClassesPerSuite_allMethodsIndexed() {
        // given
        TiaData tiaData = tiaDataWith(suite("com.example.AlphaTest",
                cls(/*classA methods=*/ 10, 20),
                cls(/*classB methods=*/ 30, 40)));

        // when
        Map<Integer, Set<String>> index = MethodToTestSuiteIndex.build(tiaData);

        // then
        assertEquals(4, index.size());
        for (int methodId : new int[]{10, 20, 30, 40}) {
            assertEquals(Collections.singleton("com.example.AlphaTest"), index.get(methodId),
                    "Method " + methodId + " should resolve to AlphaTest");
        }
    }

    /**
     * The holder caches the result so two consumers (TestSelector + Drainer) sharing one
     * instance pay the build cost once. This is the whole reason the holder exists.
     */
    @Test
    void getMap_cachesAcrossCalls() {
        // given
        TiaData tiaData = tiaDataWith(suite("com.example.AlphaTest", cls(10)));
        MethodToTestSuiteIndex holder = new MethodToTestSuiteIndex(tiaData);

        // when
        Map<Integer, Set<String>> first = holder.getMap();
        Map<Integer, Set<String>> second = holder.getMap();

        // then
        assertSame(first, second, "Second call must return the cached map instance");
    }

    private static TiaData tiaDataWith(TestSuiteTracker... suites) {
        TiaData data = new TiaData();
        Map<String, TestSuiteTracker> tracked = new LinkedHashMap<>();
        for (TestSuiteTracker s : suites) {
            tracked.put(s.getName(), s);
        }
        data.setTestSuitesTracked(tracked);
        return data;
    }

    private static TestSuiteTracker suite(String name, ClassImpactTracker... classes) {
        TestSuiteTracker tracker = new TestSuiteTracker(name);
        tracker.setClassesImpacted(Arrays.asList(classes));
        return tracker;
    }

    private static ClassImpactTracker cls(int... methodIds) {
        MethodIdSet methodSet = new MethodIdSet();
        for (int id : methodIds) {
            methodSet.appendForBulkBuild(id);
        }
        methodSet.finishBulkBuild();
        return new ClassImpactTracker("com/example/Class", methodSet);
    }
}
