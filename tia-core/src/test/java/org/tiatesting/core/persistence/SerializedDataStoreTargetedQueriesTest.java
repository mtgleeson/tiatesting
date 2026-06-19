package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the targeted select-tests reads in {@link SerializedDataStore}:
 * {@code getMethodsTrackedForFiles} and {@code getTestSuitesForMethods}. The serialized store
 * filters its in-memory {@code TiaData} rather than querying, but must return the same shape
 * as the H2 store so the selector can use either implementation interchangeably.
 */
class SerializedDataStoreTargetedQueriesTest {

    private static final String FOO_FILE = "com/example/Foo.java";
    private static final String BAR_FILE = "com/example/Bar.java";

    private SerializedDataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-test-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new SerializedDataStore(tempDir.getAbsolutePath(), "test");
        seedMapping();
    }

    @AfterEach
    void tearDown() {
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * Seed the store with: method 1 (Foo.java 10-20), method 2 (Foo.java 30-40),
     * method 3 (Bar.java 5-15). SuiteOne covers Foo[1,2] + Bar[3]; SuiteTwo covers Foo[1],
     * so method 1 exercises multi-suite dedup in both directions.
     */
    private void seedMapping() {
        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        methods.put(1, new MethodImpactTracker("com/example/Foo.methodA.()V", 10, 20));
        methods.put(2, new MethodImpactTracker("com/example/Foo.methodB.()V", 30, 40));
        methods.put(3, new MethodImpactTracker("com/example/Bar.methodC.()V", 5, 15));

        TestSuiteTracker suiteOne = new TestSuiteTracker("SuiteOne");
        suiteOne.setClassesImpacted(Arrays.asList(
                new ClassImpactTracker(FOO_FILE, Arrays.asList(1, 2)),
                new ClassImpactTracker(BAR_FILE, Collections.singletonList(3))));
        TestSuiteTracker suiteTwo = new TestSuiteTracker("SuiteTwo");
        suiteTwo.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker(FOO_FILE, Collections.singletonList(1))));

        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put("SuiteOne", suiteOne);
        suites.put("SuiteTwo", suiteTwo);

        TiaData tiaData = new TiaData();
        tiaData.setMethodsTracked(methods);
        tiaData.setTestSuitesTracked(suites);
        dataStore.persistCoreData(tiaData);
    }

    @Test
    void filesToMethodsLookupFiltersToRequestedFiles() {
        // given - the seeded mapping with two files

        // when
        Map<String, Map<Integer, MethodImpactTracker>> result =
                dataStore.getMethodsTrackedForFiles(Collections.singleton(FOO_FILE));

        // then - only Foo.java methods, deduplicated across the two covering suites
        assertEquals(Collections.singleton(FOO_FILE), result.keySet());
        assertEquals(new HashSet<>(Arrays.asList(1, 2)), result.get(FOO_FILE).keySet());
        assertEquals(10, result.get(FOO_FILE).get(1).getLineNumberStart());
    }

    @Test
    void filesToMethodsLookupHandlesUntrackedAndEmptyInput() {
        // given - the seeded mapping

        // when
        Map<String, Map<Integer, MethodImpactTracker>> untracked =
                dataStore.getMethodsTrackedForFiles(Collections.singleton("com/example/Unknown.java"));
        Map<String, Map<Integer, MethodImpactTracker>> empty =
                dataStore.getMethodsTrackedForFiles(Collections.emptySet());
        Map<String, Map<Integer, MethodImpactTracker>> nullInput =
                dataStore.getMethodsTrackedForFiles(null);

        // then
        assertTrue(untracked.isEmpty());
        assertTrue(empty.isEmpty());
        assertTrue(nullInput.isEmpty());
    }

    @Test
    void methodsToSuitesLookupResolvesAllCoveringSuites() {
        // given - method 1 covered by two suites, method 3 by one

        // when
        Map<Integer, Set<String>> result =
                dataStore.getTestSuitesForMethods(new HashSet<>(Arrays.asList(1, 3)));

        // then
        assertEquals(new HashSet<>(Arrays.asList("SuiteOne", "SuiteTwo")), result.get(1));
        assertEquals(Collections.singleton("SuiteOne"), result.get(3));
    }

    @Test
    void methodsToSuitesLookupHandlesUnknownAndEmptyInput() {
        // given - the seeded mapping

        // when
        Map<Integer, Set<String>> unknown = dataStore.getTestSuitesForMethods(Collections.singleton(999));
        Map<Integer, Set<String>> empty = dataStore.getTestSuitesForMethods(Collections.emptySet());
        Map<Integer, Set<String>> nullInput = dataStore.getTestSuitesForMethods(null);

        // then
        assertTrue(unknown.isEmpty());
        assertTrue(empty.isEmpty());
        assertTrue(nullInput.isEmpty());
    }
}
