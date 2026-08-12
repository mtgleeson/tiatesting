package org.tiatesting.core.persistence.h2;

import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for the {@code tia_test_run_history} read/write path in {@link JdbcDataStore}.
 * Uses a temp-directory H2 database per test for isolation.
 */
class JdbcDataStoreTestRunHistoryTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-test-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())), BranchSchema.schemaName("test"));
        // force schema creation
        dataStore.getTiaData(true);
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

    @Test
    void persistAndReadReturnsRow() {
        // given
        TestRunHistoryEntry entry = TestRunHistoryEntry.create(
                "main", "abc123", 1_700_000_000_000L,
                10, 2, 1, 5_000L, true, 4_000L, 80);

        // when
        dataStore.persistTestRunHistoryEntry(entry);
        List<TestRunHistoryEntry> result = dataStore.readTestRunHistory();

        // then
        assertEquals(1, result.size());
        TestRunHistoryEntry round = result.get(0);
        assertEquals(entry.getId(), round.getId());
        assertEquals(1_700_000_000_000L, round.getRunTimestampMs());
        assertEquals("main", round.getBranch());
        assertEquals("abc123", round.getCommit());
        assertEquals(10, round.getNumSuitesRan());
        assertEquals(2, round.getNumSuitesIgnored());
        assertEquals(1, round.getNumSuitesFailed());
        assertEquals(5_000L, round.getDurationMs());
        assertTrue(round.isUpdatedDbMapping());
        assertEquals(4_000L, round.getTimeSavingsMs());
        assertEquals(80, round.getSavingsPercent());
    }

    /**
     * Verify the three distributed-only history columns round-trip: a build-level row written by a
     * distributed run's sealer carries the run id it sealed, the wall-clock duration (the slowest
     * group) and the group count, alongside the serial-equivalent duration in the existing
     * {@code duration_ms} column.
     */
    @Test
    void persistAndReadRoundTripsTheDistributedColumns() {
        // given
        TestRunHistoryEntry entry = new TestRunHistoryEntry(
                "dist-id", 1_700_000_000_000L, "main", "abc123",
                10, 2, 1, 5_000L, true, 4_000L, 80,
                "ci-run-42", 1_800L, 4);

        // when
        dataStore.persistTestRunHistoryEntry(entry);
        List<TestRunHistoryEntry> result = dataStore.readTestRunHistory();

        // then
        assertEquals(1, result.size());
        TestRunHistoryEntry round = result.get(0);
        assertEquals("ci-run-42", round.getRunId());
        assertEquals(Long.valueOf(1_800L), round.getWallClockMs());
        assertEquals(Integer.valueOf(4), round.getGroupCount());
        assertEquals(5_000L, round.getDurationMs());
    }

    /**
     * Verify a non-distributed run's history row is unchanged by the new columns: all three read
     * back null rather than as zeros, so reporting can tell "this run was not distributed" from
     * "this run was distributed across zero groups in zero milliseconds".
     */
    @Test
    void nonDistributedRunLeavesTheDistributedColumnsNull() {
        // given
        TestRunHistoryEntry entry = TestRunHistoryEntry.create(
                "main", "abc123", 1_700_000_000_000L,
                10, 2, 1, 5_000L, true, 4_000L, 80);

        // when
        dataStore.persistTestRunHistoryEntry(entry);
        List<TestRunHistoryEntry> result = dataStore.readTestRunHistory();

        // then
        assertEquals(1, result.size());
        TestRunHistoryEntry round = result.get(0);
        assertNull(round.getRunId());
        assertNull(round.getWallClockMs());
        assertNull(round.getGroupCount());
    }

    @Test
    void readReturnsEmptyListWhenNothingPersisted() {
        // given a fresh DB with the table provisioned but no inserts

        // when
        List<TestRunHistoryEntry> result = dataStore.readTestRunHistory();

        // then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void multipleEntriesReturnedMostRecentFirst() {
        // given three runs at distinct timestamps, inserted out of order
        TestRunHistoryEntry oldest = TestRunHistoryEntry.create("main", "c1", 1_000L, 1, 0, 0, 10L, true, 0L, 0);
        TestRunHistoryEntry newest = TestRunHistoryEntry.create("main", "c3", 3_000L, 3, 0, 0, 30L, true, 0L, 0);
        TestRunHistoryEntry middle = TestRunHistoryEntry.create("main", "c2", 2_000L, 2, 0, 0, 20L, true, 0L, 0);

        // when
        dataStore.persistTestRunHistoryEntry(middle);
        dataStore.persistTestRunHistoryEntry(oldest);
        dataStore.persistTestRunHistoryEntry(newest);
        List<TestRunHistoryEntry> result = dataStore.readTestRunHistory();

        // then — ORDER BY run_timestamp DESC
        assertEquals(3, result.size());
        assertEquals(newest.getId(), result.get(0).getId());
        assertEquals(middle.getId(), result.get(1).getId());
        assertEquals(oldest.getId(), result.get(2).getId());
    }

    @Test
    void persistSameLogicalRunTwiceIsIdempotent() {
        // given two persists of the same (branch, commit, timestamp) triple
        TestRunHistoryEntry first = TestRunHistoryEntry.create("main", "abc", 5_000L, 5, 0, 0, 50L, true, 0L, 0);
        TestRunHistoryEntry secondWithDifferentCounts = TestRunHistoryEntry.create(
                "main", "abc", 5_000L, 99, 99, 99, 999L, false, 0L, 0);

        // when
        dataStore.persistTestRunHistoryEntry(first);
        dataStore.persistTestRunHistoryEntry(secondWithDifferentCounts);
        List<TestRunHistoryEntry> result = dataStore.readTestRunHistory();

        // then — MERGE on the deterministic id keeps a single row; the second persist's values win.
        assertEquals(1, result.size());
        assertEquals(99, result.get(0).getNumSuitesRan());
        assertEquals(999L, result.get(0).getDurationMs());
    }

    @Test
    void tiaDataLoadIncludesTestRunHistory() {
        // given a persisted entry
        TestRunHistoryEntry entry = TestRunHistoryEntry.create("main", "abc", 1L, 1, 0, 0, 1L, true, 0L, 0);
        dataStore.persistTestRunHistoryEntry(entry);

        // when
        TiaData reloaded = dataStore.getTiaData(true);

        // then the history list on TiaData reflects the persisted row
        assertNotNull(reloaded.getTestRunHistory());
        assertEquals(1, reloaded.getTestRunHistory().size());
        assertEquals(entry.getId(), reloaded.getTestRunHistory().get(0).getId());
    }
}
