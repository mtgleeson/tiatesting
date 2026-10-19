package org.tiatesting.core.persistence;

import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.LineRanges;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.persistence.dialect.SqlDialect;

import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

import static org.tiatesting.core.persistence.JdbcRows.*;
import static org.tiatesting.core.persistence.TiaSchema.*;

/**
 * Writes the suite-to-method mapping: test suite rows and their class/method edges (chunked
 * multi-row inserts over application-assigned {@code tia_source_class} id blocks), the source
 * method catalogue, the failed-suite set, the unsealed flags and the re-seed clear-out. The
 * connection-scoped methods take the caller's connection so {@link SealRepository} can run them
 * inside the seal transaction.
 */
class MappingWriter extends JdbcRepository {
    // H2's executeBatch sends one wire round trip per row, so on a remote server a seed persist of
    // millions of rows is dominated by round trips. Multi-row INSERT (... VALUES (?,?),(?,?),...)
    // collapses INSERT_CHUNK rows into one round trip. A full-chunk statement is prepared once and
    // reused; each suite's remainder (< INSERT_CHUNK) is inserted with a one-off sized statement.
    private static final int INSERT_CHUNK = 1000;
    private static final String SOURCE_CLASS_COLS = COL_ID + ", " + COL_TIA_TEST_SUITE_ID + ", " + COL_SOURCE_FILENAME;
    private static final String SOURCE_CLASS_METHOD_COLS = COL_TIA_SOURCE_CLASS_ID + ", " + COL_TIA_SOURCE_METHOD_ID;
    private static final String INSERT_SOURCE_CLASS_CHUNK_SQL =
            buildMultiRowInsertSql(TABLE_TIA_SOURCE_CLASS, SOURCE_CLASS_COLS, 3, INSERT_CHUNK);
    private static final String INSERT_SOURCE_CLASS_METHOD_CHUNK_SQL =
            buildMultiRowInsertSql(TABLE_TIA_SOURCE_CLASS_METHOD, SOURCE_CLASS_METHOD_COLS, 2, INSERT_CHUNK);

    /**
     * Construct the repository over the datastore's shared JDBC plumbing.
     *
     * @param dialect the SQL dialect rendering vendor-specific statements
     * @param connections opens a connection with the per-branch schema selected
     * @param schemaManager bootstraps the schema before first use
     */
    MappingWriter(SqlDialect dialect, Supplier<Connection> connections, SchemaManager schemaManager) {
        super(dialect, connections, schemaManager);
    }

    /**
     * Implements {@link DataStore#persistTestSuitesFailed}. Opens a connection and delegates to {@link #persistTestSuitesFailed(Connection,
     * Set, Set)}, which owns the transaction.
     *
     * @param suitesToClear the suites whose stored failed state this run supersedes
     * @param suitesFailed the suites whose latest execution in this run failed
     */
    void persistTestSuitesFailed(final Set<String> suitesToClear, final Set<String> suitesFailed){
        long startTime = System.currentTimeMillis();
        Connection connection = getConnection();

        try {
            persistTestSuitesFailed(connection, suitesToClear, suitesFailed);
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }

        log.debug("Time to save the failed test suites data to disk (ms): " + (System.currentTimeMillis() - startTime));
    }

    /**
     * Clear the unsealed flag from every currently-flagged test suite, opening and closing its
     * own connection. Called as part of the seal, once the commit value those mapping rows
     * describe is about to become the stored commit.
     */
    void clearUnsealedTestSuites(){
        Connection connection = getConnection();
        try {
            clearUnsealedTestSuites(connection);
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
     * Re-seed clear-out: delete every piece of mapping data this run did not just rewrite, inside
     * the seal's transaction. A suite this run executed had its edges rewritten and its unsealed
     * flag set by persistTestSuiteClasses, so the flag is the "observed by this run" marker.
     * Unobserved suites are deleted with their classes and edges, except developer-disabled ones,
     * which keep their row, flag and stats and lose only their edges. Orphan class, edge and method
     * rows - from earlier crashes, or suite deletions made before deleteTestSuites removed the
     * suite's coverage too - are swept across the whole mapping, and failed-suite entries naming a
     * suite that no longer exists are pruned. Core stats, history, library tables and id blocks are
     * untouched. See the "Forced runs and re-seed" chapter in {@code WIKI.md}.
     *
     * @param connection the seal's connection, already inside its transaction
     * @throws SQLException if any delete fails; the caller rolls the whole seal back
     */
    void clearMappingNotRewrittenByThisRun(Connection connection) throws SQLException {
        String notObserved = "(" + COL_UNSEALED + " IS NULL OR " + COL_UNSEALED + " = FALSE)";
        String unobservedSuiteIds = "SELECT " + COL_ID + " FROM " + TABLE_TIA_TEST_SUITE
                + " WHERE " + notObserved;

        try (Statement statement = connection.createStatement()) {
            int edges = statement.executeUpdate("DELETE FROM " + TABLE_TIA_SOURCE_CLASS_METHOD
                    + " WHERE " + COL_TIA_SOURCE_CLASS_ID + " IN (SELECT " + COL_ID + " FROM "
                    + TABLE_TIA_SOURCE_CLASS + " WHERE " + COL_TIA_TEST_SUITE_ID + " IN ("
                    + unobservedSuiteIds + "))");
            int classes = statement.executeUpdate("DELETE FROM " + TABLE_TIA_SOURCE_CLASS
                    + " WHERE " + COL_TIA_TEST_SUITE_ID + " IN (" + unobservedSuiteIds + ")");
            int suites = statement.executeUpdate("DELETE FROM " + TABLE_TIA_TEST_SUITE
                    + " WHERE " + notObserved + " AND (" + COL_DEVELOPER_DISABLED + " IS NULL OR "
                    + COL_DEVELOPER_DISABLED + " = FALSE)");
            int orphanClasses = statement.executeUpdate("DELETE FROM " + TABLE_TIA_SOURCE_CLASS
                    + " WHERE NOT EXISTS (SELECT 1 FROM " + TABLE_TIA_TEST_SUITE + " s WHERE s."
                    + COL_ID + " = " + TABLE_TIA_SOURCE_CLASS + "." + COL_TIA_TEST_SUITE_ID + ")");
            int orphanEdges = statement.executeUpdate("DELETE FROM " + TABLE_TIA_SOURCE_CLASS_METHOD
                    + " WHERE NOT EXISTS (SELECT 1 FROM " + TABLE_TIA_SOURCE_CLASS + " c WHERE c."
                    + COL_ID + " = " + TABLE_TIA_SOURCE_CLASS_METHOD + "." + COL_TIA_SOURCE_CLASS_ID + ")");
            int methods = statement.executeUpdate("DELETE FROM " + TABLE_TIA_SOURCE_METHOD
                    + " WHERE NOT EXISTS (SELECT 1 FROM " + TABLE_TIA_SOURCE_CLASS_METHOD + " e WHERE e."
                    + COL_TIA_SOURCE_METHOD_ID + " = " + TABLE_TIA_SOURCE_METHOD + "." + COL_ID + ")");
            int failed = statement.executeUpdate("DELETE FROM " + TABLE_TIA_TEST_SUITES_FAILED
                    + " WHERE NOT EXISTS (SELECT 1 FROM " + TABLE_TIA_TEST_SUITE + " s WHERE s."
                    + COL_NAME + " = " + TABLE_TIA_TEST_SUITES_FAILED + "." + COL_TEST_SUITE_NAME + ")");

            log.info("Re-seed: removed {} unobserved suite(s), {} class row(s) and {} edge(s) of "
                            + "unobserved suites, {} orphan class row(s), {} orphan edge(s), {} "
                            + "unreferenced method(s) and {} failed entr(ies) for deleted suites.",
                    suites, classes, edges, orphanClasses, orphanEdges, methods, failed);
        }
    }

    /**
     * Clear the unsealed flag from every flagged suite on a caller-supplied connection, so the
     * clear can join the seal transaction. Restricted to flagged rows only as a cheap {@code WHERE}
     * filter so the update touches fewer rows than rewriting the whole table - it is deliberately
     * NOT scoped to suites this run wrote; see the seal-bundle javadoc in
     * {@link SealRepository#persistSealedRunData} for why the clear is unconditional across all
     * flagged suites.
     *
     * @param connection the connection to update on
     * @throws SQLException if the update fails
     */
    void clearUnsealedTestSuites(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            int cleared = statement.executeUpdate("UPDATE " + TABLE_TIA_TEST_SUITE
                    + " SET " + COL_UNSEALED + " = FALSE WHERE " + COL_UNSEALED + " = TRUE");
            log.debug("Cleared the unsealed flag from {} test suite(s).", cleared);
        }
    }

    /**
     * Rewrite the source method catalogue over its own connection. Implements {@link
     * DataStore#persistSourceMethods}.
     *
     * @param methodsTracked the full catalogue to store, keyed by method id
     */
    void persistSourceMethods(final Map<Integer, MethodImpactTracker> methodsTracked){
        long startTime = System.currentTimeMillis();
        Connection connection = getConnection();

        try {
            persistSourceMethods(connection, methodsTracked);
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }

        log.debug("Time to save the methods tracked data to disk (ms): " + (System.currentTimeMillis() - startTime));
    }

    /**
     * Upsert the given suites' rows and, where they carry coverage, their edges, over its own
     * connection. Implements {@link DataStore#persistTestSuites}.
     *
     * @param testSuites the suites to write, keyed by name
     */
    void persistTestSuites(final Map<String, TestSuiteTracker> testSuites){
        long startTime = System.currentTimeMillis();
        Connection connection = getConnection();

        try {
            persistTestSuites(connection, testSuites.values());
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }

        log.debug("Time to save the test suites tracked data to disk (ms): " + (System.currentTimeMillis() - startTime));
    }

    /**
     * Delete the named suites' rows, their coverage and their failed-suite entries, over its own
     * connection. Implements {@link DataStore#deleteTestSuites}.
     *
     * @param testSuites the names of the suites to delete
     */
    void deleteTestSuites(final Set<String> testSuites){
        long startTime = System.currentTimeMillis();
        Connection connection = getConnection();

        try {
            deleteTestSuites(connection, testSuites);
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }

        log.debug("Time to delete the removed test suites from disk (ms): " + (System.currentTimeMillis() - startTime));
    }

    /**
     * Delete the given suites on a caller-supplied connection, in one transaction: each suite's
     * coverage (its {@code tia_source_class_method} edges, then its {@code tia_source_class} rows),
     * its {@code tia_test_suites_failed} row, and its {@code tia_test_suite} row.
     *
     * <p>The coverage goes because nothing else would remove it: the mapping tables have no foreign
     * keys, and every read that turns coverage into suites joins back to {@code tia_test_suite}, so
     * a deleted suite's edges are unreachable but would otherwise keep its methods in the catalogue
     * - and in every changed-file lookup - until a re-seed swept them. Removing the edges leaves the
     * methods only the deleted suite covered unreferenced, so the next seal's catalogue rebuild
     * drops them. The failed row goes too: the failed set only ever loses a suite when that suite
     * executes, which a deleted suite never will, so a failed row left behind would force-select
     * the suite on every later build. Doing it all in one transaction means a crash cannot leave
     * coverage or a failed row behind without the suite row that the next run's deletion check would
     * find and retry. The coverage is deleted before the suite row, since its id is found by name.
     *
     * <p>Suites are processed in name order so that two distributed runners deleting the same
     * suites take their row locks in the same order rather than deadlocking each other.
     *
     * @param connection the connection to delete on; owns and commits/rolls back its own transaction
     * @param testSuites the suites to delete
     * @throws SQLException if any delete fails, after rolling back all of them
     */
    private void deleteTestSuites(Connection connection, final Set<String> testSuites) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        List<String> names = new ArrayList<>(testSuites);
        Collections.sort(names);
        String suiteIdByName = "(SELECT " + COL_ID + " FROM " + TABLE_TIA_TEST_SUITE
                + " WHERE " + COL_NAME + " = ?)";

        try (PreparedStatement deleteEdges = connection.prepareStatement(
                     "DELETE FROM " + TABLE_TIA_SOURCE_CLASS_METHOD + " WHERE " + COL_TIA_SOURCE_CLASS_ID
                             + " IN (SELECT " + COL_ID + " FROM " + TABLE_TIA_SOURCE_CLASS + " WHERE "
                             + COL_TIA_TEST_SUITE_ID + " = " + suiteIdByName + ")");
             PreparedStatement deleteClasses = connection.prepareStatement(
                     "DELETE FROM " + TABLE_TIA_SOURCE_CLASS + " WHERE " + COL_TIA_TEST_SUITE_ID
                             + " = " + suiteIdByName);
             PreparedStatement deleteSuite = connection.prepareStatement(
                     "DELETE FROM " + TABLE_TIA_TEST_SUITE + " WHERE " + COL_NAME + " = ?")) {
            for (String testSuite : names){
                log.debug("Deleting test suite: {}", testSuite);
                deleteEdges.setString(1, testSuite);
                deleteEdges.addBatch();
                deleteClasses.setString(1, testSuite);
                deleteClasses.addBatch();
                deleteSuite.setString(1, testSuite);
                deleteSuite.addBatch();
            }
            deleteEdges.executeBatch();
            deleteClasses.executeBatch();
            deleteSuite.executeBatch();
            deleteTestSuitesFailed(connection, testSuites);
            connection.commit();
        } catch (Exception e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                e.addSuppressed(rollbackEx);
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException restoreEx) {
                // best-effort restore - the connection is about to be closed by the caller
                log.debug("Failed to restore autoCommit on connection: {}", restoreEx.getMessage());
            }
        }
    }

    /**
     * Upsert the given suites' name/stats rows and, for mapping-update runs, their
     * suite-to-source-class/method edges. Every suite in {@code testSuites} gets a row write
     * regardless of whether it ran this time - the caller passes the whole merged suite map -
     * but the edges are only written for suites with non-empty
     * {@link TestSuiteTracker#getClassesImpacted()}, i.e. suites that actually have coverage this
     * run. For those suites, {@link #persistTestSuiteClasses} also flags the suite as
     * {@code unsealed} in the same per-suite transaction as its edge rewrite, so a crash mid-persist
     * can never leave a suite with rewritten edges and no flag - see the "Persist flow and crash
     * safety" chapter in {@code WIKI.md}.
     *
     * <p>{@code unsealed} is deliberately kept out of this method's own upsert column list: that
     * upsert touches every suite in {@code testSuites}, including ones with no coverage this run,
     * so if the flag were part of that write it would need read-modify-write logic to avoid
     * clobbering a value set by a concurrent writer. The flag write only ever sets the column to
     * {@code true}; only the seal ({@link #clearUnsealedTestSuites(Connection)}) may clear it.
     *
     * @param connection the connection to write on
     * @param testSuites the suites whose rows to persist; a no-op when empty
     * @throws SQLException if any write fails
     */
    private void persistTestSuites(Connection connection,
                                   Collection<TestSuiteTracker> testSuites) throws SQLException {
        if (testSuites.isEmpty()){
            return;
        }

        // The stats accumulate onto the stored row rather than replacing it, so the caller's
        // figures are this run's own contribution - see the javadoc above. unsealed is never part
        // of this statement; it is set only by persistTestSuiteClasses and cleared only by
        // clearUnsealedTestSuites. The statement is prepared once and re-bound per suite.
        String mergeSql = dialect.accumulatingTestSuiteUpsert(TABLE_TIA_TEST_SUITE, COL_NAME,
                COL_NUM_RUNS, COL_AVG_RUN_TIME, COL_NUM_SUCCESS_RUNS, COL_NUM_FAIL_RUNS,
                COL_DEVELOPER_DISABLED);

        // The class/edge inserts reuse two full-chunk multi-row prepared statements for the whole
        // persist, and assign tia_source_class ids application-side from a single atomically
        // reserved block so rows can be inserted INSERT_CHUNK at a time (one round trip per
        // chunk) instead of one per row, without colliding with a concurrent writer's ids.
        // Reserve exactly the ids this persist needs, in one atomic allocation, so a concurrent
        // writer cannot be handed the same range. See the "Persist flow and crash safety" chapter
        // in WIKI.md.
        int idsNeeded = countSourceClassRows(testSuites);
        long[] nextSourceClassId = new long[]{ idsNeeded > 0 ? allocateSourceClassIdBlock(connection, idsNeeded) : 0L };
        PreparedStatement classChunkPs = connection.prepareStatement(INSERT_SOURCE_CLASS_CHUNK_SQL);
        PreparedStatement edgeChunkPs = connection.prepareStatement(INSERT_SOURCE_CLASS_METHOD_CHUNK_SQL);

        PreparedStatement suitePs = connection.prepareStatement(mergeSql, Statement.RETURN_GENERATED_KEYS);
        try {
            for (TestSuiteTracker testSuite : testSuites){
                // The stats bound here are the run's own contribution, which the statement adds to
                // whatever the row currently holds.
                suitePs.setString(1, testSuite.getName());
                suitePs.setLong(2, testSuite.getTestStats().getNumRuns());
                suitePs.setLong(3, testSuite.getTestStats().getAvgRunTime());
                suitePs.setLong(4, testSuite.getTestStats().getNumSuccessRuns());
                suitePs.setLong(5, testSuite.getTestStats().getNumFailRuns());
                suitePs.setBoolean(6, testSuite.isDeveloperDisabled());

                suitePs.executeUpdate();

                // only update the source classes mapping for the test suite when mapping data
                // exists for this test run.
                if (!testSuite.getClassesImpacted().isEmpty()){
                    ResultSet rs = suitePs.getGeneratedKeys();
                    rs.next();
                    persistTestSuiteClasses(connection, rs.getLong(COL_ID),
                            testSuite.getClassesImpacted(), classChunkPs, edgeChunkPs, nextSourceClassId);
                }
            }
        } finally {
            suitePs.close();
            classChunkPs.close();
            edgeChunkPs.close();
        }
    }

    /**
     * Read the highest existing {@code tia_source_class} id, or {@code 0} when the table is empty.
     * Ids are auto-increment starting at 1, so {@code 0} unambiguously means "empty" - used to
     * derive the first application-assigned id ({@code result + 1}).
     *
     * @param connection the H2 connection
     * @return the maximum stored id, or {@code 0} when no rows exist
     * @throws SQLException if the query fails
     */
    private long readMaxSourceClassId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT MAX(" + COL_ID + ") FROM " + TABLE_TIA_SOURCE_CLASS)) {
            rs.next();
            long max = rs.getLong(1);
            return rs.wasNull() ? 0L : max;
        }
    }

    /**
     * Count the {@code tia_source_class} rows a persist will insert: one per impacted class of
     * every suite that has coverage this run. Suites with no coverage are skipped because their
     * existing rows are left untouched, exactly matching the condition guarding
     * {@code persistTestSuiteClasses}.
     *
     * @param testSuites the suites being persisted
     * @return the number of source-class ids the persist needs to reserve
     */
    private static int countSourceClassRows(Collection<TestSuiteTracker> testSuites){
        int count = 0;
        for (TestSuiteTracker testSuite : testSuites){
            if (!testSuite.getClassesImpacted().isEmpty()){
                count += testSuite.getClassesImpacted().size();
            }
        }
        return count;
    }

    /**
     * Reserve a contiguous block of {@code tia_source_class} ids for the calling writer. The
     * counter row is locked with {@code SELECT ... FOR UPDATE} and advanced in the same
     * transaction, so two writers persisting concurrently receive disjoint ranges rather than
     * both reading the same {@code MAX(id)} and colliding on the primary key.
     *
     * <p>On first use against a database created before this table existed, the counter is
     * seeded from {@code MAX(id) + 1} of the existing rows so already-assigned ids are never
     * handed out again.
     *
     * <p>A writer that dies after allocating leaves its block unused. That is a harmless gap in
     * the id space - ids carry no meaning beyond identity.
     *
     * <p>Does not call {@code ensureSchema} itself: every path that reaches this method does so
     * through a {@code JdbcDataStore} instance that has already run {@code ensureSchema} on the
     * same instance (it is the single schema-bootstrap entry point, called before both
     * {@code getTiaCore} and the full-load read that production callers use ahead of a mapping
     * persist), so the table is already guaranteed to exist.
     *
     * @param connection the connection to allocate on; its auto-commit state is restored before
     *                   returning
     * @param blockSize the number of ids to reserve; must be positive
     * @return the first id of the reserved block; the caller may use
     *         {@code [result, result + blockSize)}
     * @throws SQLException if the lock, read or update fails, or if the counter row is
     *                       unexpectedly missing after seeding
     */
    long allocateSourceClassIdBlock(Connection connection, int blockSize) throws SQLException {
        seedSourceClassIdBlockIfAbsent(connection);

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            long nextValue;
            String selectSql = "SELECT " + COL_NEXT_VALUE + " FROM " + TABLE_TIA_ID_BLOCK
                    + " WHERE " + COL_BLOCK_NAME + " = ? FOR UPDATE";
            try (PreparedStatement ps = connection.prepareStatement(selectSql)) {
                ps.setString(1, ID_BLOCK_SOURCE_CLASS);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("Missing " + TABLE_TIA_ID_BLOCK + " counter row for block '"
                                + ID_BLOCK_SOURCE_CLASS + "' - it should have been seeded before this select.");
                    }
                    nextValue = rs.getLong(1);
                }
            }

            String updateSql = "UPDATE " + TABLE_TIA_ID_BLOCK + " SET " + COL_NEXT_VALUE + " = ?"
                    + " WHERE " + COL_BLOCK_NAME + " = ?";
            try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
                ps.setLong(1, nextValue + blockSize);
                ps.setString(2, ID_BLOCK_SOURCE_CLASS);
                ps.executeUpdate();
            }

            connection.commit();
            return nextValue;
        } catch (SQLException e) {
            // Roll back on its own try/catch so a failure here (e.g. connection already broken)
            // doesn't replace the original exception - the original is what the caller needs to
            // diagnose the real cause.
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                e.addSuppressed(rollbackEx);
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException restoreEx) {
                // best-effort restore - the connection is about to be closed/reused by the caller
                log.debug("Failed to restore autoCommit on connection: {}", restoreEx.getMessage());
            }
        }
    }

    /**
     * Check whether the {@code tia_id_block} counter row for {@link TiaSchema#ID_BLOCK_SOURCE_CLASS}
     * already exists. Used both to short-circuit {@link #seedSourceClassIdBlockIfAbsent(Connection)}
     * before it does any work, and afterwards to tell a genuinely lost seed race (row now exists,
     * tolerate the failure) apart from a real failure (row still absent, rethrow) - checked by
     * re-querying rather than by matching a vendor-specific SQL state, so the distinction holds
     * across both H2 and Postgres.
     *
     * @param connection the connection to query on
     * @return {@code true} if the {@code tia_source_class} counter row is present
     * @throws SQLException if the existence query fails
     */
    private boolean sourceClassIdBlockRowExists(Connection connection) throws SQLException {
        String sql = "SELECT 1 FROM " + TABLE_TIA_ID_BLOCK + " WHERE " + COL_BLOCK_NAME + " = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ID_BLOCK_SOURCE_CLASS);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Insert the {@code tia_source_class} counter row if it is not already present, seeding it
     * from the highest existing id so a database populated before the id-block table existed
     * continues from where it left off. Short-circuits on the existence check before doing any
     * work, so the steady-state path (row already seeded) never pays for the {@code MAX(id)}
     * scan over {@code tia_source_class}.
     *
     * <p>The conditional insert makes the write itself a no-op once another writer has already
     * seeded the row. If the insert throws, the row's existence is re-checked: a lost race
     * (another writer seeded it first, so the row now exists) is tolerated and logged; anything
     * else - the row still doesn't exist - is a genuine failure (permissions, connectivity, a
     * vendor-specific constraint error) and is rethrown so it isn't silently discarded.
     *
     * @param connection the connection to seed on
     * @throws SQLException if reading the existing maximum id fails, or if the conditional insert
     *                       fails for a reason other than a lost seed race
     */
    private void seedSourceClassIdBlockIfAbsent(Connection connection) throws SQLException {
        if (sourceClassIdBlockRowExists(connection)) {
            return;
        }

        long seed = readMaxSourceClassId(connection) + 1;
        String sql = "INSERT INTO " + TABLE_TIA_ID_BLOCK + " (" + COL_BLOCK_NAME + ", " + COL_NEXT_VALUE + ")"
                + " SELECT ?, ? WHERE NOT EXISTS ("
                + "SELECT 1 FROM " + TABLE_TIA_ID_BLOCK + " WHERE " + COL_BLOCK_NAME + " = ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ID_BLOCK_SOURCE_CLASS);
            ps.setLong(2, seed);
            ps.setString(3, ID_BLOCK_SOURCE_CLASS);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (!sourceClassIdBlockRowExists(connection)) {
                throw e;
            }
            log.debug("Seed of the {} id block lost a race with another writer - continuing.",
                    ID_BLOCK_SOURCE_CLASS);
        }
    }

    /**
     * Re-persist one suite's source-class -> method mapping using the shared, reused prepared
     * statements, and flag the suite as {@code unsealed} in the same transaction. Deletes the
     * suite's existing classes and edges (a no-op on a seed where the table is empty), assigns
     * each class an application-side id from {@code nextId}, and batch-inserts the class rows and
     * their edge rows. The {@code unsealed} flag write is issued before the commit so it lands
     * atomically with the edge rewrite: a crash partway through a persist covering many suites
     * then leaves every already-processed suite flagged, never edges-rewritten-but-unflagged,
     * which is the one direction test selection cannot detect. See the "Persist flow and crash
     * safety" chapter in {@code WIKI.md}. Kept inside a per-suite transaction so a failure leaves
     * the suite's previous mapping (and flag) intact.
     *
     * @param connection the H2 connection
     * @param testSuiteId the id of the suite these classes belong to; also used to flag it as unsealed
     * @param sourceClasses the suite's impacted classes (each with its method-id set)
     * @param classChunkPs reused full-chunk multi-row insert for {@code tia_source_class}
     * @param edgeChunkPs reused full-chunk multi-row insert for {@code tia_source_class_method}
     * @param nextId one-element holder for the next application-assigned class id; advanced in place
     * @throws SQLException if any insert/delete/flag write fails (the suite's transaction is rolled back first)
     */
    private void persistTestSuiteClasses(Connection connection, long testSuiteId,
                                         List<ClassImpactTracker> sourceClasses,
                                         PreparedStatement classChunkPs, PreparedStatement edgeChunkPs,
                                         long[] nextId) throws SQLException {
        if (sourceClasses.isEmpty()){
            return;
        }

        // Materialise this suite's class rows and edge rows, then insert them with chunked multi-row
        // statements. Per-suite, so the buffers are bounded by the suite's size.
        List<Object[]> classRows = new ArrayList<>(sourceClasses.size());
        List<Object[]> edgeRows = new ArrayList<>();
        for (ClassImpactTracker sourceClass : sourceClasses){
            long classId = nextId[0]++;
            classRows.add(new Object[]{ classId, testSuiteId, sourceClass.getSourceFilename() });
            for (Integer methodId : sourceClass.getMethodsImpacted()){
                edgeRows.add(new Object[]{ classId, methodId });
            }
        }

        // Per-test-suite atomicity: the DELETE-then-INSERT for one suite's classes + its
        // class-method edges is wrapped in one transaction, so a failure midway through the
        // re-insert leaves the suite's previous mappings intact rather than half-wiped.
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try (Statement statement = connection.createStatement()) {
            // delete the existing source class and methods before inserting the new data from the test run
            String deleteClassMethodSql = "DELETE FROM " + TABLE_TIA_SOURCE_CLASS_METHOD + " WHERE " + COL_TIA_SOURCE_CLASS_ID +
                    " IN (SELECT " + COL_ID + " FROM " + TABLE_TIA_SOURCE_CLASS +
                    " WHERE " + COL_TIA_TEST_SUITE_ID + " = " + testSuiteId + ")";
            statement.executeUpdate(deleteClassMethodSql);

            String deleteClassSql = "DELETE FROM " + TABLE_TIA_SOURCE_CLASS + " WHERE " + COL_TIA_TEST_SUITE_ID + " = " + testSuiteId;
            statement.executeUpdate(deleteClassSql);

            insertRowsChunked(connection, classChunkPs, TABLE_TIA_SOURCE_CLASS, SOURCE_CLASS_COLS, 3, classRows);
            insertRowsChunked(connection, edgeChunkPs, TABLE_TIA_SOURCE_CLASS_METHOD, SOURCE_CLASS_METHOD_COLS, 2, edgeRows);

            // Flag this suite as unsealed in the same transaction as its edge rewrite, before the
            // commit, so the two can never land apart: either both are visible after a crash or
            // neither is. This is the only statement in the class that sets the column to TRUE;
            // only the seal (clearUnsealedTestSuites) may clear it. Reuses the Statement already
            // open for the two DELETEs above (keyed off testSuiteId, already trusted for them)
            // rather than preparing a new PreparedStatement per suite, which would add a round
            // trip on the mapping persist's hot path.
            statement.executeUpdate("UPDATE " + TABLE_TIA_TEST_SUITE + " SET " + COL_UNSEALED
                    + " = TRUE WHERE " + COL_ID + " = " + testSuiteId);

            connection.commit();
        } catch (Exception e) {
            // Catch Exception (not just SQLException) so any failure in this block triggers the
            // rollback. Tia treats any exception in this class as stop-the-world: roll back, then
            // re-throw so the failure bubbles up rather than continuing with a half-written DB.
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                e.addSuppressed(rollbackEx);
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException restoreEx) {
                // best-effort restore - the connection is about to be closed by the caller
                log.debug("Failed to restore autoCommit on connection: {}", restoreEx.getMessage());
            }
        }
    }

    /**
     * Insert {@code rows} into {@code table} using multi-row inserts: full {@value #INSERT_CHUNK}-row
     * chunks go through the reused {@code fullChunkPs} (one round trip per chunk), and any remainder
     * (< {@value #INSERT_CHUNK} rows) is inserted with a single one-off statement sized to it. This
     * collapses the per-row round trips that dominate a remote-server persist.
     *
     * @param connection the H2 connection
     * @param fullChunkPs the reused statement carrying exactly {@value #INSERT_CHUNK} value tuples
     * @param table the target table (for building the remainder statement)
     * @param columnsCsv the column list (for building the remainder statement)
     * @param paramsPerRow the number of columns per row
     * @param rows the row values, each an {@code Object[]} of length {@code paramsPerRow}
     * @throws SQLException if an insert fails
     */
    private void insertRowsChunked(Connection connection, PreparedStatement fullChunkPs, String table,
                                   String columnsCsv, int paramsPerRow, List<Object[]> rows) throws SQLException {
        int total = rows.size();
        int index = 0;
        while (total - index >= INSERT_CHUNK){
            bindRows(fullChunkPs, rows, index, INSERT_CHUNK, paramsPerRow);
            fullChunkPs.executeUpdate();
            index += INSERT_CHUNK;
        }
        int remainder = total - index;
        if (remainder > 0){
            String remainderSql = buildMultiRowInsertSql(table, columnsCsv, paramsPerRow, remainder);
            try (PreparedStatement remainderPs = connection.prepareStatement(remainderSql)) {
                bindRows(remainderPs, rows, index, remainder, paramsPerRow);
                remainderPs.executeUpdate();
            }
        }
    }

    /**
     * Bind {@code count} rows from {@code rows} (starting at {@code from}) into a multi-row insert
     * statement, laying each row's values out consecutively across the placeholders.
     *
     * @param ps the multi-row insert statement (sized to hold at least {@code count} tuples)
     * @param rows the row values
     * @param from the index of the first row to bind
     * @param count the number of rows to bind
     * @param paramsPerRow the number of columns per row
     * @throws SQLException if binding fails
     */
    private void bindRows(PreparedStatement ps, List<Object[]> rows, int from, int count, int paramsPerRow) throws SQLException {
        for (int r = 0; r < count; r++){
            Object[] values = rows.get(from + r);
            for (int p = 0; p < paramsPerRow; p++){
                ps.setObject(r * paramsPerRow + p + 1, values[p]);
            }
        }
    }

    /**
     * Apply an incremental failed-suite update on a caller-supplied connection, in one
     * transaction: delete the stored row of every suite in {@code suitesToClear}, then insert every
     * suite in {@code suitesFailed} that is not already stored. Only the named suites' rows are
     * touched, so two runners of a distributed build writing for disjoint groups at the same time
     * cannot discard each other's rows - the whole-table clear-and-rewrite this replaces could. Both
     * statements bind the suite names as parameters, in chunks of {@value JdbcRepository#IN_CLAUSE_CHUNK_SIZE}.
     *
     * <p>The insert goes through {@link SqlDialect#upsert} keyed on the suite name, which for this
     * key-only table is an insert-if-absent on both vendors. A plain {@code INSERT} would turn a
     * name another writer had just stored into a primary-key violation and fail the persist.
     *
     * @param connection the connection to write on; owns and commits/rolls back its own transaction
     * @param suitesToClear the suites whose stored rows are deleted; may be empty
     * @param suitesFailed the suites to store as failed; may be empty
     * @throws SQLException if the delete or the insert fails, after rolling back both
     */
    private void persistTestSuitesFailed(final Connection connection, final Set<String> suitesToClear,
                                         final Set<String> suitesFailed) throws SQLException {
        if (suitesToClear.isEmpty() && suitesFailed.isEmpty()){
            return;
        }

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try {
            deleteTestSuitesFailed(connection, suitesToClear);

            if (!suitesFailed.isEmpty()){
                String insertSql = dialect.upsert(TABLE_TIA_TEST_SUITES_FAILED,
                        Collections.singletonList(COL_TEST_SUITE_NAME), Collections.singletonList(COL_TEST_SUITE_NAME));
                log.debug("Persisting failed test suites {}: {}", suitesFailed, insertSql);
                try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                    for (String suite : suitesFailed){
                        insert.setString(1, suite);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
            }

            connection.commit();
        } catch (Exception e) {
            // Catch Exception (not just SQLException) so any failure in this block still triggers
            // the rollback. Tia treats any exception in this class as a stop-the-world condition:
            // roll back, then re-throw so the failure bubbles up rather than continuing with a
            // half-written DB.
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                e.addSuppressed(rollbackEx);
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException restoreEx) {
                // best-effort restore - the connection is about to be closed by the caller
                log.debug("Failed to restore autoCommit on connection: {}", restoreEx.getMessage());
            }
        }
    }

    /**
     * Delete the {@code tia_test_suites_failed} rows of the given suites on a caller-supplied
     * connection, joining whatever transaction the caller has open. Issues one {@code DELETE ...
     * IN (...)} per {@value JdbcRepository#IN_CLAUSE_CHUNK_SIZE} names.
     *
     * @param connection the connection to delete on; the caller owns the transaction
     * @param suites the suites whose failed rows to delete; a no-op when empty
     * @throws SQLException if a delete fails
     */
    private void deleteTestSuitesFailed(final Connection connection, final Set<String> suites) throws SQLException {
        List<String> names = new ArrayList<>(suites);
        for (int from = 0; from < names.size(); from += IN_CLAUSE_CHUNK_SIZE){
            List<String> chunk = names.subList(from, Math.min(from + IN_CLAUSE_CHUNK_SIZE, names.size()));
            String deleteSql = "DELETE FROM " + TABLE_TIA_TEST_SUITES_FAILED + " WHERE " + COL_TEST_SUITE_NAME
                    + " IN (" + String.join(", ", Collections.nCopies(chunk.size(), "?")) + ")";
            try (PreparedStatement delete = connection.prepareStatement(deleteSql)) {
                for (int i = 0; i < chunk.size(); i++){
                    delete.setString(i + 1, chunk.get(i));
                }
                delete.executeUpdate();
            }
        }
    }

    /**
     * Rewrite {@code tia_source_method} on a caller-supplied connection, owning and
     * committing/rolling back its own transaction around {@link #writeSourceMethods(Connection, Map)}.
     * Used by the standalone {@link #persistSourceMethods(Map)} entry point; the seal path instead
     * calls {@link #writeSourceMethods(Connection, Map)} directly so the write can join the seal's
     * own transaction.
     *
     * @param connection the connection to write on; its auto-commit state is restored afterwards
     * @param sourceMethods the full method catalogue to write, keyed by method id; a null map is
     *                      a no-op
     * @throws SQLException if the clear-out or the insert fails
     */
    private void persistSourceMethods(Connection connection, Map<Integer, MethodImpactTracker> sourceMethods) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try {
            writeSourceMethods(connection, sourceMethods);
            connection.commit();
        } catch (Exception e) {
            // Catch Exception (not just SQLException) so any failure in this block - including
            // an NPE while building the insert SQL - still triggers the rollback. Tia treats
            // any exception in this class as a stop-the-world condition: roll back, then
            // re-throw so the failure bubbles up rather than continuing with a half-written DB.
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                e.addSuppressed(rollbackEx);
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException restoreEx) {
                // best-effort restore - the connection is about to be closed by the caller
                log.debug("Failed to restore autoCommit on connection: {}", restoreEx.getMessage());
            }
        }
    }

    /**
     * Rewrite {@code tia_source_method} on a caller-supplied connection without managing a
     * transaction, so the write can either own one ({@link #persistSourceMethods(Connection, Map)})
     * or join the caller's ({@link #persistSealedRunData(SealedRunData)}).
     *
     * <p>The clear-out and the {@code INSERT} must end up in the same transaction whichever
     * caller runs them, which is why this clears the table with
     * {@link SqlDialect#clearTableTransactionallySql} rather than a hard-coded statement: the
     * two vendors need different SQL to give that guarantee (see
     * {@link org.tiatesting.core.persistence.dialect.H2Dialect} and
     * {@link org.tiatesting.core.persistence.dialect.PostgresDialect}) and a hard-coded
     * {@code TRUNCATE TABLE} would silently escape the transaction on H2, unable to be undone if
     * the insert (or a later step in the same seal) failed.
     *
     * <p>The {@code INSERT} is logged as a method count, not as the statement: the statement inlines
     * every row, so it grows with the catalogue - several megabytes in one DEBUG line at tens of
     * thousands of methods. See the "Logging conventions" chapter in {@code WIKI.md}.
     *
     * @param connection the connection to write on; its auto-commit state is not changed
     * @param sourceMethods the full method catalogue to write, keyed by method id; a null map is
     *                      a no-op
     * @throws SQLException if the clear-out or the insert fails
     */
    void writeSourceMethods(Connection connection, Map<Integer, MethodImpactTracker> sourceMethods) throws SQLException {
        if (sourceMethods == null){
            return;
        }

        try (Statement statement = connection.createStatement()) {
            String clearSql = dialect.clearTableTransactionallySql(TABLE_TIA_SOURCE_METHOD);
            log.debug("Clearing indexed source methods: {}", clearSql);
            statement.executeUpdate(clearSql);

            if (sourceMethods.isEmpty()){
                return;
            }

            StringBuilder insertSqlBuilder = new StringBuilder("INSERT INTO " + TABLE_TIA_SOURCE_METHOD + " (" +
                    COL_ID + ", " +
                    COL_METHOD_NAME + ", " +
                    COL_LINE_NUMBER_START + ", " +
                    COL_LINE_NUMBER_END + ", " +
                    COL_LINE_RANGES + ", " +
                    COL_EXECUTED_RUN_COUNT + ", " +
                    COL_TRIGGERED_RUN_COUNT + ") values ");

            for (Map.Entry<Integer, MethodImpactTracker> entry : sourceMethods.entrySet()){
                // The formatted ranges are digits, '-' and ',' only, so they are safe to inline.
                String lineRanges = LineRanges.format(entry.getValue().getLineRanges());
                insertSqlBuilder.append("(" + entry.getKey() + ", '" +
                        entry.getValue().getMethodName() + "', " +
                        entry.getValue().getLineNumberStart() + ", " +
                        entry.getValue().getLineNumberEnd() + ", " +
                        (lineRanges == null ? "NULL" : "'" + lineRanges + "'") + ", " +
                        entry.getValue().getExecutedRunCount() + ", " +
                        entry.getValue().getTriggeredRunCount() + "),");
            }
            String insertSql = insertSqlBuilder.toString();
            insertSql = insertSql.substring(0, insertSql.length()-1);

            log.debug("Persisting {} indexed source method(s) to {}", sourceMethods.size(),
                    TABLE_TIA_SOURCE_METHOD);
            statement.executeUpdate(insertSql);
        }
    }
}
