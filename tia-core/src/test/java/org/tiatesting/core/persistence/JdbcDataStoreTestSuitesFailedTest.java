package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.TestSuiteTracker;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the incremental failed-suite write, {@code persistTestSuitesFailed(suitesToClear,
 * suitesFailed)}: it touches only the named suites, it is atomic, and it tolerates a suite that is
 * already stored. Also covers that deleting a suite removes its failed-suite row with it.
 *
 * <p>The write used to clear the whole table and rewrite the full set, which made it a
 * read-modify-write across the persist: two runners of a distributed build persisting at the same
 * time could each read the set before the other wrote, and the second write discarded the first
 * runner's failures. The incremental write has no read step, so the interleaving is shown by two
 * data stores over one database each writing their own disjoint suites.
 */
class JdbcDataStoreTestSuitesFailedTest {

    private static final Set<String> SEEDED_SUITES = new HashSet<>(Arrays.asList(
            "com.example.FooTest", "com.example.BarTest"));

    /**
     * Verifies that a failure during the insert half of the write rolls back the delete half too,
     * leaving the previously stored rows intact. The failed name is longer than the column allows,
     * so the insert fails only after the delete of the seeded rows has already run.
     *
     * @param dir a fresh temp directory used as the embedded H2 database location
     */
    @Test
    void aFailureDuringTheInsertLeavesThePreviouslyPersistedFailedSuitesIntact(@TempDir Path dir) {
        // given
        DataStore store = newStore(dir);
        try {
            store.persistTestSuitesFailed(Collections.emptySet(), SEEDED_SUITES);
            Set<String> tooLong = Collections.singleton(repeat('x', 300));

            // when
            assertThrows(RuntimeException.class, () -> store.persistTestSuitesFailed(SEEDED_SUITES, tooLong));

            // then
            assertEquals(SEEDED_SUITES, store.getTestSuitesFailed(),
                    "the delete must be rolled back with the failed insert");
        } finally {
            store.close();
        }
    }

    /**
     * Verifies only the suites named to clear lose their stored row; every other stored suite is
     * left exactly as it was.
     *
     * @param dir a fresh temp directory used as the embedded H2 database location
     */
    @Test
    void clearingASuiteLeavesTheOtherStoredSuitesUntouched(@TempDir Path dir) {
        // given
        DataStore store = newStore(dir);
        try {
            store.persistTestSuitesFailed(Collections.emptySet(), SEEDED_SUITES);

            // when
            store.persistTestSuitesFailed(Collections.singleton("com.example.FooTest"), Collections.emptySet());

            // then
            assertEquals(Collections.singleton("com.example.BarTest"), store.getTestSuitesFailed());
        } finally {
            store.close();
        }
    }

    /**
     * Verifies adding a suite that is already stored is a no-op rather than a primary-key
     * violation.
     *
     * @param dir a fresh temp directory used as the embedded H2 database location
     */
    @Test
    void addingAnAlreadyStoredSuiteIsANoOp(@TempDir Path dir) {
        // given
        DataStore store = newStore(dir);
        try {
            store.persistTestSuitesFailed(Collections.emptySet(), SEEDED_SUITES);

            // when
            store.persistTestSuitesFailed(Collections.emptySet(), Collections.singleton("com.example.FooTest"));

            // then
            assertEquals(SEEDED_SUITES, store.getTestSuitesFailed());
        } finally {
            store.close();
        }
    }

    /**
     * Verifies two distributed runners writing for disjoint groups over one database keep each
     * other's failures - the lost update the whole-set rewrite allowed.
     *
     * @param dir a fresh temp directory used as the embedded H2 database location
     */
    @Test
    void twoRunnersWritingDisjointSuitesBothSurvive(@TempDir Path dir) {
        // given
        DataStore runnerA = newStore(dir);
        DataStore runnerB = newStore(dir);
        try {
            Set<String> groupA = new HashSet<>(Arrays.asList("com.example.A1Test", "com.example.A2Test"));
            Set<String> groupB = new HashSet<>(Arrays.asList("com.example.B1Test", "com.example.B2Test"));

            // when
            runnerB.persistTestSuitesFailed(groupB, Collections.singleton("com.example.B1Test"));
            runnerA.persistTestSuitesFailed(groupA, Collections.singleton("com.example.A2Test"));

            // then
            assertEquals(new HashSet<>(Arrays.asList("com.example.A2Test", "com.example.B1Test")),
                    runnerA.getTestSuitesFailed());
        } finally {
            runnerA.close();
            runnerB.close();
        }
    }

    /**
     * Verifies deleting a suite also removes its failed-suite row. A deleted suite never executes
     * again, so nothing else would ever clear it.
     *
     * @param dir a fresh temp directory used as the embedded H2 database location
     */
    @Test
    void deletingASuiteRemovesItsFailedRow(@TempDir Path dir) {
        // given
        DataStore store = newStore(dir);
        try {
            Map<String, TestSuiteTracker> suites = new HashMap<>();
            suites.put("com.example.FooTest", new TestSuiteTracker("com.example.FooTest"));
            store.persistTestSuites(suites);
            store.persistTestSuitesFailed(Collections.emptySet(), SEEDED_SUITES);

            // when
            store.deleteTestSuites(Collections.singleton("com.example.FooTest"));

            // then
            assertEquals(Collections.singleton("com.example.BarTest"), store.getTestSuitesFailed());
        } finally {
            store.close();
        }
    }

    /**
     * Open an embedded H2 store on the given directory, with its schema bootstrapped.
     *
     * @param dir the database directory
     * @return the store
     */
    private static DataStore newStore(final Path dir) {
        DataStore store = DataStoreFactory.fromConfig(dir.toString(), null, "tia", "", null, "main", null);
        store.getTiaData(true);
        return store;
    }

    /**
     * Build a string of one character repeated.
     *
     * @param c the character
     * @param count how many times
     * @return the string
     */
    private static String repeat(final char c, final int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}
