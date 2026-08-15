package org.tiatesting.core.distributed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the runner-side persist operations directly, against a real embedded H2 store: the claim
 * re-verification that decides whether a runner may write at all, and the late guard on the
 * completion that catches a supersession landing after that check.
 * {@code TestRunnerServiceDistributedPersistTest} covers how the persist flow uses them.
 */
class DistributedRunnerPersistTest {

    private static final String RUN_ID = "run-1";
    private static final String RUNNER_KEY = "runner-a";

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 store in its own temp directory so each test starts with no
     * distributed run planned.
     *
     * @throws Exception if the temp directory cannot be created or the schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-persist-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test"));
        dataStore.getTiaData(true);
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
     * A runner still holding its claim is cleared to write. This is the case every healthy runner
     * in a build takes, so it is asserted explicitly rather than inferred from the failure cases.
     */
    @Test
    void claimIsLiveForTheRunnerHoldingTheGroup() {
        // given
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);

        // when
        boolean live = persistFor(0).claimIsLive();

        // then
        assertTrue(live, "the runner holding the claim must be cleared to write its mapping rows");
    }

    /**
     * A runner whose run was superseded - a newer build's plan write cleared these rows - is not
     * cleared to write. Writing anyway would leave rows describing this build's older commit under
     * the commit the newer build stores.
     */
    @Test
    void claimIsNotLiveWhenTheRunWasSuperseded() {
        // given
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        persistPlan("run-2", 1);

        // when
        boolean live = persistFor(0).claimIsLive();

        // then
        assertFalse(live, "a runner whose plan rows are gone must not write");
    }

    /**
     * A runner asking about a group another runner key holds is not cleared to write either: the
     * guard is on the claim's identity, not merely on the run still existing.
     */
    @Test
    void claimIsNotLiveWhenAnotherRunnerHoldsTheGroup() {
        // given
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, "runner-b", 5000L);

        // when
        boolean live = persistFor(0).claimIsLive();

        // then
        assertFalse(live, "a runner that does not hold the group must not write");
    }

    /**
     * A group already completed is not live, so a second persist by the same runner (a listener
     * firing twice, say) does not re-open it or write its mapping rows a second time.
     */
    @Test
    void claimIsNotLiveOnceTheGroupIsCompleted() {
        // given
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        DistributedRunnerPersist runnerPersist = persistFor(0);
        assertTrue(runnerPersist.reportGroupProgress(1000L, 2, 0, 2));
        assertNotNull(runnerPersist.completeGroup(6000L));

        // when
        boolean live = persistFor(0).claimIsLive();

        // then
        assertFalse(live, "an already-completed group must not be written to again");
    }

    /**
     * The late guard: a supersession that lands after {@code claimIsLive} passed is caught by the
     * completion's own guard, which returns null. The group is then never marked complete, so the
     * superseded run can never elect a sealer and can never advance the stored commit value.
     */
    @Test
    void completeGroupReturnsNullWhenTheClaimDiedAfterTheCheck() {
        // given
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        DistributedRunnerPersist runnerPersist = persistFor(0);
        assertTrue(runnerPersist.claimIsLive());
        persistPlan("run-2", 1);

        // when
        DistributedRunGroup completed = runnerPersist.completeGroup(6000L);

        // then
        assertNull(completed, "a completion whose claim died must report it rather than appear to succeed");
    }

    /**
     * The task 1 requirement, driven through the persist wrapper rather than the raw data store: a
     * progress report on a group whose claim has already died - the run was superseded between the
     * claim re-verification and this call - writes nothing and says so via its boolean return,
     * exactly mirroring what a failed {@link DistributedRunnerPersist#completeGroup(long)} reports.
     */
    @Test
    void reportGroupProgressWritesNothingWhenTheClaimHasDied() {
        // given
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        DistributedRunnerPersist runnerPersist = persistFor(0);
        assertTrue(runnerPersist.claimIsLive());
        persistPlan("run-2", 1);

        // when
        boolean applied = runnerPersist.reportGroupProgress(1000L, 2, 0, 2);

        // then
        assertFalse(applied, "a progress report on a dead claim must report failure rather than "
                + "appear to succeed");
    }

    /**
     * A rejected completion says which of its causes actually happened. The run being superseded -
     * a newer build's plan write having cleared these rows - is the case a reader must be able to
     * tell apart from the others, since it is the only one that is business as usual for a
     * straggler and not a sign of something wrong with the run.
     */
    @Test
    void aRejectedCompletionReportsThatTheRunWasSuperseded() {
        // given
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        persistPlan("run-2", 1);

        // when
        String description = persistFor(0).describeRejectedCompletion();

        // then
        assertTrue(description.contains("superseded"),
                "a completion rejected because the run's rows are gone must say so, was: " + description);
    }

    /**
     * A completion rejected because another runner now holds the group names that runner, so the
     * log points at the identity that took it rather than leaving a reader to guess.
     */
    @Test
    void aRejectedCompletionNamesTheRunnerThatNowHoldsTheGroup() {
        // given
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, "runner-b", 5000L);

        // when
        String description = persistFor(0).describeRejectedCompletion();

        // then
        assertTrue(description.contains("runner-b"),
                "a completion rejected because another runner holds the group must name it, was: "
                        + description);
    }

    /**
     * A completion rejected because this same runner key already completed the group says exactly
     * that, rather than reporting a supersession that did not happen. The two look identical from
     * the guarded write's row count and only the group row tells them apart.
     */
    @Test
    void aRejectedCompletionReportsAGroupThisRunnerAlreadyCompleted() {
        // given
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        DistributedRunnerPersist runnerPersist = persistFor(0);
        assertTrue(runnerPersist.reportGroupProgress(1000L, 2, 0, 2));
        assertNotNull(runnerPersist.completeGroup(6000L));

        // when
        String description = persistFor(0).describeRejectedCompletion();

        // then
        assertTrue(description.contains("already"),
                "a group this runner already completed must be reported as such, was: " + description);
        assertFalse(description.contains("superseded"),
                "a group this runner completed normally was not superseded, was: " + description);
    }

    /**
     * A completion rejected because the group has not yet discovered enough of its assigned suites
     * says so with the discovered-versus-assigned counts, so a reader is not left guessing whether
     * the completeness guard or the straggler guard is what actually blocked it - the two miss the
     * same {@code WHERE} clause and only the group row (plus the assigned suite count) tells them
     * apart. Asserts the exact "0 of 1" substring rather than the two digits independently, so the
     * assertion cannot pass with the counts swapped or embedded in unrelated text.
     */
    @Test
    void aRejectedCompletionReportsTheDiscoveredVersusAssignedCountsWhenTheGroupIsIncomplete() {
        // given - one suite assigned to the group, and no progress reported at all
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        DistributedRunnerPersist runnerPersist = persistFor(0);
        assertNull(runnerPersist.completeGroup(6000L),
                "test setup expects the completion to be rejected as incomplete");

        // when
        String description = runnerPersist.describeRejectedCompletion();

        // then
        assertTrue(description.contains("0 of 1"),
                "the rejection must name the discovered-versus-assigned counts (0 of 1), was: "
                        + description);
        assertFalse(description.contains("superseded"),
                "a group this runner still holds was not superseded, was: " + description);
        assertFalse(description.contains("already"),
                "a group never completed was not completed twice, was: " + description);
    }

    /**
     * Task 2's accumulation contract, exercised through the persist wrapper: two progress reports
     * in the same JVM - the second reporting fewer suites than the first, as a Surefire retry of a
     * smaller failing subset would - sum the ran counter and the duration, but let the later report
     * replace both the failed set and the discovered count outright: the failed set because it is
     * current state, the discovered count because the set it is drawn from is already cumulative
     * per JVM, so summing it here would double-count.
     */
    @Test
    void reportGroupProgressAccumulatesCountersButReplacesTheFailedSet() {
        // given
        persistPlan(RUN_ID, 1);
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        DistributedRunnerPersist runnerPersist = persistFor(0);

        // when - the first test plan reports 50 suites with 3 failures, the retry reports 3 more
        // suites with none failing; the discovered count is the JVM's cumulative total each time,
        // not this call's own contribution
        assertTrue(runnerPersist.reportGroupProgress(4000L, 50, 3, 50));
        assertTrue(runnerPersist.reportGroupProgress(500L, 3, 0, 53));

        // then
        DistributedRunGroup group = readGroup(RUN_ID, 0);
        assertEquals(53, group.getSuitesRan(), "the ran counter must sum across both reports");
        assertEquals(4500L, group.getActualDurationMs().longValue(),
                "the duration must sum across both reports");
        assertEquals(0, group.getSuitesFailed(),
                "the failed count must reflect only the later report, not accumulate onto the first");
        assertEquals(53, group.getSuitesDiscovered(),
                "the discovered count must reflect only the later report's cumulative total, not "
                        + "sum onto the first report's");
    }

    /**
     * A surplus runner's context is rejected at construction: it holds no group, so there is
     * nothing for it to verify, stage or complete, and quietly accepting one would mean a caller
     * had asked for writes that cannot be keyed to any group.
     */
    @Test
    void aSurplusRunnerContextIsRejected() {
        // given
        DistributedRunnerContext surplus = DistributedRunnerContext.surplusRunner(RUN_ID, RUNNER_KEY);

        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> new DistributedRunnerPersist(dataStore, surplus));
    }

    /**
     * Build the persist operations for a claimed group of the test's run.
     *
     * @param groupNumber the group this runner holds
     * @return the runner-side persist operations bound to the test's store
     */
    private DistributedRunnerPersist persistFor(final int groupNumber) {
        return new DistributedRunnerPersist(dataStore,
                DistributedRunnerContext.forClaimedGroup(RUN_ID, RUNNER_KEY, groupNumber));
    }

    /**
     * Read one group of a run back from the store, so a test can assert what a guarded update
     * actually left on disk.
     *
     * @param runId the run the group belongs to
     * @param groupNumber the group's zero-based index within the run
     * @return the stored group
     */
    private DistributedRunGroup readGroup(final String runId, final int groupNumber) {
        for (DistributedRunGroup group : dataStore.readDistributedRunGroups(runId)) {
            if (group.getGroupNumber() == groupNumber) {
                return group;
            }
        }
        throw new IllegalStateException("no group " + groupNumber + " in run " + runId);
    }

    /**
     * Build and persist a distributed run plan, which also clears any previously planned run - the
     * supersession a straggler runner has to survive.
     *
     * @param runId the run identifier to plan under
     * @param groupCount how many groups the plan is split into
     */
    private void persistPlan(final String runId, final int groupCount) {
        List<DistributedRunGroup> groups = new ArrayList<>();
        Map<Integer, List<String>> suites = new HashMap<>();
        for (int i = 0; i < groupCount; i++) {
            groups.add(DistributedRunGroup.pending(runId, i, 1000L));
            suites.put(i, Arrays.asList("com.example.Suite" + i + "Test"));
        }
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(
                DistributedRun.open(runId, "main", "plan-commit", groupCount, null,
                        1000L * groupCount, 1234L), groups, suites, null));
    }
}
