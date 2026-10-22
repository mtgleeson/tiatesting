package org.tiatesting.core.persistence;

import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.SelectionMode;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.persistence.dialect.SqlDialect;

import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

import static org.tiatesting.core.persistence.JdbcRows.*;
import static org.tiatesting.core.persistence.TiaSchema.*;

/**
 * Reads and advances the groups of a distributed run: the runners' claim, progress and completion
 * writes, and the group and group-suite reads. See the distributed test runs chapter in
 * {@code WIKI.md}.
 */
class DistributedRunGroupRepository extends JdbcRepository {

    /**
     * Construct the repository over the datastore's shared JDBC plumbing.
     *
     * @param dialect the SQL dialect rendering vendor-specific statements
     * @param connections opens a connection with the per-branch schema selected
     * @param schemaManager bootstraps the schema before first use
     */
    DistributedRunGroupRepository(SqlDialect dialect, Supplier<Connection> connections, SchemaManager schemaManager) {
        super(dialect, connections, schemaManager);
    }

    /**
     * Implements {@link DataStore#readDistributedRunGroups}.
     *
     * <p>Calls {@link SchemaManager#ensureSchema(Connection)} before querying, since this can be the first
     * call any caller makes on a freshly created per-branch schema and the distributed-run
     * tables are otherwise only created there.
     *
     * @param runId the run identifier
     * @return the run's groups in group-number order, empty if the run is unknown
     */
    List<DistributedRunGroup> readDistributedRunGroups(final String runId) {
        String sql = "SELECT * FROM " + TABLE_TIA_DISTRIBUTED_RUN_GROUP + " WHERE " + COL_RUN_ID
                + " = ? ORDER BY " + COL_GROUP_NUMBER;
        List<DistributedRunGroup> groups = new ArrayList<>();
        try (Connection connection = getConnection()) {
            schemaManager.ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, runId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        groups.add(mapGroupRow(resultSet));
                    }
                }
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
        return groups;
    }

    /**
     * Implements {@link DataStore#readDistributedRunGroupSuites}.
     *
     * <p>Calls {@link SchemaManager#ensureSchema(Connection)} before querying, since this can be the first
     * call any caller makes on a freshly created per-branch schema and the distributed-run
     * tables are otherwise only created there.
     *
     * @param runId the run identifier
     * @param groupNumber the group's zero-based index within the run
     * @return the group's suite names in name order, empty if the group is unknown
     */
    List<String> readDistributedRunGroupSuites(final String runId, final int groupNumber) {
        String sql = "SELECT " + COL_TEST_SUITE_NAME + " FROM " + TABLE_TIA_DISTRIBUTED_RUN_GROUP_SUITE
                + " WHERE " + COL_RUN_ID + " = ? AND " + COL_GROUP_NUMBER + " = ? ORDER BY "
                + COL_TEST_SUITE_NAME;
        List<String> suiteNames = new ArrayList<>();
        try (Connection connection = getConnection()) {
            schemaManager.ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, runId);
                statement.setInt(2, groupNumber);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        suiteNames.add(resultSet.getString(COL_TEST_SUITE_NAME));
                    }
                }
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
        return suiteNames;
    }

    /**
     * Implements {@link DataStore#readUnobservedGroupSuites}.
     *
     * @param runId the run identifier
     * @param groupNumber the group's zero-based index within the run
     * @return the group's suite names not yet flagged {@code observed}, in name order; empty if the
     *         group is unknown or every suite was observed
     */
    List<String> readUnobservedGroupSuites(final String runId, final int groupNumber) {
        return readGroupSuitesWithFlag(runId, groupNumber, COL_OBSERVED, false);
    }

    /**
     * Implements {@link DataStore#readUnrunnableGroupSuites}.
     *
     * @param runId the run identifier
     * @param groupNumber the group's zero-based index within the run
     * @return the group's suite names flagged {@code unrunnable}, in name order; empty if there are
     *         none
     */
    List<String> readUnrunnableGroupSuites(final String runId, final int groupNumber) {
        return readGroupSuitesWithFlag(runId, groupNumber, COL_UNRUNNABLE, true);
    }

    /**
     * Read a group's suite names whose given flag column holds the given value, in name order.
     * Calls {@link SchemaManager#ensureSchema(Connection)} before querying, like every other read
     * here.
     *
     * @param runId the run identifier
     * @param groupNumber the group's zero-based index within the run
     * @param flagColumn the flag column to filter on
     * @param flagValue the value the flag must hold
     * @return the matching suite names in name order; empty if none match
     */
    private List<String> readGroupSuitesWithFlag(final String runId, final int groupNumber,
                                                 final String flagColumn, final boolean flagValue) {
        String sql = "SELECT " + COL_TEST_SUITE_NAME + " FROM " + TABLE_TIA_DISTRIBUTED_RUN_GROUP_SUITE
                + " WHERE " + COL_RUN_ID + " = ? AND " + COL_GROUP_NUMBER + " = ? AND " + flagColumn
                + " = ? ORDER BY " + COL_TEST_SUITE_NAME;
        List<String> suiteNames = new ArrayList<>();
        try (Connection connection = getConnection()) {
            schemaManager.ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, runId);
                statement.setInt(2, groupNumber);
                statement.setBoolean(3, flagValue);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        suiteNames.add(resultSet.getString(COL_TEST_SUITE_NAME));
                    }
                }
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
        return suiteNames;
    }

    /**
     * Implements {@link DataStore#claimNextPendingGroup}.
     *
     * <p>Calls {@link SchemaManager#ensureSchema(Connection)} before the first read, since claiming can be the
     * first call any caller makes on a freshly created per-branch schema.
     *
     * <p>Deliberately does not wrap the reads and the compare-and-swap update in one transaction:
     * the safety property does not come from transaction isolation, it comes from the {@code status
     * = 'PENDING'} predicate on the single-row update in step 2 below, which the database always
     * evaluates against the latest committed row regardless of what transaction issued it. Wrapping
     * the loop in a transaction would only add lock contention between racing runners without
     * changing the outcome.
     *
     * <p>Follows the exact three-step protocol from the "Distributed test runs" chapter in {@code
     * WIKI.md}: (0) return the group this runner key already holds, if it is still {@code CLAIMED};
     * (1) read the lowest-numbered {@code PENDING} group number; (2) attempt a single-row update
     * guarded by
     * {@code status = 'PENDING'} - rowcount 1 means this runner won it, rowcount 0 means another
     * runner won it first and step 1 is retried. The retry loop is bounded by the run's total group
     * count: every failed attempt permanently removes one group from the candidate pool for this
     * runner (a group that lost {@code PENDING} status never regains it), so no single runner can
     * ever need more attempts than there are groups. A bound violation therefore means the group
     * table is not converging as expected rather than ordinary contention, and is reported as a
     * {@link TiaPersistenceException} rather than spinning forever.
     *
     * <p>Step 0 hands a group back only while it is still {@code CLAIMED}, and that is both halves
     * of one rule. A runner key holding a {@code CLAIMED} group is the CI job retry the step exists
     * for. A runner key holding a group in any other status has already finished with it: returning
     * it would have the runner re-run its suites and then fail {@link #completeGroup}'s {@code
     * status = 'CLAIMED'} predicate, leaving the run unable to seal, so this returns {@code null}
     * instead - and {@code null} rather than a fresh {@code PENDING} group, because taking a second
     * group would leave this runner key on two group rows, and step 0's own {@code SELECT} is
     * unfiltered, unordered and unlimited: it takes the first row {@code ResultSet.next()} offers as
     * the answer, so a runner key on two rows makes every later step-0 read for it non-deterministic.
     * One runner key holds at most one group, and that is what keeps this step answerable.
     *
     * @param runId the distributed run to claim a group from
     * @param runnerKey the calling runner's stable identity
     * @param claimedAtMs UTC epoch millis to record as the claim time
     * @return the claimed group, or {@code null} when the run has no group left to claim or this
     *         runner key already holds a group it has finished with
     */
    DistributedRunGroup claimNextPendingGroup(final String runId, final String runnerKey,
                                                      final long claimedAtMs) {
        String claimedByRunnerSql = "SELECT * FROM " + TABLE_TIA_DISTRIBUTED_RUN_GROUP
                + " WHERE " + COL_RUN_ID + " = ? AND " + COL_RUNNER_KEY + " = ?";
        String countGroupsSql = "SELECT COUNT(*) FROM " + TABLE_TIA_DISTRIBUTED_RUN_GROUP
                + " WHERE " + COL_RUN_ID + " = ?";
        String minPendingSql = "SELECT MIN(" + COL_GROUP_NUMBER + ") FROM " + TABLE_TIA_DISTRIBUTED_RUN_GROUP
                + " WHERE " + COL_RUN_ID + " = ? AND " + COL_STATUS + " = ?";
        String claimSql = "UPDATE " + TABLE_TIA_DISTRIBUTED_RUN_GROUP + " SET " + COL_STATUS + " = ?, "
                + COL_RUNNER_KEY + " = ?, " + COL_CLAIMED_AT + " = ? WHERE " + COL_RUN_ID + " = ? AND "
                + COL_GROUP_NUMBER + " = ? AND " + COL_STATUS + " = ?";
        String groupByNumberSql = "SELECT * FROM " + TABLE_TIA_DISTRIBUTED_RUN_GROUP
                + " WHERE " + COL_RUN_ID + " = ? AND " + COL_GROUP_NUMBER + " = ?";

        try (Connection connection = getConnection()) {
            schemaManager.ensureSchema(connection);

            // Step 0: a runner key that already holds a CLAIMED group in this run is a CI job retry
            // re-claiming its own group - return it unchanged rather than attempting a new claim.
            // Any other status means this runner already finished with that group, and it gets
            // nothing: handing the group back would re-run its suites and then fail completeGroup's
            // status = 'CLAIMED' predicate, so the run could never seal, while falling through to a
            // fresh PENDING group would leave this runner key holding two group rows - and this
            // SELECT is unfiltered, unordered and unlimited, taking whatever resultSet.next() hands
            // back as the answer, so from that point on every step-0 read for this runner is
            // non-deterministic. Returning null keeps one runner key to at most one group.
            // The status is read here rather than filtered in the SELECT precisely so the two cases
            // can be told apart - a filtered query answering "no row" cannot distinguish a runner
            // that holds a finished group from one that holds none.
            try (PreparedStatement statement = connection.prepareStatement(claimedByRunnerSql)) {
                statement.setString(1, runId);
                statement.setString(2, runnerKey);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        DistributedRunGroup heldGroup = mapGroupRow(resultSet);
                        return heldGroup.getStatus() == DistributedRunGroupStatus.CLAIMED
                                ? heldGroup : null;
                    }
                }
            }

            int groupCount;
            try (PreparedStatement statement = connection.prepareStatement(countGroupsSql)) {
                statement.setString(1, runId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    groupCount = resultSet.getInt(1);
                }
            }
            if (groupCount == 0) {
                // Either the run id is unknown, or (should never happen) it was planned with no
                // groups at all - either way there is nothing to claim.
                return null;
            }

            for (int attempt = 0; attempt < groupCount; attempt++) {
                // Step 1: the lowest-numbered PENDING group is always the next candidate, so every
                // runner converges on the same claim order rather than fanning out unpredictably.
                Integer candidateGroupNumber;
                try (PreparedStatement statement = connection.prepareStatement(minPendingSql)) {
                    statement.setString(1, runId);
                    statement.setString(2, DistributedRunGroupStatus.PENDING.name());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        resultSet.next();
                        int value = resultSet.getInt(1);
                        candidateGroupNumber = resultSet.wasNull() ? null : value;
                    }
                }
                if (candidateGroupNumber == null) {
                    // Every group has already been claimed by some runner.
                    return null;
                }

                // Step 2: the compare-and-swap. The status = 'PENDING' predicate on this single-row
                // update is the entire safety mechanism - two runners racing for the same candidate
                // both attempt this update, the database serialises them, and only one sees rowcount
                // 1. The loser (rowcount 0) falls through to retry step 1.
                int rowsUpdated;
                try (PreparedStatement statement = connection.prepareStatement(claimSql)) {
                    statement.setString(1, DistributedRunGroupStatus.CLAIMED.name());
                    statement.setString(2, runnerKey);
                    statement.setLong(3, claimedAtMs);
                    statement.setString(4, runId);
                    statement.setInt(5, candidateGroupNumber);
                    statement.setString(6, DistributedRunGroupStatus.PENDING.name());
                    rowsUpdated = statement.executeUpdate();
                }

                if (rowsUpdated == 1) {
                    try (PreparedStatement statement = connection.prepareStatement(groupByNumberSql)) {
                        statement.setString(1, runId);
                        statement.setInt(2, candidateGroupNumber);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            resultSet.next();
                            return mapGroupRow(resultSet);
                        }
                    }
                }
                // rowsUpdated == 0: another runner won the candidate group between this attempt's
                // step 1 read and its step 2 update - loop back and re-read the current minimum.
            }

            throw new TiaPersistenceException("claimNextPendingGroup for run '" + runId
                    + "' did not converge within " + groupCount
                    + " attempt(s), its natural bound (the run's total group count). Every failed "
                    + "attempt should permanently remove one group from the candidate pool, so this "
                    + "means the group table is not converging as expected rather than ordinary "
                    + "claim contention.");
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
    }

    /**
     * Implements {@link DataStore#reportGroupProgress}.
     *
     * <p>One transaction of three steps. First a guarded single-row {@code UPDATE ... WHERE run_id
     * = ? AND group_number = ? AND status = 'CLAIMED' AND runner_key = ?}, the same
     * straggler-protection predicate {@link #completeGroup} is guarded on; when it matches no row
     * the transaction is rolled back and nothing at all is written - in particular no suite is
     * flagged observed for a runner whose claim is dead. Then the {@code observed} flag is set on
     * the named suites' {@code tia_distributed_run_group_suite} rows, and finally {@code
     * suites_observed} is rewritten as the count of flagged rows. Flags only go from false to true,
     * so the count is the union of every report against the group - several JVMs included - and
     * can never regress. {@code actual_duration_ms} is written as {@code
     * COALESCE(column, 0) + ?} so several calls in the same JVM (one per Surefire retry) sum
     * instead of the last one overwriting the ones before it - retry time is real time. The {@code
     * COALESCE} is load-bearing: the plan write binds it with {@link JdbcRows#setNullableLong} as SQL
     * {@code NULL}, so the first report of a JVM adds onto a real {@code NULL} column. {@code
     * suites_failed} is written as a plain {@code = ?}, since it is current state rather than a
     * counter and a passing retry must be able to shrink it back to zero. {@code suites_ran} is
     * written as {@code GREATEST(COALESCE(column, 0), ?)}: it counts the <em>distinct</em> suites
     * the runner executed, which the caller draws from the JVM's shared tracker map - already
     * cumulative across every test plan - so a retry re-reports the same suites rather than adding
     * new ones, and a Gradle test-retry round, a fresh JVM that ran only the retried suites,
     * reports fewer. Summing would count a retried suite once per attempt, where the build's one
     * history row counts its failed suites once. {@code GREATEST} rather than a plain {@code = ?}
     * replace is what keeps a same-JVM retry's report from ever regressing the stored value below an
     * earlier, more-complete one; see {@link DataStore#reportGroupProgress} for the precondition this
     * still assumes (one JVM per group) and what several JVMs reporting against the same group do to
     * it. {@code suites_duration_ms} is written the same way and for the same reason: it is summed from the JVM's shared suite-tracker map, which already carries every
     * suite timed by every test plan this JVM has made, so a retry re-reports the same total rather
     * than a fresh increment - accumulating it would double-count the first attempt's suites while
     * {@code actual_duration_ms} legitimately grows by the retry's own window. That asymmetry is
     * deliberate: it is what leaves a retry's cost outside the suite-attributable total and inside
     * the group's overhead remainder.
     *
     * <p>Calls {@link SchemaManager#ensureSchema(Connection)} first, since reporting progress could be the
     * first contact any caller in this process makes with a freshly created per-branch schema.
     *
     * @param runId the distributed run the group belongs to
     * @param groupNumber the group's zero-based index within the run
     * @param runnerKey the calling runner's stable identity
     * @param actualDurationMs this call's measured test-execution time, in ms, added to whatever
     *                         is already stored
     * @param suitesRan the number of distinct suites the runner has executed so far, written as
     *                  the greater of this value and whatever was already stored
     * @param suitesFailed the number of suites currently failing, replacing whatever was stored
     * @param suitesObserved the names of this group's assigned suites the runner has observed so
     *                       far (finished or skipped), flagged {@code observed}; names not assigned
     *                       to the group match no row
     * @param suitesDurationMs the summed measured run time of every suite this runner has timed so
     *                         far, written as the greater of this value and whatever was already
     *                         stored - not summed, since the caller's tracker map is already
     *                         cumulative across retries
     * @return {@code true} when the guarded update applied, {@code false} when this runner's
     *         claim is no longer live
     */
    boolean reportGroupProgress(final String runId, final int groupNumber, final String runnerKey,
                                       final long actualDurationMs, final int suitesRan,
                                       final int suitesFailed, final Set<String> suitesObserved,
                                       final long suitesDurationMs) {
        String progressSql = "UPDATE " + TABLE_TIA_DISTRIBUTED_RUN_GROUP + " SET " + COL_ACTUAL_DURATION_MS
                + " = COALESCE(" + COL_ACTUAL_DURATION_MS + ", 0) + ?, " + COL_SUITES_RAN + " = GREATEST(COALESCE("
                + COL_SUITES_RAN + ", 0), ?), " + COL_SUITES_FAILED + " = ?, " + COL_SUITES_DURATION_MS
                + " = GREATEST(COALESCE(" + COL_SUITES_DURATION_MS + ", 0), ?) WHERE " + COL_RUN_ID + " = ? AND "
                + COL_GROUP_NUMBER + " = ? AND " + COL_STATUS + " = ? AND " + COL_RUNNER_KEY + " = ?";
        String observedCountSql = "UPDATE " + TABLE_TIA_DISTRIBUTED_RUN_GROUP + " SET " + COL_SUITES_OBSERVED
                + " = (SELECT COUNT(*) FROM " + TABLE_TIA_DISTRIBUTED_RUN_GROUP_SUITE + " WHERE "
                + COL_RUN_ID + " = ? AND " + COL_GROUP_NUMBER + " = ? AND " + COL_OBSERVED + " = TRUE) WHERE "
                + COL_RUN_ID + " = ? AND " + COL_GROUP_NUMBER + " = ?";

        try (Connection connection = getConnection()) {
            // H2 implicitly commits on DDL, so this must run before setAutoCommit(false).
            schemaManager.ensureSchema(connection);
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int rowsUpdated;
                try (PreparedStatement statement = connection.prepareStatement(progressSql)) {
                    statement.setLong(1, actualDurationMs);
                    statement.setInt(2, suitesRan);
                    statement.setInt(3, suitesFailed);
                    statement.setLong(4, suitesDurationMs);
                    statement.setString(5, runId);
                    statement.setInt(6, groupNumber);
                    statement.setString(7, DistributedRunGroupStatus.CLAIMED.name());
                    statement.setString(8, runnerKey);
                    rowsUpdated = statement.executeUpdate();
                }
                if (rowsUpdated != 1) {
                    connection.rollback();
                    return false;
                }

                setGroupSuiteFlag(connection, runId, groupNumber, COL_OBSERVED, suitesObserved, false);
                try (PreparedStatement statement = connection.prepareStatement(observedCountSql)) {
                    statement.setString(1, runId);
                    statement.setInt(2, groupNumber);
                    statement.setString(3, runId);
                    statement.setInt(4, groupNumber);
                    statement.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
    }

    /**
     * Set one boolean flag column to {@code TRUE} on a group's suite rows, for the named suites
     * only, in IN-clause chunks of {@link #IN_CLAUSE_CHUNK_SIZE}. Names not assigned to the group
     * match no row, and rows already flagged are skipped, so a retry re-reporting the same suites
     * writes nothing new.
     *
     * @param connection the caller's open connection, inside the caller's transaction
     * @param runId the distributed run the group belongs to
     * @param groupNumber the group whose rows to flag
     * @param flagColumn the flag column to set
     * @param suiteNames the suites to flag; may be empty, which issues no statement
     * @param onlyUnobserved true to also skip rows already flagged {@code observed}, so a suite
     *                       that was observed can never be flagged a second way and counted twice
     * @throws SQLException if an update fails
     */
    private void setGroupSuiteFlag(final Connection connection, final String runId, final int groupNumber,
                                   final String flagColumn, final Collection<String> suiteNames,
                                   final boolean onlyUnobserved)
            throws SQLException {
        List<String> names = new ArrayList<>(suiteNames);
        for (int start = 0; start < names.size(); start += IN_CLAUSE_CHUNK_SIZE) {
            List<String> chunk = names.subList(start, Math.min(names.size(), start + IN_CLAUSE_CHUNK_SIZE));
            String sql = "UPDATE " + TABLE_TIA_DISTRIBUTED_RUN_GROUP_SUITE + " SET " + flagColumn
                    + " = TRUE WHERE " + COL_RUN_ID + " = ? AND " + COL_GROUP_NUMBER + " = ? AND "
                    + flagColumn + " = FALSE AND "
                    + (onlyUnobserved ? COL_OBSERVED + " = FALSE AND " : "")
                    + COL_TEST_SUITE_NAME + " IN ("
                    + String.join(", ", Collections.nCopies(chunk.size(), "?")) + ")";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, runId);
                statement.setInt(2, groupNumber);
                for (int i = 0; i < chunk.size(); i++) {
                    statement.setString(3 + i, chunk.get(i));
                }
                statement.executeUpdate();
            }
        }
    }

    /**
     * Implements {@link DataStore#completeGroup}.
     *
     * <p>One guarded single-row {@code UPDATE} whose {@code WHERE} clause carries two predicates,
     * so the completeness check and the status flip are atomic:
     * {@code run_id = ? AND group_number = ? AND status = 'CLAIMED' AND runner_key = ?} is the
     * straggler protection, unchanged from before this group's progress was split out; {@code
     * (SELECT COUNT(*) FROM tia_distributed_run_group_suite WHERE run_id = ? AND group_number = ?
     * AND (observed = TRUE OR unrunnable = TRUE)) >= (SELECT COUNT(*) FROM
     * tia_distributed_run_group_suite WHERE run_id = ? AND group_number = ?)} is the completeness
     * guard: every assigned suite must be flagged, either observed by the runner (see {@link
     * #reportGroupProgress}) or proven never runnable. Counting flagged rows rather than comparing a
     * reported number means a suite can never count twice, and reports from several JVMs against
     * the same group add up to their union.
     *
     * <p>The guard counts observed suites, not {@code suites_ran}, deliberately.
     * {@code suites_ran} counts only suites that finished, and a class-level {@code @Disabled}
     * suite (or one excluded by a Surefire/Gradle filter, or deleted since the last mapping run)
     * never finishes even though the planner still assigned it to this group - guarding on
     * {@code suites_ran} would then block a group forever on a suite Tia never expected to run.
     * The observed flags come from what {@link
     * org.tiatesting.core.testrunner.TestRunResult#getSuitesObserved()} reports - every suite the
     * runner's own JVM saw finish or saw skipped, never a project-wide directory scan - so a group
     * whose runner has seen every assigned suite completes even when it executed fewer of them. Both
     * flags are {@code BOOLEAN DEFAULT FALSE NOT NULL}, so a group that never reported counts
     * {@code 0} flagged rows, which is less than the assigned count for any group with at least one
     * assigned suite, blocking it as intended. Row count is the entire answer, exactly as for {@link #claimNextPendingGroup} and
     * {@link DistributedRunRepository#electSealer}.
     *
     * <p>On a full run (seed or forced) the completeness predicate is instead {@code
     * suites_observed >= LEAST(1, assignedCount)}, where {@code suites_observed} is the observed
     * flag count {@link #reportGroupProgress} keeps: a full run's assigned
     * suite names come from a disk scan that over-includes non-test classes the runner never
     * observes, so requiring observed to reach the full assigned count would leave every group open
     * forever. A group assigned real suites must therefore have observed at least one; a group
     * assigned nothing completes with nothing observed.
     *
     * <p>On a non-full run the {@code unrunnable} flag is first set on the named suites' rows -
     * only rows still not {@code observed} - in the same transaction as the guarded update, so the
     * guard counts them and a suite can never be counted both ways. The caller passes only suites
     * it has proven can never run; see {@link
     * org.tiatesting.core.testrunner.TestSuiteRunnabilityClassifier}. A full run ignores them, since
     * its guard is already loosened.
     *
     * <p>Row count 0 rolls the transaction back - including any {@code unrunnable} flag just set -
     * and returns {@code null} without reading anything back, since there is by definition nothing
     * this runner wrote to return. Row count 1 re-reads the row through
     * {@link #mapGroupRow} rather than reconstructing it, so the returned group carries the stored
     * claim timestamp, estimate and the figures {@link #reportGroupProgress} accumulated rather
     * than only this call's inputs.
     *
     * <p>Calls {@link SchemaManager#ensureSchema(Connection)} first, since a runner completing its group could
     * be the first contact any caller in this process makes with a freshly created per-branch
     * schema.
     *
     * @param runId the distributed run the group belongs to
     * @param groupNumber the group's zero-based index within the run
     * @param runnerKey the calling runner's stable identity
     * @param completedAtMs UTC epoch millis to record as the completion time
     * @param unrunnableSuites assigned suites the runner never observed that are proven never
     *                         runnable, to flag {@code unrunnable}; may be empty
     * @return the updated group, or {@code null} when this runner's claim is no longer live or the
     *         group has not reported enough progress to close
     */
    DistributedRunGroup completeGroup(final String runId, final int groupNumber, final String runnerKey,
                                             final long completedAtMs, final Set<String> unrunnableSuites) {
        String assignedCountSubquery = "(SELECT COUNT(*) FROM " + TABLE_TIA_DISTRIBUTED_RUN_GROUP_SUITE
                + " WHERE " + COL_RUN_ID + " = ? AND " + COL_GROUP_NUMBER + " = ?)";
        String groupByNumberSql = "SELECT * FROM " + TABLE_TIA_DISTRIBUTED_RUN_GROUP
                + " WHERE " + COL_RUN_ID + " = ? AND " + COL_GROUP_NUMBER + " = ?";

        try (Connection connection = getConnection()) {
            // H2 implicitly commits on DDL, so this must run before setAutoCommit(false).
            schemaManager.ensureSchema(connection);

            // A full run's (seed or forced) assigned suite names come from a raw disk scan that
            // over-includes classes JUnit never reports as observed - abstract bases, fixtures,
            // anonymous $ classes - so observed can never reach assigned and the group would never
            // complete. On a full run the guard therefore takes what the runners observed as the
            // run's source of truth: the group need only have observed at least one suite (or, when
            // it was assigned nothing at all, nothing). Both inputs - the mode and the assigned count
            // - are fixed at plan time, so reading the mode separately from the conditional update
            // cannot race the guard. See the distributed test runs chapter in WIKI.md.
            boolean fullRun = readIsFullRun(connection, runId);
            String flaggedCountSubquery = "(SELECT COUNT(*) FROM " + TABLE_TIA_DISTRIBUTED_RUN_GROUP_SUITE
                    + " WHERE " + COL_RUN_ID + " = ? AND " + COL_GROUP_NUMBER + " = ? AND ("
                    + COL_OBSERVED + " = TRUE OR " + COL_UNRUNNABLE + " = TRUE))";
            String completenessGuard = fullRun
                    ? COL_SUITES_OBSERVED + " >= LEAST(1, " + assignedCountSubquery + ")"
                    : flaggedCountSubquery + " >= " + assignedCountSubquery;
            String completeSql = "UPDATE " + TABLE_TIA_DISTRIBUTED_RUN_GROUP + " SET " + COL_STATUS + " = ?, "
                    + COL_COMPLETED_AT + " = ? WHERE " + COL_RUN_ID + " = ? AND " + COL_GROUP_NUMBER + " = ? AND "
                    + COL_STATUS + " = ? AND " + COL_RUNNER_KEY + " = ? AND " + completenessGuard;

            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (!fullRun) {
                    // Flagged in the same transaction as the guard reads them, and only on rows still
                    // unobserved, so an observed suite wrongly passed in cannot be counted twice and a
                    // refused completion leaves no flag behind.
                    setGroupSuiteFlag(connection, runId, groupNumber, COL_UNRUNNABLE, unrunnableSuites, true);
                }

                int rowsUpdated;
                try (PreparedStatement statement = connection.prepareStatement(completeSql)) {
                    statement.setString(1, DistributedRunGroupStatus.COMPLETED.name());
                    statement.setLong(2, completedAtMs);
                    statement.setString(3, runId);
                    statement.setInt(4, groupNumber);
                    statement.setString(5, DistributedRunGroupStatus.CLAIMED.name());
                    statement.setString(6, runnerKey);
                    statement.setString(7, runId);
                    statement.setInt(8, groupNumber);
                    if (!fullRun) {
                        statement.setString(9, runId);
                        statement.setInt(10, groupNumber);
                    }
                    rowsUpdated = statement.executeUpdate();
                }

                if (rowsUpdated == 0) {
                    // Either this runner's claim is no longer live, or the group is still missing a
                    // suite that could have run. The caller must now write nothing, so the unrunnable
                    // flags go too.
                    connection.rollback();
                    return null;
                }

                DistributedRunGroup completed;
                try (PreparedStatement statement = connection.prepareStatement(groupByNumberSql)) {
                    statement.setString(1, runId);
                    statement.setInt(2, groupNumber);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        resultSet.next();
                        completed = mapGroupRow(resultSet);
                    }
                }
                connection.commit();
                return completed;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
    }

    /**
     * Read whether a distributed run was planned as a full run (seed or forced), on the caller's
     * own connection. A full run's group assignments come from a disk scan that over-includes
     * non-test classes, so its completion guard is loosened to observed-as-truth - see
     * {@link #completeGroup}. Read only on the completion path, which runs once per group at the
     * end of a build, never on a hot read path.
     *
     * @param connection the open connection to read on
     * @param runId the distributed run to read the mode for
     * @return true when the run row records a full-run mode; false when it does not, or no such
     *         run row exists
     * @throws SQLException if the read fails
     */
    private boolean readIsFullRun(final Connection connection, final String runId) throws SQLException {
        String sql = "SELECT " + COL_SELECTION_MODE + " FROM " + TABLE_TIA_DISTRIBUTED_RUN
                + " WHERE " + COL_RUN_ID + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        && SelectionMode.fromStoredName(resultSet.getString(1)).isFullRun();
            }
        }
    }

    /**
     * Build a {@link DistributedRunGroup} from the current row of a {@code SELECT *} against
     * {@link TiaSchema#TABLE_TIA_DISTRIBUTED_RUN_GROUP}. Shared by {@link #claimNextPendingGroup} and
     * {@link #readDistributedRunGroups} so the two cannot drift apart on how a group row maps. It
     * also means the group returned after a successful claim (or an already-claimed lookup) is read
     * back from the table rather than reconstructed field-by-field from what the caller asked for -
     * the estimated time, suites-ran and suites-failed columns are not part of the claim call's
     * inputs and must come from the stored row.
     *
     * @param resultSet the result set, positioned on the row to map
     * @return the mapped group
     * @throws SQLException if a column cannot be read
     */
    private static DistributedRunGroup mapGroupRow(ResultSet resultSet) throws SQLException {
        return new DistributedRunGroup(
                resultSet.getString(COL_RUN_ID),
                resultSet.getInt(COL_GROUP_NUMBER),
                DistributedRunGroupStatus.valueOf(resultSet.getString(COL_STATUS)),
                resultSet.getString(COL_RUNNER_KEY),
                getNullableLong(resultSet, COL_CLAIMED_AT),
                getNullableLong(resultSet, COL_COMPLETED_AT),
                resultSet.getLong(COL_ESTIMATED_MS),
                getNullableLong(resultSet, COL_ACTUAL_DURATION_MS),
                resultSet.getInt(COL_SUITES_RAN),
                resultSet.getInt(COL_SUITES_FAILED),
                resultSet.getInt(COL_SUITES_OBSERVED),
                resultSet.getLong(COL_SUITES_DURATION_MS));
    }
}
