package org.tiatesting.core.persistence;

import org.tiatesting.core.model.TestRunContribution;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.persistence.dialect.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.tiatesting.core.persistence.JdbcRows.getNullableInt;
import static org.tiatesting.core.persistence.JdbcRows.setNullableInt;
import static org.tiatesting.core.persistence.JdbcRows.setNullableString;
import static org.tiatesting.core.persistence.TiaSchema.*;

/**
 * Reads and writes runs and their test JVMs' contributions: {@code tia_test_run} and
 * {@code tia_test_run_jvm}. A single-host run's JVMs serialize on the run's row while each rebuilds
 * the run's history rows from every contribution, so the last writer always sees them all. See the
 * "Test run history" chapter in {@code WIKI.md}.
 */
class TestRunContributionRepository extends JdbcRepository {

    private static final String SUITE_SEPARATOR = ",";
    private static final List<String> CONTRIBUTION_COLUMNS = Arrays.asList(COL_RUN_ID, COL_JVM_ID,
            COL_RERUN, COL_GROUP_NUMBER, COL_SUITES_RAN, COL_SUITES_FAILED, COL_RERUN_SUITES,
            COL_TEST_TIME_MS, COL_SUITES_TIME_MS, COL_FIRST_START_MS, COL_LAST_END_MS);
    private static final List<String> CONTRIBUTION_KEY = Arrays.asList(COL_RUN_ID, COL_JVM_ID, COL_RERUN);

    /**
     * Construct the repository over the datastore's shared JDBC plumbing.
     *
     * @param dialect the SQL dialect rendering vendor-specific statements
     * @param connections opens a connection with the per-branch schema selected
     * @param schemaManager bootstraps the schema before first use
     */
    TestRunContributionRepository(SqlDialect dialect, Supplier<Connection> connections, SchemaManager schemaManager) {
        super(dialect, connections, schemaManager);
    }

    /**
     * Implements {@link DataStore#persistTestRunContribution}: make sure the run's row exists, then in
     * one transaction lock it, upsert this JVM's contributions and, when a history builder is given,
     * upsert the history rows it builds from every contribution the run now has.
     *
     * <p>The run row is inserted before the transaction, in autocommit mode, so a lost insert race
     * with another JVM of the same run (tolerated and re-checked) cannot abort the transaction on
     * Postgres.
     *
     * @param runId the run the contributions belong to
     * @param runStartMs the run's start time, stored if this call creates the run row
     * @param contributions this JVM's contributions, one per attempt kind it has run
     * @param historyEntries builds the run's history rows from all its contributions, or null to
     *                       write no history
     * @param historyRepository writes the history rows on this call's connection
     * @return the ids of history rows this call created rather than updated
     */
    Set<String> persistContribution(final String runId, final long runStartMs,
                                    final List<TestRunContribution> contributions,
                                    final Function<List<TestRunContribution>, List<TestRunHistoryEntry>> historyEntries,
                                    final TestRunHistoryRepository historyRepository) {
        try (Connection connection = getConnection()) {
            // H2 implicitly commits on DDL, so this must run before setAutoCommit(false).
            schemaManager.ensureSchema(connection);
            ensureRunExists(connection, runId, runStartMs);
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                lockRun(connection, runId);
                upsertContributions(connection, contributions);
                Set<String> created = new LinkedHashSet<>();
                if (historyEntries != null) {
                    for (TestRunHistoryEntry entry : historyEntries.apply(readContributions(connection, runId))) {
                        if (!historyRepository.historyRowExists(connection, entry.getId())) {
                            created.add(entry.getId());
                        }
                        historyRepository.upsertHistoryEntry(connection, entry);
                    }
                }
                connection.commit();
                return created;
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
     * Implements {@link DataStore#readTestRunContributions}.
     *
     * @param runId the run to read
     * @return every contribution stored for the run, empty when none
     */
    List<TestRunContribution> readContributions(final String runId) {
        try (Connection connection = getConnection()) {
            schemaManager.ensureSchema(connection);
            return readContributions(connection, runId);
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
    }

    /**
     * Implements {@link DataStore#readTestRunFailedSuites}: the union of the failures other JVMs of
     * the run recorded, first attempt and reruns together.
     *
     * @param runId the run to read
     * @param excludingJvmId the asking JVM, whose own failures it already holds in memory
     * @return the failed suite names, empty when none
     */
    Set<String> readFailedSuites(final String runId, final String excludingJvmId) {
        Set<String> failed = new LinkedHashSet<>();
        for (TestRunContribution contribution : readContributions(runId)) {
            if (!contribution.getJvmId().equals(excludingJvmId)) {
                failed.addAll(contribution.getSuitesFailed());
            }
        }
        return failed;
    }

    /**
     * Read a run's contributions on the caller's connection.
     *
     * @param connection the caller's open connection
     * @param runId the run to read
     * @return the run's contributions
     * @throws SQLException if the query fails
     */
    List<TestRunContribution> readContributions(final Connection connection, final String runId) throws SQLException {
        List<TestRunContribution> contributions = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM " + TABLE_TIA_TEST_RUN_JVM
                + " WHERE " + COL_RUN_ID + " = ?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    contributions.add(new TestRunContribution(rs.getString(COL_RUN_ID), rs.getString(COL_JVM_ID),
                            rs.getBoolean(COL_RERUN), getNullableInt(rs, COL_GROUP_NUMBER),
                            rs.getInt(COL_SUITES_RAN), splitSuites(rs.getString(COL_SUITES_FAILED)),
                            splitSuites(rs.getString(COL_RERUN_SUITES)), rs.getLong(COL_TEST_TIME_MS),
                            rs.getLong(COL_SUITES_TIME_MS), rs.getLong(COL_FIRST_START_MS),
                            rs.getLong(COL_LAST_END_MS)));
                }
            }
        }
        return contributions;
    }

    /**
     * Upsert contributions on the caller's connection, inside the caller's transaction. Keyed on
     * (run, JVM, attempt kind), so a JVM restating its totals replaces its own row only.
     *
     * @param connection the caller's open connection
     * @param contributions the rows to write; empty writes nothing
     * @throws SQLException if the upsert fails
     */
    void upsertContributions(final Connection connection, final List<TestRunContribution> contributions)
            throws SQLException {
        if (contributions.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                dialect.upsert(TABLE_TIA_TEST_RUN_JVM, CONTRIBUTION_COLUMNS, CONTRIBUTION_KEY))) {
            for (TestRunContribution c : contributions) {
                ps.setString(1, c.getRunId());
                ps.setString(2, c.getJvmId());
                ps.setBoolean(3, c.isRerun());
                setNullableInt(ps, 4, c.getGroupNumber());
                ps.setInt(5, c.getSuitesRan());
                setNullableString(ps, 6, joinSuites(c.getSuitesFailed()));
                setNullableString(ps, 7, joinSuites(c.getRerunSuites()));
                ps.setLong(8, c.getTestTimeMs());
                ps.setLong(9, c.getSuitesTimeMs());
                ps.setLong(10, c.getFirstStartMs());
                ps.setLong(11, c.getLastEndMs());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Create (or restate) a distributed run's row inside the plan write's transaction, and clear any
     * contributions left under the same run id by an earlier plan of it, so a replanned run starts
     * from nothing.
     *
     * @param connection the plan write's connection
     * @param runId the distributed run id
     * @param startMs the run's creation time
     * @throws SQLException if a statement fails
     */
    void insertRunForPlan(final Connection connection, final String runId, final long startMs) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + TABLE_TIA_TEST_RUN_JVM
                + " WHERE " + COL_RUN_ID + " = ?")) {
            delete.setString(1, runId);
            delete.executeUpdate();
        }
        try (PreparedStatement upsert = connection.prepareStatement(dialect.upsert(TABLE_TIA_TEST_RUN,
                Arrays.asList(COL_ID, COL_START_MS), Collections.singletonList(COL_ID)))) {
            upsert.setString(1, runId);
            upsert.setLong(2, startMs);
            upsert.executeUpdate();
        }
    }

    /**
     * Insert the run's row if it is absent, tolerating another JVM of the same run inserting it first.
     * The race is told apart from a real failure by re-checking the row, not by matching a vendor SQL
     * state, the same way the id-block seed does.
     *
     * @param connection the caller's connection, in autocommit mode
     * @param runId the run id
     * @param startMs the run's start time
     * @throws SQLException if the insert fails and the row still does not exist
     */
    private void ensureRunExists(final Connection connection, final String runId, final long startMs)
            throws SQLException {
        if (runExists(connection, runId)) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + TABLE_TIA_TEST_RUN + " ("
                + COL_ID + ", " + COL_START_MS + ") VALUES (?, ?)")) {
            ps.setString(1, runId);
            ps.setLong(2, startMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (!runExists(connection, runId)) {
                throw e;
            }
            log.debug("Run {} was created by another JVM first: {}", runId, e.getMessage());
        }
    }

    /**
     * Report whether the run's row exists.
     *
     * @param connection the caller's connection
     * @param runId the run id
     * @return true when the row exists
     * @throws SQLException if the query fails
     */
    private boolean runExists(final Connection connection, final String runId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM " + TABLE_TIA_TEST_RUN
                + " WHERE " + COL_ID + " = ?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Lock the run's row for the rest of the caller's transaction, so JVMs of the same run take turns.
     *
     * @param connection the caller's connection, inside its transaction
     * @param runId the run id
     * @throws SQLException if the run row is missing or the lock fails
     */
    private void lockRun(final Connection connection, final String runId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT " + COL_ID + " FROM "
                + TABLE_TIA_TEST_RUN + " WHERE " + COL_ID + " = ? FOR UPDATE")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Missing " + TABLE_TIA_TEST_RUN + " row for run '" + runId
                            + "' - it is created before the lock is taken.");
                }
            }
        }
    }

    /**
     * Render suite names for storage, or null for none.
     *
     * @param suites the names
     * @return the comma-separated names, or null when empty
     */
    static String joinSuites(final Collection<String> suites) {
        return suites.isEmpty() ? null : String.join(SUITE_SEPARATOR, suites);
    }

    /**
     * Parse stored suite names.
     *
     * @param stored the comma-separated names, or null
     * @return the names, empty for null or blank
     */
    static Set<String> splitSuites(final String stored) {
        if (stored == null || stored.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(Arrays.asList(stored.split(SUITE_SEPARATOR)));
    }
}
