package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Locks the atomicity of {@code persistTestSuitesFailed}: the clear-out of the previous failed-test
 * rows and the insert of the new set must land in the same transaction, so a failure partway through
 * the insert leaves the previously persisted rows intact rather than wiped. This is a regression test
 * for a bug where the clear-out used {@code TRUNCATE TABLE}, which H2 implements as DDL that
 * implicitly commits and so escapes the transaction - a later {@code rollback()} could not undo it,
 * leaving the failed-test set permanently empty after a failed persist. See
 * {@link JdbcDataStore#writeSourceMethods} for the same pattern fixed earlier for the source method
 * catalogue.
 */
class JdbcDataStoreTestSuitesFailedTest {

    private static final Set<String> SEEDED_SUITES = new HashSet<>(java.util.Arrays.asList(
            "com.example.FooTest", "com.example.BarTest"));

    /**
     * Verifies that a failure during the insert half of a second {@code persistTestSuitesFailed}
     * call leaves the previously persisted failed-test suites intact. The second call's set contains
     * a suite name with an embedded single quote, which breaks the hand-built {@code INSERT} SQL
     * (the value is spliced into the statement without escaping) and throws only once the insert is
     * executed - after the clear-out of the seeded rows has already run. If the clear-out is a
     * {@code TRUNCATE TABLE} rather than a {@code DELETE FROM}, the implicit commit means the
     * seeded rows are gone for good even though the overall call fails and rolls back; this test
     * fails in that case because the seeded rows would not be found afterwards.
     *
     * @param dir a fresh temp directory used as the embedded H2 database location
     */
    @Test
    void aFailureDuringTheInsertLeavesThePreviouslyPersistedFailedSuitesIntact(@TempDir Path dir) {
        // given a store seeded with a known set of failed test suites
        DataStore store = DataStoreFactory.fromConfig(dir.toString(), null, "tia", "", null, "main");
        try {
            store.getTiaData(true);
            store.persistTestSuitesFailed(new HashSet<>(SEEDED_SUITES));
            assertEquals(SEEDED_SUITES, store.getTestSuitesFailed(), "seed data must be persisted before the failing call");

            // when a second persist is attempted with a suite name that breaks the hand-built insert
            // SQL, throwing part way through the insert phase, after the clear-out has already run
            Set<String> broken = Collections.singleton("com.example.Broken'Test");

            // then the call fails, and the originally seeded rows are still present - proving the
            // clear-out was rolled back along with the rest of the failed transaction rather than
            // having escaped it
            assertThrows(RuntimeException.class, () -> store.persistTestSuitesFailed(broken));
            assertEquals(SEEDED_SUITES, store.getTestSuitesFailed(),
                    "the previously persisted failed suites must survive a rolled-back clear-out");
        } finally {
            store.close();
        }
    }
}
