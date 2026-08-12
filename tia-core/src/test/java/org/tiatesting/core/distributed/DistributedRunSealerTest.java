package org.tiatesting.core.distributed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.DistributedRunStatus;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.SealedRunData;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.testrunner.TestRunResult;
import org.tiatesting.core.testrunner.TestRunnerService;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the seal a distributed build ends with: the runner that finishes last wins the election and
 * then does, for the whole build, what a single-host run does for itself - rebuild the method
 * catalogue, apply the library drain cleanup the plan still owed, advance the stored commit value,
 * mark the run sealed and clear the staging table.
 *
 * <p>The catalogue rebuild is the reason the barrier exists. It takes the distinct method ids off
 * the suite-to-method edge table wholesale and drops any id that query omits, while each runner
 * writes only its own suites' edges - so a seal performed before the last group finished would drop
 * every method reachable only from that group's suites, making them invisible to the next build's
 * diff. {@link #aMethodReachableOnlyFromTheLastGroupToFinishSurvivesTheSeal()} is the test that
 * fails if that ordering is ever lost.
 *
 * <p>Everything runs against a real embedded H2 {@link JdbcDataStore}, subclassed to record the
 * write calls, so "a loser does nothing" is asserted as an empty write log rather than inferred
 * from unchanged state.
 */
class DistributedRunSealerTest {

    private static final String RUN_ID = "run-1";
    private static final String RUNNER_A = "runner-a";
    private static final String RUNNER_B = "runner-b";
    private static final String PLAN_COMMIT = "plan-commit";
    private static final String LIB = "com.example:lib";

    private RecordingDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 store in its own temp directory and seed a known prior commit
     * value, so every test can tell an actual seal apart from a runner that did nothing.
     *
     * @throws Exception if the temp directory cannot be created or the schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-distributed-sealer-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new RecordingDataStore(tempDir);
        dataStore.getTiaData(true);

        TiaData tiaData = dataStore.getTiaCore();
        tiaData.setCommitValue("prior-commit");
        tiaData.setBranch("prior-branch");
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);
        dataStore.callOrder.clear();
    }

    /**
     * Close the store so embedded H2 releases its file lock, then remove the temp directory.
     */
    @AfterEach
    void tearDown() {
        if (dataStore != null) {
            dataStore.close();
        }
        if (tempDir != null && tempDir.exists()) {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            tempDir.delete();
        }
    }

    /**
     * The winner rebuilds the catalogue from the union of every runner's staged trackers, not from
     * its own. No single runner in a distributed build observes the whole build's methods, so a
     * sealer that used only what it saw itself would leave every other group's methods on their
     * stale stored line numbers.
     */
    @Test
    void winnerRebuildsTheCatalogueFromTheUnionOfEveryRunnersStagedTrackers() {
        // given - two groups, each covering one method, each staging its own fresh tracker over a
        // stale stored one
        Map<Integer, MethodImpactTracker> stored = trackers(101, "com/example/A.a.()V", 1, 5);
        stored.putAll(trackers(202, "com/example/B.b.()V", 1, 5));
        seedStoredCatalogue(stored);
        seedSuiteEdges("com.example.ATest", "com/example/A.java", 101);
        seedSuiteEdges("com.example.BTest", "com/example/B.java", 202);
        persistPlan(RUN_ID, 2, null);
        dataStore.persistStagedMethodTrackers(RUN_ID,
                trackers(101, "com/example/A.a.()V", 40, 50));
        dataStore.persistStagedMethodTrackers(RUN_ID,
                trackers(202, "com/example/B.b.()V", 60, 70));
        completeAllGroups(RUN_ID, RUNNER_A, RUNNER_B);

        // when
        boolean sealed = sealerFor(RUNNER_B, 1).sealIfElected("new-commit", "main", true, false, false, 9000L);

        // then
        assertTrue(sealed, "the last runner to finish must win the election and seal");
        Map<Integer, MethodImpactTracker> catalogue = dataStore.getMethodsTracked();
        assertEquals(2, catalogue.size(), "both groups' methods must survive the rebuild: " + catalogue);
        assertEquals(40, catalogue.get(101).getLineNumberStart(),
                "the first group's staged line numbers must win over the stored ones");
        assertEquals(60, catalogue.get(202).getLineNumberStart(),
                "the last group's staged line numbers must win over the stored ones");
    }

    /**
     * A method id referenced from the edge table that no runner staged resolves from the stored
     * catalogue. It is genuinely unchanged: its line numbers could only have shifted if its file
     * changed, which would have selected its covering suites, which some group would then have run
     * and staged a fresh tracker for.
     */
    @Test
    void anIdNoRunnerStagedResolvesFromTheStoredCatalogue() {
        // given - the edge table references two methods, only one of which was re-observed
        Map<Integer, MethodImpactTracker> stored = trackers(101, "com/example/A.a.()V", 1, 5);
        stored.putAll(trackers(999, "com/example/Untouched.u.()V", 80, 90));
        seedStoredCatalogue(stored);
        seedSuiteEdges("com.example.ATest", "com/example/A.java", 101, 999);
        persistPlan(RUN_ID, 1, null);
        dataStore.persistStagedMethodTrackers(RUN_ID,
                trackers(101, "com/example/A.a.()V", 40, 50));
        completeAllGroups(RUN_ID, RUNNER_A);

        // when
        sealerFor(RUNNER_A, 0).sealIfElected("new-commit", "main", true, false, false, 9000L);

        // then
        Map<Integer, MethodImpactTracker> catalogue = dataStore.getMethodsTracked();
        assertEquals(2, catalogue.size(), "an unstaged id must be carried forward, not dropped: " + catalogue);
        assertEquals(80, catalogue.get(999).getLineNumberStart(),
                "the unstaged method must keep its stored line numbers");
        assertEquals(40, catalogue.get(101).getLineNumberStart(),
                "the staged method must take its staged line numbers");
    }

    /**
     * An id in neither the staging table nor the stored catalogue is an orphan - a reference left
     * behind by an earlier run that aborted between writing the edge and rewriting the catalogue -
     * and is dropped rather than carried forward as a null, exactly as on the single-host path.
     */
    @Test
    void anIdInNeitherStagingNorTheStoredCatalogueIsDroppedAsAnOrphan() {
        // given - the edge table references an id nothing knows about
        seedStoredCatalogue(trackers(101, "com/example/A.a.()V", 1, 5));
        seedSuiteEdges("com.example.ATest", "com/example/A.java", 101, 777);
        persistPlan(RUN_ID, 1, null);
        dataStore.persistStagedMethodTrackers(RUN_ID,
                trackers(101, "com/example/A.a.()V", 40, 50));
        completeAllGroups(RUN_ID, RUNNER_A);

        // when
        sealerFor(RUNNER_A, 0).sealIfElected("new-commit", "main", true, false, false, 9000L);

        // then
        Map<Integer, MethodImpactTracker> catalogue = dataStore.getMethodsTracked();
        assertEquals(1, catalogue.size(), "the orphan must be dropped: " + catalogue);
        assertNull(catalogue.get(777), "an id no source knows about cannot be written to the catalogue");
        assertEquals(40, catalogue.get(101).getLineNumberStart(),
                "the rest of the catalogue must still have been rebuilt from the staged trackers");
    }

    /**
     * The seal advances the stored commit value and branch, which is what makes the whole build's
     * mapping rows trustworthy to the next build's diff. Until it happens the previous commit
     * stands, so a build that never seals is re-done rather than under-selected.
     */
    @Test
    void theSealAdvancesTheStoredCommitValueAndBranch() {
        // given
        seedStoredCatalogue(trackers(101, "com/example/A.a.()V", 1, 5));
        seedSuiteEdges("com.example.ATest", "com/example/A.java", 101);
        persistPlan(RUN_ID, 1, null);
        completeAllGroups(RUN_ID, RUNNER_A);

        // when
        sealerFor(RUNNER_A, 0).sealIfElected("new-commit", "release-1", true, false, false, 9000L);

        // then
        TiaData reloaded = dataStore.getTiaCore();
        assertEquals("new-commit", reloaded.getCommitValue(),
                "the sealer must advance the stored commit value for the whole build");
        assertEquals("release-1", reloaded.getBranch(), "the sealer must record the run's branch");
        assertEquals(1, Collections.frequency(dataStore.callOrder, "persistSealedRunData"),
                "the catalogue and the commit value must be written as one bundle, once");
    }

    /**
     * The library drain cleanup the plan's own selection still owes is applied by the sealer, from
     * the drain result recorded when the run was planned. The planning process deleted pending rows
     * and advanced sequences before any runner started and then exited, so this stored result is the
     * only record of what that drain owes.
     */
    @Test
    void theSealAppliesTheDrainCleanupRecordedWhenTheRunWasPlanned() {
        // given - a tracked library with a pending stamp, drained by the plan's selection
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                LIB, "1.0.0", 7L, new HashSet<>(Collections.singletonList(101))));
        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDrainedBatch(LIB, 7L);
        drainResult.setAppliedSeq(LIB, 7L);

        seedStoredCatalogue(trackers(101, "com/example/A.a.()V", 1, 5));
        seedSuiteEdges("com.example.ATest", "com/example/A.java", 101);
        persistPlan(RUN_ID, 1, drainResult);
        completeAllGroups(RUN_ID, RUNNER_A);

        // when
        sealerFor(RUNNER_A, 0).sealIfElected("new-commit", "main", true, false, false, 9000L);

        // then
        assertTrue(dataStore.readPendingLibraryImpactedMethods(LIB).isEmpty(),
                "the drained pending stamp must be deleted by the seal");
        TrackedLibrary library = dataStore.readTrackedLibraries().get(LIB);
        assertEquals(Long.valueOf(7L), library.getLastAppliedSeq(),
                "the drained library's applied sequence must advance");
        assertEquals("new-commit", library.getMappingBaselineCommit(),
                "the drained library's mapping baseline must advance to the sealed commit");
    }

    /**
     * The staging table is roughly the size of the method catalogue, so the sealer clears it once
     * it has consumed it rather than leaving it for the next build's plan write.
     */
    @Test
    void theStagedTrackersAreDeletedOnceTheSealHasConsumedThem() {
        // given
        seedStoredCatalogue(trackers(101, "com/example/A.a.()V", 1, 5));
        seedSuiteEdges("com.example.ATest", "com/example/A.java", 101);
        persistPlan(RUN_ID, 1, null);
        dataStore.persistStagedMethodTrackers(RUN_ID,
                trackers(101, "com/example/A.a.()V", 40, 50));
        completeAllGroups(RUN_ID, RUNNER_A);

        // when
        sealerFor(RUNNER_A, 0).sealIfElected("new-commit", "main", true, false, false, 9000L);

        // then
        assertTrue(dataStore.readStagedMethodTrackers(RUN_ID).isEmpty(),
                "the sealer must clear the staging rows it consumed");
    }

    /**
     * The run reaches its terminal state once sealed, so the next build's planner can tell a run
     * that finished from one that was abandoned mid-flight.
     */
    @Test
    void theRunIsMarkedSealedAndRecordsTheRunnerThatSealedIt() {
        // given
        seedStoredCatalogue(trackers(101, "com/example/A.a.()V", 1, 5));
        seedSuiteEdges("com.example.ATest", "com/example/A.java", 101);
        persistPlan(RUN_ID, 1, null);
        completeAllGroups(RUN_ID, RUNNER_A);

        // when
        sealerFor(RUNNER_A, 0).sealIfElected("new-commit", "main", true, false, false, 9000L);

        // then
        DistributedRun run = dataStore.readDistributedRun(RUN_ID);
        assertEquals(DistributedRunStatus.SEALED, run.getStatus(), "the run must reach its terminal state");
        assertEquals(RUNNER_A, run.getSealedBy(), "the election must record which runner sealed");
        assertEquals(Long.valueOf(9000L), run.getSealedAtMs(), "the election must record when");
    }

    /**
     * A runner whose peers have not all finished loses the election and does nothing at all - not
     * even a read of the staging table. Its seal would rebuild the catalogue from an edge set still
     * missing the unfinished groups' suites.
     */
    @Test
    void aRunnerWhosePeersAreStillRunningDoesNothingAtAll() {
        // given - two groups, only this runner's own is complete
        seedStoredCatalogue(trackers(101, "com/example/A.a.()V", 1, 5));
        seedSuiteEdges("com.example.ATest", "com/example/A.java", 101);
        persistPlan(RUN_ID, 2, null);
        dataStore.persistStagedMethodTrackers(RUN_ID,
                trackers(101, "com/example/A.a.()V", 40, 50));
        claimAndComplete(RUN_ID, RUNNER_A);
        dataStore.callOrder.clear();

        // when
        boolean sealed = sealerFor(RUNNER_A, 0).sealIfElected("new-commit", "main", true, false, false, 9000L);

        // then
        assertFalse(sealed, "a runner cannot seal while another group is still running");
        assertEquals(Collections.<String>emptyList(), dataStore.callOrder,
                "a runner that lost the election must make no writes at all");
        assertEquals("prior-commit", dataStore.getTiaCore().getCommitValue(),
                "the stored commit value must not move until the whole build has finished");
        assertFalse(dataStore.readStagedMethodTrackers(RUN_ID).isEmpty(),
                "the staging rows must survive for the runner that does seal");
    }

    /**
     * Only one runner can ever seal a run. The second attempt loses the election and does nothing,
     * so a build cannot end up with two catalogue rebuilds racing each other.
     */
    @Test
    void aSecondRunnerAttemptingToSealAfterTheWinnerDoesNothingAtAll() {
        // given - one runner has already sealed
        seedStoredCatalogue(trackers(101, "com/example/A.a.()V", 1, 5));
        seedSuiteEdges("com.example.ATest", "com/example/A.java", 101);
        persistPlan(RUN_ID, 2, null);
        completeAllGroups(RUN_ID, RUNNER_A, RUNNER_B);
        assertTrue(sealerFor(RUNNER_B, 1).sealIfElected("new-commit", "main", true, false, false, 9000L));
        dataStore.callOrder.clear();

        // when
        boolean sealed = sealerFor(RUNNER_A, 0).sealIfElected("other-commit", "other-branch", true, false, false, 9500L);

        // then
        assertFalse(sealed, "exactly one runner may seal a run");
        assertEquals(Collections.<String>emptyList(), dataStore.callOrder,
                "the runner that lost the election must make no writes at all");
        assertEquals("new-commit", dataStore.getTiaCore().getCommitValue(),
                "the loser must not restamp the commit value");
    }

    /**
     * A sealer from a superseded run - one whose plan rows a newer build's plan write already
     * cleared - does nothing. Proceeding would find the staging table empty (the newer plan write
     * cleared it too), resolve every method id from the stored catalogue, and silently drop from the
     * catalogue every id the stored catalogue lacks: the exact under-selection the staging table
     * exists to prevent.
     */
    @Test
    void aSealerFromASupersededRunDoesNothingAtAll() {
        // given - the run's groups all finished, then a newer build superseded it
        seedStoredCatalogue(trackers(101, "com/example/A.a.()V", 1, 5));
        seedSuiteEdges("com.example.ATest", "com/example/A.java", 101);
        persistPlan(RUN_ID, 1, null);
        completeAllGroups(RUN_ID, RUNNER_A);
        persistPlan("run-2", 1, null);
        dataStore.callOrder.clear();

        // when
        boolean sealed = sealerFor(RUNNER_A, 0).sealIfElected("new-commit", "main", true, false, false, 9000L);

        // then
        assertFalse(sealed, "a runner whose run no longer exists must not seal");
        assertEquals(Collections.<String>emptyList(), dataStore.callOrder,
                "a superseded runner must make no writes at all");
        assertEquals("prior-commit", dataStore.getTiaCore().getCommitValue(),
                "a superseded runner must not advance the stored commit value");
    }

    /**
     * A build that does not own mapping updates still finishes its run - the staging rows are
     * cleared and the run reaches SEALED - but writes no catalogue and does not advance the stored
     * commit value, mirroring what a single-host run with {@code updateDBMapping=false} does.
     */
    @Test
    void aRunThatDoesNotOwnMappingUpdatesFinishesWithoutSealingTheCommit() {
        // given
        seedStoredCatalogue(trackers(101, "com/example/A.a.()V", 1, 5));
        seedSuiteEdges("com.example.ATest", "com/example/A.java", 101);
        persistPlan(RUN_ID, 1, null);
        completeAllGroups(RUN_ID, RUNNER_A);

        // when
        boolean sealed = sealerFor(RUNNER_A, 0).sealIfElected("new-commit", "main", false, false, false, 9000L);

        // then
        assertTrue(sealed, "the elected runner still finishes the run");
        assertEquals(0, Collections.frequency(dataStore.callOrder, "persistSealedRunData"),
                "a run that does not own mapping updates has nothing to seal");
        assertEquals("prior-commit", dataStore.getTiaCore().getCommitValue(),
                "a run that does not own mapping updates must not advance the stored commit value");
        assertEquals(DistributedRunStatus.SEALED, dataStore.readDistributedRun(RUN_ID).getStatus(),
                "the run must still reach its terminal state");
    }

    /**
     * <b>The test this stage's ordering exists for.</b> Two runners persist through the real
     * {@link TestRunnerService} flow, one after the other, each covering a method only its own
     * suites reach. The method reachable only from the group that finishes <em>last</em> must be in
     * the catalogue once the build is sealed.
     *
     * <p>It bites because the catalogue is rebuilt wholesale from {@code SELECT DISTINCT
     * tia_source_method_id}: bypass the barrier and the first runner to finish wins the election
     * instead, rebuilds the catalogue from an edge set that has only its own group's suites in it,
     * and the second runner then loses the election and never seals - so the last group's method is
     * never written to {@code tia_source_method}, disappears from the next build's diff, and its
     * covering suites silently stop being selected. The mid-way assertions lock the ordering
     * directly: nothing may be sealed while a group is still running.
     */
    @Test
    void aMethodReachableOnlyFromTheLastGroupToFinishSurvivesTheSeal() {
        // given - a two-group plan, and two runners each covering one method nothing else reaches
        persistPlan(RUN_ID, 2, null);
        TestRunnerService service = new TestRunnerService(dataStore);
        DistributedRunnerContext firstRunner = claim(RUN_ID, RUNNER_A);
        DistributedRunnerContext lastRunner = claim(RUN_ID, RUNNER_B);

        // when - the first runner finishes and persists its whole share
        service.persistTestRunData(true, true, false, PLAN_COMMIT, "main",
                System.currentTimeMillis(), runResultFor("com.example.ATest", "com/example/A.java",
                        101, "com/example/A.a.()V"), firstRunner);

        // then - nothing has been sealed yet, because a group is still running
        assertEquals("prior-commit", dataStore.getTiaCore().getCommitValue(),
                "no runner may advance the stored commit value while a group is still running");
        assertTrue(dataStore.getMethodsTracked().isEmpty(),
                "no runner may rebuild the catalogue while a group is still running");
        assertEquals(DistributedRunStatus.OPEN, dataStore.readDistributedRun(RUN_ID).getStatus(),
                "the run must stay open until every group has finished");

        // when - the last group finishes and persists
        service.persistTestRunData(true, true, false, PLAN_COMMIT, "main",
                System.currentTimeMillis(), runResultFor("com.example.BTest", "com/example/B.java",
                        202, "com/example/B.b.()V"), lastRunner);

        // then - both groups' methods are in the catalogue, including the last group's
        Map<Integer, MethodImpactTracker> catalogue = dataStore.getMethodsTracked();
        assertNotNull(catalogue.get(202), "the method reachable only from the last group to finish "
                + "must survive the seal. Catalogue: " + catalogue);
        assertNotNull(catalogue.get(101), "the first group's method must survive the seal. Catalogue: "
                + catalogue);
        assertEquals(PLAN_COMMIT, dataStore.getTiaCore().getCommitValue(),
                "the last runner to finish must seal the build");
        DistributedRun run = dataStore.readDistributedRun(RUN_ID);
        assertEquals(DistributedRunStatus.SEALED, run.getStatus());
        assertEquals(RUNNER_B, run.getSealedBy(),
                "the runner that finished last must be the one that sealed");
    }

    /**
     * A runner whose completion is rejected does not go on to attempt the seal. Supersession here
     * lands <em>after</em> the claim re-verification passed - a newer build's plan write arrives
     * while the runner is staging - so the completion's own guard is what catches it. Its group is
     * then never marked complete, and a runner that never completed its group has nothing to seal.
     */
    @Test
    void aRunnerWhoseCompletionIsRejectedDoesNotAttemptTheSeal() {
        // given - a single-group run that a newer build supersedes mid-persist
        persistPlan(RUN_ID, 1, null);
        TestRunnerService service = new TestRunnerService(dataStore);
        DistributedRunnerContext context = claim(RUN_ID, RUNNER_A);
        dataStore.afterStagingMethodTrackers = new Runnable() {
            /** Supersede the run the moment the staging write lands. */
            @Override
            public void run() {
                dataStore.afterStagingMethodTrackers = null;
                persistPlan("run-2", 1, null);
            }
        };

        // when
        service.persistTestRunData(true, true, false, PLAN_COMMIT, "main",
                System.currentTimeMillis(), runResultFor("com.example.ATest", "com/example/A.java",
                        101, "com/example/A.a.()V"), context);

        // then
        assertEquals("completeGroup", dataStore.callOrder.get(dataStore.callOrder.size() - 1),
                "the runner must have reached its completion, and nothing may follow the rejected "
                        + "one. Call order: " + dataStore.callOrder);
        assertEquals(0, Collections.frequency(dataStore.callOrder, "persistSealedRunData"),
                "a runner that never completed its group must not seal. Call order: " + dataStore.callOrder);
        assertEquals(0, Collections.frequency(dataStore.callOrder, "markDistributedRunSealed"),
                "a superseded runner must not mark any run sealed. Call order: " + dataStore.callOrder);
        assertEquals("prior-commit", dataStore.getTiaCore().getCommitValue(),
                "a superseded runner must not advance the stored commit value");
    }

    /**
     * Build the sealer a given runner would use.
     *
     * @param runnerKey the identity the runner claimed under
     * @param groupNumber the group that runner holds
     * @return the sealer bound to the test's store
     */
    private DistributedRunSealer sealerFor(final String runnerKey, final int groupNumber) {
        return new DistributedRunSealer(dataStore,
                DistributedRunnerContext.forClaimedGroup(RUN_ID, runnerKey, groupNumber));
    }

    /**
     * Build and persist a distributed run plan, which also clears any previously planned run - the
     * supersession a straggler sealer has to survive.
     *
     * @param runId the run identifier to plan under
     * @param groupCount how many groups the plan is split into
     * @param drainResult the library-impact drain the plan's own selection performed, or null
     */
    private void persistPlan(final String runId, final int groupCount,
                             final LibraryImpactDrainResult drainResult) {
        List<DistributedRunGroup> groups = new ArrayList<>();
        Map<Integer, List<String>> suites = new HashMap<>();
        for (int i = 0; i < groupCount; i++) {
            groups.add(DistributedRunGroup.pending(runId, i, 1000L));
            suites.put(i, Arrays.asList("com.example.Suite" + i + "Test"));
        }
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(
                DistributedRun.open(runId, "main", PLAN_COMMIT, groupCount, null,
                        1000L * groupCount, 1234L), groups, suites, drainResult));
    }

    /**
     * Claim a group for a runner key and wrap it in the context that runner carries to its persist.
     *
     * @param runId the run to claim from
     * @param runnerKey the identity to claim under
     * @return the claimed runner's context
     */
    private DistributedRunnerContext claim(final String runId, final String runnerKey) {
        DistributedRunGroup claimed = dataStore.claimNextPendingGroup(runId, runnerKey, 5000L);
        assertNotNull(claimed, "test setup expects a group to be available to claim");
        return DistributedRunnerContext.forClaimedGroup(runId, runnerKey, claimed.getGroupNumber());
    }

    /**
     * Claim and immediately complete one group, standing in for a runner that has finished its
     * share of the build.
     *
     * @param runId the run to claim from
     * @param runnerKey the identity to claim under
     */
    private void claimAndComplete(final String runId, final String runnerKey) {
        DistributedRunnerContext context = claim(runId, runnerKey);
        assertNotNull(dataStore.completeGroup(runId, context.getGroupNumber().intValue(), runnerKey,
                6000L, 1000L, 1, 0), "test setup expects the completion to be accepted");
    }

    /**
     * Complete every group of a run, one runner key per group, so the run is at the barrier.
     *
     * @param runId the run to complete
     * @param runnerKeys one identity per group, in claim order
     */
    private void completeAllGroups(final String runId, final String... runnerKeys) {
        for (String runnerKey : runnerKeys) {
            claimAndComplete(runId, runnerKey);
        }
        dataStore.callOrder.clear();
    }

    /**
     * Seed the stored method catalogue, standing in for what previous builds recorded.
     *
     * @param catalogue the trackers to store, keyed by method id
     */
    private void seedStoredCatalogue(final Map<Integer, MethodImpactTracker> catalogue) {
        dataStore.persistSourceMethods(catalogue);
        dataStore.callOrder.clear();
    }

    /**
     * Add a suite to the stored mapping with edges to the given method ids, so the ids appear in
     * the {@code SELECT DISTINCT} the sealer rebuilds the catalogue from.
     *
     * @param suiteName the suite to store
     * @param sourceFile the covered source file's mapping key
     * @param methodIds the method ids the suite covers
     */
    private void seedSuiteEdges(final String suiteName, final String sourceFile, final int... methodIds) {
        Map<String, TestSuiteTracker> tracked = dataStore.getTestSuitesTracked();
        TestSuiteTracker tracker = new TestSuiteTracker(suiteName);
        Set<Integer> methods = new HashSet<>();
        for (int methodId : methodIds) {
            methods.add(Integer.valueOf(methodId));
        }
        tracker.setClassesImpacted(Collections.singletonList(new ClassImpactTracker(sourceFile, methods)));
        tracked.put(suiteName, tracker);
        dataStore.persistTestSuites(tracked);
        dataStore.callOrder.clear();
    }

    /**
     * Build a mutable one-entry tracker map, used both as one runner's staged observation and as a
     * seed for the stored catalogue.
     *
     * @param methodId the method id
     * @param methodName the method name
     * @param lineStart the method's first line
     * @param lineEnd the method's last line
     * @return a mutable map holding the single tracker
     */
    private Map<Integer, MethodImpactTracker> trackers(final int methodId, final String methodName,
                                                       final int lineStart, final int lineEnd) {
        Map<Integer, MethodImpactTracker> staged = new HashMap<>();
        staged.put(Integer.valueOf(methodId), new MethodImpactTracker(methodName, lineStart, lineEnd));
        return staged;
    }

    /**
     * Build the result one runner of the two-group build reports: its own suite's coverage of one
     * method, and the full discovered suite set both runners see (a runner that reported only its
     * own group's suites would prune the other group's mapping rows).
     *
     * @param suiteName the suite this runner executed
     * @param sourceFile the source file that suite covered
     * @param methodId the covered method's id
     * @param methodName the covered method's name
     * @return the result to hand to the persist
     */
    private TestRunResult runResultFor(final String suiteName, final String sourceFile,
                                       final int methodId, final String methodName) {
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        TestSuiteTracker tracker = new TestSuiteTracker(suiteName);
        tracker.setClassesImpacted(Collections.singletonList(new ClassImpactTracker(sourceFile,
                new HashSet<>(Collections.singletonList(Integer.valueOf(methodId))))));
        trackers.put(suiteName, tracker);

        Map<Integer, MethodImpactTracker> methodTrackers = new HashMap<>();
        methodTrackers.put(Integer.valueOf(methodId), new MethodImpactTracker(methodName, 1, 10));

        Set<String> runnerSuites = new HashSet<>(Arrays.asList("com.example.ATest", "com.example.BTest"));
        return new TestRunResult(trackers, new HashSet<String>(), runnerSuites,
                new HashSet<>(Collections.singletonList(suiteName)), methodTrackers, new TestStats(),
                null, 1, 1);
    }

    /**
     * An embedded-H2 {@link JdbcDataStore} that records the write calls these tests assert on.
     * Subclassing rather than decorating keeps every other operation - the plan, claim, completion
     * and staging reads the tests depend on - working against the real store.
     */
    private static final class RecordingDataStore extends JdbcDataStore {

        private final List<String> callOrder = new ArrayList<>();

        /**
         * Optional action to run immediately after a staging write lands, so a test can make a
         * supersession arrive in the window between the runner's claim re-verification and its
         * completion - the window only the completion's own guard covers.
         */
        private Runnable afterStagingMethodTrackers;

        /**
         * Open an embedded H2 store under a test-owned directory, in the same per-branch schema the
         * other persistence tests use.
         *
         * @param databaseDir the directory to hold the embedded database files
         */
        RecordingDataStore(final File databaseDir) {
            super(new H2Dialect(),
                    new H2ConnectionProvider(H2ConnectionSettings.embedded(databaseDir.getAbsolutePath())),
                    BranchSchema.schemaName("test"));
        }

        /**
         * Record and delegate the core-row write.
         *
         * @param tiaData the core data to write
         */
        @Override
        public void persistCoreData(final TiaData tiaData) {
            callOrder.add("persistCoreData");
            super.persistCoreData(tiaData);
        }

        /**
         * Record and delegate the seal bundle, which only the run's elected sealer may write.
         *
         * @param sealedRunData the bundle to write
         */
        @Override
        public void persistSealedRunData(final SealedRunData sealedRunData) {
            callOrder.add("persistSealedRunData");
            super.persistSealedRunData(sealedRunData);
        }

        /**
         * Record and delegate the standalone catalogue write, which nothing on the distributed path
         * may make.
         *
         * @param methodsTracked the catalogue to write
         */
        @Override
        public void persistSourceMethods(final Map<Integer, MethodImpactTracker> methodsTracked) {
            callOrder.add("persistSourceMethods");
            super.persistSourceMethods(methodsTracked);
        }

        /**
         * Record and delegate the full suite mapping write.
         *
         * @param testSuites the suites whose rows and edges to write
         */
        @Override
        public void persistTestSuites(final Map<String, TestSuiteTracker> testSuites) {
            callOrder.add("persistTestSuites");
            super.persistTestSuites(testSuites);
        }

        /**
         * Record and delegate the failed-suite set write.
         *
         * @param testSuitesFailed the failed suite names to store
         */
        @Override
        public void persistTestSuitesFailed(final Set<String> testSuitesFailed) {
            callOrder.add("persistTestSuitesFailed");
            super.persistTestSuitesFailed(testSuitesFailed);
        }

        /**
         * Record and delegate the staging write a distributed runner makes in place of the
         * catalogue, then run any test-supplied action that has to land mid-persist.
         *
         * @param runId the run to stage under
         * @param methodsTracked the trackers the runner observed
         */
        @Override
        public void persistStagedMethodTrackers(final String runId,
                                                final Map<Integer, MethodImpactTracker> methodsTracked) {
            callOrder.add("persistStagedMethodTrackers");
            super.persistStagedMethodTrackers(runId, methodsTracked);
            if (afterStagingMethodTrackers != null) {
                afterStagingMethodTrackers.run();
            }
        }

        /**
         * Record and delegate the staging cleanup the sealer performs.
         *
         * @param runId the run to clear
         */
        @Override
        public void deleteStagedMethodTrackers(final String runId) {
            callOrder.add("deleteStagedMethodTrackers");
            super.deleteStagedMethodTrackers(runId);
        }

        /**
         * Record and delegate the terminal-state write only the sealer makes.
         *
         * @param runId the run to mark sealed
         */
        @Override
        public void markDistributedRunSealed(final String runId) {
            callOrder.add("markDistributedRunSealed");
            super.markDistributedRunSealed(runId);
        }

        /**
         * Record and delegate the history-row write.
         *
         * @param entry the history entry to write
         */
        @Override
        public void persistTestRunHistoryEntry(final TestRunHistoryEntry entry) {
            callOrder.add("persistTestRunHistoryEntry");
            super.persistTestRunHistoryEntry(entry);
        }

        /**
         * Record and delegate the group completion, the write that releases the barrier.
         *
         * @param runId the run the group belongs to
         * @param groupNumber the group's zero-based index within the run
         * @param runnerKey the calling runner's identity
         * @param completedAtMs UTC epoch millis of the completion
         * @param actualDurationMs measured test-execution time of this group
         * @param suitesRan number of suites the runner executed
         * @param suitesFailed number of this runner's failed suites
         * @return the updated group, or null when the claim is no longer live
         */
        @Override
        public DistributedRunGroup completeGroup(final String runId, final int groupNumber,
                                                 final String runnerKey, final long completedAtMs,
                                                 final long actualDurationMs, final int suitesRan,
                                                 final int suitesFailed) {
            callOrder.add("completeGroup");
            return super.completeGroup(runId, groupNumber, runnerKey, completedAtMs,
                    actualDurationMs, suitesRan, suitesFailed);
        }
    }
}
