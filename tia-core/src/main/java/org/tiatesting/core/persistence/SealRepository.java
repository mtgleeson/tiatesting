package org.tiatesting.core.persistence;

import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.CoreStatsIncrement;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.dialect.SqlDialect;

import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

import static org.tiatesting.core.persistence.JdbcRows.*;
import static org.tiatesting.core.persistence.TiaSchema.*;

/**
 * Writes the {@code tia_core} row and runs the seal: the one transaction that writes the method
 * catalogue, the library drain cleanup, the unsealed-flag clear and the advanced commit value
 * together. See the "Persist flow and crash safety" chapter in {@code WIKI.md}.
 */
class SealRepository extends JdbcRepository {

    private final MappingReader mappingReader;
    private final MappingWriter mappingWriter;
    private final LibraryRepository libraryRepository;

    /**
     * Construct the repository over the datastore's shared JDBC plumbing and the repositories
     * whose connection-scoped writes join the seal transaction.
     *
     * @param dialect the SQL dialect rendering vendor-specific statements
     * @param connections opens a connection with the per-branch schema selected
     * @param schemaManager bootstraps and migrates the schema before first use
     * @param mappingReader reads the existing core row the core write accumulates onto
     * @param mappingWriter writes the method catalogue, re-seed clear-out and unsealed-flag clear
     * @param libraryRepository writes the library drain cleanup and tracked-library baselines
     */
    SealRepository(SqlDialect dialect, Supplier<Connection> connections, SchemaManager schemaManager,
                   MappingReader mappingReader, MappingWriter mappingWriter, LibraryRepository libraryRepository) {
        super(dialect, connections, schemaManager);
        this.mappingReader = mappingReader;
        this.mappingWriter = mappingWriter;
        this.libraryRepository = libraryRepository;
    }

    /**
     * Write the core row (commit, branch and stats) as absolute values over its own connection,
     * outside any seal. Implements {@link DataStore#persistCoreData}.
     *
     * @param tiaData the core data to store
     */
    void persistCoreData(final TiaData tiaData){
        long startTime = System.currentTimeMillis();
        Connection connection = getConnection();

        try {
            persistTiaCore(connection, tiaData);
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }

        log.debug("Time to save the Tia core data to disk (ms): " + (System.currentTimeMillis() - startTime));
    }

    /**
     * Persist a run's seal atomically: the method catalogue, the library drain cleanup, the
     * clearing of every currently-flagged unsealed suite, and the commit value are all written in
     * one transaction, so none of them can end up ahead of the others. The catalogue's line ranges
     * and each library's mapping baseline are both claims about the commit being sealed, so a
     * partial write would leave a later diff reading them against the wrong baseline. Clearing the
     * unsealed flags is unconditional and not scoped to this run's own suites - it is what lets
     * one runner's seal clear flags a peer runner set, in the distributed model this bundle is
     * groundwork for. See the "Persist flow and crash safety" chapter in {@code WIKI.md}.
     *
     * @param sealedRunData the complete seal payload
     */
    void persistSealedRunData(final SealedRunData sealedRunData){
        long startTime = System.currentTimeMillis();
        Connection connection = getConnection();

        try {
            // H2 implicitly commits on DDL, so this must run before setAutoCommit(false) - running
            // it inside the transaction would silently break atomicity.
            schemaManager.ensureLibraryTableExists(connection);
            connection.setAutoCommit(false);

            try {
                mappingWriter.writeSourceMethods(connection, sealedRunData.getMethodsTracked());

                for (LibraryImpactDrainResult.DrainedBatchKey key : sealedRunData.getDrainedMethodBatchKeys()){
                    libraryRepository.deletePendingLibraryImpactedMethods(connection, key.getGroupArtifact(), key.getPublishSeq());
                }
                for (LibraryImpactDrainResult.DrainedBatchKey key : sealedRunData.getDrainedForcedBatchKeys()){
                    libraryRepository.deletePendingLibraryForcedSelections(connection, key.getGroupArtifact(), key.getPublishSeq());
                }
                for (TrackedLibrary library : sealedRunData.getLibrariesToPersist()){
                    libraryRepository.persistTrackedLibrary(connection, library);
                }

                if (sealedRunData.isReseed()) {
                    // Must precede clearUnsealedTestSuites: the unsealed flag is what marks the
                    // suites this run rewrote, and the clear-out keeps exactly those.
                    mappingWriter.clearMappingNotRewrittenByThisRun(connection);
                }

                // Clear every currently-flagged suite before the commit value advances: those
                // suites' mapping rows are now safe to trust against the commit about to become
                // the stored one. Deliberately unconditional and not scoped to this run's own
                // suites (see TestSelector.addUnsealedTests, which force-selects every flagged
                // suite): on a run that executes what it selected, every flag cleared here was
                // just re-accounted for by this run's own edge write. That is a weaker guarantee
                // than "never drops an unaccounted flag" - a run that does not execute its full
                // selection (a --tests filter, a tag filter, a fail-fast abort) can clear a
                // flagged suite's flag without recapturing its coverage, and the same is true
                // across peer forks, where one fork's seal clears flags another fork set. The
                // flag is a best-effort narrowing of the force-rerun window, not an absolute
                // guarantee. See the "Persist flow and crash safety" chapter in WIKI.md.
                mappingWriter.clearUnsealedTestSuites(connection);

                // Seal last within the transaction too, so the write order still reads as
                // "everything, then the commit value" even though they commit together.
                persistSealedTiaCore(connection, sealedRunData.getTiaData(),
                        sealedRunData.getStatsIncrement());

                connection.commit();
            } catch (Exception e) {
                // Catch Exception (not just SQLException) so any failure - including an unchecked
                // one - rolls back before the exception is rethrown.
                try {
                    connection.rollback();
                } catch (SQLException rollbackEx) {
                    e.addSuppressed(rollbackEx);
                }
                throw e;
            }
            // Deliberately no autoCommit restore here, unlike persistSourceMethods(Connection, Map)
            // and persistTestSuiteClasses: this connection is local to this method and closed a few
            // lines below, so nothing needs it restored. Restoring it would call
            // setAutoCommit(true), which commits the open transaction - on a failure that bypasses
            // the catch above (e.g. an Error, which "catch (Exception)" does not catch), that would
            // silently publish a half-written seal instead of leaving it uncommitted for the
            // connection close to discard.
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }

        log.debug("Time to persist the sealed run data (ms): " + (System.currentTimeMillis() - startTime));
    }

    /**
     * Write the core row at the seal: the commit value, the branch and the last-updated stamp as
     * absolutes, and the run stats as an <em>accumulation</em> against whatever the row currently
     * holds.
     *
     * <p>The distinction is the point. The commit value is this run's to declare, so writing it
     * from the caller's snapshot is correct. The stats are a running total several builds
     * contribute to, so writing those from the snapshot would discard any increment that landed
     * between this run's read and its write - the whole mapping persist, which is seconds to
     * minutes. Expressing them as {@code column = column + ?} moves the read to write time, inside
     * the seal's transaction, where nothing can interleave. The rolling averages are the same
     * arithmetic {@link TiaData#incrementStats} performs, transcribed into SQL: every reference to
     * a stats column on the right-hand side sees the row's pre-update value, so the two assignments
     * that make up an average stay consistent with each other.
     *
     * <p>An empty increment (a Surefire retry, which must not count as another run) leaves the
     * stats columns out of the statement entirely rather than adding zero.
     *
     * @param connection the connection to write on, already inside the seal's transaction
     * @param tiaData the core data carrying the commit, branch and last-updated to stamp
     * @param increment this run's contribution to the stats
     * @throws SQLException if the write fails
     */
    private void persistSealedTiaCore(Connection connection, TiaData tiaData,
                                      CoreStatsIncrement increment) throws SQLException {
        TiaData existingTiaCore = mappingReader.getCoreData(connection);

        if (existingTiaCore.getCommitValue() == null) {
            // No row yet, so there is nothing to accumulate against: the increment is the absolute
            // value. Merging it onto the in-memory instance and taking the ordinary INSERT path
            // keeps one definition of that arithmetic rather than a second copy of it in SQL.
            applyIncrement(tiaData, increment);
            persistTiaCore(connection, tiaData);
            return;
        }

        StringBuilder sql = new StringBuilder("UPDATE " + TABLE_TIA_CORE + " SET "
                + COL_COMMIT_VALUE + "='" + tiaData.getCommitValue()
                + "', " + COL_BRANCH + "=" + sqlStringOrNull(tiaData.getBranch())
                + ", " + COL_LAST_UPDATED + "='" + tiaData.getLastUpdated() + "'");

        if (increment.getNumRuns() > 0) {
            long n = increment.getNumRuns();
            long runTime = increment.getRunTimeMs();

            if (increment.isAllTestsRun()) {
                // Folds into the all-tests average - the baseline savings are measured against -
                // and leaves the selected-run average alone.
                sql.append(", ").append(COL_ALL_TESTS_RUN_TIME).append("=((")
                        .append(COL_NUM_ALL_TESTS_RUNS).append(" * ").append(COL_ALL_TESTS_RUN_TIME)
                        .append(") + ").append(n * runTime).append(") / (")
                        .append(COL_NUM_ALL_TESTS_RUNS).append(" + ").append(n).append(")");
                sql.append(", ").append(COL_NUM_ALL_TESTS_RUNS).append("=")
                        .append(COL_NUM_ALL_TESTS_RUNS).append(" + ").append(n);
            } else {
                // The selected-run average is over the selected sub-count, which is the total run
                // count minus the all-tests runs - the same denominator incrementStats uses.
                sql.append(", ").append(COL_AVG_RUN_TIME).append("=(((")
                        .append(COL_NUM_RUNS).append(" - ").append(COL_NUM_ALL_TESTS_RUNS)
                        .append(") * ").append(COL_AVG_RUN_TIME).append(") + ").append(n * runTime)
                        .append(") / ((").append(COL_NUM_RUNS).append(" - ")
                        .append(COL_NUM_ALL_TESTS_RUNS).append(") + ").append(n).append(")");
            }

            sql.append(", ").append(COL_NUM_RUNS).append("=").append(COL_NUM_RUNS).append(" + ").append(n);
            sql.append(", ").append(COL_NUM_SUCCESS_RUNS).append("=").append(COL_NUM_SUCCESS_RUNS)
                    .append(" + ").append(increment.getNumSuccessRuns());
            sql.append(", ").append(COL_NUM_FAIL_RUNS).append("=").append(COL_NUM_FAIL_RUNS)
                    .append(" + ").append(increment.getNumFailRuns());
        }

        if (increment.hasOverheadModel()) {
            sql.append(", ").append(COL_FIXED_OVERHEAD_MS).append("=((")
                    .append(COL_NUM_OVERHEAD_MEASUREMENTS).append(" * ").append(COL_FIXED_OVERHEAD_MS)
                    .append(") + ").append(increment.getFixedOverheadMs()).append(") / (")
                    .append(COL_NUM_OVERHEAD_MEASUREMENTS).append(" + 1)");
            sql.append(", ").append(COL_CAPTURE_OVERHEAD_PER_SUITE_MS).append("=((")
                    .append(COL_NUM_OVERHEAD_MEASUREMENTS).append(" * ")
                    .append(COL_CAPTURE_OVERHEAD_PER_SUITE_MS).append(") + ")
                    .append(increment.getCaptureOverheadPerSuiteMs()).append(") / (")
                    .append(COL_NUM_OVERHEAD_MEASUREMENTS).append(" + 1)");
            sql.append(", ").append(COL_NUM_OVERHEAD_MEASUREMENTS).append("=")
                    .append(COL_NUM_OVERHEAD_MEASUREMENTS).append(" + 1");
        }

        log.debug("Persisting the sealed Tia core data: {}", sql);
        Statement statement = connection.createStatement();
        statement.executeUpdate(sql.toString());
    }

    /**
     * Apply an increment to an in-memory {@link TiaData}, for the one path that cannot accumulate
     * in SQL: the very first seal on a database with no core row to accumulate against.
     *
     * @param tiaData the core data to mutate
     * @param increment the increment to apply
     */
    private void applyIncrement(TiaData tiaData, CoreStatsIncrement increment) {
        if (increment.getNumRuns() > 0) {
            TestStats runStats = new TestStats();
            runStats.setNumRuns(increment.getNumRuns());
            runStats.setAvgRunTime(increment.getRunTimeMs());
            runStats.setNumSuccessRuns(increment.getNumSuccessRuns());
            runStats.setNumFailRuns(increment.getNumFailRuns());
            tiaData.incrementStats(runStats, increment.isAllTestsRun());
        }
        if (increment.hasOverheadModel()) {
            tiaData.getTestStats().incrementOverheadModel(increment.getFixedOverheadMs(),
                    increment.getCaptureOverheadPerSuiteMs());
        }
    }

    /**
     * Insert the core row if none exists, otherwise overwrite it with the given absolute values.
     *
     * @param connection the connection to write on
     * @param tiaData the core data to store
     * @throws SQLException if the statement fails
     */
    private void persistTiaCore(Connection connection, TiaData tiaData) throws SQLException {
        TiaData existingTiaCore = mappingReader.getCoreData(connection);
        String sql;

        if (existingTiaCore.getCommitValue() == null){
            sql = "INSERT INTO " + TABLE_TIA_CORE + " (" + COL_COMMIT_VALUE + ", " + COL_BRANCH + ", " + COL_LAST_UPDATED + ", " + COL_NUM_RUNS + ", " +
                    COL_AVG_RUN_TIME + ", " + COL_NUM_SUCCESS_RUNS + ", " + COL_NUM_FAIL_RUNS + ", " +
                    COL_ALL_TESTS_RUN_TIME + ", " + COL_NUM_ALL_TESTS_RUNS + ", " +
                    COL_FIXED_OVERHEAD_MS + ", " + COL_CAPTURE_OVERHEAD_PER_SUITE_MS + ", " +
                    COL_NUM_OVERHEAD_MEASUREMENTS + ") values ('" +
                    tiaData.getCommitValue() + "', " +
                    sqlStringOrNull(tiaData.getBranch()) + ", '" +
                    tiaData.getLastUpdated() + "', " +
                    tiaData.getTestStats().getNumRuns() + ", " +
                    tiaData.getTestStats().getAvgRunTime()  + ", " +
                    tiaData.getTestStats().getNumSuccessRuns()  + ", " +
                    tiaData.getTestStats().getNumFailRuns() + ", " +
                    tiaData.getTestStats().getAllTestsRunTime() + ", " +
                    tiaData.getTestStats().getNumAllTestsRuns() + ", " +
                    tiaData.getTestStats().getFixedOverheadMs() + ", " +
                    tiaData.getTestStats().getCaptureOverheadPerSuiteMs() + ", " +
                    tiaData.getTestStats().getNumOverheadMeasurements() + ")";
        }else{
            sql = "UPDATE " + TABLE_TIA_CORE + " SET " +
                    COL_COMMIT_VALUE + "='" + tiaData.getCommitValue() +
                    "', " + COL_BRANCH + "=" + sqlStringOrNull(tiaData.getBranch()) +
                    ", " + COL_LAST_UPDATED + "='" + tiaData.getLastUpdated() +
                    "', " + COL_NUM_RUNS + "=" + tiaData.getTestStats().getNumRuns() +
                    ", " + COL_AVG_RUN_TIME + "=" + tiaData.getTestStats().getAvgRunTime() +
                    ", " + COL_NUM_SUCCESS_RUNS + "=" + tiaData.getTestStats().getNumSuccessRuns() +
                    ", " + COL_NUM_FAIL_RUNS + "=" + tiaData.getTestStats().getNumFailRuns() +
                    ", " + COL_ALL_TESTS_RUN_TIME + "=" + tiaData.getTestStats().getAllTestsRunTime() +
                    ", " + COL_NUM_ALL_TESTS_RUNS + "=" + tiaData.getTestStats().getNumAllTestsRuns() +
                    ", " + COL_FIXED_OVERHEAD_MS + "=" + tiaData.getTestStats().getFixedOverheadMs() +
                    ", " + COL_CAPTURE_OVERHEAD_PER_SUITE_MS + "=" + tiaData.getTestStats().getCaptureOverheadPerSuiteMs() +
                    ", " + COL_NUM_OVERHEAD_MEASUREMENTS + "=" + tiaData.getTestStats().getNumOverheadMeasurements();
        }

        log.debug("Persisting Tia core data: {}", sql);
        Statement statement = connection.createStatement();
        statement.executeUpdate(sql);
    }

    /**
     * Render a string as a SQL literal for the inline-concatenation persist statements: a quoted,
     * single-quote-escaped value, or the keyword {@code NULL} when the value is {@code null}. Used
     * for the {@code branch} column, which is genuinely absent on stats-only runs and must be
     * stored as SQL NULL rather than the literal text {@code 'null'}.
     *
     * @param value the value to render, or {@code null}
     * @return {@code 'value'} (with embedded single quotes doubled) or {@code NULL}
     */
    private String sqlStringOrNull(final String value){
        if (value == null){
            return "NULL";
        }
        return "'" + value.replace("'", "''") + "'";
    }
}
