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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link SealedRunDataAssembler} skips the method catalogue rewrite when the rewrite
 * would produce the stored table unchanged - nothing observed, no run counted and no catalogue row
 * left unreferenced - and that every other case still takes the full rebuild. Uses a
 * temp-directory embedded H2 database whose store counts the two full reads a rebuild makes.
 */
class SealedRunDataAssemblerNoOpCatalogueTest {

    private static final int EXECUTED_ID = 1;
    private static final int NOT_EXECUTED_ID = 2;
    private static final int UNREFERENCED_ID = 9;

    private CountingDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database whose edge table references methods 1 and 2, and whose
     * catalogue holds exactly those two methods with stored run counts.
     *
     * @throws Exception if the temp directory cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-assembler-no-op-catalogue-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new CountingDataStore(tempDir);
        dataStore.getTiaData();

        TestSuiteTracker suite = new TestSuiteTracker("com.example.CarTest");
        suite.setClassesImpacted(Collections.singletonList(new ClassImpactTracker("com/example/Car.java",
                new HashSet<>(Arrays.asList(EXECUTED_ID, NOT_EXECUTED_ID)))));
        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put(suite.getName(), suite);
        dataStore.persistTestSuites(suites);

        Map<Integer, MethodImpactTracker> stored = new HashMap<>();
        stored.put(EXECUTED_ID, tracker("com/example/Car.drive.()V", 10, 4, 2));
        stored.put(NOT_EXECUTED_ID, tracker("com/example/Car.park.()V", 20, 7, 1));
        dataStore.persistSourceMethods(stored);
        dataStore.resetCounts();
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
     * A counted seal that observed nothing and covered and triggered nothing, over a fully
     * referenced catalogue, carries no catalogue and makes neither full read.
     */
    @Test
    void nothingObservedNothingCountedAndNoUnreferencedRowsSkipsTheRewrite() {
        // given - the fully referenced catalogue from setUp

        // when
        SealedRunData sealed = assemble(Collections.emptyMap(), true, Collections.emptySet(),
                Collections.emptySet());

        // then
        assertNull(sealed.getMethodsTracked());
        assertFalse(sealed.isMethodCatalogueRewritten());
        assertEquals(0, dataStore.methodsTrackedReads);
        assertEquals(0, dataStore.uniqueMethodIdReads);
    }

    /**
     * A retry does not count the run, so covered and triggered ids cannot move a counter and the
     * rewrite is still skipped.
     */
    @Test
    void retryWithNothingObservedSkipsTheRewriteEvenWithCoveredAndTriggeredIds() {
        // given
        Set<Integer> ids = Collections.singleton(EXECUTED_ID);

        // when
        SealedRunData sealed = assemble(Collections.emptyMap(), false, ids, ids);

        // then
        assertNull(sealed.getMethodsTracked());
    }

    /**
     * Null observed trackers and null id sets count as empty.
     */
    @Test
    void nullInputsAreTreatedAsEmpty() {
        // given - the fully referenced catalogue from setUp

        // when
        SealedRunData sealed = assemble(null, true, null, null);

        // then
        assertNull(sealed.getMethodsTracked());
    }

    /**
     * A catalogue row no edge references would be dropped by a rebuild, so the seal takes the full
     * rebuild and the row is not carried into the new catalogue.
     */
    @Test
    void unreferencedCatalogueRowForcesTheRebuildAndIsDropped() {
        // given
        Map<Integer, MethodImpactTracker> withExtra = dataStore.getMethodsTracked();
        withExtra.put(UNREFERENCED_ID, tracker("com/example/Car.gone.()V", 90, 0, 0));
        dataStore.persistSourceMethods(withExtra);

        // when
        SealedRunData sealed = assemble(Collections.emptyMap(), true, Collections.emptySet(),
                Collections.emptySet());

        // then
        assertTrue(sealed.isMethodCatalogueRewritten());
        assertEquals(new HashSet<>(Arrays.asList(EXECUTED_ID, NOT_EXECUTED_ID)),
                sealed.getMethodsTracked().keySet());
    }

    /**
     * An observed tracker may carry new line numbers, so the seal takes the full rebuild.
     */
    @Test
    void observedTrackersForceTheRebuild() {
        // given
        Map<Integer, MethodImpactTracker> observed = new HashMap<>();
        observed.put(EXECUTED_ID, new MethodImpactTracker("com/example/Car.drive.()V", 40, 45));

        // when
        SealedRunData sealed = assemble(observed, true, Collections.emptySet(), Collections.emptySet());

        // then
        assertEquals(40, sealed.getMethodsTracked().get(EXECUTED_ID).getLineNumberStart());
    }

    /**
     * A counted run with a covered method moves that method's executed-run count, so the seal
     * takes the full rebuild even with nothing observed.
     */
    @Test
    void countedRunWithCoveredIdsForcesTheRebuildAndCounts() {
        // given - the stored executed-run count of EXECUTED_ID is 4

        // when
        SealedRunData sealed = assemble(Collections.emptyMap(), true,
                Collections.singleton(EXECUTED_ID), Collections.emptySet());

        // then
        assertEquals(5, sealed.getMethodsTracked().get(EXECUTED_ID).getExecutedRunCount());
    }

    /**
     * A counted run with a triggering method moves that method's triggered-run count, so the seal
     * takes the full rebuild even with nothing observed.
     */
    @Test
    void countedRunWithTriggeredIdsForcesTheRebuildAndCounts() {
        // given - the stored triggered-run count of EXECUTED_ID is 2

        // when
        SealedRunData sealed = assemble(Collections.emptyMap(), true,
                Collections.emptySet(), Collections.singleton(EXECUTED_ID));

        // then
        assertEquals(3, sealed.getMethodsTracked().get(EXECUTED_ID).getTriggeredRunCount());
    }

    /**
     * Persisting a seal that skipped the rewrite leaves every stored catalogue row as it was while
     * still advancing the commit.
     */
    @Test
    void skippedRewriteLeavesTheStoredCatalogueUntouchedAndAdvancesTheCommit() {
        // given
        Map<Integer, MethodImpactTracker> before = dataStore.getMethodsTracked();
        SealedRunData sealed = assemble(Collections.emptyMap(), true, Collections.emptySet(),
                Collections.emptySet());

        // when
        dataStore.persistSealedRunData(sealed);

        // then
        assertCatalogueEquals(before, dataStore.getMethodsTracked());
        assertEquals("commitB", dataStore.getTiaCore().getCommitValue());
    }

    /**
     * Assemble a seal at commit {@code commitB}, stamped now, with no drain, no stats and no re-seed.
     *
     * @param observed the trackers the run observed
     * @param countRun whether the seal counts as a run in the method run stats
     * @param coveredMethodIds the ids of the methods the run executed
     * @param triggeredMethodIds the ids of the changed methods that triggered the run
     * @return the assembled seal payload
     */
    private SealedRunData assemble(Map<Integer, MethodImpactTracker> observed, boolean countRun,
                                   Set<Integer> coveredMethodIds, Set<Integer> triggeredMethodIds) {
        TiaData tiaData = dataStore.getTiaCore();
        tiaData.setCommitValue("commitB");
        tiaData.setLastUpdated(Instant.now());
        return new SealedRunDataAssembler(dataStore).assemble(tiaData, observed, null, "commitB",
                false, CoreStatsIncrement.none(), false, countRun, coveredMethodIds, triggeredMethodIds);
    }

    /**
     * Assert two catalogues hold the same ids with the same name, lines and run counts per id.
     * {@link MethodImpactTracker#equals} compares the method name only, so it can't be used here.
     *
     * @param expected the catalogue expected
     * @param actual the catalogue read back
     */
    private static void assertCatalogueEquals(Map<Integer, MethodImpactTracker> expected,
                                              Map<Integer, MethodImpactTracker> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (Map.Entry<Integer, MethodImpactTracker> entry : expected.entrySet()) {
            MethodImpactTracker want = entry.getValue();
            MethodImpactTracker got = actual.get(entry.getKey());
            assertEquals(want.getMethodName(), got.getMethodName());
            assertEquals(want.getLineNumberStart(), got.getLineNumberStart());
            assertEquals(want.getLineNumberEnd(), got.getLineNumberEnd());
            assertArrayEquals(want.getLineRanges(), got.getLineRanges());
            assertEquals(want.getExecutedRunCount(), got.getExecutedRunCount());
            assertEquals(want.getTriggeredRunCount(), got.getTriggeredRunCount());
        }
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
    private static MethodImpactTracker tracker(String methodName, int lineStart, long executedRunCount,
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
    private static void deleteRecursively(File file) {
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

    /** A real H2 store that counts the two full catalogue reads a rebuild makes. */
    private static final class CountingDataStore extends JdbcDataStore {
        private int methodsTrackedReads;
        private int uniqueMethodIdReads;

        /**
         * Open an embedded H2 store in the given directory.
         *
         * @param dir the directory holding the H2 files
         */
        CountingDataStore(File dir) {
            super(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(dir.getAbsolutePath())),
                    BranchSchema.schemaName("test", null));
        }

        /**
         * Read the full catalogue, counting the call.
         *
         * @return every tracked method keyed by id
         */
        @Override
        public Map<Integer, MethodImpactTracker> getMethodsTracked() {
            methodsTrackedReads++;
            return super.getMethodsTracked();
        }

        /**
         * Read every method id the edge table references, counting the call.
         *
         * @return the referenced method ids
         */
        @Override
        public Set<Integer> getUniqueMethodIdsTracked() {
            uniqueMethodIdReads++;
            return super.getUniqueMethodIdsTracked();
        }

        /** Zero both counters, so a test counts only the reads its own action makes. */
        void resetCounts() {
            methodsTrackedReads = 0;
            uniqueMethodIdReads = 0;
        }
    }
}
