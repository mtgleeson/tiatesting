package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link JdbcDataStore#hasUnreferencedSourceMethods()}: it reports a catalogue row that no
 * suite-to-method edge references, and nothing else - an empty catalogue, a fully referenced one
 * and an edge with no catalogue row (an orphan the other way round) all report false. Uses a
 * temp-directory embedded H2 database.
 */
class JdbcDataStoreUnreferencedSourceMethodsTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database with the full schema and no mapping rows.
     *
     * @throws Exception if the temp directory cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-unreferenced-methods-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
        dataStore.getTiaData();
    }

    /**
     * Close the data store and remove the temp database files.
     */
    @AfterEach
    void tearDown() {
        dataStore.close();
        deleteRecursively(tempDir);
    }

    /**
     * A schema with no catalogue rows has nothing unreferenced.
     */
    @Test
    void emptyCatalogueHasNoUnreferencedRows() {
        // given - a fresh schema with no catalogue rows

        // when
        boolean unreferenced = dataStore.hasUnreferencedSourceMethods();

        // then
        assertFalse(unreferenced);
    }

    /**
     * A catalogue whose every row is referenced by an edge has nothing unreferenced.
     */
    @Test
    void catalogueFullyReferencedByEdgesHasNoUnreferencedRows() {
        // given
        persistSuiteEdges("com.example.CarTest", 1, 2);
        persistCatalogue(1, 2);

        // when
        boolean unreferenced = dataStore.hasUnreferencedSourceMethods();

        // then
        assertFalse(unreferenced);
    }

    /**
     * A catalogue row no edge references is reported.
     */
    @Test
    void catalogueRowWithNoEdgeIsReported() {
        // given
        persistSuiteEdges("com.example.CarTest", 1);
        persistCatalogue(1, 2);

        // when
        boolean unreferenced = dataStore.hasUnreferencedSourceMethods();

        // then
        assertTrue(unreferenced);
    }

    /**
     * An edge whose method id has no catalogue row is an orphan edge, not an unreferenced catalogue
     * row - a rebuild drops it from the output, and it is absent from the stored table anyway.
     */
    @Test
    void edgeWithNoCatalogueRowIsNotReported() {
        // given
        persistSuiteEdges("com.example.CarTest", 1, 3);
        persistCatalogue(1);

        // when
        boolean unreferenced = dataStore.hasUnreferencedSourceMethods();

        // then
        assertFalse(unreferenced);
    }

    /**
     * Write one suite whose single covered class references the given method ids.
     *
     * @param suiteName the suite to write
     * @param methodIds the method ids the suite's edges reference
     */
    private void persistSuiteEdges(String suiteName, Integer... methodIds) {
        Set<Integer> ids = new HashSet<>();
        Collections.addAll(ids, methodIds);
        TestSuiteTracker suite = new TestSuiteTracker(suiteName);
        suite.setClassesImpacted(Collections.singletonList(new ClassImpactTracker("com/example/Car.java", ids)));
        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put(suiteName, suite);
        dataStore.persistTestSuites(suites);
    }

    /**
     * Write a catalogue holding one method per given id.
     *
     * @param methodIds the ids to catalogue
     */
    private void persistCatalogue(Integer... methodIds) {
        Map<Integer, MethodImpactTracker> catalogue = new HashMap<>();
        for (Integer id : methodIds) {
            catalogue.put(id, new MethodImpactTracker("com/example/Car.m" + id + ".()V", id * 10, id * 10 + 5));
        }
        dataStore.persistSourceMethods(catalogue);
    }

    /**
     * Recursively delete a directory and its contents, best-effort.
     *
     * @param file the file or directory to delete
     */
    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
