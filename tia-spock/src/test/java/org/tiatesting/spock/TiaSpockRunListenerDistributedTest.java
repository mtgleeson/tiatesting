package org.tiatesting.spock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestIdentifier;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.distributed.DistributedRunCompletion;
import org.tiatesting.core.distributed.DistributedRunConfig;
import org.tiatesting.core.distributed.DistributedRunnerAssignment;
import org.tiatesting.core.distributed.DistributedRunnerContext;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.DistributedRunStatus;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the other half of the Gradle/Spock distributed runner: that the claim
 * {@link TiaSpockGlobalExtension} makes before the run actually reaches the persist at the end of
 * it. The claim was already wired up, but until the context reached
 * {@code TestRunnerService.persistTestRunData} a distributed Gradle build persisted as a single
 * host - every runner rebuilding the method catalogue from an edge set holding only its own
 * group's suites, and stamping the commit as though the whole build were done. Nothing in the
 * build fails when that happens, which is why it is asserted here rather than left to inspection.
 *
 * <p>Driven against a real embedded-H2 {@link JdbcDataStore} following the fixture
 * {@code TiaSpockTestRunInitializerDistributedTest} uses: what is under test is which rows the
 * persist writes, and a fake store would only assert that the listener calls the methods the test
 * already knows it calls.
 */
class TiaSpockRunListenerDistributedTest {

    private static final String BRANCH = "main";
    private static final String PLAN_COMMIT = "commit-1";
    private static final String RUN_ID = "run-1";

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database in a new temp directory and bootstrap its schema, so
     * each test starts from an isolated store with no run planned and cannot see another test's
     * claims.
     *
     * @throws Exception if the temp directory cannot be created or schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-spock-listener-distributed-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName(BRANCH));
        dataStore.getTiaData(true);

        // Seed a prior commit stamp, as every store a real run persists into already has one.
        TiaData tiaData = dataStore.getTiaCore();
        tiaData.setCommitValue("prior-commit");
        tiaData.setBranch(BRANCH);
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);
    }

    /**
     * Drop anything a test recorded for its runner's JVM exit, clear the JVM-static skip observation
     * a test may have written into via {@link #recordSpecObservedViaSkip}, close the data store so
     * its embedded H2 database releases its file lock, then remove the temp directory. The skip set
     * is otherwise never cleared within one JVM - production relies on that, since one Gradle test
     * worker JVM runs exactly one build - but several {@code @Test} methods run in this one JVM, so
     * a test that wrote into it must not leak a spec name into the next test's observation.
     */
    @AfterEach
    void tearDown() {
        DistributedRunCompletion.discardPendingCompletions();
        SharedSpockSkipObservation.suitesObservedViaSkip().clear();
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
     * Persist a single-group run plan, so one runner's completion is enough to finish the build and
     * a test can assert on the seal without standing up a second runner.
     *
     * @param runId the run identifier to plan under
     */
    private void persistSingleGroupPlan(final String runId) {
        List<DistributedRunGroup> groups = new ArrayList<>();
        groups.add(DistributedRunGroup.pending(runId, 0, 1000L));
        Map<Integer, List<String>> suitesByGroup = new HashMap<>();
        suitesByGroup.put(0, Collections.singletonList("com.example.ATest"));
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(
                DistributedRun.open(runId, BRANCH, PLAN_COMMIT, 1, null, 1000L, 5000L),
                groups, suitesByGroup, null));
    }

    /**
     * Claim the plan's group the way the Spock extension does, and convert the assignment into the
     * context it hands to the listener - the exact path under test, rather than a context built by
     * hand.
     *
     * @param runnerKey the identity to claim under
     * @return the claimed runner's context
     */
    private DistributedRunnerContext claimAsTheExtensionDoes(final String runnerKey) {
        DistributedRunnerAssignment assignment = DistributedRunnerAssignment.claim(dataStore,
                DistributedRunConfig.forRunner(RUN_ID, runnerKey), PLAN_COMMIT,
                System.currentTimeMillis());
        assertTrue(assignment.isClaimed(), "test setup expects a group to be available to claim");
        return assignment.toRunnerContext(RUN_ID);
    }

    /**
     * Build the listener as the Spock extension builds it for a history-logging run, with the
     * given distributed context.
     *
     * @param distributedRunnerContext the claimed runner's context, or null for an ordinary build
     * @return the listener under test
     */
    private TiaSpockRunListener listenerFor(final DistributedRunnerContext distributedRunnerContext) {
        return new TiaSpockRunListener(new StubVCSReader(PLAN_COMMIT), dataStore,
                Collections.singleton("com.example.ATest"), 0, false, false, true, null,
                distributedRunnerContext);
    }

    /**
     * Drive the production skip-observation path directly, rather than the {@code
     * TiaSpockRunListener.specSkipped} hook Spock never actually calls: build a class-level JUnit
     * Platform {@link TestIdentifier} for the given spec name and feed it through a fresh {@link
     * TiaSpockSkipExecutionListener}, exactly as the listener {@link TiaSpockLauncherSessionListener}
     * registers would on a real skip. The identifier only needs a {@link ClassSource} naming the
     * spec and a container type - nothing else the listener reads.
     *
     * @param specName the fully-qualified spec name to record as observed via the skip path
     */
    private void recordSpecObservedViaSkip(final String specName) {
        TestDescriptor descriptor = new AbstractTestDescriptor(UniqueId.forEngine("stub-spock-engine"),
                specName, ClassSource.from(specName)) {
            @Override
            public Type getType() {
                return Type.CONTAINER;
            }
        };
        new TiaSpockSkipExecutionListener().executionSkipped(TestIdentifier.from(descriptor),
                "stubbed skip for test");
    }

    /**
     * Verify the context the extension claimed reaches the persist, by asserting on what only the
     * distributed flow writes: the group is marked complete and the run is sealed by this runner.
     * On the single-host path neither row would move, which is exactly the silent failure the
     * listener's previously hard-coded null context produced.
     */
    @Test
    void shouldPersistThroughTheDistributedFlowWhenTheExtensionClaimedAGroup() {
        // given
        persistSingleGroupPlan(RUN_ID);
        DistributedRunnerContext context = claimAsTheExtensionDoes("runner-a");
        TiaSpockRunListener listener = listenerFor(context);

        // when - the runner's tests finish, and then its JVM exits. The skip-observation listener
        // records this fork's one assigned suite first, so the completeness guard sees it as
        // observed - without it the guard would see zero observed against one assigned and block
        // the completion.
        recordSpecObservedViaSkip("com.example.ATest");
        listener.finishAllTests(Collections.singleton("com.example.ATest"), System.currentTimeMillis());
        DistributedRunCompletion.completePendingCompletions();

        // then
        DistributedRunGroup group = dataStore.readDistributedRunGroups(RUN_ID).get(0);
        assertEquals(DistributedRunGroupStatus.COMPLETED, group.getStatus(),
                "the runner must complete its group, which is what releases the barrier");
        assertEquals("runner-a", group.getRunnerKey());
        DistributedRun run = dataStore.readDistributedRun(RUN_ID);
        assertEquals(DistributedRunStatus.SEALED, run.getStatus(),
                "the only runner in the build must go on to seal it");
        assertEquals("runner-a", run.getSealedBy());
    }

    /**
     * Verify the build's one history row is the aggregated distributed row, keyed by the run id,
     * rather than the per-runner row a single-host persist writes. The run id on the row is what
     * tells the two apart in the stored history.
     */
    @Test
    void shouldRecordTheBuildsAggregatedHistoryRowAgainstTheRunId() {
        // given
        persistSingleGroupPlan(RUN_ID);
        DistributedRunnerContext context = claimAsTheExtensionDoes("runner-a");
        TiaSpockRunListener listener = listenerFor(context);

        // when - the runner's tests finish, and then its JVM exits. The skip-observation listener
        // records this fork's one assigned suite first, so the completeness guard sees it as observed.
        recordSpecObservedViaSkip("com.example.ATest");
        listener.finishAllTests(Collections.singleton("com.example.ATest"), System.currentTimeMillis());
        DistributedRunCompletion.completePendingCompletions();

        // then
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
        assertEquals(1, history.size(), "a distributed build writes one row for the whole build: "
                + history);
        assertEquals(RUN_ID, history.get(0).getRunId(),
                "the row must be the aggregated distributed one, not a runner's own");
    }

    /**
     * Verify an ordinary Gradle build is untouched by any of this: with no context the listener
     * persists on the single-host path, writing its own history row and leaving every distributed
     * row exactly as the (unrelated) planned run left it.
     */
    @Test
    void shouldPersistOnTheSingleHostPathWhenTheBuildIsNotDistributed() {
        // given - a run planned and claimed by some other build, which this one must not touch
        persistSingleGroupPlan(RUN_ID);
        claimAsTheExtensionDoes("another-build");

        // when
        listenerFor(null).finishAllTests(Collections.singleton("com.example.ATest"),
                System.currentTimeMillis());

        // then
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
        assertEquals(1, history.size(), history.toString());
        assertNull(history.get(0).getRunId(),
                "a single-host run's history row belongs to no distributed run");
        assertEquals(DistributedRunGroupStatus.CLAIMED,
                dataStore.readDistributedRunGroups(RUN_ID).get(0).getStatus(),
                "a non-distributed run must not complete anybody's group");
        assertEquals(DistributedRunStatus.OPEN, dataStore.readDistributedRun(RUN_ID).getStatus(),
                "a non-distributed run must not seal anybody's run");
    }

    /**
     * Minimal VCS reader reporting a fixed branch and commit. The listener reads only those two
     * values from it and then closes it; nothing on this path diffs.
     */
    private static final class StubVCSReader implements VCSReader {

        private final String headCommit;

        /**
         * @param headCommit the commit this workspace is reported to be on
         */
        private StubVCSReader(final String headCommit) {
            this.headCommit = headCommit;
        }

        /**
         * @return the fixed branch these tests plan and claim against
         */
        @Override
        public String getBranchName() {
            return BRANCH;
        }

        /**
         * @return the workspace commit this reader was built with
         */
        @Override
        public String getHeadCommit() {
            return headCommit;
        }

        /**
         * Never called: the listener runs after selection, and a distributed runner never diffs.
         *
         * @param baseChangeNum ignored
         * @param sourceFilesDirs ignored
         * @param testFilesDirs ignored
         * @param checkLocalChanges ignored
         * @return never returns
         */
        @Override
        public Set<SourceFileDiffContext> getDiffFiles(final String baseChangeNum,
                                                        final List<String> sourceFilesDirs,
                                                        final List<String> testFilesDirs,
                                                        final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("the run listener must not diff");
        }

        /**
         * Never called on this path.
         *
         * @param diffs ignored
         * @param baseChangeNum ignored
         * @param checkLocalChanges ignored
         */
        @Override
        public void loadContentForDiffs(final Collection<SourceFileDiffContext> diffs,
                                         final String baseChangeNum, final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("the run listener must not diff");
        }

        /**
         * Never called on this path.
         *
         * @param baseChangeNum ignored
         * @param checkLocalChanges ignored
         * @return never returns
         */
        @Override
        public Set<String> getChangedFilePaths(final String baseChangeNum, final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("the run listener must not diff");
        }

        /**
         * No resources to release.
         */
        @Override
        public void close() {
        }
    }
}
