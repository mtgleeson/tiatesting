package org.tiatesting.persistence.h2;


import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.TiaPersistenceException;

import javax.sql.ConnectionPoolDataSource;
import javax.sql.DataSource;
import javax.swing.plaf.nimbus.State;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ForkJoinPool;

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
    public boolean persistTiaData(TiaData tiaData) {
        long startTime = System.currentTimeMillis();
        Connection connection = getConnection();

        try {
            persistTiaCore(connection, tiaData);
            persistTestSuitesFailed(connection, tiaData.getTestSuitesFailed());
            persistSourceMethods(connection, tiaData.getMethodsTracked());
            persistTestSuites(connection, tiaData.getTestSuitesTracked().values());
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }

        log.debug("Time to save the Tia data to disk (ms): " + (System.currentTimeMillis() - startTime));
        return true;
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

            statement.executeUpdate(mergeSql, Statement.RETURN_GENERATED_KEYS);
            ResultSet rs = statement.getGeneratedKeys();
            rs.next();
            persistTestSuiteClasses(connection, rs.getLong(COL_ID), testSuite.getClassesImpacted());
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
        statement.executeUpdate(deleteClassMethodSql);

        String deleteClassSql = "DELETE FROM " + TABLE_TIA_SOURCE_CLASS + " WHERE " + COL_TIA_TEST_SUITE_ID + " = " + testSuiteId;
        statement.executeUpdate(deleteClassSql);

        for (ClassImpactTracker sourceClass : sourceClasses){
            String insertSql = "INSERT INTO " + TABLE_TIA_SOURCE_CLASS + " (" +
                    COL_TIA_TEST_SUITE_ID + ", " +
                    COL_SOURCE_FILENAME + ") " +
                    "VALUES (" +
                    testSuiteId + ", '" +
                    sourceClass.getSourceFilename() + "')";

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

        ps.executeBatch();
    }

    private void persistTestSuitesFailed(Connection connection, Set<String> testSuitesFailed) throws SQLException {
        if (testSuitesFailed.isEmpty()){
            return;
        }

        String truncateSql = "TRUNCATE TABLE " + TABLE_TIA_TEST_SUITES_FAILED;

        StringBuilder insertSqlBuilder = new StringBuilder("INSERT INTO " + TABLE_TIA_TEST_SUITES_FAILED + " (" + COL_TEST_SUITE_NAME + ") values ");
        for (String testSuite : testSuitesFailed){
            insertSqlBuilder.append("('" + testSuite + "'),");
        }
        String insertSql = insertSqlBuilder.toString();
        insertSql = insertSql.substring(0, insertSql.length()-1);

        Statement statement = connection.createStatement();
        statement.executeUpdate(truncateSql);
        statement.executeUpdate(insertSql);
    }

    private void persistSourceMethods(Connection connection, Map<Integer, MethodImpactTracker> sourceMethods) throws SQLException {
        if (sourceMethods.isEmpty()){
            return;
        }

        String truncateSql = "TRUNCATE TABLE " + TABLE_TIA_SOURCE_METHOD;

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

        Statement statement = connection.createStatement();
        statement.executeUpdate(truncateSql);
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
                long startQueryTime = System.currentTimeMillis();
                tiaData = getCoreData(connection);
                log.info("SQL query time for core: {}", (System.currentTimeMillis() - startQueryTime) / 1000);
                startQueryTime = System.currentTimeMillis();
                tiaData.setTestSuitesTracked(getTestSuitesData(connection));
                log.info("SQL query time for test suites: {}", (System.currentTimeMillis() - startQueryTime) / 1000);
                startQueryTime = System.currentTimeMillis();
                tiaData.setTestSuitesFailed(getTestSuitesFailed(connection));
                log.info("SQL query time for failed tests: {}", (System.currentTimeMillis() - startQueryTime) / 1000);
                startQueryTime = System.currentTimeMillis();
                tiaData.setMethodsTracked(getMethodsTracked(connection));
                log.info("SQL query time for methods tracked: {}", (System.currentTimeMillis() - startQueryTime) / 1000);

/*
                startQueryTime = System.currentTimeMillis();
                String sql = "SELECT * FROM " + TABLE_TIA_SOURCE_CLASS;
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql);
                log.info("SQL query time for all source classes: {}", (System.currentTimeMillis() - startQueryTime) / 1000);

                startQueryTime = System.currentTimeMillis();
                sql = "SELECT * FROM " + TABLE_TIA_SOURCE_CLASS_METHOD;
                Statement statement2 = connection.createStatement();
                ResultSet resultSet2 = statement2.executeQuery(sql);
                log.info("SQL query time for all source class methods: {}", (System.currentTimeMillis() - startQueryTime) / 1000);
*/
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

    private Map<String, TestSuiteTracker> getTestSuitesData(Connection connection) throws SQLException {
        Map<String, TestSuiteTracker> testSuites = new HashMap<>();
        String sql = "SELECT * FROM " + TABLE_TIA_TEST_SUITE;
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql);

        while(resultSet.next()){
            TestSuiteTracker testSuite = new TestSuiteTracker();
            testSuite.setId(resultSet.getLong(COL_ID));
            testSuite.setName(resultSet.getString(COL_NAME));
            testSuite.getTestStats().setNumRuns(resultSet.getLong(COL_NUM_RUNS));
            testSuite.getTestStats().setAvgRunTime(resultSet.getLong(COL_AVG_RUN_TIME));
            testSuite.getTestStats().setNumSuccessRuns(resultSet.getLong(COL_NUM_SUCCESS_RUNS));
            testSuite.getTestStats().setNumFailRuns(resultSet.getLong(COL_NUM_FAIL_RUNS));
            testSuites.put(testSuite.getName(), testSuite);
        }

        testSuites.values().parallelStream().forEach( testSuite -> {
            try {
                testSuite.setClassesImpacted(getSourceClasses(connection, testSuite.getId()));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });

        return testSuites;
    }

    private List<ClassImpactTracker> getSourceClasses(Connection connection, long testSuiteId) throws SQLException {
        List<ClassImpactTracker> sourceClasses = new ArrayList<>();
        long startQueryTime = System.currentTimeMillis();
        String sql = "SELECT " + COL_ID + ", " + COL_SOURCE_FILENAME + ", " + COL_TIA_SOURCE_METHOD_ID +
                " FROM " + TABLE_TIA_SOURCE_CLASS +
                " JOIN " + TABLE_TIA_SOURCE_CLASS_METHOD + " TSCM " +
                    "ON " + TABLE_TIA_SOURCE_CLASS + "." + COL_ID + " = TSCM." + COL_TIA_SOURCE_CLASS_ID +
                " WHERE " + COL_TIA_TEST_SUITE_ID + " = " + testSuiteId +
                " ORDER BY " + TABLE_TIA_SOURCE_CLASS + "." + COL_ID;

        connection = getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql);
        long currentSourceClassId = 0;
        ClassImpactTracker sourceClass = null;

        while (resultSet.next()){
            long sourceClassId = resultSet.getLong(COL_ID);

            if (sourceClassId != currentSourceClassId){
                if (sourceClass != null){
                    sourceClasses.add(sourceClass);
                }
                String classSourceFilename = resultSet.getString(COL_SOURCE_FILENAME);
                Set<Integer> sourceClassMethods = new HashSet<>();
                sourceClass = new ClassImpactTracker(classSourceFilename, sourceClassMethods);
                currentSourceClassId = sourceClassId;
            }

            sourceClass.getMethodsImpacted().add(resultSet.getInt(COL_TIA_SOURCE_METHOD_ID));
        }

        connection.close();

        // add the last source class
        if (sourceClass != null){
            sourceClasses.add(sourceClass);
        }

        return sourceClasses;
    }

    /*
    private Set<Integer> getSourceClassMethods(Connection connection, long sourceClassId) throws SQLException {
        Set<Integer> sourceClassMethods = new HashSet<>();
        String sql = "SELECT * FROM " + TABLE_TIA_SOURCE_CLASS_METHOD + " WHERE " + COL_TIA_SOURCE_CLASS_ID + " = " + sourceClassId;
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql);

        while(resultSet.next()){
            sourceClassMethods.add(resultSet.getInt(COL_TIA_SOURCE_METHOD_ID));
        }

        return sourceClassMethods;
    }
*/
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

        String createSourceClassMethodTableSql = "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_SOURCE_CLASS_METHOD +
                " (" + COL_TIA_SOURCE_CLASS_ID + " BIGINT, " +
                COL_TIA_SOURCE_METHOD_ID + " INT, " +
                "PRIMARY KEY (" + COL_TIA_SOURCE_CLASS_ID + ", " + COL_TIA_SOURCE_METHOD_ID + "))";

        try {
            Connection connection = getConnection();
            Statement statement = connection.createStatement();
            statement.executeUpdate(createCoreTableSql);
            statement.executeUpdate(createSourceMethodTableSql);
            statement.executeUpdate(createTestSuitesFailedTableSql);
            statement.executeUpdate(createTestSuiteTableSql);
            statement.executeUpdate(createTestSuiteNameIndexSql);
            statement.executeUpdate(createSourceClassTableSql);
            statement.executeUpdate(createSourceClassMethodTableSql);
            connection.close();
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
        log.info("Finished creating the Tia DB");
    }

    private boolean checkTiaDBExists(Connection connection) throws SQLException {
        boolean tiaDBExists = false;

        ResultSet rset = null;
        rset = connection.getMetaData().getTables(null, null, "TIA_CORE", new String[]{"TABLE"});
        if (rset.next()) {
            tiaDBExists = true;
        }

        return tiaDBExists;
    }

    private Connection getConnection(){
        Connection connection;
        try {
            DataSource dataSource = this.establishDataSource();
            connection = dataSource.getConnection() ;
            log.trace("Connected to the embedded H2 database {}", jdbcURL);
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
        return ds ;
    }

    private String buildJdbcUrl(){
        long cacheSizeKB = Runtime.getRuntime().maxMemory() / 1024 / 2; // use half of the available memory
        long pageSizeByte = 1024 * 4 * 100; //4KB is the default, set it to 10 times the size
        return "jdbc:h2:" + this.dataStorePath + "/tiadb-" + this.dbNameSuffix + ";PAGE_SIZE=" + pageSizeByte + ";CACHE_SIZE= " + cacheSizeKB + ";AUTO_SERVER=TRUE";
    }
}
