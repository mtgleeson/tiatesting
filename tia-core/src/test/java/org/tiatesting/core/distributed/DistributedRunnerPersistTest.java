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
        assertNotNull(persistFor(0).completeGroup(1000L, 2, 0, 6000L));

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
        DistributedRunGroup completed = runnerPersist.completeGroup(1000L, 2, 0, 6000L);

        // then
        assertNull(completed, "a completion whose claim died must report it rather than appear to succeed");
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
