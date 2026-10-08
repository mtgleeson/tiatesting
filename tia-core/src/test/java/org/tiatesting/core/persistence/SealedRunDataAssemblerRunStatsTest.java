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
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests how {@link SealedRunDataAssembler} accumulates the per-method run stats when it rebuilds
 * the method catalogue: stored counts are carried onto freshly observed trackers, each observed
 * method's executed-run count goes up by one for a counted run, and a retry adds nothing. Uses a
 * temp-directory embedded H2 database so the assembler reads a real edge table and catalogue.
 */
class SealedRunDataAssemblerRunStatsTest {

    private static final int EXECUTED_ID = 1;
    private static final int NOT_EXECUTED_ID = 2;
    private static final int NEW_ID = 3;

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database whose edge table references methods 1, 2 and 3, and whose
     * catalogue already holds methods 1 and 2 with stored run counts.
     *
     * @throws Exception if the temp directory cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-assembler-run-stats-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
        dataStore.getTiaData();

        TestSuiteTracker suite = new TestSuiteTracker("com.example.CarTest");
        suite.setClassesImpacted(Collections.singletonList(new ClassImpactTracker("com/example/Car.java",
                new HashSet<>(Arrays.asList(EXECUTED_ID, NOT_EXECUTED_ID, NEW_ID)))));
        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put(suite.getName(), suite);
        dataStore.persistTestSuites(suites);

        Map<Integer, MethodImpactTracker> stored = new HashMap<>();
        stored.put(EXECUTED_ID, tracker("com/example/Car.drive.()V", 10, 4, 2));
        stored.put(NOT_EXECUTED_ID, tracker("com/example/Car.park.()V", 20, 7, 1));
        dataStore.persistSourceMethods(stored);
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
     * A counted run adds one to each observed method's executed-run count on top of its stored
     * counts, starts a newly catalogued method at one, and leaves an unobserved method as stored.
     */
    @Test
    void countedRunIncrementsObservedMethodsAndCarriesStoredCounts() {
        // given
        Map<Integer, MethodImpactTracker> observed = observedTrackers();

        // when
        Map<Integer, MethodImpactTracker> catalogue = assemble(observed, true);

        // then
        assertEquals(5, catalogue.get(EXECUTED_ID).getExecutedRunCount());
        assertEquals(2, catalogue.get(EXECUTED_ID).getTriggeredRunCount());
        assertEquals(7, catalogue.get(NOT_EXECUTED_ID).getExecutedRunCount());
        assertEquals(1, catalogue.get(NOT_EXECUTED_ID).getTriggeredRunCount());
        assertEquals(1, catalogue.get(NEW_ID).getExecutedRunCount());
        assertEquals(0, catalogue.get(NEW_ID).getTriggeredRunCount());
    }

    /**
     * A retry of failed tests carries the stored counts onto the observed trackers but adds no
     * execution, since the first attempt already counted the run.
     */
    @Test
    void uncountedRunCarriesStoredCountsWithoutIncrementing() {
        // given
        Map<Integer, MethodImpactTracker> observed = observedTrackers();

        // when
        Map<Integer, MethodImpactTracker> catalogue = assemble(observed, false);

        // then
        assertEquals(4, catalogue.get(EXECUTED_ID).getExecutedRunCount());
        assertEquals(2, catalogue.get(EXECUTED_ID).getTriggeredRunCount());
        assertEquals(7, catalogue.get(NOT_EXECUTED_ID).getExecutedRunCount());
        assertEquals(0, catalogue.get(NEW_ID).getExecutedRunCount());
    }

    /**
     * The accumulated counts are what the seal writes, so they read back from the catalogue.
     */
    @Test
    void sealPersistsAccumulatedCounts() {
        // given
        TiaData tiaData = dataStore.getTiaCore();
        tiaData.setCommitValue("commitB");
        tiaData.setBranch("main");
        tiaData.setLastUpdated(Instant.now());

        // when
        dataStore.persistSealedRunData(new SealedRunDataAssembler(dataStore).assemble(tiaData,
                observedTrackers(), null, "commitB", false, CoreStatsIncrement.none(), false, true));
        Map<Integer, MethodImpactTracker> read = dataStore.getMethodsTracked();

        // then
        assertEquals(5, read.get(EXECUTED_ID).getExecutedRunCount());
        assertEquals(2, read.get(EXECUTED_ID).getTriggeredRunCount());
        assertEquals(1, read.get(NEW_ID).getExecutedRunCount());
    }

    /**
     * Run the assembler for a selective run with no library drain and return the catalogue it built.
     *
     * @param observed the trackers observed by the run being sealed
     * @param countRun whether the seal counts as a run in the per-method stats
     * @return the rebuilt catalogue keyed by method id
     */
    private Map<Integer, MethodImpactTracker> assemble(Map<Integer, MethodImpactTracker> observed, boolean countRun) {
        TiaData tiaData = dataStore.getTiaCore();
        return new SealedRunDataAssembler(dataStore).assemble(tiaData, observed, null, "commitB",
                false, CoreStatsIncrement.none(), false, countRun).getMethodsTracked();
    }

    /**
     * Build the trackers a run observed: method 1 (already catalogued) and method 3 (new), both
     * fresh from coverage with zero counts as the coverage client builds them.
     *
     * @return the observed trackers keyed by method id
     */
    private Map<Integer, MethodImpactTracker> observedTrackers() {
        Map<Integer, MethodImpactTracker> observed = new HashMap<>();
        observed.put(EXECUTED_ID, new MethodImpactTracker("com/example/Car.drive.()V", 11, 13));
        observed.put(NEW_ID, new MethodImpactTracker("com/example/Car.stop.()V", 30, 32));
        return observed;
    }

    /**
     * Build a catalogue tracker carrying stored run counts.
     *
     * @param methodName the full method name
     * @param lineStart the method's first code line
     * @param executedRunCount the stored executed-run count
     * @param triggeredRunCount the stored triggered-run count
     * @return the tracker
     */
    private MethodImpactTracker tracker(String methodName, int lineStart, long executedRunCount,
                                        long triggeredRunCount) {
        MethodImpactTracker tracker = new MethodImpactTracker(methodName, lineStart, lineStart + 2);
        tracker.setExecutedRunCount(executedRunCount);
        tracker.setTriggeredRunCount(triggeredRunCount);
        return tracker;
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
