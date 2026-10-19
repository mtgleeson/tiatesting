package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.CoreStatsIncrement;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link DataStore#deleteTestSuites}: deleting a suite removes its class and edge rows along
 * with its suite row, leaving every other suite's coverage as stored, so the next seal's catalogue
 * rebuild prunes the methods only the deleted suite covered. Runs on embedded H2; the Postgres
 * mirror re-runs it against the other vendor's SQL.
 */
class DeleteTestSuitesTest {

    private static final String DELETED = "com.example.DeletedTest";
    private static final String KEPT = "com.example.KeptTest";

    DataStore dataStore;
    private File tempDir;

    /**
     * Open the store under test. Overridden by the Postgres mirror so the same assertions run
     * against the other vendor's SQL.
     *
     * @return an open datastore the fixture owns and closes
     * @throws Exception if the store cannot be opened
     */
    DataStore openStore() throws Exception {
        tempDir = File.createTempFile("tia-delete-suites-", "");
        tempDir.delete();
        tempDir.mkdirs();
        return new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
    }

    /**
     * Open a fresh store holding two suites: DELETED covers methods 1 and 2 through one class, and
     * KEPT covers methods 2 and 3 through another. The catalogue holds methods 1, 2 and 3.
     *
     * @throws Exception if the store cannot be opened
     */
    @BeforeEach
    void setUp() throws Exception {
        dataStore = openStore();
        dataStore.getTiaData();

        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put(DELETED, suite(DELETED, "com/example/Deleted.java", 1, 2));
        suites.put(KEPT, suite(KEPT, "com/example/Kept.java", 2, 3));
        dataStore.persistTestSuites(suites);

        Map<Integer, MethodImpactTracker> catalogue = new HashMap<>();
        for (int id = 1; id <= 3; id++) {
            catalogue.put(id, new MethodImpactTracker("com/example/M.m" + id + ".()V", id * 10, id * 10 + 2));
        }
        dataStore.persistSourceMethods(catalogue);
    }

    /**
     * Close the store and remove any temp directory {@link #openStore()} created.
     */
    @AfterEach
    void tearDown() {
        if (dataStore != null) {
            dataStore.close();
        }
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * Deleting a suite removes its suite row, its one class row and its two edges, and leaves the
     * other suite's class row and edges as stored.
     *
     * @throws SQLException if a row count cannot be read
     */
    @Test
    void deletingASuiteRemovesItsClassAndEdgeRows() throws SQLException {
        // given - two suites, one class row and two edges each

        // when
        dataStore.deleteTestSuites(Collections.singleton(DELETED));

        // then
        assertFalse(dataStore.getTestSuitesTracked().containsKey(DELETED));
        assertEquals(1, countRows(TiaSchema.TABLE_TIA_SOURCE_CLASS));
        assertEquals(2, countRows(TiaSchema.TABLE_TIA_SOURCE_CLASS_METHOD));
        assertEquals(new HashSet<>(Arrays.asList(2, 3)), dataStore.getUniqueMethodIdsTracked());
        assertEquals(new HashSet<>(Arrays.asList(2, 3)), new HashSet<>(
                dataStore.readTestSuiteCoverage(Collections.singleton(KEPT)).get(KEPT).get(0).getMethodsImpacted()));
    }

    /**
     * After the delete, the method only the deleted suite covered is referenced by no edge, so the
     * next seal rewrites the catalogue and drops it, while the method both suites covered stays.
     */
    @Test
    void theNextSealPrunesTheMethodOnlyTheDeletedSuiteCovered() {
        // given
        dataStore.deleteTestSuites(Collections.singleton(DELETED));
        TiaData tiaData = dataStore.getTiaCore();
        tiaData.setCommitValue("commitB");
        tiaData.setLastUpdated(Instant.now());

        // when
        dataStore.persistSealedRunData(new SealedRunDataAssembler(dataStore).assemble(tiaData,
                Collections.emptyMap(), null, "commitB", false, CoreStatsIncrement.none(), false, true,
                Collections.emptySet(), Collections.emptySet()));

        // then
        assertEquals(new HashSet<>(Arrays.asList(2, 3)), dataStore.getMethodsTracked().keySet());
        assertFalse(dataStore.hasUnreferencedSourceMethods());
    }

    /**
     * Deleting a suite name that is not tracked changes nothing.
     *
     * @throws SQLException if a row count cannot be read
     */
    @Test
    void deletingAnUntrackedSuiteChangesNothing() throws SQLException {
        // given - two tracked suites, neither named "com.example.UnknownTest"

        // when
        dataStore.deleteTestSuites(Collections.singleton("com.example.UnknownTest"));

        // then
        assertEquals(2, dataStore.getTestSuitesTracked().size());
        assertEquals(2, countRows(TiaSchema.TABLE_TIA_SOURCE_CLASS));
        assertEquals(4, countRows(TiaSchema.TABLE_TIA_SOURCE_CLASS_METHOD));
        assertTrue(dataStore.getMethodsTracked().keySet().containsAll(Arrays.asList(1, 2, 3)));
    }

    /**
     * Build a suite whose single class covers the given methods.
     *
     * @param suiteName the suite name
     * @param sourceFile the covered class's source file
     * @param methodIds the method ids the class covers
     * @return the suite tracker
     */
    private static TestSuiteTracker suite(String suiteName, String sourceFile, Integer... methodIds) {
        TestSuiteTracker suite = new TestSuiteTracker(suiteName);
        suite.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker(sourceFile, new HashSet<>(Arrays.asList(methodIds)))));
        return suite;
    }

    /**
     * Count every row in a table of the store's schema.
     *
     * @param table the table to count
     * @return the row count
     * @throws SQLException if the count fails
     */
    private long countRows(String table) throws SQLException {
        try (Connection connection = ((JdbcDataStore) dataStore).getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
