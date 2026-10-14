package org.tiatesting.core.persistence;

import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.SelectionMode;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.DistributedRunStatus;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestRunTrigger;
import org.tiatesting.core.persistence.dialect.SqlDialect;

import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

import static org.tiatesting.core.persistence.JdbcRows.*;
import static org.tiatesting.core.persistence.TiaSchema.*;

/**
 * Reads and writes the distributed-run rows themselves: the plan (run, groups and their suites,
 * written in one transaction), the staged selection breakdown the sealer copies onto the build's
 * history row, and the sealer election. See the distributed test runs chapter in {@code WIKI.md}.
 */
class DistributedRunRepository extends JdbcRepository {

    /**
     * Construct the repository over the datastore's shared JDBC plumbing.
     *
     * @param dialect the SQL dialect rendering vendor-specific statements
     * @param connections opens a connection with the per-branch schema selected
     * @param schemaManager bootstraps the schema before first use
     */
    DistributedRunRepository(SqlDialect dialect, Supplier<Connection> connections, SchemaManager schemaManager) {
        super(dialect, connections, schemaManager);
    }

    /**
     * Implements {@link DataStore#persistDistributedRunPlan}.
     *
     * <p>Clears the four plan tables plus the two run-id-keyed selection-breakdown tables before
     * inserting, so the plan tables hold exactly one run and a replanned run never keeps a
     * previous run's staged breakdown. Tia isolates each branch in its own schema, so every row
     * cleared belongs to this branch's previous build - sealed or abandoned - and this is the only
     * retention mechanism the distributed tables have. This method itself does not write a new
     * breakdown - {@link #persistDistributedRunSelectionDetails} does that in a separate call once
     * this one returns.
     *
     * <p>All of it runs under one transaction. The rollback catches {@link Exception} rather than
     * {@link SQLException} deliberately: an unchecked exception that escaped without rolling back
     * would leave the tables cleared but the new plan unwritten, so every runner would find
     * nothing to claim and exit successfully - a green build that ran no tests.
     *
     * <p>Calls {@link SchemaManager#ensureSchema(Connection)} before starting the transaction, since this can
     * be the first call any caller makes on a freshly created per-branch schema. It must run
     * before {@code setAutoCommit(false)}: H2 implicitly commits on DDL, so issuing it inside the
     * transaction would silently break the atomicity this method depends on.
     *
     * @param plan the validated plan to persist
     */
    void persistDistributedRunPlan(final DistributedRunPlan plan) {
        String runSql = "INSERT INTO " + TABLE_TIA_DISTRIBUTED_RUN + " ("
                + COL_RUN_ID + ", " + COL_BRANCH + ", " + COL_COMMIT_VALUE + ", " + COL_STATUS + ", "
                + COL_GROUP_COUNT + ", " + COL_TARGET_RUN_TIME_MS + ", " + COL_ESTIMATED_TOTAL_MS + ", "
                + COL_CREATED_AT + ", " + COL_SEALED_BY + ", " + COL_SEALED_AT + ", " + COL_DRAIN_RESULT
                + ", " + COL_SELECTION_MODE + ", " + COL_GROUPS_AVAILABLE + ", " + COL_RUN_SOURCE
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String groupSql = "INSERT INTO " + TABLE_TIA_DISTRIBUTED_RUN_GROUP + " ("
                + COL_RUN_ID + ", " + COL_GROUP_NUMBER + ", " + COL_STATUS + ", " + COL_RUNNER_KEY + ", "
                + COL_CLAIMED_AT + ", " + COL_COMPLETED_AT + ", " + COL_ESTIMATED_MS + ", "
                + COL_ACTUAL_DURATION_MS + ", " + COL_SUITES_RAN + ", " + COL_SUITES_FAILED + ", "
                + COL_SUITES_OBSERVED + ", " + COL_SUITES_DURATION_MS
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String suiteSql = "INSERT INTO " + TABLE_TIA_DISTRIBUTED_RUN_GROUP_SUITE + " ("
                + COL_RUN_ID + ", " + COL_GROUP_NUMBER + ", " + COL_TEST_SUITE_NAME + ") VALUES (?, ?, ?)";

        DistributedRun run = plan.getRun();
        Connection connection = getConnection();
        try {
            // H2 implicitly commits on DDL, so this must run before setAutoCommit(false) - running
            // it inside the transaction would silently break atomicity.
            schemaManager.ensureSchema(connection);
            connection.setAutoCommit(false);
            try {
                String[] tablesToClear = {
                        TABLE_TIA_DISTRIBUTED_RUN_GROUP_SUITE,
                        TABLE_TIA_DISTRIBUTED_RUN_METHOD_STAGE,
                        TABLE_TIA_DISTRIBUTED_RUN_COVERED_METHOD,
                        TABLE_TIA_DISTRIBUTED_RUN_GROUP,
                        TABLE_TIA_DISTRIBUTED_RUN,
                        TABLE_TIA_DISTRIBUTED_RUN_SELECTION,
                        TABLE_TIA_DISTRIBUTED_RUN_TRIGGER
                };
                try (Statement clearStatement = connection.createStatement()) {
                    for (String table : tablesToClear) {
                        clearStatement.executeUpdate(dialect.clearTableTransactionallySql(table));
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(runSql)) {
                    statement.setString(1, run.getRunId());
                    statement.setString(2, run.getBranch());
                    statement.setString(3, run.getCommitValue());
                    statement.setString(4, run.getStatus().name());
                    statement.setInt(5, run.getGroupCount());
                    setNullableLong(statement, 6, run.getTargetRunTimeMs());
                    statement.setLong(7, run.getEstimatedTotalMs());
                    statement.setLong(8, run.getCreatedAtMs());
                    statement.setString(9, run.getSealedBy());
                    setNullableLong(statement, 10, run.getSealedAtMs());
                    setDrainResult(statement, 11, plan.getDrainResult());
                    statement.setString(12, run.getSelectionMode().name());
                    statement.setInt(13, run.getGroupsAvailable());
                    setNullableString(statement, 14, run.getRunSource());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(groupSql)) {
                    for (DistributedRunGroup group : plan.getGroups()) {
                        statement.setString(1, group.getRunId());
                        statement.setInt(2, group.getGroupNumber());
                        statement.setString(3, group.getStatus().name());
                        statement.setString(4, group.getRunnerKey());
                        setNullableLong(statement, 5, group.getClaimedAtMs());
                        setNullableLong(statement, 6, group.getCompletedAtMs());
                        statement.setLong(7, group.getEstimatedMs());
                        setNullableLong(statement, 8, group.getActualDurationMs());
                        statement.setInt(9, group.getSuitesRan());
                        statement.setInt(10, group.getSuitesFailed());
                        statement.setInt(11, group.getSuitesObserved());
                        statement.setLong(12, group.getSuitesDurationMs());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                try (PreparedStatement statement = connection.prepareStatement(suiteSql)) {
                    for (Map.Entry<Integer, List<String>> entry : plan.getSuitesByGroup().entrySet()) {
                        for (String suiteName : entry.getValue()) {
                            statement.setString(1, run.getRunId());
                            statement.setInt(2, entry.getKey());
                            statement.setString(3, suiteName);
                            statement.addBatch();
                        }
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
        } catch (Exception e) {
            throw new TiaPersistenceException(e);
        } finally {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                throw new TiaPersistenceException(closeFailure);
            }
        }
    }

    /**
     * Build a {@link DistributedRun} from the current row of a {@code SELECT *} against
     * {@code tia_distributed_run}. Shared by {@link #readDistributedRun} and
     * {@link #readAllDistributedRuns} so the two cannot drift apart on how a run row maps.
     *
     * <p>Deliberately does not decode the {@code drain_result} blob. Every runner in the build
     * reads a run row to claim its group, and only the sealer consumes the drain result, so
     * decoding it here would make a blob the runners never look at - one written by a planner on a
     * different Tia version, say - fail every claim in the build. {@link
     * #readDistributedRunDrainResult} decodes it for the one caller that wants it.
     *
     * @param resultSet the result set, positioned on a run row
     * @return the run the row describes
     * @throws SQLException if any column cannot be read
     */
    private static DistributedRun mapRunRow(ResultSet resultSet) throws SQLException {
        return new DistributedRun(
                resultSet.getString(COL_RUN_ID),
                resultSet.getString(COL_BRANCH),
                resultSet.getString(COL_COMMIT_VALUE),
                DistributedRunStatus.valueOf(resultSet.getString(COL_STATUS)),
                resultSet.getInt(COL_GROUP_COUNT),
                resultSet.getInt(COL_GROUPS_AVAILABLE),
                getNullableLong(resultSet, COL_TARGET_RUN_TIME_MS),
                resultSet.getLong(COL_ESTIMATED_TOTAL_MS),
                resultSet.getLong(COL_CREATED_AT),
                resultSet.getString(COL_SEALED_BY),
                getNullableLong(resultSet, COL_SEALED_AT),
                SelectionMode.fromStoredName(resultSet.getString(COL_SELECTION_MODE)),
                resultSet.getString(COL_RUN_SOURCE));
    }

    /**
     * Implements {@link DataStore#readDistributedRunDrainResult}.
     *
     * <p>Calls {@link SchemaManager#ensureSchema(Connection)} before querying, since this can be the first
     * call any caller makes on a freshly created per-branch schema and the distributed-run
     * tables are otherwise only created there.
     *
     * @param runId the run identifier
     * @return the run, or null if no run is planned under that id
     */
    /**
     * {@inheritDoc}
     *
     * <p>Reads and decodes the {@code drain_result} blob on its own rather than as part of
     * {@link #readDistributedRun}, so that the Java deserialization it performs is paid, and can
     * fail, only for the sealer that actually applies the cleanup - never for the runners that
     * merely read the run row to claim a group.
     *
     * @param runId the run identifier
     * @return the drain result stored with the plan, or null if the run drained nothing or no run
     *         is planned under that id
     */
    LibraryImpactDrainResult readDistributedRunDrainResult(final String runId) {
        String sql = "SELECT " + COL_DRAIN_RESULT + " FROM " + TABLE_TIA_DISTRIBUTED_RUN
                + " WHERE " + COL_RUN_ID + " = ?";
        try (Connection connection = getConnection()) {
            schemaManager.ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, runId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return null;
                    }
                    return getDrainResult(resultSet);
                }
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
    }

    /**
     * Read one distributed run row, without decoding its drain result. Implements
     * {@link DataStore#readDistributedRun}.
     *
     * @param runId the run to read
     * @return the run, or null if no run has that id
     */
    DistributedRun readDistributedRun(final String runId) {
        String sql = "SELECT * FROM " + TABLE_TIA_DISTRIBUTED_RUN + " WHERE " + COL_RUN_ID + " = ?";
        try (Connection connection = getConnection()) {
            schemaManager.ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, runId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return null;
                    }
                    return mapRunRow(resultSet);
                }
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
    }

    /**
     * Implements {@link DataStore#readAllDistributedRuns}.
     *
     * <p>Calls {@link SchemaManager#ensureSchema(Connection)} before querying, since this can be the first
     * call any caller makes on a freshly created per-branch schema and the distributed-run
     * tables are otherwise only created there.
     *
     * @return the runs currently planned, most recently created first, empty if there are none
     */
    List<DistributedRun> readAllDistributedRuns() {
        String sql = "SELECT * FROM " + TABLE_TIA_DISTRIBUTED_RUN + " ORDER BY " + COL_CREATED_AT + " DESC";
        List<DistributedRun> runs = new ArrayList<>();
        try (Connection connection = getConnection()) {
            schemaManager.ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    runs.add(mapRunRow(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
        return runs;
    }

    /**
     * Implements {@link DataStore#persistDistributedRunSelectionDetails}.
     *
     * <p>Idempotent per {@code runId}: deletes whatever was previously staged in both the
     * counters table and the trigger table, then writes {@code details} (or {@link
     * TestRunSelectionDetails#empty()} if {@code details} is null) - a counters row is always
     * written, so a read always finds a definite row rather than having to distinguish "not
     * staged" from "staged as empty". Mirrors {@link TestRunHistoryRepository#persistTestRunTriggers}'s
     * delete-then-batch-insert shape, keyed by run id instead of history id.
     *
     * <p>Calls {@link SchemaManager#ensureSchema(Connection)} first, since this can be the first call any
     * caller makes on a freshly created per-branch schema.
     *
     * @param runId the distributed run these triggers belong to
     * @param details the breakdown to stage; null is treated as {@link TestRunSelectionDetails#empty()}
     */
    void persistDistributedRunSelectionDetails(final String runId, final TestRunSelectionDetails details) {
        TestRunSelectionDetails toPersist = details == null ? TestRunSelectionDetails.empty() : details;
        Connection connection = getConnection();
        try {
            schemaManager.ensureSchema(connection);
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + TABLE_TIA_DISTRIBUTED_RUN_SELECTION + " WHERE " + COL_RUN_ID + " = ?")) {
                delete.setString(1, runId);
                delete.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + TABLE_TIA_DISTRIBUTED_RUN_TRIGGER + " WHERE " + COL_RUN_ID + " = ?")) {
                delete.setString(1, runId);
                delete.executeUpdate();
            }
            String insertCounters = "INSERT INTO " + TABLE_TIA_DISTRIBUTED_RUN_SELECTION + " ("
                    + COL_RUN_ID + ", " + COL_NUM_MODIFIED_TEST_FILES + ", " + COL_NUM_NEW_TEST_FILES + ", "
                    + COL_NUM_PREVIOUSLY_FAILED + ", " + COL_NUM_UNSEALED_MAPPING + ", "
                    + COL_NUM_PENDING_LIBRARY + ") VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(insertCounters)) {
                ps.setString(1, runId);
                ps.setInt(2, toPersist.getNumModifiedTestFiles());
                ps.setInt(3, toPersist.getNumNewTestFiles());
                ps.setInt(4, toPersist.getNumPreviouslyFailed());
                ps.setInt(5, toPersist.getNumUnsealedMapping());
                ps.setInt(6, toPersist.getNumPendingLibrary());
                ps.executeUpdate();
            }
            List<TestRunTrigger> triggers = toPersist.getTriggers();
            if (!triggers.isEmpty()) {
                String insertTrigger = "INSERT INTO " + TABLE_TIA_DISTRIBUTED_RUN_TRIGGER + " ("
                        + COL_RUN_ID + ", " + COL_TRIGGER_TYPE + ", " + COL_TRIGGER_NAME + ", "
                        + COL_TRIGGER_METHOD_ID + ", " + COL_TEST_COUNT + ") VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement ps = connection.prepareStatement(insertTrigger)) {
                    for (TestRunTrigger t : triggers) {
                        ps.setString(1, runId);
                        ps.setString(2, t.getType().name());
                        ps.setString(3, t.getName());
                        setNullableInt(ps, 4, t.getMethodId());
                        ps.setInt(5, t.getTestCount());
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
     * Implements {@link DataStore#readDistributedRunSelectionDetails}.
     *
     * <p>Calls {@link SchemaManager#ensureSchema(Connection)} first, since this can be the first call any
     * caller makes on a freshly created per-branch schema.
     *
     * @param runId the distributed run to read the staged breakdown for
     * @return the staged breakdown, or {@link TestRunSelectionDetails#empty()} if nothing was
     *         staged for that run id
     */
    TestRunSelectionDetails readDistributedRunSelectionDetails(final String runId) {
        Connection connection = getConnection();
        try {
            schemaManager.ensureSchema(connection);
            boolean countersFound = false;
            int numModifiedTestFiles = 0;
            int numNewTestFiles = 0;
            int numPreviouslyFailed = 0;
            int numUnsealedMapping = 0;
            int numPendingLibrary = 0;
            String countersSql = "SELECT " + COL_NUM_MODIFIED_TEST_FILES + ", " + COL_NUM_NEW_TEST_FILES
                    + ", " + COL_NUM_PREVIOUSLY_FAILED + ", " + COL_NUM_UNSEALED_MAPPING + ", "
                    + COL_NUM_PENDING_LIBRARY + " FROM " + TABLE_TIA_DISTRIBUTED_RUN_SELECTION
                    + " WHERE " + COL_RUN_ID + " = ?";
            try (PreparedStatement ps = connection.prepareStatement(countersSql)) {
                ps.setString(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        countersFound = true;
                        numModifiedTestFiles = rs.getInt(COL_NUM_MODIFIED_TEST_FILES);
                        numNewTestFiles = rs.getInt(COL_NUM_NEW_TEST_FILES);
                        numPreviouslyFailed = rs.getInt(COL_NUM_PREVIOUSLY_FAILED);
                        numUnsealedMapping = rs.getInt(COL_NUM_UNSEALED_MAPPING);
                        numPendingLibrary = rs.getInt(COL_NUM_PENDING_LIBRARY);
                    }
                }
            }

            List<TestRunTrigger> triggers = new ArrayList<>();
            String triggerSql = "SELECT " + COL_TRIGGER_TYPE + ", " + COL_TRIGGER_NAME + ", "
                    + COL_TRIGGER_METHOD_ID + ", " + COL_TEST_COUNT + " FROM "
                    + TABLE_TIA_DISTRIBUTED_RUN_TRIGGER + " WHERE "
                    + COL_RUN_ID + " = ? ORDER BY " + COL_TEST_COUNT + " DESC";
            try (PreparedStatement ps = connection.prepareStatement(triggerSql)) {
                ps.setString(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        triggers.add(new TestRunTrigger(
                                TestRunTrigger.Type.valueOf(rs.getString(COL_TRIGGER_TYPE)),
                                rs.getString(COL_TRIGGER_NAME),
                                getNullableInt(rs, COL_TRIGGER_METHOD_ID),
                                rs.getInt(COL_TEST_COUNT)));
                    }
                }
            }

            if (!countersFound && triggers.isEmpty()) {
                return TestRunSelectionDetails.empty();
            }
            return new TestRunSelectionDetails(triggers, numModifiedTestFiles, numNewTestFiles,
                    numPreviouslyFailed, numUnsealedMapping, numPendingLibrary,
                    SelectionMode.SELECTIVE);
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
     * Implements {@link DataStore#electSealer}.
     *
     * <p>One conditional single-row {@code UPDATE}, and its row count is the entire answer. The
     * {@code sealed_by IS NULL} predicate makes at most one runner win; the {@code NOT EXISTS
     * (SELECT 1 FROM tia_distributed_run_group WHERE run_id = ? AND status <> 'COMPLETED')}
     * predicate makes that runner the last one, which is what guarantees the {@code SELECT
     * DISTINCT} the sealer runs over the edge table sees every group's edges. The database
     * evaluates both against the latest committed row, so racing runners serialise on this
     * statement and exactly one sees row count 1.
     *
     * <p>Nothing is read before or instead of the update, deliberately. Row count 0 covers both
     * "another runner won" and "my run row no longer exists because a newer build superseded it",
     * and the row count is the only signal that separates the two from winning. Falling back to a
     * read would let a straggler sealer conclude it should proceed, rebuild the catalogue from an
     * empty staging table and drop every method id the on-disk catalogue lacks. See the sealing
     * material in the distributed test runs chapter of {@code WIKI.md}.
     *
     * <p>Calls {@link SchemaManager#ensureSchema(Connection)} first, since electing a sealer could be the first
     * contact any caller in this process makes with a freshly created per-branch schema.
     *
     * @param runId the distributed run to elect a sealer for
     * @param runnerKey the calling runner's stable identity
     * @param sealedAtMs UTC epoch millis to record as the election time
     * @return {@code true} when this runner won the election, {@code false} when it must do nothing
     */
    boolean electSealer(final String runId, final String runnerKey, final long sealedAtMs) {
        String electSql = "UPDATE " + TABLE_TIA_DISTRIBUTED_RUN + " SET " + COL_SEALED_BY + " = ?, "
                + COL_SEALED_AT + " = ? WHERE " + COL_RUN_ID + " = ? AND " + COL_SEALED_BY + " IS NULL"
                + " AND NOT EXISTS (SELECT 1 FROM " + TABLE_TIA_DISTRIBUTED_RUN_GROUP
                + " WHERE " + COL_RUN_ID + " = ? AND " + COL_STATUS + " <> ?)";

        try (Connection connection = getConnection()) {
            schemaManager.ensureSchema(connection);

            try (PreparedStatement statement = connection.prepareStatement(electSql)) {
                statement.setString(1, runnerKey);
                statement.setLong(2, sealedAtMs);
                statement.setString(3, runId);
                statement.setString(4, runId);
                statement.setString(5, DistributedRunGroupStatus.COMPLETED.name());
                return statement.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
    }

    /**
     * Implements {@link DataStore#markDistributedRunSealed}.
     *
     * <p>Unguarded on purpose, unlike {@link DistributedRunGroupRepository#completeGroup} and
     * {@link #electSealer}: only the runner that already won {@link #electSealer} reaches this
     * call, so the mutual exclusion has already happened and a second predicate here would guard
     * nothing. An unknown run id simply updates no rows.
     *
     * @param runId the distributed run to mark sealed
     */
    void markDistributedRunSealed(final String runId) {
        String sql = "UPDATE " + TABLE_TIA_DISTRIBUTED_RUN + " SET " + COL_STATUS + " = ? WHERE "
                + COL_RUN_ID + " = ?";
        try (Connection connection = getConnection()) {
            schemaManager.ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, DistributedRunStatus.SEALED.name());
                statement.setString(2, runId);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
    }
}
