package org.tiatesting.core.persistence.h2;

import org.tiatesting.core.model.RunOrigin;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
    private H2ConnectionSettings settings;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-test-", "");
        tempDir.delete();
        tempDir.mkdirs();
        settings = H2ConnectionSettings.embedded(tempDir.getAbsolutePath());
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(settings), BranchSchema.schemaName("test", null));
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
                10, 2, 1, 5_000L, true, 4_000L, 80, RunOrigin.unknown());

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
                "ci-run-42", 1_800L, 4, RunOrigin.unknown());

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
                10, 2, 1, 5_000L, true, 4_000L, 80, RunOrigin.unknown());

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
        TestRunHistoryEntry oldest = TestRunHistoryEntry.create("main", "c1", 1_000L, 1, 0, 0, 10L, true, 0L, 0, RunOrigin.unknown());
        TestRunHistoryEntry newest = TestRunHistoryEntry.create("main", "c3", 3_000L, 3, 0, 0, 30L, true, 0L, 0, RunOrigin.unknown());
        TestRunHistoryEntry middle = TestRunHistoryEntry.create("main", "c2", 2_000L, 2, 0, 0, 20L, true, 0L, 0, RunOrigin.unknown());

        // when
        dataStore.persistTestRunHistoryEntry(middle);
        dataStore.persistTestRunHistoryEntry(oldest);
        dataStore.persistTestRunHistoryEntry(newest);
        List<TestRunHistoryEntry> result = dataStore.readTestRunHistory();

        // then - ORDER BY run_timestamp DESC
        assertEquals(3, result.size());
        assertEquals(newest.getId(), result.get(0).getId());
        assertEquals(middle.getId(), result.get(1).getId());
        assertEquals(oldest.getId(), result.get(2).getId());
    }

    @Test
    void persistSameLogicalRunTwiceIsIdempotent() {
        // given two persists of the same (branch, commit, timestamp) triple
        TestRunHistoryEntry first = TestRunHistoryEntry.create("main", "abc", 5_000L, 5, 0, 0, 50L, true, 0L, 0, RunOrigin.unknown());
        TestRunHistoryEntry secondWithDifferentCounts = TestRunHistoryEntry.create(
                "main", "abc", 5_000L, 99, 99, 99, 999L, false, 0L, 0, RunOrigin.unknown());

        // when
        dataStore.persistTestRunHistoryEntry(first);
        dataStore.persistTestRunHistoryEntry(secondWithDifferentCounts);
        List<TestRunHistoryEntry> result = dataStore.readTestRunHistory();

        // then - MERGE on the deterministic id keeps a single row; the second persist's values win.
        assertEquals(1, result.size());
        assertEquals(99, result.get(0).getNumSuitesRan());
        assertEquals(999L, result.get(0).getDurationMs());
    }

    @Test
    void tiaDataLoadIncludesTestRunHistory() {
        // given a persisted entry
        TestRunHistoryEntry entry = TestRunHistoryEntry.create("main", "abc", 1L, 1, 0, 0, 1L, true, 0L, 0, RunOrigin.unknown());
        dataStore.persistTestRunHistoryEntry(entry);

        // when
        TiaData reloaded = dataStore.getTiaData(true);

        // then the history list on TiaData reflects the persisted row
        assertNotNull(reloaded.getTestRunHistory());
        assertEquals(1, reloaded.getTestRunHistory().size());
        assertEquals(entry.getId(), reloaded.getTestRunHistory().get(0).getId());
    }

    /**
     * The run-origin columns round-trip: the source and the host the run reported are what come
     * back, so the history table can be split by origin.
     */
    @Test
    void persistAndReadRoundTripsTheRunOriginColumns() {
        // given
        TestRunHistoryEntry entry = TestRunHistoryEntry.create(
                "main", "abc123", 1_700_000_000_000L,
                10, 2, 1, 5_000L, false, 4_000L, 80,
                RunOrigin.of(RunOrigin.SOURCE_LOCAL, "dev-laptop-7"));

        // when
        dataStore.persistTestRunHistoryEntry(entry);
        List<TestRunHistoryEntry> result = dataStore.readTestRunHistory();

        // then
        assertEquals(1, result.size());
        RunOrigin round = result.get(0).getRunOrigin();
        assertEquals(RunOrigin.SOURCE_LOCAL, round.getRunSource());
        assertEquals("dev-laptop-7", round.getHostName());
    }

    /**
     * An unknown origin is stored as SQL NULL, not as a placeholder string. Several unrelated runs
     * would otherwise appear to share a machine called "unknown".
     */
    @Test
    void anUnknownRunOriginReadsBackNull() {
        // given
        TestRunHistoryEntry entry = TestRunHistoryEntry.create(
                "main", "abc123", 1_700_000_000_000L,
                10, 2, 1, 5_000L, false, 4_000L, 80, RunOrigin.unknown());

        // when
        dataStore.persistTestRunHistoryEntry(entry);
        List<TestRunHistoryEntry> result = dataStore.readTestRunHistory();

        // then
        RunOrigin round = result.get(0).getRunOrigin();
        assertNull(round.getRunSource());
        assertNull(round.getHostName());
    }

    /**
     * A distributed build records its source but no host, since no single machine ran it.
     */
    @Test
    void aDistributedRunStoresItsSourceWithoutAHost() {
        // given
        TestRunHistoryEntry entry = TestRunHistoryEntry.createForDistributedRun(
                "main", "abc123", "ci-run-42", 1_700_000_000_000L,
                10, 2, 1, 5_000L, true, 4_000L, 80, 1_800L, 4,
                RunOrigin.of(RunOrigin.SOURCE_CI, null));

        // when
        dataStore.persistTestRunHistoryEntry(entry);
        List<TestRunHistoryEntry> result = dataStore.readTestRunHistory();

        // then
        RunOrigin round = result.get(0).getRunOrigin();
        assertEquals(RunOrigin.SOURCE_CI, round.getRunSource());
        assertNull(round.getHostName(),
                "a distributed build must not be attributed to a single host");
    }

    /**
     * A history table predating the run-origin columns gains them via migration on next contact, and
     * the pre-existing rows read back null rather than being retro-labelled with an origin nobody
     * recorded.
     */
    @Test
    void migrationAddsTheRunOriginColumnsAndOldRowsReadNull() throws Exception {
        // given - persist a row, then drop the columns to simulate a pre-migration DB. The engine
        // stays alive for the JVM (DB_CLOSE_DELAY=-1), so the drop is visible to a fresh datastore
        // against the same file.
        dataStore.persistTestRunHistoryEntry(TestRunHistoryEntry.create(
                "main", "abc123", 1_700_000_000_000L, 10, 2, 1, 5_000L, true, 4_000L, 80,
                RunOrigin.of(RunOrigin.SOURCE_CI, "build-agent-3")));

        try (Connection connection = DriverManager.getConnection(new H2ConnectionProvider(settings).jdbcUrl(),
                settings.getUsername(), settings.getPassword());
             Statement statement = connection.createStatement()) {
            // a raw JDBC connection defaults to the vendor's default schema, not the branch schema
            // JdbcDataStore selects on its own connections - select it explicitly before altering
            // the unqualified table name.
            statement.execute(new H2Dialect().selectSchemaSql(BranchSchema.schemaName("test", null)));
            statement.executeUpdate("ALTER TABLE tia_test_run_history DROP COLUMN run_source");
            statement.executeUpdate("ALTER TABLE tia_test_run_history DROP COLUMN host_name");
        }

        // when - a fresh datastore re-runs the history DDL on read, which must re-add them
        JdbcDataStore migrated = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(settings), BranchSchema.schemaName("test", null));
        List<TestRunHistoryEntry> result = migrated.readTestRunHistory();
        migrated.close();

        // then
        assertEquals(1, result.size());
        RunOrigin round = result.get(0).getRunOrigin();
        assertNull(round.getRunSource(), "a pre-migration row must not be retro-labelled");
        assertNull(round.getHostName(), "a pre-migration row must not be retro-labelled");
    }
}
