package org.tiatesting.core.persistence;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.LibraryPublish;
import org.tiatesting.core.model.LineRanges;
import org.tiatesting.core.model.MethodIdSet;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.PendingLibraryForcedSelection;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.CoreStatsIncrement;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestRunTrigger;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.connection.ConnectionProvider;
import org.tiatesting.core.persistence.dialect.SqlDialect;

import java.sql.*;
import java.util.*;

import static org.tiatesting.core.persistence.JdbcRows.*;
import static org.tiatesting.core.persistence.TiaSchema.*;

public class JdbcDataStore implements DataStore {
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
    // Upper bound on parameterized IN-clause size for the targeted queries. Keeps each
    // statement well under H2's parameter limits and bounds per-statement parse cost;
    // larger inputs are split into multiple queries and merged client-side.
    private static final int IN_CLAUSE_CHUNK_SIZE = 1000;
    private final Logger log = LoggerFactory.getLogger(JdbcDataStore.class);
    private final SqlDialect dialect;
    private final ConnectionProvider connectionProvider;
    private final String schema;
    // Memoizes the per-branch schema's CREATE SCHEMA IF NOT EXISTS: once this instance has
    // created it, later connections only need the (cheap) SET SCHEMA / search_path selection.
    private boolean branchSchemaCreated;
    private final SchemaManager schemaManager;
    private final TestRunHistoryRepository testRunHistoryRepository;
    private final LibraryRepository libraryRepository;
    private final DistributedRunRepository distributedRunRepository;
    private final DistributedRunGroupRepository distributedRunGroupRepository;
    private final DistributedMethodStagingRepository distributedMethodStagingRepository;

    /**
     * Construct a datastore over a vendor-neutral SQL dialect and connection provider. The dialect
     * supplies the vendor-varying SQL (upserts, identity DDL, table-existence lookup, and the
     * per-branch schema create/select statements) and the provider owns connection acquisition and
     * lifecycle (URL building, server-mode retry, and the close/shutdown behaviour). Every
     * connection this instance hands out is pinned to {@code schema} - see the per-branch schema
     * WIKI chapter.
     *
     * @param dialect the SQL dialect that renders vendor-specific statements
     * @param connectionProvider the connection provider that opens and closes connections
     * @param schema the per-branch schema to create (if needed) and select on every connection;
     *               {@code null}/blank leaves the connection's default schema untouched
     */
    public JdbcDataStore(SqlDialect dialect, ConnectionProvider connectionProvider, String schema){
        this.dialect = dialect;
        this.connectionProvider = connectionProvider;
        this.schema = schema;
        this.schemaManager = new SchemaManager(dialect, this::getConnection, connectionProvider.jdbcUrl());
        this.testRunHistoryRepository = new TestRunHistoryRepository(dialect, this::getConnection, schemaManager);
        this.libraryRepository = new LibraryRepository(dialect, this::getConnection, schemaManager);
        this.distributedRunRepository = new DistributedRunRepository(dialect, this::getConnection, schemaManager);
        this.distributedRunGroupRepository = new DistributedRunGroupRepository(dialect, this::getConnection, schemaManager);
        this.distributedMethodStagingRepository = new DistributedMethodStagingRepository(dialect, this::getConnection, schemaManager);

        String schemaDescription = (schema == null || schema.trim().isEmpty())
                ? "the connection's default schema"
                : "schema '" + schema + "'";
        log.info("Using {}, with {}.", connectionProvider.connectionSummary(), schemaDescription);
    }

    /**
     * Read the full Tia data from the database, bootstrapping the schema on first contact.
     *
     * @return the fully loaded Tia DB
     */
    @Override
    public TiaData getTiaData() {
        return readTiaDataFromDB();
    }

    @Override
    public TiaData getTiaCore(){
        TiaData tiaData;
        Connection connection = getConnection();

        try {
            // The targeted select-tests path reads the core row before any full load has run,
            // so this may be the first ever contact with the DB - bootstrap the schema first.
            schemaManager.ensureSchema(connection);
            tiaData = getCoreData(connection);
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }

        return tiaData;
    }

    @Override
    public Map<String, TestSuiteTracker> getTestSuitesTracked(){
        Map<String, TestSuiteTracker> testSuitesTracked;
        Connection connection = getConnection();

        try {
            testSuitesTracked = getTestSuitesData(connection, false);
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }

        return testSuitesTracked;
    }

    /**
     * {@inheritDoc} One indexed join per {@value #IN_CLAUSE_CHUNK_SIZE} suite names - suite by
     * name, its class rows by suite id, their edges by class id - so the cost scales with the
     * named suites' coverage, not the size of the mapping.
     *
     * @param suiteNames the suites whose coverage to read
     * @return each named suite's stored class trackers, keyed by suite name
     */
    @Override
    public Map<String, List<ClassImpactTracker>> readTestSuiteCoverage(final Set<String> suiteNames){
        Map<String, Map<String, ClassImpactTracker>> classesBySuite = new HashMap<>();
        if (suiteNames.isEmpty()){
            return new HashMap<>();
        }

        Connection connection = getConnection();
        try {
            List<String> names = new ArrayList<>(suiteNames);
            for (int from = 0; from < names.size(); from += IN_CLAUSE_CHUNK_SIZE){
                List<String> chunk = names.subList(from, Math.min(from + IN_CLAUSE_CHUNK_SIZE, names.size()));
                String sql = "SELECT s." + COL_NAME + " AS suite_name, c." + COL_SOURCE_FILENAME
                        + " AS class_source_filename, cm." + COL_TIA_SOURCE_METHOD_ID + " AS method_id"
                        + " FROM " + TABLE_TIA_TEST_SUITE + " s"
                        + " JOIN " + TABLE_TIA_SOURCE_CLASS + " c ON c." + COL_TIA_TEST_SUITE_ID + " = s." + COL_ID
                        + " JOIN " + TABLE_TIA_SOURCE_CLASS_METHOD + " cm ON cm." + COL_TIA_SOURCE_CLASS_ID + " = c." + COL_ID
                        + " WHERE s." + COL_NAME + " IN (" + String.join(", ", Collections.nCopies(chunk.size(), "?")) + ")";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    for (int i = 0; i < chunk.size(); i++){
                        ps.setString(i + 1, chunk.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()){
                            String filename = rs.getString("class_source_filename");
                            classesBySuite.computeIfAbsent(rs.getString("suite_name"), k -> new HashMap<>())
                                    .computeIfAbsent(filename, k -> new ClassImpactTracker(k, new MethodIdSet()))
                                    .getMethodsImpacted().add(rs.getInt("method_id"));
                        }
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

        Map<String, List<ClassImpactTracker>> coverage = new HashMap<>();
        classesBySuite.forEach((suite, classes) -> coverage.put(suite, new ArrayList<>(classes.values())));
        return coverage;
    }

    @Override
    public Map<Integer, MethodImpactTracker> getMethodsTracked(){
        Map<Integer, MethodImpactTracker> methodsTracked;
        Connection connection = getConnection();

        try {
            methodsTracked = getMethodsTracked(connection);
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }

        return methodsTracked;
    }

    @Override
    public Set<Integer> getUniqueMethodIdsTracked(){
        Set<Integer> methodIdsTracked = new HashSet<>();
        Connection connection = getConnection();

        try {
            String sql = "SELECT DISTINCT " + COL_TIA_SOURCE_METHOD_ID + " FROM " + TABLE_TIA_SOURCE_CLASS_METHOD;
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql);

            while(resultSet.next()){
                methodIdsTracked.add(resultSet.getInt(COL_TIA_SOURCE_METHOD_ID));
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

        return methodIdsTracked;
    }

    /**
     * Targeted changed-files-to-tracked-methods read: resolve a set of stored source-file keys to their tracked
     * methods with one indexed query per {@value #IN_CLAUSE_CHUNK_SIZE}-key chunk, instead
     * of loading the full suite-to-method mapping. {@code SELECT DISTINCT} collapses the
     * per-covering-suite duplication of (file, method) pairs in {@code tia_source_class} so
     * the result (and the bytes on the wire in server mode) stays proportional to the
     * methods in the requested files, not to the suites covering them.
     *
     * @param sourceFilenames the mapping keys of the source files to look up
     * @return map of source filename to (method id to method tracker); empty when the input
     *         is null or empty
     */
    @Override
    public Map<String, Map<Integer, MethodImpactTracker>> getMethodsTrackedForFiles(final Set<String> sourceFilenames){
        Map<String, Map<Integer, MethodImpactTracker>> methodsByFile = new HashMap<>();
        if (sourceFilenames == null || sourceFilenames.isEmpty()){
            return methodsByFile;
        }

        try (Connection connection = getConnection()){
            schemaManager.ensureSchema(connection);
            List<String> filenames = new ArrayList<>(sourceFilenames);
            for (int from = 0; from < filenames.size(); from += IN_CLAUSE_CHUNK_SIZE){
                List<String> chunk = filenames.subList(from, Math.min(from + IN_CLAUSE_CHUNK_SIZE, filenames.size()));
                queryMethodsTrackedForFiles(connection, chunk, methodsByFile);
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }

        return methodsByFile;
    }

    /**
     * Run the changed-files-to-tracked-methods query for one chunk of source-file keys and merge the rows into the
     * caller's result map. Parameterized {@code IN} placeholders keep repo-derived filenames
     * out of the SQL text.
     *
     * @param connection the open H2 connection to query on
     * @param filenames the chunk of source-file mapping keys (sized within the IN-clause limit)
     * @param methodsByFile the result map to merge rows into, keyed by file then method id
     * @throws SQLException if the query fails
     */
    private void queryMethodsTrackedForFiles(Connection connection, List<String> filenames,
                                             Map<String, Map<Integer, MethodImpactTracker>> methodsByFile) throws SQLException {
        String placeholders = String.join(", ", Collections.nCopies(filenames.size(), "?"));
        String sql = "SELECT DISTINCT sc." + COL_SOURCE_FILENAME + " AS class_source_filename, " +
                "sm." + COL_ID + " AS method_id, sm." + COL_METHOD_NAME + ", " +
                "sm." + COL_LINE_NUMBER_START + ", sm." + COL_LINE_NUMBER_END + ", sm." + COL_LINE_RANGES + " " +
                "FROM " + TABLE_TIA_SOURCE_CLASS + " sc " +
                "JOIN " + TABLE_TIA_SOURCE_CLASS_METHOD + " scm ON scm." + COL_TIA_SOURCE_CLASS_ID + " = sc." + COL_ID + " " +
                "JOIN " + TABLE_TIA_SOURCE_METHOD + " sm ON sm." + COL_ID + " = scm." + COL_TIA_SOURCE_METHOD_ID + " " +
                "WHERE sc." + COL_SOURCE_FILENAME + " IN (" + placeholders + ")";

        try (PreparedStatement statement = connection.prepareStatement(sql)){
            int paramIndex = 1;
            for (String filename : filenames){
                statement.setString(paramIndex++, filename);
            }

            try (ResultSet resultSet = statement.executeQuery()){
                while (resultSet.next()){
                    String sourceFilename = resultSet.getString("class_source_filename");
                    int methodId = resultSet.getInt("method_id");
                    MethodImpactTracker methodTracker = readMethodTracker(resultSet);
                    methodsByFile.computeIfAbsent(sourceFilename, key -> new HashMap<>()).put(methodId, methodTracker);
                }
            }
        }
    }

    /**
     * Targeted methods-to-covering-suites read: resolve a set of impacted method ids to the names of the test
     * suites covering them with one indexed query per {@value #IN_CLAUSE_CHUNK_SIZE}-id
     * chunk, instead of building the full in-memory method-to-suites reverse index.
     *
     * @param methodIds the tracked method ids to find covering test suites for
     * @return map of method id to covering test-suite names; empty when the input is null
     *         or empty
     */
    @Override
    public Map<Integer, Set<String>> getTestSuitesForMethods(final Set<Integer> methodIds){
        Map<Integer, Set<String>> suitesByMethodId = new HashMap<>();
        if (methodIds == null || methodIds.isEmpty()){
            return suitesByMethodId;
        }

        try (Connection connection = getConnection()){
            schemaManager.ensureSchema(connection);
            List<Integer> ids = new ArrayList<>(methodIds);
            for (int from = 0; from < ids.size(); from += IN_CLAUSE_CHUNK_SIZE){
                List<Integer> chunk = ids.subList(from, Math.min(from + IN_CLAUSE_CHUNK_SIZE, ids.size()));
                queryTestSuitesForMethods(connection, chunk, suitesByMethodId);
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }

        return suitesByMethodId;
    }

    /**
     * Run the methods-to-covering-suites query for one chunk of method ids and merge the rows into the caller's
     * result map. {@code SELECT DISTINCT} collapses duplicate (method, suite) pairs that occur
     * when a suite covers the same method through multiple source classes.
     *
     * @param connection the open H2 connection to query on
     * @param methodIds the chunk of method ids (sized within the IN-clause limit)
     * @param suitesByMethodId the result map to merge rows into, keyed by method id
     * @throws SQLException if the query fails
     */
    private void queryTestSuitesForMethods(Connection connection, List<Integer> methodIds,
                                           Map<Integer, Set<String>> suitesByMethodId) throws SQLException {
        String placeholders = String.join(", ", Collections.nCopies(methodIds.size(), "?"));
        String sql = "SELECT DISTINCT scm." + COL_TIA_SOURCE_METHOD_ID + " AS method_id, " +
                "ts." + COL_NAME + " AS suite_name " +
                "FROM " + TABLE_TIA_SOURCE_CLASS_METHOD + " scm " +
                "JOIN " + TABLE_TIA_SOURCE_CLASS + " sc ON sc." + COL_ID + " = scm." + COL_TIA_SOURCE_CLASS_ID + " " +
                "JOIN " + TABLE_TIA_TEST_SUITE + " ts ON ts." + COL_ID + " = sc." + COL_TIA_TEST_SUITE_ID + " " +
                "WHERE scm." + COL_TIA_SOURCE_METHOD_ID + " IN (" + placeholders + ")";

        try (PreparedStatement statement = connection.prepareStatement(sql)){
            int paramIndex = 1;
            for (Integer methodId : methodIds){
                statement.setInt(paramIndex++, methodId);
            }

            try (ResultSet resultSet = statement.executeQuery()){
                while (resultSet.next()){
                    int methodId = resultSet.getInt("method_id");
                    String suiteName = resultSet.getString("suite_name");
                    suitesByMethodId.computeIfAbsent(methodId, key -> new HashSet<>()).add(suiteName);
                }
            }
        }
    }

    @Override
    public int getNumTestSuites(){
        int numTestSuites = 0;
        Connection connection = getConnection();

        try {
            String sql = "SELECT COUNT(*) FROM " + TABLE_TIA_TEST_SUITE;
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql);

            if (resultSet.next()){
                numTestSuites = resultSet.getInt(1);
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

        return numTestSuites;
    }

    @Override
    public int getNumSourceMethods(){
        int numSourceMethods = 0;
        Connection connection = getConnection();

        try {
            String sql = "SELECT COUNT(*) FROM " + TABLE_TIA_SOURCE_METHOD;
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql);

            if (resultSet.next()){
                numSourceMethods = resultSet.getInt(1);
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

        return numSourceMethods;
    }

    @Override
    public Set<String> getTestSuitesFailed(){
        Set<String> testSuitesFailed;
        Connection connection = getConnection();

        try {
            testSuitesFailed = getTestSuitesFailed(connection);
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }

        return testSuitesFailed;
    }

    @Override
    public void persistCoreData(final TiaData tiaData){
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
    @Override
    public void persistSealedRunData(final SealedRunData sealedRunData){
        long startTime = System.currentTimeMillis();
        Connection connection = getConnection();

        try {
            // H2 implicitly commits on DDL, so this must run before setAutoCommit(false) - running
            // it inside the transaction would silently break atomicity.
            schemaManager.ensureLibraryTableExists(connection);
            connection.setAutoCommit(false);

            try {
                writeSourceMethods(connection, sealedRunData.getMethodsTracked());

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
                    clearMappingNotRewrittenByThisRun(connection);
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
                clearUnsealedTestSuites(connection);

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
     * {@inheritDoc} Opens a connection and delegates to {@link #persistTestSuitesFailed(Connection,
     * Set, Set)}, which owns the transaction.
     *
     * @param suitesToClear the suites whose stored failed state this run supersedes
     * @param suitesFailed the suites whose latest execution in this run failed
     */
    @Override
    public void persistTestSuitesFailed(final Set<String> suitesToClear, final Set<String> suitesFailed){
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
    @Override
    public void clearUnsealedTestSuites(){
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
     * Clear the unsealed flag from every flagged suite on a caller-supplied connection, so the
     * clear can join the seal transaction. Restricted to flagged rows only as a cheap {@code WHERE}
     * filter so the update touches fewer rows than rewriting the whole table - it is deliberately
     * NOT scoped to suites this run wrote; see the seal-bundle javadoc in
     * {@link #persistSealedRunData} for why the clear is unconditional across all flagged suites.
     *
     * @param connection the connection to update on
     * @throws SQLException if the update fails
     */
    /**
     * Re-seed clear-out: delete every piece of mapping data this run did not just rewrite, inside
     * the seal's transaction. A suite this run executed had its edges rewritten and its unsealed
     * flag set by persistTestSuiteClasses, so the flag is the "observed by this run" marker.
     * Unobserved suites are deleted with their classes and edges, except developer-disabled ones,
     * which keep their row, flag and stats and lose only their edges. Orphan class, edge and method
     * rows - from earlier crashes, or suite deletions that never cascaded - are swept across the
     * whole mapping, and failed-suite entries naming a suite that no longer exists are pruned. Core
     * stats, history, library tables and id blocks are untouched. See the "Forced runs and
     * re-seed" chapter in {@code WIKI.md}.
     *
     * @param connection the seal's connection, already inside its transaction
     * @throws SQLException if any delete fails; the caller rolls the whole seal back
     */
    private void clearMappingNotRewrittenByThisRun(Connection connection) throws SQLException {
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

    private void clearUnsealedTestSuites(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            int cleared = statement.executeUpdate("UPDATE " + TABLE_TIA_TEST_SUITE
                    + " SET " + COL_UNSEALED + " = FALSE WHERE " + COL_UNSEALED + " = TRUE");
            log.debug("Cleared the unsealed flag from {} test suite(s).", cleared);
        }
    }

    @Override
    public void persistSourceMethods(final Map<Integer, MethodImpactTracker> methodsTracked){
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

    @Override
    public void persistTestSuites(final Map<String, TestSuiteTracker> testSuites){
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

    @Override
    public void deleteTestSuites(final Set<String> testSuites){
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
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#readTrackedLibraries}.
     */
    @Override
    public Map<String, TrackedLibrary> readTrackedLibraries() {
        return libraryRepository.readTrackedLibraries();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#persistTrackedLibrary}.
     */
    @Override
    public void persistTrackedLibrary(final TrackedLibrary trackedLibrary) {
        libraryRepository.persistTrackedLibrary(trackedLibrary);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#deleteTrackedLibrary}.
     */
    @Override
    public void deleteTrackedLibrary(final String groupArtifact) {
        libraryRepository.deleteTrackedLibrary(groupArtifact);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#readPendingLibraryImpactedMethods}.
     */
    @Override
    public List<PendingLibraryImpactedMethod> readPendingLibraryImpactedMethods(final String groupArtifact) {
        return libraryRepository.readPendingLibraryImpactedMethods(groupArtifact);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#readAllPendingLibraryImpactedMethods}.
     */
    @Override
    public List<PendingLibraryImpactedMethod> readAllPendingLibraryImpactedMethods() {
        return libraryRepository.readAllPendingLibraryImpactedMethods();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#persistPendingLibraryImpactedMethods}.
     */
    @Override
    public void persistPendingLibraryImpactedMethods(final PendingLibraryImpactedMethod pending) {
        libraryRepository.persistPendingLibraryImpactedMethods(pending);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#deletePendingLibraryImpactedMethods}.
     */
    @Override
    public void deletePendingLibraryImpactedMethods(final String groupArtifact, final long publishSeq) {
        libraryRepository.deletePendingLibraryImpactedMethods(groupArtifact, publishSeq);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#readAllPendingLibraryForcedSelections}.
     */
    @Override
    public List<PendingLibraryForcedSelection> readAllPendingLibraryForcedSelections() {
        return libraryRepository.readAllPendingLibraryForcedSelections();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#readPendingLibraryForcedSelections}.
     */
    @Override
    public List<PendingLibraryForcedSelection> readPendingLibraryForcedSelections(final String groupArtifact) {
        return libraryRepository.readPendingLibraryForcedSelections(groupArtifact);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#deletePendingLibraryForcedSelections}.
     */
    @Override
    public void deletePendingLibraryForcedSelections(final String groupArtifact, final long publishSeq) {
        libraryRepository.deletePendingLibraryForcedSelections(groupArtifact, publishSeq);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#readLibraryPublishes}.
     */
    @Override
    public List<LibraryPublish> readLibraryPublishes(final String groupArtifact) {
        return libraryRepository.readLibraryPublishes(groupArtifact);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#persistLibraryPublish}.
     */
    @Override
    public long persistLibraryPublish(final LibraryPublish publish, final Set<Integer> impactedMethodIds,
                                      final List<PendingLibraryForcedSelection> forcedSelections) {
        return libraryRepository.persistLibraryPublish(publish, impactedMethodIds, forcedSelections);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#readAllLibraryPublishes}.
     */
    @Override
    public List<LibraryPublish> readAllLibraryPublishes() {
        return libraryRepository.readAllLibraryPublishes();
    }

    @Override
    public Map<Integer, MethodImpactTracker> getMethodsTrackedForIds(final Set<Integer> methodIds) {
        Map<Integer, MethodImpactTracker> result = new HashMap<>();
        if (methodIds == null || methodIds.isEmpty()) {
            return result;
        }

        try (Connection connection = getConnection()) {
            schemaManager.ensureSchema(connection);
            List<Integer> ids = new ArrayList<>(methodIds);
            for (int from = 0; from < ids.size(); from += IN_CLAUSE_CHUNK_SIZE) {
                List<Integer> chunk = ids.subList(from, Math.min(from + IN_CLAUSE_CHUNK_SIZE, ids.size()));
                queryMethodsForIds(connection, chunk, result);
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }

        return result;
    }

    /**
     * Run the method-by-id read for one chunk of ids and merge the rows into the caller's map.
     *
     * @param connection the open H2 connection to query on
     * @param chunk the method ids for this chunk
     * @param result accumulator mapping method id to its tracker
     * @throws SQLException if the query fails
     */
    private void queryMethodsForIds(Connection connection, List<Integer> chunk,
                                    Map<Integer, MethodImpactTracker> result) throws SQLException {
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < chunk.size(); i++) {
            placeholders.append(i == 0 ? "?" : ", ?");
        }
        String sql = "SELECT * FROM " + TABLE_TIA_SOURCE_METHOD
                + " WHERE " + COL_ID + " IN (" + placeholders + ")";
        PreparedStatement ps = connection.prepareStatement(sql);
        for (int i = 0; i < chunk.size(); i++) {
            ps.setInt(i + 1, chunk.get(i));
        }
        ResultSet resultSet = ps.executeQuery();
        while (resultSet.next()) {
            result.put(resultSet.getInt(COL_ID), readCatalogueMethodTracker(resultSet));
        }
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link LibraryRepository#lookupLibraryPublish}.
     */
    @Override
    public LibraryPublish lookupLibraryPublish(final String groupArtifact, final String jarHash, final String version) {
        return libraryRepository.lookupLibraryPublish(groupArtifact, jarHash, version);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link TestRunHistoryRepository#persistTestRunHistoryEntry}.
     */
    @Override
    public void persistTestRunHistoryEntry(final TestRunHistoryEntry entry) {
        testRunHistoryRepository.persistTestRunHistoryEntry(entry);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link TestRunHistoryRepository#readTestRunHistory}.
     */
    @Override
    public List<TestRunHistoryEntry> readTestRunHistory() {
        return testRunHistoryRepository.readTestRunHistory();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link TestRunHistoryRepository#persistTestRunTriggers}.
     */
    @Override
    public void persistTestRunTriggers(final String historyId, final List<TestRunTrigger> triggers) {
        testRunHistoryRepository.persistTestRunTriggers(historyId, triggers);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link TestRunHistoryRepository#readTestRunTriggers}.
     */
    @Override
    public List<TestRunTrigger> readTestRunTriggers(final String historyId) {
        return testRunHistoryRepository.readTestRunTriggers(historyId);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link TestRunHistoryRepository#readTestRunTriggersByHistoryId}.
     */
    @Override
    public Map<String, List<TestRunTrigger>> readTestRunTriggersByHistoryId(final Collection<String> historyIds) {
        return testRunHistoryRepository.readTestRunTriggersByHistoryId(historyIds);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunRepository#persistDistributedRunPlan}.
     */
    @Override
    public void persistDistributedRunPlan(final DistributedRunPlan plan) {
        distributedRunRepository.persistDistributedRunPlan(plan);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunRepository#readDistributedRunDrainResult}.
     */
    @Override
    public LibraryImpactDrainResult readDistributedRunDrainResult(final String runId) {
        return distributedRunRepository.readDistributedRunDrainResult(runId);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunRepository#readDistributedRun}.
     */
    @Override
    public DistributedRun readDistributedRun(final String runId) {
        return distributedRunRepository.readDistributedRun(runId);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunGroupRepository#readDistributedRunGroups}.
     */
    @Override
    public List<DistributedRunGroup> readDistributedRunGroups(final String runId) {
        return distributedRunGroupRepository.readDistributedRunGroups(runId);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunGroupRepository#readDistributedRunGroupSuites}.
     */
    @Override
    public List<String> readDistributedRunGroupSuites(final String runId, final int groupNumber) {
        return distributedRunGroupRepository.readDistributedRunGroupSuites(runId, groupNumber);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunRepository#readAllDistributedRuns}.
     */
    @Override
    public List<DistributedRun> readAllDistributedRuns() {
        return distributedRunRepository.readAllDistributedRuns();
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunRepository#persistDistributedRunSelectionDetails}.
     */
    @Override
    public void persistDistributedRunSelectionDetails(final String runId, final TestRunSelectionDetails details) {
        distributedRunRepository.persistDistributedRunSelectionDetails(runId, details);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunRepository#readDistributedRunSelectionDetails}.
     */
    @Override
    public TestRunSelectionDetails readDistributedRunSelectionDetails(final String runId) {
        return distributedRunRepository.readDistributedRunSelectionDetails(runId);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunGroupRepository#claimNextPendingGroup}.
     */
    @Override
    public DistributedRunGroup claimNextPendingGroup(final String runId, final String runnerKey,
                                                      final long claimedAtMs) {
        return distributedRunGroupRepository.claimNextPendingGroup(runId, runnerKey, claimedAtMs);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunGroupRepository#reportGroupProgress}.
     */
    @Override
    public boolean reportGroupProgress(final String runId, final int groupNumber, final String runnerKey,
                                       final long actualDurationMs, final int suitesRan,
                                       final int suitesFailed, final int suitesObserved,
                                       final long suitesDurationMs) {
        return distributedRunGroupRepository.reportGroupProgress(runId, groupNumber, runnerKey, actualDurationMs, suitesRan, suitesFailed, suitesObserved, suitesDurationMs);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunGroupRepository#completeGroup}.
     */
    @Override
    public DistributedRunGroup completeGroup(final String runId, final int groupNumber, final String runnerKey,
                                             final long completedAtMs) {
        return distributedRunGroupRepository.completeGroup(runId, groupNumber, runnerKey, completedAtMs);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunRepository#electSealer}.
     */
    @Override
    public boolean electSealer(final String runId, final String runnerKey, final long sealedAtMs) {
        return distributedRunRepository.electSealer(runId, runnerKey, sealedAtMs);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedRunRepository#markDistributedRunSealed}.
     */
    @Override
    public void markDistributedRunSealed(final String runId) {
        distributedRunRepository.markDistributedRunSealed(runId);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedMethodStagingRepository#persistStagedMethodTrackers}.
     */
    @Override
    public void persistStagedMethodTrackers(final String runId, final Map<Integer, MethodImpactTracker> methodsTracked,
                                            final Set<Integer> coveredMethodIds) {
        distributedMethodStagingRepository.persistStagedMethodTrackers(runId, methodsTracked, coveredMethodIds);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedMethodStagingRepository#readStagedMethodTrackers}.
     */
    @Override
    public Map<Integer, MethodImpactTracker> readStagedMethodTrackers(final String runId) {
        return distributedMethodStagingRepository.readStagedMethodTrackers(runId);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedMethodStagingRepository#readStagedCoveredMethodIds}.
     */
    @Override
    public Set<Integer> readStagedCoveredMethodIds(final String runId) {
        return distributedMethodStagingRepository.readStagedCoveredMethodIds(runId);
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link DistributedMethodStagingRepository#deleteStagedMethodTrackers}.
     */
    @Override
    public void deleteStagedMethodTrackers(final String runId) {
        distributedMethodStagingRepository.deleteStagedMethodTrackers(runId);
    }

    /**
     * Delete the given suites' {@code tia_test_suite} rows and their {@code tia_test_suites_failed}
     * rows on a caller-supplied connection, in one transaction. The failed rows have to go too:
     * the failed set only ever loses a suite when that suite executes, which a deleted suite never
     * will, so a failed row left behind would force-select the suite on every later build. Doing
     * both in one transaction means a crash cannot leave the failed row without the suite row that
     * the next run's deletion check would find and retry.
     *
     * @param connection the connection to delete on; owns and commits/rolls back its own transaction
     * @param testSuites the suites to delete
     * @throws SQLException if either delete fails, after rolling back both
     */
    private void deleteTestSuites(Connection connection, final Set<String> testSuites) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try (PreparedStatement deleteSuite = connection.prepareStatement(
                "DELETE FROM " + TABLE_TIA_TEST_SUITE + " WHERE " + COL_NAME + " = ?")) {
            for (String testSuite : testSuites){
                log.debug("Deleting test suite: {}", testSuite);
                deleteSuite.setString(1, testSuite);
                deleteSuite.addBatch();
            }
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
        TiaData existingTiaCore = getCoreData(connection);

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

    private void persistTiaCore(Connection connection, TiaData tiaData) throws SQLException {
        TiaData existingTiaCore = getCoreData(connection);
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
     * <p>Does not call {@code ensureIdBlockTableExists} itself: every path that reaches this
     * method does so through a {@code JdbcDataStore} instance that has already run
     * {@code ensureSchema} on the same instance (it is the single schema-bootstrap entry point,
     * called before both {@code getTiaCore} and the full-load read that production callers use
     * ahead of a mapping persist), so the table is already guaranteed to exist. Re-checking it
     * here on every call would add a redundant DDL round trip to the persist path.
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
     * statements bind the suite names as parameters, in chunks of {@value #IN_CLAUSE_CHUNK_SIZE}.
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
     * IN (...)} per {@value #IN_CLAUSE_CHUNK_SIZE} names.
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
     * @param connection the connection to write on; its auto-commit state is not changed
     * @param sourceMethods the full method catalogue to write, keyed by method id; a null map is
     *                      a no-op
     * @throws SQLException if the clear-out or the insert fails
     */
    private void writeSourceMethods(Connection connection, Map<Integer, MethodImpactTracker> sourceMethods) throws SQLException {
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

            log.debug("Persisting indexed source methods: {}", insertSql);
            statement.executeUpdate(insertSql);
        }
    }

    private TiaData readTiaDataFromDB(){
        TiaData tiaData = new TiaData();

        Connection connection = getConnection();

        try {
            // Bootstrap the schema (create on first contact, idempotent migrations on
            // existing DBs). A freshly created DB has no mapping - return the empty TiaData.
            if (!schemaManager.ensureSchema(connection)){
                return tiaData;
            } else {
                long startQueryTime = System.currentTimeMillis();
                tiaData = getCoreData(connection);
                log.debug("SQL query time for core: {}", (System.currentTimeMillis() - startQueryTime) / 1000);
                startQueryTime = System.currentTimeMillis();
                tiaData.setTestSuitesTracked(getTestSuitesData(connection, true));
                log.debug("SQL query time for test suites: {}", (System.currentTimeMillis() - startQueryTime) / 1000);
                startQueryTime = System.currentTimeMillis();
                tiaData.setTestSuitesFailed(getTestSuitesFailed(connection));
                log.debug("SQL query time for failed tests: {}", (System.currentTimeMillis() - startQueryTime) / 1000);
                startQueryTime = System.currentTimeMillis();
                tiaData.setMethodsTracked(getMethodsTracked(connection));
                log.debug("SQL query time for methods tracked: {}", (System.currentTimeMillis() - startQueryTime) / 1000);
                startQueryTime = System.currentTimeMillis();
                tiaData.setLibrariesTracked(readTrackedLibraries());
                log.debug("SQL query time for tracked libraries: {}", (System.currentTimeMillis() - startQueryTime) / 1000);
                // Lazy: only HTML reports consume tiaData.getPendingLibraryImpactedMethods() and
                // tiaData.getPendingLibraryForcedSelections(). The select-tests selector +
                // drainer use per-library / full-table reads directly instead, so paying for a
                // full-table scan + result materialisation here on every load is wasted IO.
                // The loaders run on first getter call and cache; reports trigger them naturally.
                tiaData.setPendingLibraryImpactedMethodsLoader(this::readAllPendingLibraryImpactedMethods);
                tiaData.setPendingLibraryForcedSelectionsLoader(this::readAllPendingLibraryForcedSelections);
                tiaData.setLibraryPublishesLoader(this::readAllLibraryPublishes);
                startQueryTime = System.currentTimeMillis();
                tiaData.setTestRunHistory(readTestRunHistory());
                log.debug("SQL query time for test run history: {}", (System.currentTimeMillis() - startQueryTime) / 1000);
                return tiaData;
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

    private TiaData getCoreData(Connection connection) throws SQLException {
        TiaData tiaData = new TiaData();
        String sql = "SELECT * FROM " + TABLE_TIA_CORE;
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql);

        if (resultSet.next()){
            tiaData.setCommitValue(resultSet.getString(COL_COMMIT_VALUE));
            tiaData.setBranch(resultSet.getString(COL_BRANCH));
            tiaData.setLastUpdated(resultSet.getTimestamp(COL_LAST_UPDATED).toInstant());
            tiaData.getTestStats().setNumRuns(resultSet.getLong(COL_NUM_RUNS));
            tiaData.getTestStats().setAvgRunTime(resultSet.getLong(COL_AVG_RUN_TIME));
            tiaData.getTestStats().setNumSuccessRuns(resultSet.getLong(COL_NUM_SUCCESS_RUNS));
            tiaData.getTestStats().setNumFailRuns(resultSet.getLong(COL_NUM_FAIL_RUNS));
            tiaData.getTestStats().setAllTestsRunTime(resultSet.getLong(COL_ALL_TESTS_RUN_TIME));
            tiaData.getTestStats().setNumAllTestsRuns(resultSet.getLong(COL_NUM_ALL_TESTS_RUNS));
            tiaData.getTestStats().setFixedOverheadMs(resultSet.getLong(COL_FIXED_OVERHEAD_MS));
            tiaData.getTestStats().setCaptureOverheadPerSuiteMs(
                    resultSet.getLong(COL_CAPTURE_OVERHEAD_PER_SUITE_MS));
            tiaData.getTestStats().setNumOverheadMeasurements(
                    resultSet.getLong(COL_NUM_OVERHEAD_MEASUREMENTS));
        }

        return tiaData;
    }

    private Set<String> getTestSuitesFailed(Connection connection) throws SQLException {
        Set<String> testSuitesFailed = new HashSet<>();
        String sql = "SELECT * FROM " + TABLE_TIA_TEST_SUITES_FAILED;
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql);

        while(resultSet.next()){
            testSuitesFailed.add(resultSet.getString(COL_TEST_SUITE_NAME));
        }

        return testSuitesFailed;
    }

    /**
     * Read the full method catalogue from {@code tia_source_method}, including each method's run
     * stats. Used by the seal to carry the stored counts forward and by the HTML reports.
     *
     * @param connection the open connection to read on
     * @return every catalogued method keyed by method id
     * @throws SQLException if the query fails
     */
    private Map<Integer, MethodImpactTracker> getMethodsTracked(Connection connection) throws SQLException {
        Map<Integer, MethodImpactTracker> sourceMethods = new HashMap<>();
        String sql = "SELECT * FROM " + TABLE_TIA_SOURCE_METHOD;
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql);

        while(resultSet.next()){
            sourceMethods.put(resultSet.getInt(COL_ID), readCatalogueMethodTracker(resultSet));
        }

        return sourceMethods;
    }

    /**
     * Load the full suite-to-class-to-method mapping. When {@code loadClassesData} is false only
     * the suite metadata is read; otherwise the classes and their method edges are loaded too.
     *
     * <p>The mapping is read with three separate scans rather than one three-way join. A single
     * join repeats the ~180-char {@code tia_source_class.source_filename} varchar on every method
     * edge, so H2 deserialises that string once per edge (~5.6M times on the reference DB) even
     * though there are far fewer class rows (~948K). Reading the class rows on their own scan
     * deserialises each filename once, and the method-edge scan selects only the two integer ids,
     * so no varchar is materialised for the multi-million-row edge table. This is the dominant
     * cost of the full load, as measured by the {@code profileHtmlReport} harness.
     *
     * @param connection the open DB connection to read from
     * @param loadClassesData whether to load the class and method-edge mapping, not just suites
     * @return the tracked test suites keyed by suite name
     * @throws SQLException if any of the reads fail
     */
    private Map<String, TestSuiteTracker> getTestSuitesData(Connection connection, boolean loadClassesData) throws SQLException {
        if (!loadClassesData) {
            return loadTestSuitesMetadataOnly(connection);
        }

        // Suite metadata (one row per suite), read and mapped in exactly one place. Every suite
        // starts with an empty (mutable) classesImpacted list; the class scan below fills it.
        Map<String, TestSuiteTracker> testSuites = loadTestSuitesMetadataOnly(connection);
        Map<Long, TestSuiteTracker> suitesById = new HashMap<>(testSuites.size() * 2);
        for (TestSuiteTracker suite : testSuites.values()) {
            suite.setClassesImpacted(new ArrayList<>());
            suitesById.put(suite.getId(), suite);
        }

        // Class rows (filename read once per class), then the method edges (int ids only).
        Map<Long, ClassImpactTracker> classesById = loadClassTrackers(connection, suitesById);
        appendClassMethodEdges(connection, classesById);

        // Drop classes with no method edges - matching the previous inner-join semantics between
        // tia_source_class and tia_source_class_method - and finalise the method-id set of the
        // rest so subsequent contains/equals/iteration is correct.
        for (TestSuiteTracker suite : testSuites.values()) {
            Iterator<ClassImpactTracker> it = suite.getClassesImpacted().iterator();
            while (it.hasNext()) {
                ClassImpactTracker classTracker = it.next();
                if (classTracker.getMethodsImpacted().isEmpty()) {
                    it.remove();
                } else {
                    classTracker.getMethodsImpacted().finishBulkBuild();
                }
            }
        }

        return testSuites;
    }

    /**
     * Scan {@code tia_source_class} once, building one {@link ClassImpactTracker} per class row and
     * attaching it to its owning suite's {@code classesImpacted} list. Reading the class rows on
     * their own scan means the {@code source_filename} varchar is deserialised once per class
     * rather than once per method edge. Classes whose suite is not present are skipped, mirroring
     * the previous join which only emitted class rows joined to an existing suite.
     *
     * @param connection the open DB connection to read from
     * @param suitesById the already-loaded suites keyed by suite id, whose lists are appended to
     * @return the class trackers keyed by their {@code tia_source_class.id}, for the edge scan
     * @throws SQLException if the read fails
     */
    private Map<Long, ClassImpactTracker> loadClassTrackers(Connection connection,
                                                            Map<Long, TestSuiteTracker> suitesById) throws SQLException {
        String sql = "SELECT " + COL_ID + " AS class_id, " + COL_TIA_TEST_SUITE_ID + " AS suite_id, " +
                COL_SOURCE_FILENAME + " AS class_source_filename FROM " + TABLE_TIA_SOURCE_CLASS;

        Map<Long, ClassImpactTracker> classesById = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                long classId = rs.getLong("class_id");
                long suiteId = rs.getLong("suite_id");
                TestSuiteTracker suite = suitesById.get(suiteId);
                if (suite == null) {
                    continue;
                }
                ClassImpactTracker classTracker =
                        new ClassImpactTracker(rs.getString("class_source_filename"), new MethodIdSet());
                classesById.put(classId, classTracker);
                suite.getClassesImpacted().add(classTracker);
            }
        }
        return classesById;
    }

    /**
     * Scan {@code tia_source_class_method} once, appending each edge's method id to its class's
     * method-id set. Only the two integer id columns are selected, so no varchar is materialised
     * for the multi-million-row edge table. Uses {@code appendForBulkBuild} to avoid the per-row
     * {@code Integer.valueOf} allocation and the sorted-insert shift; the caller finalises each
     * set with {@code finishBulkBuild} afterwards. Edges referencing a class not present in
     * {@code classesById} are skipped (they cannot occur under referential integrity).
     *
     * @param connection the open DB connection to read from
     * @param classesById the class trackers keyed by {@code tia_source_class.id} to append into
     * @throws SQLException if the read fails
     */
    private void appendClassMethodEdges(Connection connection,
                                        Map<Long, ClassImpactTracker> classesById) throws SQLException {
        String sql = "SELECT " + COL_TIA_SOURCE_CLASS_ID + " AS class_id, " +
                COL_TIA_SOURCE_METHOD_ID + " AS method_id FROM " + TABLE_TIA_SOURCE_CLASS_METHOD;

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                long classId = rs.getLong("class_id");
                ClassImpactTracker classTracker = classesById.get(classId);
                if (classTracker == null) {
                    continue;
                }
                classTracker.getMethodsImpacted().appendForBulkBuild(rs.getInt("method_id"));
            }
        }
    }

    /**
     * Cheap path used when the caller only needs suite-level stats (e.g. test-stats updates).
     * Skips the class / method join entirely.
     */
    private Map<String, TestSuiteTracker> loadTestSuitesMetadataOnly(Connection connection) throws SQLException {
        Map<String, TestSuiteTracker> testSuites = new HashMap<>();
        String sql = "SELECT * FROM " + TABLE_TIA_TEST_SUITE;
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                TestSuiteTracker testSuite = new TestSuiteTracker();
                testSuite.setId(resultSet.getLong(COL_ID));
                testSuite.setName(resultSet.getString(COL_NAME));
                testSuite.getTestStats().setNumRuns(resultSet.getLong(COL_NUM_RUNS));
                testSuite.getTestStats().setAvgRunTime(resultSet.getLong(COL_AVG_RUN_TIME));
                testSuite.getTestStats().setNumSuccessRuns(resultSet.getLong(COL_NUM_SUCCESS_RUNS));
                testSuite.getTestStats().setNumFailRuns(resultSet.getLong(COL_NUM_FAIL_RUNS));
                testSuite.setDeveloperDisabled(resultSet.getBoolean(COL_DEVELOPER_DISABLED));
                testSuite.setUnsealed(resultSet.getBoolean(COL_UNSEALED));
                testSuites.put(testSuite.getName(), testSuite);
            }
        }
        return testSuites;
    }

    /**
     * Release any process-level resources the datastore holds open, by delegating to the
     * {@link ConnectionProvider}'s lifecycle. For embedded H2 the provider issues a graceful
     * {@code SHUTDOWN} that flushes the MVStore write buffer to disk and releases the
     * {@code .mv.db} file lock, so a forked test JVM can open the same file; for server mode it is
     * a no-op (the shared server database must not be torn down for other clients). See the
     * connection-lifecycle handling in {@link ConnectionProvider} and the pluggable-datastore
     * WIKI chapter.
     */
    @Override
    public void close() {
        connectionProvider.close();
    }

    /**
     * Acquire a connection via the injected {@link ConnectionProvider}, then pin it to this
     * instance's per-branch {@link #schema}. The provider owns the vendor-specific acquisition
     * (URL, credentials, and the server-mode retry). On the first call, if a schema is configured,
     * it is created (idempotently) before being selected - creation is memoised via
     * {@link #branchSchemaCreated} since later connections only need re-selecting, but every
     * connection (including the first) has the schema selected so unqualified statements resolve
     * against it rather than the vendor's default schema. Any {@link SQLException} - from the
     * provider or from the create/select statements - is wrapped in the unchecked
     * {@link TiaPersistenceException} so every caller in this class keeps its previous
     * unchecked-failure behaviour. If the schema create/select statements fail after the
     * connection was already acquired, the connection is closed before the wrapped exception is
     * thrown, so a schema-setup failure never leaks an open connection.
     *
     * @return an open connection to the configured database, with the per-branch schema selected
     * @throws TiaPersistenceException wrapping any {@link SQLException} raised while acquiring the
     *         connection or creating/selecting the schema
     */
    Connection getConnection(){
        Connection connection;
        try {
            connection = connectionProvider.get();
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }

        if (schema != null && !schema.trim().isEmpty()) {
            try (Statement statement = connection.createStatement()) {
                // H2 requires the schema to exist before SET SCHEMA, so create-before-select
                // ordering is mandatory; skip the CREATE round trip once it is known to exist.
                if (!branchSchemaCreated) {
                    statement.execute(dialect.createSchemaIfNotExistsSql(schema));
                    branchSchemaCreated = true;
                }
                statement.execute(dialect.selectSchemaSql(schema));
            } catch (SQLException e) {
                try {
                    connection.close();
                } catch (SQLException closeEx) {
                    // Swallow: the original schema-setup failure is the one worth reporting.
                }
                throw new TiaPersistenceException(e);
            }
        }
        return connection;
    }

    /**
     * Expose the injected {@link ConnectionProvider} for tests that need to assert which provider the
     * {@link DataStoreFactory} wired in (for example that a postgres URL routes to the auto-creating
     * {@code PostgresConnectionProvider}). Package-private: for test use only, like {@link #getConnection()}.
     *
     * @return the connection provider this datastore was constructed with
     */
    ConnectionProvider getConnectionProvider() {
        return connectionProvider;
    }
}
