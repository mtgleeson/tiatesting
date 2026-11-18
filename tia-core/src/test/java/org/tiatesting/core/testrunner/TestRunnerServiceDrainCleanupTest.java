package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.*;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.*;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.staticselection.StaticTestSelectionRuleMode;

import java.io.File;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Post-run drain cleanup under the publish ledger model: drained stamp batches are deleted by
 * {@code (groupArtifact, publishSeq)}, each drained library's {@code last_applied_seq} advances
 * to the resolved build's sequence and its {@code mapping_baseline_commit} to the commit that
 * build was published at, and an all-tests run moves the baseline of every tracked library whose
 * held build is known.
 * See the drain-rule and mapping-baseline sections of the library publish-time stamping chapter in {@code WIKI.md}.
 */
class TestRunnerServiceDrainCleanupTest {

    private static final String LIB = "com.example:lib";

    private JdbcDataStore dataStore;
    private TestRunnerService service;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())), BranchSchema.schemaName("test", null));
        dataStore.getTiaData();
        service = new TestRunnerService(dataStore);

        TiaData tiaData = dataStore.getTiaData();
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
     * A drain result carrying both a drained method batch and a drained forced-selection batch
     * for the same library deletes both pending rows after the test run - the forced-selection
     * table is cleaned up by the same drain-cleanup path as the impacted-method table.
     */
    @Test
    void deletesDrainedForcedSelectionBatchAlongsideMethodBatchAfterTestRun() {
        // given a tracked library published once with both an impacted method stamp and a
        // forced-selection batch, both landing at publish seq 1
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));
        LibraryPublish publish = new LibraryPublish(LIB, "1.0.0", "hash1", "libcommit1", System.currentTimeMillis());
        List<PendingLibraryForcedSelection> forcedSelections = Collections.singletonList(
                new PendingLibraryForcedSelection(LIB, "1.0.0", 1L, "widget-rule",
                        StaticTestSelectionRuleMode.RUN_ALL, Collections.emptyList()));
        long assignedSeq = dataStore.persistLibraryPublish(publish, new HashSet<>(Arrays.asList(10)), forcedSelections);

        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDrainedBatch(LIB, assignedSeq);
        drainResult.addDrainedForcedBatch(LIB, assignedSeq);
        drainResult.setAppliedSeq(LIB, assignedSeq);

        // when the post-run persist applies the drain result
        persistWithDrainResult(drainResult, 1);

        // then both the impacted-method stamp and the forced-selection batch are gone
        assertTrue(dataStore.readPendingLibraryImpactedMethods(LIB).isEmpty());
        assertTrue(dataStore.readPendingLibraryForcedSelections(LIB).isEmpty());
    }

    /**
     * A drained library's last_applied_seq advances to the resolved build's sequence and its
     * mapping baseline to the commit that build was published at - the library source its covering
     * suites just re-ran against - not to the consumer's own sealed commit.
     */
    @Test
    void advancesAppliedSeqAndBaselineForDrainedLibrary() {
        // given a tracked library and a drain result applying seq 5, a build published at 480
        TrackedLibrary lib = new TrackedLibrary(LIB, "/projects/lib", null);
        lib.setMappingBaselineCommit("470");
        dataStore.persistTrackedLibrary(lib);
        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDrainedBatch(LIB, 5L);
        drainResult.setAppliedSeq(LIB, 5L);
        drainResult.setHeldBuildCommit(LIB, "480");

        // when the post-run persist applies the drain result (not an all-tests run)
        persistWithDrainResult(drainResult, 1);

        // then the applied seq advances and the baseline is the held build's publish commit
        TrackedLibrary updated = dataStore.readTrackedLibraries().get(LIB);
        assertEquals(Long.valueOf(5L), updated.getLastAppliedSeq());
        assertEquals("480", updated.getMappingBaselineCommit());
    }

    /**
     * A drained library whose ledger row carries no commit keeps its baseline: the applied seq
     * still advances, and the next publish diffs a wider range rather than a narrower one.
     */
    @Test
    void drainedLibraryKeepsItsBaselineWhenTheHeldBuildCommitIsUnknown() {
        // given a tracked library with a baseline and a drain result with no held build commit
        TrackedLibrary lib = new TrackedLibrary(LIB, "/projects/lib", null);
        lib.setMappingBaselineCommit("470");
        dataStore.persistTrackedLibrary(lib);
        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDrainedBatch(LIB, 5L);
        drainResult.setAppliedSeq(LIB, 5L);

        // when the post-run persist applies the drain result (not an all-tests run)
        persistWithDrainResult(drainResult, 1);

        // then the applied seq advances and the baseline is untouched
        TrackedLibrary updated = dataStore.readTrackedLibraries().get(LIB);
        assertEquals(Long.valueOf(5L), updated.getLastAppliedSeq());
        assertEquals("470", updated.getMappingBaselineCommit());
    }

    /**
     * An all-tests run re-covers every suite, so a library whose held build the drain identified
     * has its baseline moved to that build's publish commit even though nothing was drained. A
     * library whose held build is unknown keeps the baseline it has.
     */
    @Test
    void allTestsRunMovesTheBaselineOnlyWhereTheHeldBuildIsKnown() {
        // given two tracked libraries with baselines, one with a known held build
        TrackedLibrary known = new TrackedLibrary("com.example:known", "/known", null);
        known.setMappingBaselineCommit("470");
        TrackedLibrary unknown = new TrackedLibrary("com.example:unknown", "/unknown", null);
        unknown.setMappingBaselineCommit("460");
        dataStore.persistTrackedLibrary(known);
        dataStore.persistTrackedLibrary(unknown);
        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.setHeldBuildCommit("com.example:known", "480");

        // when an all-tests run persists (ignored count 0)
        persistWithDrainResult(drainResult, 0);

        // then only the library with a known held build moved, to that build's commit
        assertEquals("480", dataStore.readTrackedLibraries().get("com.example:known").getMappingBaselineCommit());
        assertEquals("460", dataStore.readTrackedLibraries().get("com.example:unknown").getMappingBaselineCommit());
    }

    /**
     * A selective run is not a statement about a library it did not drain: a held build commit on
     * the drain result moves no baseline unless the library drained or every suite re-ran.
     */
    @Test
    void selectiveRunDoesNotMoveTheBaselineOfAnUndrainedLibrary() {
        // given a tracked library with a baseline and a drain result naming only its held build
        TrackedLibrary lib = new TrackedLibrary(LIB, "/projects/lib", null);
        lib.setMappingBaselineCommit("470");
        dataStore.persistTrackedLibrary(lib);
        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.setHeldBuildCommit(LIB, "480");

        // when a selective run persists (ignored count > 0)
        persistWithDrainResult(drainResult, 1);

        // then the baseline is untouched
        assertEquals("470", dataStore.readTrackedLibraries().get(LIB).getMappingBaselineCommit());
    }

    /**
     * A library with no baseline yet and no known held build takes the sealed commit on an
     * all-tests run (a seed, typically), so that its first publish stamps instead of seeding.
     */
    @Test
    void allTestsRunGivesTheSealedCommitToLibrariesWithNoBaseline() {
        // given two tracked libraries with no baseline and no drain at all
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:a", "/a", null));
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:b", "/b", null));

        // when an all-tests run persists (ignored count 0)
        persistWithDrainResult(null, 0);

        // then both baselines are set to the sealed commit
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

    /**
     * A drain that only established a line - nothing drained - still stores the followed line and
     * deletes the other line's batches unrun, leaving the applied sequence and baseline alone.
     */
    @Test
    void establishingALineStoresItAndDeletesDiscardedBatches() {
        // given a library with no followed line holding a method batch and a forced batch from
        // another line, both reported as discarded
        TrackedLibrary lib = new TrackedLibrary(LIB, "/projects/lib", null);
        lib.setMappingBaselineCommit("470");
        dataStore.persistTrackedLibrary(lib);
        LibraryPublish otherLine = new LibraryPublish(LIB, "9.0", "HB", "c9", System.currentTimeMillis());
        otherLine.setSourceLine("lineB");
        long seq = dataStore.persistLibraryPublish(otherLine, new HashSet<>(Arrays.asList(10)),
                Collections.singletonList(new PendingLibraryForcedSelection(LIB, "9.0", 0L, "rule",
                        StaticTestSelectionRuleMode.RUN_ALL, Collections.<String>emptyList())));
        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDiscardedBatch(LIB, seq);
        drainResult.addDiscardedForcedBatch(LIB, seq);
        drainResult.setLineToFollow(LIB, "lineA");

        // when a selective run persists
        persistWithDrainResult(drainResult, 1);

        // then both batches are gone and only the followed line changed
        assertTrue(dataStore.readPendingLibraryImpactedMethods(LIB).isEmpty());
        assertTrue(dataStore.readPendingLibraryForcedSelections(LIB).isEmpty());
        TrackedLibrary updated = dataStore.readTrackedLibraries().get(LIB);
        assertEquals("lineA", updated.getFollowedLine());
        assertEquals("470", updated.getMappingBaselineCommit());
        assertNull(updated.getLastAppliedSeq());
    }

    /**
     * A line switch drains nothing, yet the seal still moves the library onto the new line: the
     * followed line, the applied sequence and the baseline (the resolved build's publish commit)
     * all change, and every pending batch is deleted unrun.
     */
    @Test
    void lineSwitchPersistsNewLineAppliedSeqAndBaseline() {
        // given a library following line A at seq 5 with a pending batch on each line, and a
        // drain result as the drainer reports a switch to a line B build at seq 2
        TrackedLibrary lib = new TrackedLibrary(LIB, "/projects/lib", null);
        lib.setFollowedLine("lineA");
        lib.setLastAppliedSeq(5L);
        lib.setMappingBaselineCommit("470");
        dataStore.persistTrackedLibrary(lib);
        LibraryPublish lineA = new LibraryPublish(LIB, "1.1", "HA", "c8", System.currentTimeMillis());
        lineA.setSourceLine("lineA");
        long seqA = dataStore.persistLibraryPublish(lineA, new HashSet<>(Arrays.asList(10)),
                Collections.<PendingLibraryForcedSelection>emptyList());
        LibraryPublish lineB = new LibraryPublish(LIB, "2.0", "HB", "c9", System.currentTimeMillis());
        lineB.setSourceLine("lineB");
        long seqB = dataStore.persistLibraryPublish(lineB, new HashSet<>(Arrays.asList(20)),
                Collections.singletonList(new PendingLibraryForcedSelection(LIB, "2.0", 0L, "rule",
                        StaticTestSelectionRuleMode.RUN_ALL, Collections.<String>emptyList())));
        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDiscardedBatch(LIB, seqA);
        drainResult.addDiscardedBatch(LIB, seqB);
        drainResult.addDiscardedForcedBatch(LIB, seqB);
        drainResult.setLineToFollow(LIB, "lineB");
        drainResult.setAppliedSeq(LIB, seqB);
        drainResult.setHeldBuildCommit(LIB, "c9");
        drainResult.addLineSwitch(LIB);

        // when the run persists
        persistWithDrainResult(drainResult, 0);

        // then the library follows line B from the resolved build, and nothing is left pending
        TrackedLibrary updated = dataStore.readTrackedLibraries().get(LIB);
        assertEquals("lineB", updated.getFollowedLine());
        assertEquals(Long.valueOf(seqB), updated.getLastAppliedSeq());
        assertEquals("c9", updated.getMappingBaselineCommit());
        assertTrue(dataStore.readPendingLibraryImpactedMethods(LIB).isEmpty());
        assertTrue(dataStore.readPendingLibraryForcedSelections(LIB).isEmpty());
    }

    /**
     * A drain that drained a batch and established the line in the same run writes both to the
     * one library row.
     */
    @Test
    void drainingAndEstablishingALineUpdateTheSameRow() {
        // given a library with no followed line and a drain that applied seq 5 on line A
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));
        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDrainedBatch(LIB, 5L);
        drainResult.setAppliedSeq(LIB, 5L);
        drainResult.setHeldBuildCommit(LIB, "480");
        drainResult.setLineToFollow(LIB, "lineA");

        // when a selective run persists
        persistWithDrainResult(drainResult, 1);

        // then the row carries the applied seq, the baseline and the followed line
        TrackedLibrary updated = dataStore.readTrackedLibraries().get(LIB);
        assertEquals(Long.valueOf(5L), updated.getLastAppliedSeq());
        assertEquals("480", updated.getMappingBaselineCommit());
        assertEquals("lineA", updated.getFollowedLine());
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
     * <p>The run reports suites as having executed. That is what a real run of either shape does, and
     * it matters here: a run that executed no suite at all is treated as an empty (misconfigured) run
     * and advances no library baseline - see {@code TestRunResult#ranNoExpectedSuites}.
     *
     * @param drainResult the drain result to apply, or null.
     * @param ignoredCount the number of ignored suites for the run.
     */
    private void persistWithDrainResult(LibraryImpactDrainResult drainResult, int ignoredCount) {
        TestRunResult testRunResult = new TestRunResult(
                new HashMap<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>(), new TestStats(), drainResult, ignoredCount, TestRunResultFixture.syntheticPlanOutcome(2, 0),
                TestRunSelectionDetails.empty(), new TestJvmContribution(), TestRunResultFixture.runIdentity());
        // history logging is off in this test to keep the focus on drain cleanup
        service.persistTestRunData(true, false, "newcommit", "main", System.currentTimeMillis(), testRunResult, null);
    }
}
