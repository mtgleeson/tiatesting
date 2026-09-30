package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link DataStore#readTestSuiteCoverage}: the targeted read of just the named suites'
 * stored coverage that a fresh-JVM retry unions its own capture with.
 */
class JdbcDataStoreSuiteCoverageTest {

    /**
     * Verifies the read returns the named suites' classes and method ids, and nothing for a suite
     * that was not named.
     *
     * @param dir a fresh temp directory used as the embedded H2 database location
     */
    @Test
    void readTestSuiteCoverage_returnsOnlyTheNamedSuitesCoverage(@TempDir Path dir) {
        // given
        DataStore store = DataStoreFactory.fromConfig(dir.toString(), null, "tia", "", null, "main", null);
        try {
            store.getTiaData(true);
            Map<String, TestSuiteTracker> suites = new HashMap<>();
            suites.put("ATest", suite("ATest", new ClassImpactTracker("com/example/X.java", Arrays.asList(1, 2)),
                    new ClassImpactTracker("com/example/Y.java", Collections.singletonList(3))));
            suites.put("BTest", suite("BTest", new ClassImpactTracker("com/example/Z.java", Collections.singletonList(9))));
            store.persistTestSuites(suites);

            // when
            Map<String, List<ClassImpactTracker>> coverage =
                    store.readTestSuiteCoverage(new HashSet<>(Arrays.asList("ATest", "NoSuchTest")));

            // then
            assertEquals(Collections.singleton("ATest"), coverage.keySet());
            Map<String, TreeSet<Integer>> classes = new HashMap<>();
            for (ClassImpactTracker cls : coverage.get("ATest")) {
                classes.put(cls.getSourceFilename(), new TreeSet<>(cls.getMethodsImpacted()));
            }
            assertEquals(new TreeSet<>(Arrays.asList(1, 2)), classes.get("com/example/X.java"));
            assertEquals(new TreeSet<>(Collections.singletonList(3)), classes.get("com/example/Y.java"));
            assertFalse(coverage.containsKey("BTest"));
        } finally {
            store.close();
        }
    }

    /**
     * Verifies an empty request reads nothing.
     *
     * @param dir a fresh temp directory used as the embedded H2 database location
     */
    @Test
    void readTestSuiteCoverage_noSuitesNamed_returnsEmpty(@TempDir Path dir) {
        // given
        DataStore store = DataStoreFactory.fromConfig(dir.toString(), null, "tia", "", null, "main", null);
        try {
            store.getTiaData(true);

            // when
            Map<String, List<ClassImpactTracker>> coverage = store.readTestSuiteCoverage(Collections.emptySet());

            // then
            assertTrue(coverage.isEmpty());
        } finally {
            store.close();
        }
    }

    /**
     * Build a suite tracker with the given coverage.
     *
     * @param name the suite name
     * @param classes the classes it covers
     * @return the tracker
     */
    private static TestSuiteTracker suite(final String name, final ClassImpactTracker... classes) {
        TestSuiteTracker suite = new TestSuiteTracker(name);
        suite.setClassesImpacted(Arrays.asList(classes));
        return suite;
    }
}
