package org.tiatesting.core.persistence.h2;


import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodIdSet;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.TiaPersistenceException;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

public class H2DataStore implements DataStore {
    private static final String COL_COMMIT_VALUE = "commit_value";
    private static final String COL_LAST_UPDATED = "last_updated";
    private static final String COL_NUM_RUNS = "num_runs";
    private static final String COL_AVG_RUN_TIME = "avg_run_time";
    private static final String COL_NUM_SUCCESS_RUNS = "num_success_runs";
    private static final String COL_NUM_FAIL_RUNS = "num_fail_runs";
    private static final String TABLE_TIA_CORE = "tia_core";
    private static final String TABLE_TIA_TEST_SUITE = "tia_test_suite";
    private static final String TABLE_TIA_TEST_SUITES_FAILED = TABLE_TIA_TEST_SUITE + "s_failed";
    private static final String TABLE_TIA_SOURCE_METHOD = "tia_source_method";
    private static final String COL_NAME = "name";
    private static final String COL_SOURCE_FILENAME = "source_file" + COL_NAME;
    private static final String TABLE_TIA_SOURCE_CLASS = "tia_source_class";
    private static final String COL_ID = "id";
    private static final String COL_TIA_TEST_SUITE_ID = "tia_test_suite_" + COL_ID;
    private static final String TABLE_TIA_SOURCE_CLASS_METHOD = "tia_source_class_method";
    private static final String COL_TIA_SOURCE_CLASS_ID = "tia_source_class_" + COL_ID;
    private static final String COL_TIA_SOURCE_METHOD_ID = "tia_source_method_" + COL_ID;
    private static final String COL_METHOD_NAME = "method_" + COL_NAME;
    private static final String COL_LINE_NUMBER_START = "line_number_start";
    private static final String COL_LINE_NUMBER_END = "line_number_end";
    private static final String COL_TEST_SUITE_NAME = "test_suite_" + COL_NAME;
    private static final String TABLE_TIA_LIBRARY = "tia_library";
    private static final String COL_GROUP_ARTIFACT = "group_artifact";
    private static final String COL_PROJECT_DIR = "project_dir";
    private static final String COL_SOURCE_DIRS_CSV = "source_dirs_csv";
    private static final String COL_LAST_SOURCE_PROJECT_VERSION = "last_source_project_version";
    private static final String COL_LAST_SOURCE_PROJECT_JAR_HASH = "last_source_project_jar_hash";
    private static final String COL_LAST_RELEASED_LIBRARY_VERSION = "last_released_library_version";
    private static final String TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD = "tia_pending_library_impacted_method";
    private static final String COL_STAMP_VERSION = "stamp_version";
    private static final String COL_STAMP_JAR_HASH = "stamp_jar_hash";
    private static final String COL_UNKNOWN_NEXT_VERSION = "unknown_next_version";
    private static final String TABLE_TIA_TEST_RUN_HISTORY = "tia_test_run_history";
    private static final String COL_RUN_TIMESTAMP = "run_timestamp";
    private static final String COL_BRANCH = "branch";
    private static final String COL_NUM_SUITES_RAN = "num_suites_ran";
    private static final String COL_NUM_SUITES_IGNORED = "num_suites_ignored";
    private static final String COL_NUM_SUITES_FAILED = "num_suites_failed";
    private static final String COL_DURATION_MS = "duration_ms";
    private static final String COL_UPDATED_DB_MAPPING = "updated_db_mapping";
    private static final String IDX_TEST_RUN_HISTORY_TS = "idx_test_run_history_ts";
    private final Logger log = LoggerFactory.getLogger(H2DataStore.class);
    private final H2ConnectionSettings settings;
    private final String jdbcURL;
    private final String username;
    private final String password;

    /**
     * Construct a datastore from resolved connection settings. The settings determine whether
     * Tia connects to an embedded file-on-disk H2 or a remote server-mode H2; see
     * {@link H2ConnectionSettings}.
     *
     * @param settings the resolved embedded- or server-mode connection settings
     */
    public H2DataStore(H2ConnectionSettings settings){
        this.settings = settings;
        this.username = settings.getUsername();
        this.password = settings.getPassword();
        this.jdbcURL = buildJdbcUrl();

        log.info("Using H2 as the Tia datastore in {} mode with the connection: {}",
                settings.isServerMode() ? "server" : "embedded", this.jdbcURL);
    }

    /**
     * Expose the resolved JDBC URL this datastore connects with. Package-private: it lets tests
     * assert that embedded mode composes the engine-option URL while server mode uses the
     * user-supplied URL verbatim.
     *
     * @return the JDBC URL in use for this datastore
     */
    String getJdbcUrl() {
        return jdbcURL;
    }

    @Override
    public TiaData getTiaData(boolean readFromDisk) {
        return readTiaDataFromDB();
    }

    @Override
    public TiaData getTiaCore(){
        TiaData tiaData;
        Connection connection = getConnection();

        try {
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

    @Override
    public void persistTestSuitesFailed(final Set<String> testSuitesFailed){
        long startTime = System.currentTimeMillis();
        Connection connection = getConnection();

        try {
            persistTestSuitesFailed(connection, testSuitesFailed);
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
        persistTestSuitesInternal(testSuites, true);
    }

    @Override
    public void persistTestSuiteStatsOnly(final Map<String, TestSuiteTracker> testSuites){
        persistTestSuitesInternal(testSuites, false);
    }

    /**
     * Shared implementation behind {@link #persistTestSuites(Map)} and
     * {@link #persistTestSuiteStatsOnly(Map)}. When {@code includeClassMappings} is {@code false}
     * the suite-to-source-class / method edges are left untouched - the only writes are the
     * MERGE on {@code tia_test_suite} (name + stats columns). This is the path used by
     * stats-only runs.
     *
     * @param testSuites           the suites whose rows to persist
     * @param includeClassMappings whether to also delete-and-reinsert the per-suite
     *                             {@code tia_source_class} / {@code tia_source_class_method}
     *                             edges; true for mapping-update runs, false for stats-only.
     */
    private void persistTestSuitesInternal(Map<String, TestSuiteTracker> testSuites, boolean includeClassMappings){
        long startTime = System.currentTimeMillis();
        Connection connection = getConnection();

        try {
            persistTestSuites(connection, testSuites.values(), includeClassMappings);
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

    @Override
    public Map<String, TrackedLibrary> readTrackedLibraries() {
        Map<String, TrackedLibrary> libraries = new HashMap<>();
        Connection connection = getConnection();

        try {
            if (!checkTableExists(connection, TABLE_TIA_LIBRARY)) {
                return libraries;
            }
            String sql = "SELECT * FROM " + TABLE_TIA_LIBRARY;
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql);

            while (resultSet.next()) {
                TrackedLibrary lib = new TrackedLibrary();
                lib.setGroupArtifact(resultSet.getString(COL_GROUP_ARTIFACT));
                lib.setProjectDir(resultSet.getString(COL_PROJECT_DIR));
                lib.setSourceDirsCsv(resultSet.getString(COL_SOURCE_DIRS_CSV));
                lib.setLastSourceProjectVersion(resultSet.getString(COL_LAST_SOURCE_PROJECT_VERSION));
                lib.setLastSourceProjectJarHash(resultSet.getString(COL_LAST_SOURCE_PROJECT_JAR_HASH));
                lib.setLastReleasedLibraryVersion(resultSet.getString(COL_LAST_RELEASED_LIBRARY_VERSION));
                libraries.put(lib.getGroupArtifact(), lib);
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

        return libraries;
    }

    @Override
    public void persistTrackedLibrary(final TrackedLibrary trackedLibrary) {
        Connection connection = getConnection();

        try {
            ensureLibraryTableExists(connection);
            String sql = "MERGE INTO " + TABLE_TIA_LIBRARY + " ("
                    + COL_GROUP_ARTIFACT + ", "
                    + COL_PROJECT_DIR + ", "
                    + COL_SOURCE_DIRS_CSV + ", "
                    + COL_LAST_SOURCE_PROJECT_VERSION + ", "
                    + COL_LAST_SOURCE_PROJECT_JAR_HASH + ", "
                    + COL_LAST_RELEASED_LIBRARY_VERSION + ") "
                    + "KEY (" + COL_GROUP_ARTIFACT + ") VALUES (?, ?, ?, ?, ?, ?)";

            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setString(1, trackedLibrary.getGroupArtifact());
            ps.setString(2, trackedLibrary.getProjectDir());
            ps.setString(3, trackedLibrary.getSourceDirsCsv());
            ps.setString(4, trackedLibrary.getLastSourceProjectVersion());
            ps.setString(5, trackedLibrary.getLastSourceProjectJarHash());
            ps.setString(6, trackedLibrary.getLastReleasedLibraryVersion());
            ps.executeUpdate();
            log.debug("Persisted tracked library: {}", trackedLibrary.getGroupArtifact());
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

    @Override
    public void deleteTrackedLibrary(final String groupArtifact) {
        Connection connection = getConnection();

        try {
            if (!checkTableExists(connection, TABLE_TIA_LIBRARY)) {
                return;
            }
            String sql = "DELETE FROM " + TABLE_TIA_LIBRARY + " WHERE " + COL_GROUP_ARTIFACT + " = ?";
            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setString(1, groupArtifact);
            ps.executeUpdate();
            log.debug("Deleted tracked library: {}", groupArtifact);
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

    @Override
    public List<PendingLibraryImpactedMethod> readPendingLibraryImpactedMethods(final String groupArtifact) {
        List<PendingLibraryImpactedMethod> result = new ArrayList<>();
        Connection connection = getConnection();

        try {
            if (!checkTableExists(connection, TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD)) {
                return result;
            }
            String sql = "SELECT * FROM " + TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD
                    + " WHERE " + COL_GROUP_ARTIFACT + " = ?";
            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setString(1, groupArtifact);
            result = buildPendingBatchesFromResultSet(ps.executeQuery());
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }

        return result;
    }

    @Override
    public List<PendingLibraryImpactedMethod> readAllPendingLibraryImpactedMethods() {
        List<PendingLibraryImpactedMethod> result = new ArrayList<>();
        Connection connection = getConnection();

        try {
            if (!checkTableExists(connection, TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD)) {
                return result;
            }
            String sql = "SELECT * FROM " + TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD;
            Statement statement = connection.createStatement();
            result = buildPendingBatchesFromResultSet(statement.executeQuery(sql));
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }

        return result;
    }

    @Override
    public void persistPendingLibraryImpactedMethods(final PendingLibraryImpactedMethod pending) {
        Connection connection = getConnection();

        try {
            ensurePendingLibraryImpactedMethodTableExists(connection);

            if (!pending.getSourceMethodIds().isEmpty()) {
                String insertSql = "MERGE INTO " + TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD + " ("
                        + COL_GROUP_ARTIFACT + ", " + COL_STAMP_VERSION + ", "
                        + COL_STAMP_JAR_HASH + ", " + COL_UNKNOWN_NEXT_VERSION + ", "
                        + COL_TIA_SOURCE_METHOD_ID + ") "
                        + "KEY (" + COL_GROUP_ARTIFACT + ", " + COL_STAMP_VERSION + ", " + COL_TIA_SOURCE_METHOD_ID + ") "
                        + "VALUES (?, ?, ?, ?, ?)";
                PreparedStatement insertPs = connection.prepareStatement(insertSql);

                for (Integer methodId : pending.getSourceMethodIds()) {
                    insertPs.setString(1, pending.getGroupArtifact());
                    insertPs.setString(2, pending.getStampVersion());
                    insertPs.setString(3, pending.getStampJarHash());
                    insertPs.setBoolean(4, pending.isUnknownNextVersion());
                    insertPs.setInt(5, methodId);
                    insertPs.addBatch();
                }
                insertPs.executeBatch();
            }

            log.debug("Persisted {} pending impacted methods for {}@{}",
                    pending.getSourceMethodIds().size(), pending.getGroupArtifact(), pending.getStampVersion());
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

    @Override
    public void deletePendingLibraryImpactedMethods(final String groupArtifact, final String stampVersion) {
        Connection connection = getConnection();

        try {
            if (!checkTableExists(connection, TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD)) {
                return;
            }
            String sql = "DELETE FROM " + TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD
                    + " WHERE " + COL_GROUP_ARTIFACT + " = ? AND " + COL_STAMP_VERSION + " = ?";
            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setString(1, groupArtifact);
            ps.setString(2, stampVersion);
            ps.executeUpdate();
            log.debug("Deleted pending impacted methods for {}@{}", groupArtifact, stampVersion);
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

    @Override
    public void persistTestRunHistoryEntry(final TestRunHistoryEntry entry) {
        Connection connection = getConnection();

        try {
            ensureTestRunHistoryTableExists(connection);
            String sql = "MERGE INTO " + TABLE_TIA_TEST_RUN_HISTORY + " ("
                    + COL_ID + ", "
                    + COL_RUN_TIMESTAMP + ", "
                    + COL_BRANCH + ", "
                    + COL_COMMIT_VALUE + ", "
                    + COL_NUM_SUITES_RAN + ", "
                    + COL_NUM_SUITES_IGNORED + ", "
                    + COL_NUM_SUITES_FAILED + ", "
                    + COL_DURATION_MS + ", "
                    + COL_UPDATED_DB_MAPPING + ") "
                    + "KEY (" + COL_ID + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

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

    @Override
    public List<TestRunHistoryEntry> readTestRunHistory() {
        List<TestRunHistoryEntry> history = new ArrayList<>();
        Connection connection = getConnection();

        try {
            // Ensure table exists for older DBs that pre-date this feature; reads return empty
            // until the first insert.
            ensureTestRunHistoryTableExists(connection);

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
                        resultSet.getBoolean(COL_UPDATED_DB_MAPPING)));
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
     * Group flat pending rows from a result set into batches keyed by
     * {@code (groupArtifact, stampVersion)}.
     *
     * <p>The reducer uses a {@link LinkedHashMap}, so it tolerates rows arriving in arbitrary
     * order — rows for the same batch are accumulated by key regardless of where they appear
     * in the result. Callers therefore do not need to apply an {@code ORDER BY} in the SQL,
     * which lets H2 skip materialising an {@code MVSortedTempResult} for these reads.
     *
     * @param resultSet the JDBC result set positioned before the first row
     * @return one {@link PendingLibraryImpactedMethod} per distinct
     *         {@code (groupArtifact, stampVersion)} pair in the result
     * @throws SQLException if the underlying result-set traversal fails
     */
    private List<PendingLibraryImpactedMethod> buildPendingBatchesFromResultSet(ResultSet resultSet) throws SQLException {
        Map<String, PendingLibraryImpactedMethod> batchMap = new LinkedHashMap<>();

        while (resultSet.next()) {
            String ga = resultSet.getString(COL_GROUP_ARTIFACT);
            String sv = resultSet.getString(COL_STAMP_VERSION);
            String key = ga + "|" + sv;

            PendingLibraryImpactedMethod batch = batchMap.get(key);
            if (batch == null) {
                batch = new PendingLibraryImpactedMethod(ga, sv,
                        resultSet.getString(COL_STAMP_JAR_HASH), new HashSet<>());
                batch.setUnknownNextVersion(resultSet.getBoolean(COL_UNKNOWN_NEXT_VERSION));
                batchMap.put(key, batch);
            }
            batch.getSourceMethodIds().add(resultSet.getInt(COL_TIA_SOURCE_METHOD_ID));
        }

        return new ArrayList<>(batchMap.values());
    }

    private void deleteTestSuites(Connection connection, final Set<String> testSuites) throws SQLException {
        Statement statement = connection.createStatement();

        for (String testSuite : testSuites){
            String deleteTestSuiteSql = "DELETE FROM " + TABLE_TIA_TEST_SUITE + " WHERE " + COL_NAME + " = '" + testSuite +"'";
            log.debug("Deleting test suite: {}", deleteTestSuiteSql);

            statement.executeUpdate(deleteTestSuiteSql);
        }
    }

    private void persistTiaCore(Connection connection, TiaData tiaData) throws SQLException {
        TiaData existingTiaCore = getCoreData(connection);
        String sql;

        if (existingTiaCore.getCommitValue() == null){
            sql = "INSERT INTO " + TABLE_TIA_CORE + " (" + COL_COMMIT_VALUE + ", " + COL_BRANCH + ", " + COL_LAST_UPDATED + ", " + COL_NUM_RUNS + ", " +
                    COL_AVG_RUN_TIME + ", " + COL_NUM_SUCCESS_RUNS + ", " + COL_NUM_FAIL_RUNS + ") values ('" +
                    tiaData.getCommitValue() + "', " +
                    sqlStringOrNull(tiaData.getBranch()) + ", '" +
                    tiaData.getLastUpdated() + "', " +
                    tiaData.getTestStats().getNumRuns() + ", " +
                    tiaData.getTestStats().getAvgRunTime()  + ", " +
                    tiaData.getTestStats().getNumSuccessRuns()  + ", " +
                    tiaData.getTestStats().getNumFailRuns() + ")";
        }else{
            sql = "UPDATE " + TABLE_TIA_CORE + " SET " +
                    COL_COMMIT_VALUE + "='" + tiaData.getCommitValue() +
                    "', " + COL_BRANCH + "=" + sqlStringOrNull(tiaData.getBranch()) +
                    ", " + COL_LAST_UPDATED + "='" + tiaData.getLastUpdated() +
                    "', " + COL_NUM_RUNS + "=" + tiaData.getTestStats().getNumRuns() +
                    ", " + COL_AVG_RUN_TIME + "=" + tiaData.getTestStats().getAvgRunTime() +
                    ", " + COL_NUM_SUCCESS_RUNS + "=" + tiaData.getTestStats().getNumSuccessRuns() +
                    ", " + COL_NUM_FAIL_RUNS + "=" + tiaData.getTestStats().getNumFailRuns();
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

    private void persistTestSuites(Connection connection, Collection<TestSuiteTracker> testSuites,
                                   boolean includeClassMappings) throws SQLException {
        if (testSuites.isEmpty()){
            return;
        }

        Statement statement = connection.createStatement();

        for (TestSuiteTracker testSuite : testSuites){
            String mergeSql = "MERGE INTO " + TABLE_TIA_TEST_SUITE + " (" +
                    COL_NAME + ", " +
                    COL_NUM_RUNS + ", " +
                    COL_AVG_RUN_TIME + ", " +
                    COL_NUM_SUCCESS_RUNS + ", " +
                    COL_NUM_FAIL_RUNS + ") " +
                    "KEY (" + COL_NAME + ") VALUES ('" +
                    testSuite.getName() + "', " +
                    testSuite.getTestStats().getNumRuns() + ", " +
                    testSuite.getTestStats().getAvgRunTime() + ", " +
                    testSuite.getTestStats().getNumSuccessRuns() + ", " +
                    testSuite.getTestStats().getNumFailRuns() + ")";

            log.debug("Persisting test suites: {}", mergeSql);
            statement.executeUpdate(mergeSql, Statement.RETURN_GENERATED_KEYS);

            // only update the source classes mapping for the test suite if the caller is the
            // full-mapping path AND mapping data exists for this test run. Stats-only runs
            // (includeClassMappings=false) skip this entirely so tia_source_class /
            // tia_source_class_method remain untouched.
            if (includeClassMappings && !testSuite.getClassesImpacted().isEmpty()){
                ResultSet rs = statement.getGeneratedKeys();
                rs.next();
                persistTestSuiteClasses(connection, rs.getLong(COL_ID), testSuite.getClassesImpacted());
            }
        }
    }

    private void persistTestSuiteClasses(Connection connection, long testSuiteId, List<ClassImpactTracker> sourceClasses) throws SQLException {
        if (sourceClasses.isEmpty()){
            return;
        }

        // Per-test-suite atomicity: the DELETE-then-INSERT for one suite's classes + its
        // class-method edges is wrapped in one transaction, so a failure midway through the
        // re-insert leaves the suite's previous mappings intact rather than half-wiped.
        // Wrapping the entire outer persistTestSuites loop would put millions of edges in
        // one transaction and risk MVStore undo-log blow-up; per-suite is the right balance —
        // each suite is internally consistent, and at worst a partial outer-loop failure
        // leaves some suites updated and some not (which is what would happen anyway).
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try (Statement statement = connection.createStatement()) {
            // delete the existing source class and methods before inserting the new data from the test run
            String deleteClassMethodSql = "DELETE FROM " + TABLE_TIA_SOURCE_CLASS_METHOD + " WHERE " + COL_TIA_SOURCE_CLASS_ID +
                    " IN (SELECT " + COL_ID + " FROM " + TABLE_TIA_SOURCE_CLASS +
                    " WHERE " + COL_TIA_TEST_SUITE_ID + " = " + testSuiteId + ")";
            log.debug("Deleting test suite class methods: {}", deleteClassMethodSql);
            statement.executeUpdate(deleteClassMethodSql);

            String deleteClassSql = "DELETE FROM " + TABLE_TIA_SOURCE_CLASS + " WHERE " + COL_TIA_TEST_SUITE_ID + " = " + testSuiteId;
            log.debug("Deleting test suite class: {}", deleteClassSql);
            statement.executeUpdate(deleteClassSql);

            for (ClassImpactTracker sourceClass : sourceClasses){
                String insertSql = "INSERT INTO " + TABLE_TIA_SOURCE_CLASS + " (" +
                        COL_TIA_TEST_SUITE_ID + ", " +
                        COL_SOURCE_FILENAME + ") " +
                        "VALUES (" +
                        testSuiteId + ", '" +
                        sourceClass.getSourceFilename() + "')";

                log.debug("Persisting test suite class: {}", insertSql);
                statement.executeUpdate(insertSql, Statement.RETURN_GENERATED_KEYS);
                try (ResultSet rs = statement.getGeneratedKeys()) {
                    rs.next();
                    persistTestSuiteClassMethods(connection, rs.getLong(COL_ID), sourceClass.getMethodsImpacted());
                }
            }

            connection.commit();
        } catch (Exception e) {
            // Catch Exception (not just SQLException) so any failure in this block — including
            // an NPE while building the insert SQL — still triggers the rollback. Tia treats
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
                // best-effort restore — the connection is about to be closed by the caller
                log.debug("Failed to restore autoCommit on connection: {}", restoreEx.getMessage());
            }
        }
    }

    /**
     * Insert the (class, method) edge rows for one source class. Runs inside the surrounding
     * transaction opened by {@link #persistTestSuiteClasses(Connection, long, List)} — must not
     * touch {@code connection.setAutoCommit(...)} or the rollback semantics on its caller break.
     */
    private void persistTestSuiteClassMethods(Connection connection, long testSuiteClassId, Set<Integer> sourceMethodIds) throws SQLException {
        if (sourceMethodIds.isEmpty()){
            return;
        }

        String insertSql = "INSERT INTO " + TABLE_TIA_SOURCE_CLASS_METHOD + " (" +
                COL_TIA_SOURCE_CLASS_ID + ", " +
                COL_TIA_SOURCE_METHOD_ID + ") " +
                "VALUES (? , ?)";

        try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
            for (Integer sourceMethodId : sourceMethodIds){
                ps.setLong(1, testSuiteClassId);
                ps.setLong(2, sourceMethodId);
                ps.addBatch();
            }

            log.debug("Persisting test suite class methods: {}", insertSql);
            ps.executeBatch();
        }
    }

    private void persistTestSuitesFailed(Connection connection, Set<String> testSuitesFailed) throws SQLException {
        if (testSuitesFailed == null){
            return;
        }

        // TRUNCATE + INSERT must be atomic. H2's TRUNCATE is transactional, so an exception
        // during the INSERT rolls the truncate back too — leaving the previous failed-test
        // rows intact rather than wiping them. Same pattern (and same negligible cost) as
        // persistSourceMethods.
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try (Statement statement = connection.createStatement()) {
            String truncateSql = "TRUNCATE TABLE " + TABLE_TIA_TEST_SUITES_FAILED;
            log.debug("Truncating failed test suites: {}", truncateSql);
            statement.executeUpdate(truncateSql);

            if (testSuitesFailed.isEmpty()){
                connection.commit();
                return;
            }

            StringBuilder insertSqlBuilder = new StringBuilder("INSERT INTO " + TABLE_TIA_TEST_SUITES_FAILED + " (" + COL_TEST_SUITE_NAME + ") values ");
            for (String testSuite : testSuitesFailed){
                insertSqlBuilder.append("('" + testSuite + "'),");
            }
            String insertSql = insertSqlBuilder.toString();
            insertSql = insertSql.substring(0, insertSql.length()-1);

            log.debug("Persisting failed test suites: {}", insertSql);
            statement.executeUpdate(insertSql);

            connection.commit();
        } catch (Exception e) {
            // Catch Exception (not just SQLException) so any failure in this block — including
            // an NPE while building the insert SQL — still triggers the rollback. Tia treats
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
                // best-effort restore — the connection is about to be closed by the caller
                log.debug("Failed to restore autoCommit on connection: {}", restoreEx.getMessage());
            }
        }
    }

    private void persistSourceMethods(Connection connection, Map<Integer, MethodImpactTracker> sourceMethods) throws SQLException {
        if (sourceMethods == null){
            return;
        }

        // TRUNCATE + INSERT must be atomic. H2's TRUNCATE is transactional (unlike MySQL/InnoDB),
        // so wrapping both statements in one transaction means an exception during the INSERT
        // rolls the truncate back too, leaving the previous tia_source_method rows intact rather
        // than wiping the table. Negligible perf cost — the entire bulk insert was already a
        // single batched multi-VALUES statement.
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try (Statement statement = connection.createStatement()) {
            String truncateSql = "TRUNCATE TABLE " + TABLE_TIA_SOURCE_METHOD;
            log.debug("Truncating indexed source methods: {}", truncateSql);
            statement.executeUpdate(truncateSql);

            if (sourceMethods.isEmpty()){
                connection.commit();
                return;
            }

            StringBuilder insertSqlBuilder = new StringBuilder("INSERT INTO " + TABLE_TIA_SOURCE_METHOD + " (" +
                    COL_ID + ", " +
                    COL_METHOD_NAME + ", " +
                    COL_LINE_NUMBER_START + ", " +
                    COL_LINE_NUMBER_END + ") values ");

            for (Map.Entry<Integer, MethodImpactTracker> entry : sourceMethods.entrySet()){
                insertSqlBuilder.append("(" + entry.getKey() + ", '" +
                        entry.getValue().getMethodName() + "', " +
                        entry.getValue().getLineNumberStart() + ", " +
                        entry.getValue().getLineNumberEnd() + "),");
            }
            String insertSql = insertSqlBuilder.toString();
            insertSql = insertSql.substring(0, insertSql.length()-1);

            log.debug("Persisting indexed source methods: {}", insertSql);
            statement.executeUpdate(insertSql);

            connection.commit();
        } catch (Exception e) {
            // Catch Exception (not just SQLException) so any failure in this block — including
            // an NPE while building the insert SQL — still triggers the rollback. Tia treats
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
                // best-effort restore — the connection is about to be closed by the caller
                log.debug("Failed to restore autoCommit on connection: {}", restoreEx.getMessage());
            }
        }
    }

    private TiaData readTiaDataFromDB(){
        TiaData tiaData = new TiaData();

        Connection connection = getConnection();

        try {
            if (!checkTiaDBExists(connection)){
                log.debug("The Tia DB doesn't currently exist at {}", jdbcURL);
                createTiaDB();
                return tiaData;
            } else {
                // Ensure schema migrations have run on this existing DB before any reads.
                // Idempotent: safe to call on every load.
                ensureSourceClassTestSuiteIndexExists(connection);
                ensureTestRunHistoryTableExists(connection);

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
                // Lazy: only HTML reports consume tiaData.getPendingLibraryImpactedMethods().
                // The select-tests selector + drainer use per-library reads instead, so paying
                // for a full-table scan + result materialisation here on every load is wasted IO.
                // The loader runs on first getter call and caches; reports trigger it naturally.
                tiaData.setPendingLibraryImpactedMethodsLoader(this::readAllPendingLibraryImpactedMethods);
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

    private Map<Integer, MethodImpactTracker> getMethodsTracked(Connection connection) throws SQLException {
        Map<Integer, MethodImpactTracker> sourceMethods = new HashMap<>();
        String sql = "SELECT * FROM " + TABLE_TIA_SOURCE_METHOD;
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql);

        while(resultSet.next()){
            String methodName = resultSet.getString(COL_METHOD_NAME);
            int lineNumberStart = resultSet.getInt(COL_LINE_NUMBER_START);
            int lineNumberEnd = resultSet.getInt(COL_LINE_NUMBER_END);
            MethodImpactTracker sourceMethod = new MethodImpactTracker(methodName, lineNumberStart, lineNumberEnd);
            sourceMethods.put(resultSet.getInt(COL_ID), sourceMethod);
        }

        return sourceMethods;
    }

    /**
     * Load every test suite (and, optionally, the source-class / source-method coverage map for each)
     * in a single bulk query.
     *
     * <p>Earlier versions ran one {@code SELECT * FROM tia_test_suite} followed by N per-suite
     * {@code SELECT … JOIN tia_source_class_method WHERE tia_test_suite_id = ?} queries, parallelised
     * via {@code parallelStream}. On a 1k-suite / 5.6M-edge DB that was 86%+ of total CPU because
     * 1000 random-access B-tree walks all serialised on the same MVStore file. A single bulk join
     * amortises the per-row cost over one cursor.
     *
     * <p>The query intentionally does <strong>not</strong> use {@code ORDER BY}: at 5.6M rows H2
     * could not fold the sort into the index and instead spilled the result set to a temp file
     * (visible as {@code MVSortedTempResult} in profiling — ~56% of post-fix CPU). The reducer
     * therefore tolerates rows arriving in any order: it keeps id-keyed maps for both the suite
     * and the class layer, so an arbitrary row updates the right tracker via two hash lookups.
     * After the loop, every {@link MethodIdSet} is finalised once.
     *
     * <p>{@code LEFT JOIN}s preserve the existing behaviour: a test suite with no classes still
     * appears in the returned map (with an empty {@code classesImpacted} list); a class row with
     * no matching method edge is dropped (matches the previous {@code INNER JOIN} between
     * {@code tia_source_class} and {@code tia_source_class_method}).
     */
    private Map<String, TestSuiteTracker> getTestSuitesData(Connection connection, boolean loadClassesData) throws SQLException {
        if (!loadClassesData) {
            return loadTestSuitesMetadataOnly(connection);
        }

        // Aliased columns let us pull the suite + class + method data out of one cursor without
        // ambiguous-column errors on the shared "id" / "source_filename" names.
        String sql = "SELECT ts." + COL_ID + " AS suite_id, ts." + COL_NAME + " AS suite_name, " +
                "ts." + COL_NUM_RUNS + " AS suite_num_runs, ts." + COL_AVG_RUN_TIME + " AS suite_avg_run_time, " +
                "ts." + COL_NUM_SUCCESS_RUNS + " AS suite_num_success_runs, ts." + COL_NUM_FAIL_RUNS + " AS suite_num_fail_runs, " +
                "sc." + COL_ID + " AS class_id, sc." + COL_SOURCE_FILENAME + " AS class_source_filename, " +
                "scm." + COL_TIA_SOURCE_METHOD_ID + " AS method_id " +
                "FROM " + TABLE_TIA_TEST_SUITE + " ts " +
                "LEFT JOIN " + TABLE_TIA_SOURCE_CLASS + " sc ON sc." + COL_TIA_TEST_SUITE_ID + " = ts." + COL_ID + " " +
                "LEFT JOIN " + TABLE_TIA_SOURCE_CLASS_METHOD + " scm ON scm." + COL_TIA_SOURCE_CLASS_ID + " = sc." + COL_ID;

        // Suite trackers keyed by both id (for fast lookup during the unordered scan) and name
        // (the public return-shape); the two maps share the same TestSuiteTracker instances.
        Map<Long, TestSuiteTracker> suitesById = new HashMap<>();
        Map<String, TestSuiteTracker> testSuites = new HashMap<>();
        // ClassImpactTrackers keyed by their tia_source_class.id while we're still building.
        // Cleared after the loop — only TestSuiteTracker references survive.
        Map<Long, ClassImpactTracker> classesById = new HashMap<>();

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {

            while (rs.next()) {
                long suiteId = rs.getLong("suite_id");
                TestSuiteTracker suite = suitesById.get(suiteId);
                if (suite == null) {
                    suite = new TestSuiteTracker();
                    suite.setId(suiteId);
                    suite.setName(rs.getString("suite_name"));
                    suite.getTestStats().setNumRuns(rs.getLong("suite_num_runs"));
                    suite.getTestStats().setAvgRunTime(rs.getLong("suite_avg_run_time"));
                    suite.getTestStats().setNumSuccessRuns(rs.getLong("suite_num_success_runs"));
                    suite.getTestStats().setNumFailRuns(rs.getLong("suite_num_fail_runs"));
                    suite.setClassesImpacted(new ArrayList<>());
                    suitesById.put(suiteId, suite);
                    testSuites.put(suite.getName(), suite);
                }

                // LEFT JOINs may yield (suite, NULL class) rows for suites with no classes,
                // and (suite, class, NULL method) rows for classes with no methods. Skip both —
                // matches the previous behaviour where the INNER JOIN between tia_source_class
                // and tia_source_class_method dropped classes with no method edges.
                long classIdValue = rs.getLong("class_id");
                if (rs.wasNull()) {
                    continue;
                }
                int methodId = rs.getInt("method_id");
                if (rs.wasNull()) {
                    continue;
                }

                ClassImpactTracker classTracker = classesById.get(classIdValue);
                if (classTracker == null) {
                    classTracker = new ClassImpactTracker(rs.getString("class_source_filename"), new MethodIdSet());
                    classesById.put(classIdValue, classTracker);
                    suite.getClassesImpacted().add(classTracker);
                }

                // appendForBulkBuild avoids the per-row Integer.valueOf allocation and the
                // O(n) shift that add(int) does to keep the array sorted. finishBulkBuild
                // (called once per class below) sorts + dedupes the underlying int[].
                classTracker.getMethodsImpacted().appendForBulkBuild(methodId);
            }
        }

        // Finalise every class's method-id set so subsequent contains/equals/iteration is correct.
        for (ClassImpactTracker classTracker : classesById.values()) {
            classTracker.getMethodsImpacted().finishBulkBuild();
        }

        return testSuites;
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
                testSuites.put(testSuite.getName(), testSuite);
            }
        }
        return testSuites;
    }

    private void createTiaDB(){
        log.info("Creating the Tia DB");
        String createCoreTableSql = "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_CORE + " (" +
                COL_COMMIT_VALUE + " VARCHAR(255) PRIMARY KEY, " +
                COL_BRANCH + " VARCHAR(255), " +
                COL_LAST_UPDATED + " TIMESTAMP WITH TIME ZONE, " +
                COL_NUM_RUNS + " BIGINT, " +
                COL_AVG_RUN_TIME + " BIGINT, " +
                COL_NUM_SUCCESS_RUNS + " BIGINT," +
                COL_NUM_FAIL_RUNS + " BIGINT)";

        String createTestSuitesFailedTableSql = "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_TEST_SUITES_FAILED + " " +
                "(" + COL_TEST_SUITE_NAME + " VARCHAR(255) PRIMARY KEY)";

        String createSourceMethodTableSql = "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_SOURCE_METHOD + " " +
                "(" + COL_ID + " INT PRIMARY KEY, " +
                COL_METHOD_NAME + " VARCHAR(2000), " +
                COL_LINE_NUMBER_START + " INT, " +
                COL_LINE_NUMBER_END + " INT)";

        String createTestSuiteTableSql = "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_TEST_SUITE + " " +
                "(" + COL_ID + " BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                COL_NAME + " VARCHAR(500), " +
                COL_SOURCE_FILENAME + " VARCHAR(500), " +
                COL_NUM_RUNS + " BIGINT, " +
                COL_AVG_RUN_TIME + " BIGINT, " +
                COL_NUM_SUCCESS_RUNS + " BIGINT, " +
                COL_NUM_FAIL_RUNS + " BIGINT)";

        String createTestSuiteNameIndexSql = "CREATE UNIQUE INDEX " + COL_SOURCE_FILENAME + "_idx ON " +
                TABLE_TIA_TEST_SUITE + " (" + COL_SOURCE_FILENAME + ")";

        String createSourceClassTableSql = "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_SOURCE_CLASS + " " +
                "(" + COL_ID + " BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                COL_TIA_TEST_SUITE_ID + " BIGINT, " +
                COL_SOURCE_FILENAME + " VARCHAR(500))";

        // Index on tia_source_class.tia_test_suite_id is essential: without it, the bulk join in
        // getTestSuitesData becomes a nested-loop scan of all tia_source_class rows for every
        // suite — observed at ~30% of select-tests CPU on a 940K-row tia_source_class table.
        String createSourceClassTestSuiteIndexSql = buildCreateSourceClassTestSuiteIndexSql();

        String createSourceClassMethodTableSql = "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_SOURCE_CLASS_METHOD +
                " (" + COL_TIA_SOURCE_CLASS_ID + " BIGINT, " +
                COL_TIA_SOURCE_METHOD_ID + " INT, " +
                "PRIMARY KEY (" + COL_TIA_SOURCE_CLASS_ID + ", " + COL_TIA_SOURCE_METHOD_ID + "))";

        String createLibraryTableSql = buildCreateLibraryTableSql();
        String createPendingLibraryMethodTableSql = buildCreatePendingLibraryImpactedMethodTableSql();
        String createTestRunHistoryTableSql = buildCreateTestRunHistoryTableSql();
        String createTestRunHistoryIndexSql = buildCreateTestRunHistoryIndexSql();

        try {
            Connection connection = getConnection();
            Statement statement = connection.createStatement();
            statement.executeUpdate(createCoreTableSql);
            statement.executeUpdate(createSourceMethodTableSql);
            statement.executeUpdate(createTestSuitesFailedTableSql);
            statement.executeUpdate(createTestSuiteTableSql);
            statement.executeUpdate(createTestSuiteNameIndexSql);
            statement.executeUpdate(createSourceClassTableSql);
            statement.executeUpdate(createSourceClassTestSuiteIndexSql);
            statement.executeUpdate(createSourceClassMethodTableSql);
            statement.executeUpdate(createLibraryTableSql);
            statement.executeUpdate(createPendingLibraryMethodTableSql);
            statement.executeUpdate(createTestRunHistoryTableSql);
            statement.executeUpdate(createTestRunHistoryIndexSql);
            connection.close();
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
        log.info("Finished creating the Tia DB");
    }

    private boolean checkTiaDBExists(Connection connection) throws SQLException {
        return checkTableExists(connection, TABLE_TIA_CORE);
    }

    /**
     * Check whether a table exists in the H2 database.
     */
    private boolean checkTableExists(Connection connection, String tableName) throws SQLException {
        ResultSet rset = connection.getMetaData().getTables(null, null, tableName.toUpperCase(), new String[]{"TABLE"});
        return rset.next();
    }

    /**
     * Build the DDL for the {@code tia_library} table.
     */
    private String buildCreateLibraryTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_LIBRARY + " ("
                + COL_GROUP_ARTIFACT + " VARCHAR(512) PRIMARY KEY, "
                + COL_PROJECT_DIR + " VARCHAR(1000), "
                + COL_SOURCE_DIRS_CSV + " VARCHAR(2000), "
                + COL_LAST_SOURCE_PROJECT_VERSION + " VARCHAR(128), "
                + COL_LAST_SOURCE_PROJECT_JAR_HASH + " VARCHAR(128), "
                + COL_LAST_RELEASED_LIBRARY_VERSION + " VARCHAR(128))";
    }

    /**
     * Ensure the {@code tia_library} table exists, creating it if necessary.
     * Called before insert/merge operations on the library table to handle
     * databases that were created before this feature was added.
     */
    private void ensureLibraryTableExists(Connection connection) throws SQLException {
        if (!checkTableExists(connection, TABLE_TIA_LIBRARY)) {
            Statement statement = connection.createStatement();
            statement.executeUpdate(buildCreateLibraryTableSql());
            log.debug("Created {} table in existing Tia DB", TABLE_TIA_LIBRARY);
        }
    }

    /**
     * Build the DDL for the {@code tia_pending_library_impacted_method} table.
     */
    private String buildCreatePendingLibraryImpactedMethodTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD + " ("
                + COL_GROUP_ARTIFACT + " VARCHAR(512) NOT NULL, "
                + COL_STAMP_VERSION + " VARCHAR(128) NOT NULL, "
                + COL_STAMP_JAR_HASH + " VARCHAR(128), "
                + COL_UNKNOWN_NEXT_VERSION + " BOOLEAN NOT NULL DEFAULT FALSE, "
                + COL_TIA_SOURCE_METHOD_ID + " INT NOT NULL, "
                + "PRIMARY KEY (" + COL_GROUP_ARTIFACT + ", " + COL_STAMP_VERSION + ", " + COL_TIA_SOURCE_METHOD_ID + "), "
                + "FOREIGN KEY (" + COL_GROUP_ARTIFACT + ") REFERENCES " + TABLE_TIA_LIBRARY + "(" + COL_GROUP_ARTIFACT + ") ON DELETE CASCADE)";
    }

    /**
     * Ensure the {@code tia_pending_library_impacted_method} table exists, creating
     * it and its parent {@code tia_library} table if necessary.
     */
    private void ensurePendingLibraryImpactedMethodTableExists(Connection connection) throws SQLException {
        ensureLibraryTableExists(connection);
        if (!checkTableExists(connection, TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD)) {
            Statement statement = connection.createStatement();
            statement.executeUpdate(buildCreatePendingLibraryImpactedMethodTableSql());
            log.debug("Created {} table in existing Tia DB", TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD);
        }
    }

    /**
     * Build the DDL for the {@code tia_test_run_history} table.
     *
     * @return the {@code CREATE TABLE IF NOT EXISTS} statement for the history table
     */
    private String buildCreateTestRunHistoryTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_TEST_RUN_HISTORY + " ("
                + COL_ID + " VARCHAR(64) NOT NULL PRIMARY KEY, "
                + COL_RUN_TIMESTAMP + " BIGINT NOT NULL, "
                + COL_BRANCH + " VARCHAR(255), "
                + COL_COMMIT_VALUE + " VARCHAR(255), "
                + COL_NUM_SUITES_RAN + " INT, "
                + COL_NUM_SUITES_IGNORED + " INT, "
                + COL_NUM_SUITES_FAILED + " INT, "
                + COL_DURATION_MS + " BIGINT, "
                + COL_UPDATED_DB_MAPPING + " BOOLEAN)";
    }

    /**
     * Build the DDL for the index on {@code tia_test_run_history.run_timestamp}. Backs the
     * default sort by recency in the report read path ({@code ORDER BY run_timestamp DESC}).
     *
     * @return the {@code CREATE INDEX IF NOT EXISTS} statement for the timestamp index
     */
    private String buildCreateTestRunHistoryIndexSql() {
        return "CREATE INDEX IF NOT EXISTS " + IDX_TEST_RUN_HISTORY_TS + " ON "
                + TABLE_TIA_TEST_RUN_HISTORY + " (" + COL_RUN_TIMESTAMP + ")";
    }

    /**
     * Ensure the {@code tia_test_run_history} table and its timestamp index exist on an
     * already-populated DB. Idempotent via {@code CREATE TABLE/INDEX IF NOT EXISTS}; called
     * on every load and before each insert so DBs created before this feature gain the
     * table on first contact.
     *
     * @param connection the H2 connection to issue the DDL on
     * @throws SQLException if either DDL statement fails
     */
    private void ensureTestRunHistoryTableExists(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        statement.executeUpdate(buildCreateTestRunHistoryTableSql());
        statement.executeUpdate(buildCreateTestRunHistoryIndexSql());
    }

    /**
     * DDL for the index on {@code tia_source_class.tia_test_suite_id}. Uses
     * {@code CREATE INDEX IF NOT EXISTS} so it's safe to call repeatedly — both for new DBs
     * (called from {@code createTiaDB}) and for migrating existing DBs missing the index.
     */
    private static String buildCreateSourceClassTestSuiteIndexSql() {
        return "CREATE INDEX IF NOT EXISTS " + COL_TIA_TEST_SUITE_ID + "_idx ON "
                + TABLE_TIA_SOURCE_CLASS + " (" + COL_TIA_TEST_SUITE_ID + ")";
    }

    /**
     * Migration: ensure the {@code tia_source_class.tia_test_suite_id} index exists on
     * an already-populated DB. Without this index, the bulk join in {@code getTestSuitesData}
     * degrades to a nested-loop scan of {@code tia_source_class}.
     */
    private void ensureSourceClassTestSuiteIndexExists(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        statement.executeUpdate(buildCreateSourceClassTestSuiteIndexSql());
    }

    /**
     * Force-close the embedded H2 database so the underlying {@code .mv.db} file lock is
     * released. Required when running inside a Maven plugin's JVM that will later fork a
     * surefire/test JVM: without an explicit close, {@code DB_CLOSE_DELAY=-1} keeps the
     * database open in the Maven JVM and the forked test JVM cannot open the same file —
     * H2 reports {@code "Database may be already in use"}.
     *
     * <p>Issues {@code SHUTDOWN IMMEDIATELY} via a short-lived connection. Failures during
     * close are swallowed (logged at debug) so cleanup errors never mask the real exception
     * a calling {@code try}/{@code finally} block is unwinding.
     *
     * <p>This is an <b>embedded-mode-only</b> concern. In server mode the database engine lives
     * in the remote server process and is shared by every connected client, so issuing
     * {@code SHUTDOWN IMMEDIATELY} would tear down the whole server database for all of them.
     * Server-mode {@code close()} is therefore a no-op - individual connections are already
     * closed by each operation's {@code finally} block.
     */
    @Override
    public void close() {
        if (settings.isServerMode()){
            // Never SHUTDOWN a shared server DB - it would kill the database for every other
            // connected client. Per-operation connections are already closed by their callers.
            return;
        }

        // try-with-resources on Connection + Statement: SHUTDOWN IMMEDIATELY tears down the
        // session, so the implicit close() calls typically throw "connection is closed" —
        // that's expected and the outer catch swallows it. Failures during close are logged
        // at debug so cleanup errors never mask the real exception a calling try/finally is
        // unwinding.
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN IMMEDIATELY");
        } catch (Throwable t) {
            log.debug("H2DataStore.close ignoring shutdown exception for {}: {}", jdbcURL, t.toString());
        }
    }

    private Connection getConnection(){
        Connection connection;
        try {
            DataSource dataSource = this.establishDataSource();
            connection = dataSource.getConnection() ;
            log.debug("Connected to the embedded H2 database {}", jdbcURL);
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }

        return connection;
    }

    private DataSource establishDataSource() {
        JdbcDataSource ds = Objects.requireNonNull( new JdbcDataSource() );
        ds.setURL( jdbcURL );
        ds.setUser( username );
        ds.setPassword( password );
        ds.setDescription( "Tia database" );
        return ds;
    }

    /**
     * Build the embedded-mode H2 JDBC URL for this datastore.
     *
     * <p>The {@code DB_CLOSE_DELAY=-1} flag keeps the underlying database open for the lifetime
     * of the JVM, instead of H2's embedded-mode default of closing the entire database whenever
     * the last open connection is closed. Closing the database forces an {@code MVStore.commit()}
     * which flushes dirty pages — including the temp-result pages H2 writes to spill {@code ORDER
     * BY} sorts that aren't covered by an index. With this flag, individual {@code Connection
     * .close()} calls become near-free and the per-method open/close pattern in this class no
     * longer triggers a full flush per call.
     *
     * <p>{@code DB_CLOSE_ON_EXIT=FALSE} is the necessary companion: it stops H2 from registering
     * its JVM shutdown hook to close the database on exit. The shutdown hook is unsafe inside
     * Maven plugins because Plexus tears down the plugin's {@code ClassRealm} before the hook
     * fires, so the hook's call to {@code DbException.get(...)} fails with a
     * {@code NoClassDefFoundError: org/h2/api/ErrorCode}. Writes are still durable because every
     * persist method commits its transaction explicitly via {@code connection.commit()}, which
     * forces the MVStore to flush the changed pages.
     *
     * <p>In <b>server mode</b> the user-supplied URL is used as given. The embedded-only
     * options above are deliberately omitted: {@code PAGE_SIZE} / {@code CACHE_SIZE} /
     * {@code DB_CLOSE_DELAY} / {@code DB_CLOSE_ON_EXIT} configure the database engine instance,
     * which in server mode lives in the remote server process and is configured when that server
     * is started - not by the connecting client. The one transformation applied is expanding an
     * optional {@value H2ConnectionSettings#BRANCH_PLACEHOLDER} token to {@code tiadb-<branch>}
     * (see {@link #applyServerDbNamePlaceholder(String)}); a URL without the token is used verbatim
     * and per-branch isolation is then the user's responsibility.
     *
     * @return the H2 JDBC URL: the server URL (with any {@value H2ConnectionSettings#BRANCH_PLACEHOLDER}
     *         token expanded) in server mode, or the composed embedded-mode URL (with engine
     *         options) otherwise
     */
    private String buildJdbcUrl(){
        if (settings.isServerMode()){
            return applyServerDbNamePlaceholder(settings.getDbUrl());
        }

        long cacheSizeKB = Runtime.getRuntime().maxMemory() / 1024 / 2; // use half of the available memory
        long pageSizeByte = 1024 * 4 * 100; //4KB is the default, set it to 10 times the size
        // Sanitize the branch the same way server mode does: the branch name is now the short VCS
        // name (e.g. feature/foo), so a path separator must not leak into the on-disk file name.
        return "jdbc:h2:" + settings.getDbFilePath() + "/tiadb-" + sanitizeBranchForDbName(settings.getBranchSuffix())
                + ";PAGE_SIZE=" + pageSizeByte
                + ";CACHE_SIZE=" + cacheSizeKB
                + ";DB_CLOSE_DELAY=-1"
                + ";DB_CLOSE_ON_EXIT=FALSE";
    }

    /**
     * Expand the optional {@value H2ConnectionSettings#BRANCH_PLACEHOLDER} token in a server-mode
     * URL to {@code tiadb-<branch>}, giving the user a per-branch database without hand-editing the
     * URL on every branch switch (mirrors embedded mode's {@code tiadb-<branch>} file suffix). Only
     * the token itself is replaced, so any prefix or suffix the user writes around it is preserved -
     * e.g. {@code .../{branch}-myproject} becomes {@code .../tiadb-main-myproject}. When the URL does
     * not contain the token it is returned unchanged, so a fully-specified URL keeps taking
     * precedence.
     *
     * @param dbUrl the configured server-mode JDBC URL
     * @return the URL with any {@value H2ConnectionSettings#BRANCH_PLACEHOLDER} token replaced by
     *         {@code tiadb-<sanitized-branch>}, or {@code dbUrl} unchanged when the token is absent
     */
    private String applyServerDbNamePlaceholder(final String dbUrl){
        if (dbUrl == null || !dbUrl.contains(H2ConnectionSettings.BRANCH_PLACEHOLDER)){
            return dbUrl;
        }
        String dbName = "tiadb-" + sanitizeBranchForDbName(settings.getBranchSuffix());
        return dbUrl.replace(H2ConnectionSettings.BRANCH_PLACEHOLDER, dbName);
    }

    /**
     * Sanitize a branch name for use as the database-name portion of a JDBC URL. Path separators
     * ({@code /} and {@code \}) are replaced with {@code -} because a branch like {@code feature/foo}
     * would otherwise be interpreted as a nested path in the H2 database name. A {@code null} or
     * blank branch yields an empty string, leaving the {@code tiadb-} prefix on its own.
     *
     * @param branch the raw VCS branch name
     * @return the branch with path separators replaced by {@code -}, or an empty string when blank
     */
    private String sanitizeBranchForDbName(final String branch){
        if (branch == null || branch.trim().isEmpty()){
            return "";
        }
        return branch.replace('/', '-').replace('\\', '-');
    }
}
