package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.SealedRunData;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the single-host seal of a run that selected nothing: it still advances the commit and
 * writes the history row, but leaves the method catalogue as stored - unless a suite deletion or
 * an earlier unsealed edge write left a catalogue row no edge references, which still needs the
 * rewrite to prune it. Uses a temp-directory embedded H2 database.
 */
class TestRunnerServiceNoOpCatalogueSealTest {

    private static final String SUITE = "com.example.FooTest";
    private static final String OTHER_SUITE = "com.example.BarTest";
    private static final String SOURCE_FILE = "com/example/Foo.java";
    private static final String METHOD_NAME = "com/example/Foo.save.()V";
    private static final int METHOD_ID = 7;
    private static final String OTHER_METHOD_NAME = "com/example/Foo.load.()V";
    private static final int OTHER_METHOD_ID = 8;

    private RecordingDataStore dataStore;
    private TestRunnerService service;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database with a sealed commit and build the service over it.
     *
     * @throws Exception if the temp directory cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-no-op-catalogue-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new RecordingDataStore(tempDir);
        service = new TestRunnerService(dataStore);

        TiaData tiaData = dataStore.getTiaData();
        tiaData.setCommitValue("initial");
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);
    }

    /**
     * Close the data store and remove the temp database files.
     */
    @AfterEach
    void tearDown() {
        dataStore.close();
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * A run that selected nothing and ignored every suite seals - the commit advances and the
     * history row is written - without rewriting the catalogue, which keeps its stored rows.
     */
    @Test
    void runThatSelectedNothingSealsWithoutRewritingTheCatalogue() {
        // given - a seed run in which SUITE covers METHOD_ID and OTHER_SUITE covers OTHER_METHOD_ID
        Set<String> bothSuites = new HashSet<>(Arrays.asList(SUITE, OTHER_SUITE));
        service.persistTestRunData(true, false, "commit-1", "main", System.currentTimeMillis(),
                seedResult(bothSuites), null);
        Map<Integer, MethodImpactTracker> before = dataStore.getMethodsTracked();
        TestRunResult nothingSelected = nothingSelectedResult(bothSuites);

        // when
        service.persistTestRunData(true, true, "commit-2", "main", System.currentTimeMillis(),
                nothingSelected, null);

        // then
        assertFalse(nothingSelected.ranNoExpectedSuites());
        assertFalse(dataStore.lastSealedRunData.isMethodCatalogueRewritten());
        assertEquals("commit-2", dataStore.getTiaCore().getCommitValue());
        assertEquals(1, dataStore.readTestRunHistory().size());
        Map<Integer, MethodImpactTracker> after = dataStore.getMethodsTracked();
        assertEquals(before.keySet(), after.keySet());
        assertEquals(before.get(METHOD_ID).getExecutedRunCount(), after.get(METHOD_ID).getExecutedRunCount());
        assertEquals(before.get(METHOD_ID).getLineNumberStart(), after.get(METHOD_ID).getLineNumberStart());
    }

    /**
     * An earlier run rewrote OTHER_SUITE's edges so they no longer reference OTHER_METHOD_ID, then
     * stopped before its seal, leaving a catalogue row no edge references. A later run that
     * selected nothing must still rewrite the catalogue, pruning that row, as the full rebuild
     * always has.
     */
    @Test
    void runThatSelectedNothingStillPrunesACatalogueRowAnUnsealedEdgeWriteLeftUnreferenced() {
        // given - a seed run in which SUITE covers METHOD_ID and OTHER_SUITE covers OTHER_METHOD_ID,
        // then an unsealed edge write moving OTHER_SUITE onto METHOD_ID
        Set<String> bothSuites = new HashSet<>(Arrays.asList(SUITE, OTHER_SUITE));
        service.persistTestRunData(true, false, "commit-1", "main", System.currentTimeMillis(),
                seedResult(bothSuites), null);
        dataStore.persistTestSuites(Collections.singletonMap(OTHER_SUITE, suite(OTHER_SUITE, METHOD_ID)));
        TestRunResult nothingSelected = nothingSelectedResult(bothSuites);

        // when
        service.persistTestRunData(true, false, "commit-2", "main", System.currentTimeMillis(),
                nothingSelected, null);

        // then
        assertTrue(dataStore.lastSealedRunData.isMethodCatalogueRewritten());
        Map<Integer, MethodImpactTracker> after = dataStore.getMethodsTracked();
        assertTrue(after.containsKey(METHOD_ID));
        assertFalse(after.containsKey(OTHER_METHOD_ID));
    }

    /**
     * A run that selected nothing and no longer discovers OTHER_SUITE deletes that suite with its
     * coverage, leaving OTHER_METHOD_ID referenced by no edge, so the seal rewrites the catalogue
     * and prunes it.
     */
    @Test
    void runThatSelectedNothingButDeletedASuitePrunesTheMethodOnlyThatSuiteCovered() {
        // given - a seed run in which SUITE covers METHOD_ID and OTHER_SUITE covers OTHER_METHOD_ID
        Set<String> bothSuites = new HashSet<>(Arrays.asList(SUITE, OTHER_SUITE));
        service.persistTestRunData(true, false, "commit-1", "main", System.currentTimeMillis(),
                seedResult(bothSuites), null);
        TestRunResult nothingSelected = nothingSelectedResult(Collections.singleton(SUITE));

        // when
        service.persistTestRunData(true, false, "commit-2", "main", System.currentTimeMillis(),
                nothingSelected, null);

        // then
        assertTrue(dataStore.lastSealedRunData.isMethodCatalogueRewritten());
        Map<Integer, MethodImpactTracker> after = dataStore.getMethodsTracked();
        assertTrue(after.containsKey(METHOD_ID));
        assertFalse(after.containsKey(OTHER_METHOD_ID));
    }

    /**
     * Build the result of a seed run that executed both suites, SUITE covering METHOD_ID and
     * OTHER_SUITE covering OTHER_METHOD_ID.
     *
     * @param runnerSuites every suite the runner knows of
     * @return the run result
     */
    private TestRunResult seedResult(Set<String> runnerSuites) {
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        trackers.put(SUITE, suite(SUITE, METHOD_ID));
        trackers.put(OTHER_SUITE, suite(OTHER_SUITE, OTHER_METHOD_ID));

        Map<Integer, MethodImpactTracker> observed = new HashMap<>();
        observed.put(METHOD_ID, new MethodImpactTracker(METHOD_NAME, 3, 6));
        observed.put(OTHER_METHOD_ID, new MethodImpactTracker(OTHER_METHOD_NAME, 8, 10));

        return new TestRunResult(trackers, new HashSet<>(), runnerSuites, runnerSuites,
                new HashSet<>(runnerSuites), observed, new TestStats(), null, 0, runnerSuites.size(), 0,
                TestRunSelectionDetails.empty(), RunAttempt.FIRST);
    }

    /**
     * Build the result of a run whose selection was empty: Tia ignored every suite the runner
     * knows of, so nothing ran, nothing was observed and nothing was covered or triggered.
     *
     * @param runnerSuites every suite the runner knows of; a tracked suite missing here is deleted
     * @return the run result
     */
    private TestRunResult nothingSelectedResult(Set<String> runnerSuites) {
        return new TestRunResult(new HashMap<>(), new HashSet<>(), runnerSuites, new HashSet<>(),
                new HashSet<>(), new HashMap<>(), new TestStats(), null, runnerSuites.size(), 0, 0,
                TestRunSelectionDetails.empty(), RunAttempt.FIRST);
    }

    /**
     * Build a suite tracker whose one covered class references a single method.
     *
     * @param suiteName the suite name
     * @param methodId the covered method id
     * @return the suite tracker
     */
    private TestSuiteTracker suite(String suiteName, int methodId) {
        TestSuiteTracker suite = new TestSuiteTracker(suiteName);
        suite.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker(SOURCE_FILE, new HashSet<>(Collections.singleton(methodId)))));
        return suite;
    }

    /** An embedded-H2 store that keeps the last seal bundle it was handed. */
    private static final class RecordingDataStore extends JdbcDataStore {

        private SealedRunData lastSealedRunData;

        /**
         * Open an embedded H2 store in the given directory.
         *
         * @param dir the directory holding the H2 files
         */
        RecordingDataStore(File dir) {
            super(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(dir.getAbsolutePath())),
                    BranchSchema.schemaName("test", null));
        }

        /**
         * Keep and delegate the seal bundle.
         *
         * @param sealedRunData the bundle to write
         */
        @Override
        public void persistSealedRunData(SealedRunData sealedRunData) {
            lastSealedRunData = sealedRunData;
            super.persistSealedRunData(sealedRunData);
        }
    }
}
