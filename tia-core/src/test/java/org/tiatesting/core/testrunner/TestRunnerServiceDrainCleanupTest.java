package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.*;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.*;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;

import java.io.File;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Post-run drain cleanup under the publish ledger model: drained stamp batches are deleted by
 * {@code (groupArtifact, publishSeq)}, each drained library's {@code last_applied_seq} advances
 * to the resolved build's sequence and its {@code mapping_baseline_commit} to the run's sealed
 * commit, and an all-tests run advances every tracked library's baseline.
 * See the drain-rule and mapping-baseline sections of the library publish-time stamping chapter in {@code WIKI.md}.
 */
class TestRunnerServiceDrainCleanupTest {

    private static final String LIB = "com.example:lib";

    private H2DataStore dataStore;
    private TestRunnerService service;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
        dataStore.getTiaData(true);
        service = new TestRunnerService(dataStore);

        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("initial");
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);
    }

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
     * Only the drained batch (identified by its publish seq) is deleted; batches above the
     * resolved build stay pending for a later drain.
     */
    @Test
    void deletesDrainedPendingBatchesBySeqAfterTestRun() {
        // given two pending batches at seq 1 and 2, with seq 1 drained
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                LIB, "1.0.0", 1L, new HashSet<>(Arrays.asList(10))));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                LIB, "1.1.0", 2L, new HashSet<>(Arrays.asList(20))));

        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDrainedBatch(LIB, 1L);
        drainResult.setAppliedSeq(LIB, 1L);

        // when the post-run persist applies the drain result
        persistWithDrainResult(drainResult, 1);

        // then only the seq-1 batch is gone
        List<PendingLibraryImpactedMethod> remaining = dataStore.readPendingLibraryImpactedMethods(LIB);
        assertEquals(1, remaining.size());
        assertEquals(2L, remaining.get(0).getPublishSeq());
    }

    /**
     * A drained library's last_applied_seq advances to the resolved build's sequence and its
     * mapping baseline to the run's sealed commit (its covering suites just re-ran with coverage).
     */
    @Test
    void advancesAppliedSeqAndBaselineForDrainedLibrary() {
        // given a tracked library and a drain result applying seq 5
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));
        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDrainedBatch(LIB, 5L);
        drainResult.setAppliedSeq(LIB, 5L);

        // when the post-run persist applies the drain result (not an all-tests run)
        persistWithDrainResult(drainResult, 1);

        // then the applied seq and mapping baseline are advanced
        TrackedLibrary updated = dataStore.readTrackedLibraries().get(LIB);
        assertEquals(Long.valueOf(5L), updated.getLastAppliedSeq());
        assertEquals("newcommit", updated.getMappingBaselineCommit());
    }

    /**
     * An all-tests run re-captures every library's method line numbers, so every tracked
     * library's mapping baseline advances to the sealed commit - even libraries with no drain.
     */
    @Test
    void allTestsRunAdvancesBaselineForEveryTrackedLibrary() {
        // given two tracked libraries and no drain at all
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:a", "/a", null));
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:b", "/b", null));

        // when an all-tests run persists (ignored count 0)
        persistWithDrainResult(null, 0);

        // then both baselines advance to the sealed commit
        assertEquals("newcommit", dataStore.readTrackedLibraries().get("com.example:a").getMappingBaselineCommit());
        assertEquals("newcommit", dataStore.readTrackedLibraries().get("com.example:b").getMappingBaselineCommit());
    }

    /**
     * A selective run (some suites ignored) with no drain must NOT advance baselines - the
     * libraries' covering suites did not necessarily re-run, so their line numbers are unchanged.
     */
    @Test
    void selectiveRunWithoutDrainLeavesBaselinesAlone() {
        // given a tracked library with an existing baseline
        TrackedLibrary lib = new TrackedLibrary(LIB, "/projects/lib", null);
        lib.setMappingBaselineCommit("old-baseline");
        dataStore.persistTrackedLibrary(lib);

        // when a selective run persists (ignored count > 0, no drain)
        persistWithDrainResult(null, 1);

        // then the baseline is untouched
        assertEquals("old-baseline", dataStore.readTrackedLibraries().get(LIB).getMappingBaselineCommit());
    }

    @Test
    void handlesNullDrainResultGracefully() {
        persistWithDrainResult(null, 1);
    }

    @Test
    void handlesEmptyDrainResultGracefully() {
        persistWithDrainResult(new LibraryImpactDrainResult(), 1);
    }

    /**
     * Multiple libraries drained in one run are each cleaned up and advanced independently.
     */
    @Test
    void handlesMultipleLibrariesInDrainResult() {
        // given two tracked libraries with one pending batch each, both drained
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:a", "/a", null));
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:b", "/b", null));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:a", "1.0.0", 1L, new HashSet<>(Arrays.asList(10))));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:b", "2.0.0", 3L, new HashSet<>(Arrays.asList(20))));

        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDrainedBatch("com.example:a", 1L);
        drainResult.addDrainedBatch("com.example:b", 3L);
        drainResult.setAppliedSeq("com.example:a", 1L);
        drainResult.setAppliedSeq("com.example:b", 3L);

        // when the post-run persist applies the drain result
        persistWithDrainResult(drainResult, 1);

        // then both libraries' stamps are deleted and both applied seqs advanced
        assertTrue(dataStore.readPendingLibraryImpactedMethods("com.example:a").isEmpty());
        assertTrue(dataStore.readPendingLibraryImpactedMethods("com.example:b").isEmpty());
        assertEquals(Long.valueOf(1L), dataStore.readTrackedLibraries().get("com.example:a").getLastAppliedSeq());
        assertEquals(Long.valueOf(3L), dataStore.readTrackedLibraries().get("com.example:b").getLastAppliedSeq());
    }

    /**
     * Run the post-test-run persist with the given drain result and ignored-suite count
     * (0 makes it an all-tests run).
     *
     * @param drainResult the drain result to apply, or null.
     * @param ignoredCount the number of ignored suites for the run.
     */
    private void persistWithDrainResult(LibraryImpactDrainResult drainResult, int ignoredCount) {
        TestRunResult testRunResult = new TestRunResult(
                new HashMap<>(), new HashSet<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>(), new TestStats(), drainResult, ignoredCount, 0);
        // history logging is off in this test to keep the focus on drain cleanup
        service.persistTestRunData(true, false, false, "newcommit", "main", System.currentTimeMillis(), testRunResult);
    }
}
