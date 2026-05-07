package org.tiatesting.core.persistence.h2;


import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodIdSet;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
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
    private final Logger log = LoggerFactory.getLogger(H2DataStore.class);
    private final String jdbcURL;
    private final String username = "sa";
    private final String password = "1234";
    private final String dbNameSuffix;
    private final String dataStorePath;

    public H2DataStore(String dataStorePath, String dbNameSuffix){
        this.dataStorePath = dataStorePath;
        this.dbNameSuffix = dbNameSuffix;
        this.jdbcURL = buildJdbcUrl();

        log.info("Using H2 as the Tia datastore with the connection: {}", this.jdbcURL);
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
                    + " WHERE " + COL_GROUP_ARTIFACT + " = ? ORDER BY " + COL_STAMP_VERSION;
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
            String sql = "SELECT * FROM " + TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD
                    + " ORDER BY " + COL_GROUP_ARTIFACT + ", " + COL_STAMP_VERSION;
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

    /**
     * Group flat pending rows from a result set into batches keyed by
     * {@code (groupArtifact, stampVersion)}.
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
            sql = "INSERT INTO " + TABLE_TIA_CORE + " (" + COL_COMMIT_VALUE + ", " + COL_LAST_UPDATED + ", " + COL_NUM_RUNS + ", " +
                    COL_AVG_RUN_TIME + ", " + COL_NUM_SUCCESS_RUNS + ", " + COL_NUM_FAIL_RUNS + ") values ('" +
                    tiaData.getCommitValue() + "', '" +
                    tiaData.getLastUpdated() + "', " +
                    tiaData.getTestStats().getNumRuns() + ", " +
                    tiaData.getTestStats().getAvgRunTime()  + ", " +
                    tiaData.getTestStats().getNumSuccessRuns()  + ", " +
                    tiaData.getTestStats().getNumFailRuns() + ")";
        }else{
            sql = "UPDATE " + TABLE_TIA_CORE + " SET " +
                    COL_COMMIT_VALUE + "='" + tiaData.getCommitValue() +
                    "', " + COL_LAST_UPDATED + "='" + tiaData.getLastUpdated() +
                    "', " + COL_NUM_RUNS + "=" + tiaData.getTestStats().getNumRuns() +
                    ", " + COL_AVG_RUN_TIME + "=" + tiaData.getTestStats().getAvgRunTime() +
                    ", " + COL_NUM_SUCCESS_RUNS + "=" + tiaData.getTestStats().getNumSuccessRuns() +
                    ", " + COL_NUM_FAIL_RUNS + "=" + tiaData.getTestStats().getNumFailRuns();
        }

        log.debug("Persisting Tia core data: {}", sql);
        Statement statement = connection.createStatement();
        statement.executeUpdate(sql);
    }

    private void persistTestSuites(Connection connection, Collection<TestSuiteTracker> testSuites) throws SQLException {
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

            // only update the source classes mapping for the test suite if mapping data exists for this test run
            if (!testSuite.getClassesImpacted().isEmpty()){
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

        Statement statement = connection.createStatement();

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
            ResultSet rs = statement.getGeneratedKeys();
            rs.next();
            persistTestSuiteClassMethods(connection, rs.getLong(COL_ID), sourceClass.getMethodsImpacted());
        }
    }

    private void persistTestSuiteClassMethods(Connection connection, long testSuiteClassId, Set<Integer> sourceMethodIds) throws SQLException {
        if (sourceMethodIds.isEmpty()){
            return;
        }

        String insertSql = "INSERT INTO " + TABLE_TIA_SOURCE_CLASS_METHOD + " (" +
                COL_TIA_SOURCE_CLASS_ID + ", " +
                COL_TIA_SOURCE_METHOD_ID + ") " +
                "VALUES (? , ?)";

        connection.setAutoCommit(true);
        PreparedStatement ps = connection.prepareStatement(insertSql);

        for (Integer sourceMethodId : sourceMethodIds){
            ps.setLong(1, testSuiteClassId);
            ps.setLong(2, sourceMethodId);
            ps.addBatch();
        }

        log.debug("Persisting test suite class methods: {}", insertSql);
        ps.executeBatch();
    }

    private void persistTestSuitesFailed(Connection connection, Set<String> testSuitesFailed) throws SQLException {
        if (testSuitesFailed == null){
            return;
        }

        String truncateSql = "TRUNCATE TABLE " + TABLE_TIA_TEST_SUITES_FAILED;
        log.debug("Truncating failed test suites: {}", truncateSql);
        Statement statement = connection.createStatement();
        statement.executeUpdate(truncateSql);

        if (testSuitesFailed.isEmpty()){
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
    }

    private void persistSourceMethods(Connection connection, Map<Integer, MethodImpactTracker> sourceMethods) throws SQLException {
        if (sourceMethods == null){
            return;
        }

        String truncateSql = "TRUNCATE TABLE " + TABLE_TIA_SOURCE_METHOD;
        log.debug("Truncating indexed source methods: {}", truncateSql);
        Statement statement = connection.createStatement();
        statement.executeUpdate(truncateSql);

        if (sourceMethods.isEmpty()){
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
                startQueryTime = System.currentTimeMillis();
                tiaData.setPendingLibraryImpactedMethods(readAllPendingLibraryImpactedMethods());
                log.debug("SQL query time for pending library methods: {}", (System.currentTimeMillis() - startQueryTime) / 1000);
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
     * 1000 random-access B-tree walks all serialised on the same MVStore file. A single sequential
     * left-join scan amortises the per-row cost over one cursor and lets the OS page cache stream
     * the file linearly.
     *
     * <p>The output rows are ordered by {@code (tia_test_suite_id, tia_source_class_id)} so the
     * reducer can detect group boundaries by comparing each row's id columns to the previous row's.
     * {@code LEFT JOIN}s preserve the existing behaviour: a test suite with no classes still appears
     * in the returned map (with an empty {@code classesImpacted} list); a class row with no
     * matching method edge is dropped (matches the previous {@code INNER JOIN} between
     * {@code tia_source_class} and {@code tia_source_class_method}).
     */
    private Map<String, TestSuiteTracker> getTestSuitesData(Connection connection, boolean loadClassesData) throws SQLException {
        if (!loadClassesData) {
            return loadTestSuitesMetadataOnly(connection);
        }

        Map<String, TestSuiteTracker> testSuites = new HashMap<>();

        // Aliased columns let us pull the suite + class + method data out of one cursor without
        // ambiguous-column errors on the shared "id" / "source_filename" names.
        String sql = "SELECT ts." + COL_ID + " AS suite_id, ts." + COL_NAME + " AS suite_name, " +
                "ts." + COL_NUM_RUNS + " AS suite_num_runs, ts." + COL_AVG_RUN_TIME + " AS suite_avg_run_time, " +
                "ts." + COL_NUM_SUCCESS_RUNS + " AS suite_num_success_runs, ts." + COL_NUM_FAIL_RUNS + " AS suite_num_fail_runs, " +
                "sc." + COL_ID + " AS class_id, sc." + COL_SOURCE_FILENAME + " AS class_source_filename, " +
                "scm." + COL_TIA_SOURCE_METHOD_ID + " AS method_id " +
                "FROM " + TABLE_TIA_TEST_SUITE + " ts " +
                "LEFT JOIN " + TABLE_TIA_SOURCE_CLASS + " sc ON sc." + COL_TIA_TEST_SUITE_ID + " = ts." + COL_ID + " " +
                "LEFT JOIN " + TABLE_TIA_SOURCE_CLASS_METHOD + " scm ON scm." + COL_TIA_SOURCE_CLASS_ID + " = sc." + COL_ID + " " +
                "ORDER BY ts." + COL_ID + ", sc." + COL_ID;

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {

            TestSuiteTracker currentSuite = null;
            long currentSuiteId = -1;
            ClassImpactTracker currentClass = null;
            long currentClassId = -1;

            while (rs.next()) {
                long suiteId = rs.getLong("suite_id");

                if (suiteId != currentSuiteId) {
                    // Flush the in-progress class onto the previous suite before moving on.
                    if (currentClass != null && currentSuite != null) {
                        currentSuite.getClassesImpacted().add(currentClass);
                    }
                    currentClass = null;
                    currentClassId = -1;

                    currentSuite = new TestSuiteTracker();
                    currentSuite.setId(suiteId);
                    currentSuite.setName(rs.getString("suite_name"));
                    currentSuite.getTestStats().setNumRuns(rs.getLong("suite_num_runs"));
                    currentSuite.getTestStats().setAvgRunTime(rs.getLong("suite_avg_run_time"));
                    currentSuite.getTestStats().setNumSuccessRuns(rs.getLong("suite_num_success_runs"));
                    currentSuite.getTestStats().setNumFailRuns(rs.getLong("suite_num_fail_runs"));
                    currentSuite.setClassesImpacted(new ArrayList<>());
                    testSuites.put(currentSuite.getName(), currentSuite);
                    currentSuiteId = suiteId;
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

                if (classIdValue != currentClassId) {
                    if (currentClass != null) {
                        // Lock in the previous class's method-id set before moving on.
                        currentClass.getMethodsImpacted().finishBulkBuild();
                        currentSuite.getClassesImpacted().add(currentClass);
                    }
                    currentClass = new ClassImpactTracker(rs.getString("class_source_filename"), new MethodIdSet());
                    currentClassId = classIdValue;
                }

                // appendForBulkBuild avoids the per-row Integer.valueOf allocation and the
                // O(n) shift that add(int) does to keep the array sorted; finishBulkBuild
                // sorts + dedupes once when we're done with this class.
                currentClass.getMethodsImpacted().appendForBulkBuild(methodId);
            }

            // Flush the final class onto the final suite.
            if (currentClass != null && currentSuite != null) {
                currentClass.getMethodsImpacted().finishBulkBuild();
                currentSuite.getClassesImpacted().add(currentClass);
            }
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

    private String buildJdbcUrl(){
        long cacheSizeKB = Runtime.getRuntime().maxMemory() / 1024 / 2; // use half of the available memory
        long pageSizeByte = 1024 * 4 * 100; //4KB is the default, set it to 10 times the size
        //return "jdbc:h2:" + this.dataStorePath + "/tiadb-" + this.dbNameSuffix + ";PAGE_SIZE=" + pageSizeByte + ";CACHE_SIZE= " + cacheSizeKB + ";AUTO_SERVER=TRUE"; // using AUTO_SERVER adds about 15secs when it connects to the DB in the JVM for the first time, not sure if its needed?
        return "jdbc:h2:" + this.dataStorePath + "/tiadb-" + this.dbNameSuffix + ";PAGE_SIZE=" + pageSizeByte + ";CACHE_SIZE=" + cacheSizeKB;
    }
}
