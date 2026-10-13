package org.tiatesting.core.persistence;

import org.tiatesting.core.model.LineRanges;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.persistence.dialect.SqlDialect;

import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

import static org.tiatesting.core.persistence.JdbcRows.*;
import static org.tiatesting.core.persistence.TiaSchema.*;

/**
 * Stages the method trackers and covered method ids each runner of a distributed run collects, so
 * the sealer can write the method catalogue and run stats once, after the barrier. See the
 * distributed test runs and "Method run stats" chapters in {@code WIKI.md}.
 */
class DistributedMethodStagingRepository extends JdbcRepository {

    /**
     * Construct the repository over the datastore's shared JDBC plumbing.
     *
     * @param dialect the SQL dialect rendering vendor-specific statements
     * @param connections opens a connection with the per-branch schema selected
     * @param schemaManager bootstraps and migrates the schema before first use
     */
    DistributedMethodStagingRepository(SqlDialect dialect, Supplier<Connection> connections, SchemaManager schemaManager) {
        super(dialect, connections, schemaManager);
    }

    /**
     * Implements {@link DataStore#persistStagedMethodTrackers}.
     *
     * <p>Written as one upsert batch via {@link SqlDialect#upsert}, keyed on {@code (run_id, id)}:
     * several runners can stage overlapping method ids concurrently (two runners covering the same
     * changed method independently), and a plain {@code INSERT} would fail on the composite
     * primary key the second time the same id is staged. The whole batch commits as one
     * transaction, so a failure partway through leaves the previously-staged rows untouched rather
     * than half of this call's rows landing and half not.
     *
     * <p>Calls {@link SchemaManager#ensureSchema(Connection)} before starting the transaction, since staging can
     * be the first call any caller makes on a freshly created per-branch schema. It must run before
     * {@code setAutoCommit(false)}: H2 implicitly commits on DDL, so issuing it inside the
     * transaction would silently break the atomicity this method depends on.
     *
     * <p>The rollback catches {@link Exception} rather than {@link SQLException} deliberately: an
     * unchecked exception that escaped without rolling back would leave the run's staged rows
     * incomplete, which is exactly the silent under-selection failure the staging table exists to
     * avoid.
     *
     * <p>The batch loop iterates the method ids in ascending order rather than the caller-supplied
     * map's own iteration order. Row locks within the upsert are taken in the order rows are
     * written, and a plain {@code HashMap}'s iteration order depends on its capacity, which depends
     * on how many entries that particular runner happened to observe - so two runners staging the
     * same two ids can genuinely visit them in opposite order. When that happens under Postgres,
     * each runner holds the lock the other wants and waits for the lock it holds to be released,
     * which Postgres detects and resolves by aborting one transaction; under server-mode H2 the same
     * conflict surfaces as a lock-wait timeout instead. Sorting to one global order before the loop
     * means every runner takes the shared rows' locks in the same sequence, so the second runner to
     * reach a contended row simply waits for the first to commit rather than the two deadlocking
     * against each other. This is not an optional tidiness pass - removing the sort reintroduces the
     * deadlock. The covered method ids are upserted in ascending order for the same reason, after
     * the trackers.
     *
     * @param runId the distributed run to stage under
     * @param methodsTracked the trackers this runner observed, keyed by method id; may be empty
     * @param coveredMethodIds the ids of the methods this runner's suites executed; may be empty
     */
    void persistStagedMethodTrackers(final String runId, final Map<Integer, MethodImpactTracker> methodsTracked,
                                            final Set<Integer> coveredMethodIds) {
        List<String> columns = Arrays.asList(COL_RUN_ID, COL_ID, COL_METHOD_NAME,
                COL_LINE_NUMBER_START, COL_LINE_NUMBER_END, COL_LINE_RANGES);
        List<String> keyColumns = Arrays.asList(COL_RUN_ID, COL_ID);
        String sql = dialect.upsert(TABLE_TIA_DISTRIBUTED_RUN_METHOD_STAGE, columns, keyColumns);
        String coveredSql = dialect.upsert(TABLE_TIA_DISTRIBUTED_RUN_COVERED_METHOD, keyColumns, keyColumns);
        // Ascending-id order, not the caller's map order: see the deadlock-avoidance note on this
        // method's javadoc above.
        Map<Integer, MethodImpactTracker> orderedMethodsTracked = new TreeMap<>(methodsTracked);
        Set<Integer> orderedCoveredMethodIds = new TreeSet<>(coveredMethodIds);

        Connection connection = getConnection();
        try {
            // H2 implicitly commits on DDL, so this must run before setAutoCommit(false) - running
            // it inside the transaction would silently break atomicity.
            schemaManager.ensureSchema(connection);
            connection.setAutoCommit(false);
            try {
                if (!orderedMethodsTracked.isEmpty()) {
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        for (Map.Entry<Integer, MethodImpactTracker> entry : orderedMethodsTracked.entrySet()) {
                            MethodImpactTracker tracker = entry.getValue();
                            statement.setString(1, runId);
                            statement.setInt(2, entry.getKey());
                            statement.setString(3, tracker.getMethodName());
                            statement.setInt(4, tracker.getLineNumberStart());
                            statement.setInt(5, tracker.getLineNumberEnd());
                            statement.setString(6, LineRanges.format(tracker.getLineRanges()));
                            statement.addBatch();
                        }
                        statement.executeBatch();
                    }
                }
                if (!orderedCoveredMethodIds.isEmpty()) {
                    try (PreparedStatement statement = connection.prepareStatement(coveredSql)) {
                        for (Integer methodId : orderedCoveredMethodIds) {
                            statement.setString(1, runId);
                            statement.setInt(2, methodId);
                            statement.addBatch();
                        }
                        statement.executeBatch();
                    }
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
     * Implements {@link DataStore#readStagedMethodTrackers}.
     *
     * <p>Calls {@link SchemaManager#ensureSchema(Connection)} before querying, since this can be the first call
     * any caller makes on a freshly created per-branch schema and the staging table is otherwise
     * only created there.
     *
     * @param runId the distributed run to read
     * @return the staged trackers keyed by method id, empty if nothing was staged
     */
    Map<Integer, MethodImpactTracker> readStagedMethodTrackers(final String runId) {
        String sql = "SELECT " + COL_ID + ", " + COL_METHOD_NAME + ", " + COL_LINE_NUMBER_START + ", "
                + COL_LINE_NUMBER_END + ", " + COL_LINE_RANGES + " FROM " + TABLE_TIA_DISTRIBUTED_RUN_METHOD_STAGE
                + " WHERE " + COL_RUN_ID + " = ?";
        Map<Integer, MethodImpactTracker> staged = new HashMap<>();
        try (Connection connection = getConnection()) {
            schemaManager.ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, runId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        MethodImpactTracker tracker = readMethodTracker(resultSet);
                        staged.put(resultSet.getInt(COL_ID), tracker);
                    }
                }
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
        return staged;
    }

    /**
     * Implements {@link DataStore#readStagedCoveredMethodIds}.
     *
     * @param runId the distributed run to read
     * @return the staged covered method ids, empty if nothing was staged
     */
    Set<Integer> readStagedCoveredMethodIds(final String runId) {
        String sql = "SELECT " + COL_ID + " FROM " + TABLE_TIA_DISTRIBUTED_RUN_COVERED_METHOD
                + " WHERE " + COL_RUN_ID + " = ?";
        Set<Integer> covered = new HashSet<>();
        try (Connection connection = getConnection()) {
            schemaManager.ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, runId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        covered.add(resultSet.getInt(COL_ID));
                    }
                }
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
        return covered;
    }

    /**
     * Implements {@link DataStore#deleteStagedMethodTrackers}.
     *
     * <p>Calls {@link SchemaManager#ensureSchema(Connection)} before deleting, since this can be the first call
     * any caller makes on a freshly created per-branch schema.
     *
     * @param runId the distributed run to clear; deleting an unknown run is a no-op
     */
    void deleteStagedMethodTrackers(final String runId) {
        try (Connection connection = getConnection()) {
            schemaManager.ensureSchema(connection);
            for (String table : new String[]{TABLE_TIA_DISTRIBUTED_RUN_METHOD_STAGE,
                    TABLE_TIA_DISTRIBUTED_RUN_COVERED_METHOD}) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE " + COL_RUN_ID + " = ?")) {
                    statement.setString(1, runId);
                    statement.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
    }
}
