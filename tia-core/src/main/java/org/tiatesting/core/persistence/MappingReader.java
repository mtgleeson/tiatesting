package org.tiatesting.core.persistence;

import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodIdSet;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.dialect.SqlDialect;

import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

import static org.tiatesting.core.persistence.JdbcRows.*;
import static org.tiatesting.core.persistence.TiaSchema.*;

/**
 * Reads the suite-to-method mapping and the core row. Holds both the targeted queries the
 * select-tests hot path uses (changed files to tracked methods, methods to covering suites, each
 * one indexed query per {@value JdbcRepository#IN_CLAUSE_CHUNK_SIZE}-key chunk) and the full-load
 * reads behind {@link JdbcDataStore#getTiaData}. Performance-sensitive: see the Performance notes
 * in {@code CLAUDE.md} and the "Profiling select-tests against a synthetic large DB" chapter in
 * {@code WIKI.md}.
 */
class MappingReader extends JdbcRepository {

    /**
     * Construct the repository over the datastore's shared JDBC plumbing.
     *
     * @param dialect the SQL dialect rendering vendor-specific statements
     * @param connections opens a connection with the per-branch schema selected
     * @param schemaManager bootstraps the schema before first use
     */
    MappingReader(SqlDialect dialect, Supplier<Connection> connections, SchemaManager schemaManager) {
        super(dialect, connections, schemaManager);
    }

    /**
     * Read the core row on its own, bootstrapping the schema first since the targeted select-tests
     * path can be the first contact with the DB. Implements {@link DataStore#getTiaCore}.
     *
     * @return the core data; empty when no row has been written yet
     */
    TiaData getTiaCore(){
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

    /**
     * Read every tracked suite's row and stats, without its class and method edges. Implements
     * {@link DataStore#getTestSuitesTracked}.
     *
     * @return the tracked suites keyed by name
     */
    Map<String, TestSuiteTracker> getTestSuitesTracked(){
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
     * Implements {@link DataStore#readTestSuiteCoverage}. One indexed join per {@value JdbcRepository#IN_CLAUSE_CHUNK_SIZE} suite names - suite by
     * name, its class rows by suite id, their edges by class id - so the cost scales with the
     * named suites' coverage, not the size of the mapping.
     *
     * @param suiteNames the suites whose coverage to read
     * @return each named suite's stored class trackers, keyed by suite name
     */
    Map<String, List<ClassImpactTracker>> readTestSuiteCoverage(final Set<String> suiteNames){
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

    /**
     * Read the full source method catalogue over its own connection. Implements {@link
     * DataStore#getMethodsTracked}.
     *
     * @return every tracked method keyed by id
     */
    Map<Integer, MethodImpactTracker> getMethodsTracked(){
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

    /**
     * Read the distinct ids of the methods any suite covers. Implements {@link
     * DataStore#getUniqueMethodIdsTracked}.
     *
     * @return the covered method ids
     */
    Set<Integer> getUniqueMethodIdsTracked(){
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
     * Check for a catalogue row that no edge references, over its own connection. The anti-join is
     * served by {@code idx_source_class_method_method_id} and stops at the first hit, so it costs
     * far less than the full catalogue and edge-id reads a catalogue rebuild makes. Implements
     * {@link DataStore#hasUnreferencedSourceMethods}.
     *
     * @return true when at least one catalogue row is unreferenced
     */
    boolean hasUnreferencedSourceMethods() {
        String sql = "SELECT 1 FROM " + TABLE_TIA_SOURCE_METHOD + " m WHERE NOT EXISTS (SELECT 1 FROM "
                + TABLE_TIA_SOURCE_CLASS_METHOD + " e WHERE e." + COL_TIA_SOURCE_METHOD_ID
                + " = m." + COL_ID + ") LIMIT 1";
        Connection connection = getConnection();

        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next();
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
     * Targeted changed-files-to-tracked-methods read: resolve a set of stored source-file keys to their tracked
     * methods with one indexed query per {@value JdbcRepository#IN_CLAUSE_CHUNK_SIZE}-key chunk, instead
     * of loading the full suite-to-method mapping. {@code SELECT DISTINCT} collapses the
     * per-covering-suite duplication of (file, method) pairs in {@code tia_source_class} so
     * the result (and the bytes on the wire in server mode) stays proportional to the
     * methods in the requested files, not to the suites covering them.
     *
     * @param sourceFilenames the mapping keys of the source files to look up
     * @return map of source filename to (method id to method tracker); empty when the input
     *         is null or empty
     */
    Map<String, Map<Integer, MethodImpactTracker>> getMethodsTrackedForFiles(final Set<String> sourceFilenames){
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
     * suites covering them with one indexed query per {@value JdbcRepository#IN_CLAUSE_CHUNK_SIZE}-id
     * chunk, instead of building the full in-memory method-to-suites reverse index.
     *
     * @param methodIds the tracked method ids to find covering test suites for
     * @return map of method id to covering test-suite names; empty when the input is null
     *         or empty
     */
    Map<Integer, Set<String>> getTestSuitesForMethods(final Set<Integer> methodIds){
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

    /**
     * Count the tracked test suites. Implements {@link DataStore#getNumTestSuites}.
     *
     * @return the number of suite rows
     */
    int getNumTestSuites(){
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

    /**
     * Count the methods in the source method catalogue. Implements {@link
     * DataStore#getNumSourceMethods}.
     *
     * @return the number of method rows
     */
    int getNumSourceMethods(){
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

    /**
     * Read the failed-suite set over its own connection. Implements {@link
     * DataStore#getTestSuitesFailed}.
     *
     * @return the names of the suites whose latest run failed
     */
    Set<String> getTestSuitesFailed(){
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

    /**
     * Read the catalogue rows for the given method ids, one indexed query per {@value
     * JdbcRepository#IN_CLAUSE_CHUNK_SIZE}-id chunk. Implements {@link
     * DataStore#getMethodsTrackedForIds}.
     *
     * @param methodIds the method ids to read
     * @return the found methods keyed by id; empty when the input is null or empty
     */
    Map<Integer, MethodImpactTracker> getMethodsTrackedForIds(final Set<Integer> methodIds) {
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
     * Read the core row on a caller-supplied connection, so the seal can read it inside its
     * transaction.
     *
     * @param connection the connection to read on
     * @return the core data; empty (null commit value) when no row has been written yet
     * @throws SQLException if the statement fails
     */
    TiaData getCoreData(Connection connection) throws SQLException {
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

    /**
     * Read the failed-suite set on a caller-supplied connection.
     *
     * @param connection the connection to read on
     * @return the names of the suites whose latest run failed
     * @throws SQLException if the statement fails
     */
    Set<String> getTestSuitesFailed(Connection connection) throws SQLException {
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
    Map<Integer, MethodImpactTracker> getMethodsTracked(Connection connection) throws SQLException {
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
    Map<String, TestSuiteTracker> getTestSuitesData(Connection connection, boolean loadClassesData) throws SQLException {
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
}
