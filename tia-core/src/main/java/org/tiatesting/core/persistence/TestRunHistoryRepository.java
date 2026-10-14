package org.tiatesting.core.persistence;

import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunTrigger;
import org.tiatesting.core.persistence.dialect.SqlDialect;

import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

import static org.tiatesting.core.persistence.JdbcRows.*;
import static org.tiatesting.core.persistence.TiaSchema.*;

/**
 * Reads and writes the run history: one {@code tia_test_run_history} row per test run, plus the
 * selection triggers ({@code tia_test_run_history_trigger}) attributed to each row.
 */
class TestRunHistoryRepository extends JdbcRepository {

    /**
     * Construct the repository over the datastore's shared JDBC plumbing.
     *
     * @param dialect the SQL dialect rendering vendor-specific statements
     * @param connections opens a connection with the per-branch schema selected
     * @param schemaManager bootstraps the schema before first use
     */
    TestRunHistoryRepository(SqlDialect dialect, Supplier<Connection> connections, SchemaManager schemaManager) {
        super(dialect, connections, schemaManager);
    }

    /**
     * Upsert one run-history row, bootstrapping the schema first. Implements {@link DataStore#persistTestRunHistoryEntry}.
     *
     * @param entry the history row to write
     */
    void persistTestRunHistoryEntry(final TestRunHistoryEntry entry) {
        Connection connection = getConnection();

        try {
            schemaManager.ensureSchema(connection);
            String sql = dialect.upsert(TABLE_TIA_TEST_RUN_HISTORY,
                    Arrays.asList(COL_ID, COL_RUN_TIMESTAMP, COL_BRANCH, COL_COMMIT_VALUE,
                            COL_NUM_SUITES_RAN, COL_NUM_SUITES_IGNORED, COL_NUM_SUITES_FAILED,
                            COL_DURATION_MS, COL_UPDATED_DB_MAPPING, COL_TIME_SAVINGS,
                            COL_SAVINGS_PERCENT, COL_WALL_CLOCK_SAVINGS,
                            COL_WALL_CLOCK_SAVINGS_PERCENT, COL_RUN_ID, COL_WALL_CLOCK_MS,
                            COL_GROUP_COUNT, COL_GROUPS_AVAILABLE, COL_RUN_SOURCE, COL_HOST_NAME,
                            COL_NUM_MODIFIED_TEST_FILES,
                            COL_NUM_NEW_TEST_FILES, COL_NUM_PREVIOUSLY_FAILED, COL_NUM_UNSEALED_MAPPING,
                            COL_NUM_PENDING_LIBRARY, COL_RERUN, COL_SELECTION_MODE),
                    Collections.singletonList(COL_ID));

            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setString(1, entry.getId());
            ps.setLong(2, entry.getRunTimestampMs());
            ps.setString(3, entry.getBranch());
            ps.setString(4, entry.getCommit());
            ps.setInt(5, entry.getNumSuitesRan());
            ps.setInt(6, entry.getNumSuitesIgnored());
            ps.setInt(7, entry.getNumSuitesFailed());
            ps.setLong(8, entry.getDurationMs());
            ps.setBoolean(9, entry.isUpdatedDbMapping());
            ps.setLong(10, entry.getTimeSavingsMs());
            ps.setInt(11, entry.getSavingsPercent());
            ps.setLong(12, entry.getWallClockSavingsMs());
            ps.setInt(13, entry.getWallClockSavingsPercent());
            // A single-host run binds SQL NULL for all four.
            setNullableString(ps, 14, entry.getRunId());
            setNullableLong(ps, 15, entry.getWallClockMs());
            setNullableInt(ps, 16, entry.getGroupCount());
            setNullableInt(ps, 17, entry.getGroupsAvailable());
            // Both nullable for the same reason as the distributed columns above: an unknown origin
            // is stored as SQL NULL rather than as a placeholder several unrelated runs would share.
            setNullableString(ps, 18, entry.getRunOrigin().getRunSource());
            setNullableString(ps, 19, entry.getRunOrigin().getHostName());
            // Nullable for the same reason: an all-tests run with nothing to attribute stores SQL
            // NULL rather than a made-up zero.
            setNullableInt(ps, 20, entry.getNumModifiedTestFiles());
            setNullableInt(ps, 21, entry.getNumNewTestFiles());
            setNullableInt(ps, 22, entry.getNumPreviouslyFailed());
            setNullableInt(ps, 23, entry.getNumUnsealedMapping());
            setNullableInt(ps, 24, entry.getNumPendingLibrary());
            ps.setBoolean(25, entry.isRerun());
            // Nullable like the counters: a row whose mode was not recorded stores SQL NULL.
            setNullableString(ps, 26, entry.getSelectionMode() == null ? null
                    : entry.getSelectionMode().name());
            ps.executeUpdate();
            log.debug("Persisted test run history entry {} ({})", entry.getId(), entry.getRunTimestampMs());
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }
    }

    /**
     * Read every run-history row, most recent first. Implements {@link DataStore#readTestRunHistory}.
     *
     * @return the stored history rows, newest first
     */
    List<TestRunHistoryEntry> readTestRunHistory() {
        List<TestRunHistoryEntry> history = new ArrayList<>();
        Connection connection = getConnection();

        try {
            schemaManager.ensureSchema(connection);

            String sql = "SELECT * FROM " + TABLE_TIA_TEST_RUN_HISTORY
                    + " ORDER BY " + COL_RUN_TIMESTAMP + " DESC";
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql);

            while (resultSet.next()) {
                history.add(new TestRunHistoryEntry(
                        resultSet.getString(COL_ID),
                        resultSet.getLong(COL_RUN_TIMESTAMP),
                        resultSet.getString(COL_BRANCH),
                        resultSet.getString(COL_COMMIT_VALUE),
                        resultSet.getInt(COL_NUM_SUITES_RAN),
                        resultSet.getInt(COL_NUM_SUITES_IGNORED),
                        resultSet.getInt(COL_NUM_SUITES_FAILED),
                        resultSet.getLong(COL_DURATION_MS),
                        resultSet.getBoolean(COL_UPDATED_DB_MAPPING),
                        resultSet.getLong(COL_TIME_SAVINGS),
                        resultSet.getInt(COL_SAVINGS_PERCENT),
                        resultSet.getLong(COL_WALL_CLOCK_SAVINGS),
                        resultSet.getInt(COL_WALL_CLOCK_SAVINGS_PERCENT),
                        resultSet.getString(COL_RUN_ID),
                        getNullableLong(resultSet, COL_WALL_CLOCK_MS),
                        getNullableInt(resultSet, COL_GROUP_COUNT),
                        getNullableInt(resultSet, COL_GROUPS_AVAILABLE),
                        RunOrigin.of(resultSet.getString(COL_RUN_SOURCE),
                                resultSet.getString(COL_HOST_NAME)),
                        getNullableInt(resultSet, COL_NUM_MODIFIED_TEST_FILES),
                        getNullableInt(resultSet, COL_NUM_NEW_TEST_FILES),
                        getNullableInt(resultSet, COL_NUM_PREVIOUSLY_FAILED),
                        getNullableInt(resultSet, COL_NUM_UNSEALED_MAPPING),
                        getNullableInt(resultSet, COL_NUM_PENDING_LIBRARY),
                        resultSet.getBoolean(COL_RERUN),
                        readSelectionMode(resultSet)));
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }

        return history;
    }

    /**
     * Persist one run history row's selection triggers, replacing whatever was previously stored
     * for that row. Delete-then-batch-insert makes the operation idempotent - re-persisting the
     * same list (a retried fork, a re-run of the same seal) leaves exactly one row per trigger
     * rather than accumulating duplicates.
     *
     * @param historyId the {@code tia_test_run_history} row these triggers belong to
     * @param triggers the triggers to store; null or empty leaves the row with no triggers
     */
    void persistTestRunTriggers(final String historyId, final List<TestRunTrigger> triggers) {
        Connection connection = getConnection();
        try {
            schemaManager.ensureSchema(connection);
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + TABLE_TIA_TEST_RUN_HISTORY_TRIGGER
                            + " WHERE " + COL_HISTORY_ID + " = ?")) {
                delete.setString(1, historyId);
                delete.executeUpdate();
            }
            if (triggers != null && !triggers.isEmpty()) {
                String insert = "INSERT INTO " + TABLE_TIA_TEST_RUN_HISTORY_TRIGGER + " ("
                        + COL_HISTORY_ID + ", " + COL_TRIGGER_TYPE + ", " + COL_TRIGGER_NAME + ", "
                        + COL_TEST_COUNT + ") VALUES (?, ?, ?, ?)";
                try (PreparedStatement ps = connection.prepareStatement(insert)) {
                    for (TestRunTrigger t : triggers) {
                        ps.setString(1, historyId);
                        ps.setString(2, t.getType().name());
                        ps.setString(3, t.getName());
                        ps.setInt(4, t.getTestCount());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }
    }

    /**
     * Read the selection triggers for one run history row, highest suite count first.
     *
     * @param historyId the {@code tia_test_run_history} row to read triggers for
     * @return the row's triggers ordered by suite count descending; empty if none were recorded
     */
    List<TestRunTrigger> readTestRunTriggers(final String historyId) {
        return readTestRunTriggersByHistoryId(Collections.singletonList(historyId))
                .getOrDefault(historyId, Collections.emptyList());
    }

    /**
     * Bulk-read the selection triggers for several run history rows in one query, for report
     * generation over a page of history rows rather than one query per row.
     *
     * @param historyIds the {@code tia_test_run_history} row ids to read triggers for
     * @return map of history id to its triggers, each ordered by suite count descending; empty when
     *         {@code historyIds} is null or empty, and a history id with no triggers is simply
     *         absent from the map
     */
    Map<String, List<TestRunTrigger>> readTestRunTriggersByHistoryId(final Collection<String> historyIds) {
        Map<String, List<TestRunTrigger>> byId = new LinkedHashMap<>();
        if (historyIds == null || historyIds.isEmpty()) {
            return byId;
        }
        Connection connection = getConnection();
        try {
            schemaManager.ensureSchema(connection);
            String placeholders = String.join(",", Collections.nCopies(historyIds.size(), "?"));
            String sql = "SELECT " + COL_HISTORY_ID + ", " + COL_TRIGGER_TYPE + ", "
                    + COL_TRIGGER_NAME + ", " + COL_TEST_COUNT + " FROM "
                    + TABLE_TIA_TEST_RUN_HISTORY_TRIGGER + " WHERE " + COL_HISTORY_ID
                    + " IN (" + placeholders + ") ORDER BY " + COL_TEST_COUNT + " DESC";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int i = 1;
                for (String id : historyIds) {
                    ps.setString(i++, id);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byId.computeIfAbsent(rs.getString(COL_HISTORY_ID), k -> new ArrayList<>())
                                .add(new TestRunTrigger(
                                        TestRunTrigger.Type.valueOf(rs.getString(COL_TRIGGER_TYPE)),
                                        rs.getString(COL_TRIGGER_NAME),
                                        null,
                                        rs.getInt(COL_TEST_COUNT)));
                    }
                }
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }
        return byId;
    }
}
