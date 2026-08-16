package org.tiatesting.junit.junit4;

import org.junit.runner.Description;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.distributed.DistributedForkProperties;
import org.tiatesting.core.distributed.DistributedRunCompletion;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Cover the last link in the JUnit 4 distributed runner: that the run id, runner key and group
 * number the Maven goal forwarded through the fork properties file reach
 * {@code TestRunnerService.persistTestRunData} at the end of the run.
 *
 * <p>This is asserted rather than assumed because the failure it guards against is silent. With no
 * context the listener persists as a single host: it rebuilds the method catalogue from an edge set
 * holding only its own group's suites, drops every method the other groups reach, and stamps the
 * commit as though the whole build were done - so the next build's diff cannot see those methods
 * and the suites covering them stop being selected. No test fails, and the build goes green.
 *
 * <p>Driven against a real embedded-H2 {@link JdbcDataStore}, because what is under test is which
 * rows the persist writes. The two run hooks are called with a null description and result: the
 * listener does not read either argument, and building real ones would add a JUnit 4 runner to the
 * fixture without testing anything more. {@link #runTheListener()} does call {@code testIgnored}
 * with one real {@link Description} first, though - not to exercise the ignore path itself, but
 * because the distributed completeness guard needs this fork to have observed at least as many
 * suites as the plan assigned it, and nothing else in this fixture's stripped-down lifecycle would
 * do that.
 */
class TiaJunit4ListenerDistributedTest {

    private static final String BRANCH = "main";
    private static final String PLAN_COMMIT = "commit-1";
    private static final String RUN_ID = "run-1";
    private static final String SUITE = "com.example.ATest";

    private static final String[] MANAGED_PROPERTIES = {
            "tiaEnabled", "tiaUpdateDBMapping", "tiaUpdateDBStats", "tiaUpdateDBTestRunHistory",
            "tiaSelectedTests", "tiaIgnoredTestSuiteCount", "testClassesDir", "test",
            H2ConnectionSettings.PROP_DB_FILE_PATH, H2ConnectionSettings.PROP_DB_URL,
            DistributedForkProperties.PROP_DISTRIBUTED, DistributedForkProperties.PROP_RUN_ID,
            DistributedForkProperties.PROP_RUNNER_KEY, DistributedForkProperties.PROP_GROUP_NUMBER
    };

    private Map<String, String> savedProperties;
    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Save and clear every system property these tests set, create a fresh embedded H2 database in
     * a new temp directory, and point the listener's datastore construction at it. The core row is
     * seeded with a prior commit stamp, as every store a real run persists into already has one.
     *
     * @throws Exception if the temp directory cannot be created or schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        savedProperties = new LinkedHashMap<>();
        for (String key : MANAGED_PROPERTIES) {
            savedProperties.put(key, System.getProperty(key));
            System.clearProperty(key);
        }

        tempDir = File.createTempFile("tia-junit5-distributed-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName(BRANCH));
        dataStore.getTiaData(true);

        TiaData tiaData = dataStore.getTiaCore();
        tiaData.setCommitValue("prior-commit");
        tiaData.setBranch(BRANCH);
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);

        System.setProperty("tiaEnabled", "true");
        System.setProperty(H2ConnectionSettings.PROP_DB_FILE_PATH, tempDir.getAbsolutePath());
        System.setProperty("tiaSelectedTests", SUITE);
    }

    /**
     * Drop anything a test recorded for its fork's JVM exit, close the datastore so embedded H2
     * releases its file lock, remove the temp directory, and restore the system properties saved in
     * {@link #setUp()} so these tests leave the JVM as they found it.
     */
    @AfterEach
    void tearDown() {
        DistributedRunCompletion.discardPendingCompletions();
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
        for (Map.Entry<String, String> entry : savedProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Persist a single-group run plan, so one runner's completion is enough to finish the build and
     * a test can assert on the seal without standing up a second runner.
     */
    private void persistSingleGroupPlan() {
        List<DistributedRunGroup> groups = new ArrayList<>();
        groups.add(DistributedRunGroup.pending(RUN_ID, 0, 1000L));
        Map<Integer, List<String>> suitesByGroup = new HashMap<>();
        suitesByGroup.put(0, Collections.singletonList(SUITE));
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(
                DistributedRun.open(RUN_ID, BRANCH, PLAN_COMMIT, 1, null, 1000L, 5000L),
                groups, suitesByGroup, null));
    }

    /**
     * Claim the plan's group for a runner key and publish the resulting handoff as system
     * properties, standing in for the Maven goal's claim plus the Tia agent's {@code premain}
     * republishing of the fork properties file.
     *
     * @param runnerKey the identity to claim under
     */
    private void givenAClaimedDistributedFork(final String runnerKey) {
        DistributedRunGroup claimed = dataStore.claimNextPendingGroup(RUN_ID, runnerKey,
                System.currentTimeMillis());
        assertNotNull(claimed, "test setup expects a group to be available to claim");
        Map<String, String> forkProperties = DistributedForkProperties.forkProperties(RUN_ID,
                runnerKey, Integer.valueOf(claimed.getGroupNumber()));
        for (Map.Entry<String, String> entry : forkProperties.entrySet()) {
            System.setProperty(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Run a whole fork's lifecycle for a run in which the suite executed but no coverage was
     * collected, which is all this fixture needs: the flags leave mapping and stats off, so the
     * persist writes only its recording, and the fork's JVM exit does the completion and the seal.
     *
     * <p>{@code testIgnored} is called once, with a {@link Description} named after the plan's
     * assigned suite, before the run hooks - not to exercise the ignore path, but to give the
     * listener's internally-tracked observed set one entry. Without it the completeness guard would
     * see none of the group's assigned suites observed and block every completion in this class. The
     * name matters: the guard counts only the observed suites that are in this runner's own group,
     * so a suite the plan never assigned would not count toward the completion.
     *
     * <p>The exit is driven directly, since a test cannot exit the JVM it runs in. It is part of
     * the lifecycle rather than something a test adds: the barrier is released when the fork
     * finishes, not when one of its test plans does.
     *
     * @throws Exception if the JUnit 4 run-start hook fails, which its signature allows
     */
    private void runTheListener() throws Exception {
        TiaJunit4Listener listener = new TiaJunit4Listener(new StubVCSReader());
        listener.testIgnored(Description.createSuiteDescription(SUITE));
        listener.testRunStarted(null);
        listener.testRunFinished(null);
        DistributedRunCompletion.completePendingCompletions();
    }

    /**
     * Verify a distributed fork persists through the distributed flow, by asserting on what only
     * that flow writes: the group the build JVM claimed is marked complete under the forwarded
     * runner key, and the run is sealed. Neither row moves on the single-host path.
     *
     * @throws Exception if the listener's run hooks fail
     */
    @Test
    void shouldPersistThroughTheDistributedFlowWhenTheForkWasGivenAClaimedGroup() throws Exception {
        // given
        persistSingleGroupPlan();
        givenAClaimedDistributedFork("runner-a");

        // when
        runTheListener();

        // then
        DistributedRunGroup group = dataStore.readDistributedRunGroups(RUN_ID).get(0);
        assertEquals(DistributedRunGroupStatus.COMPLETED, group.getStatus(),
                "the fork must complete the group the build JVM claimed, which releases the barrier");
        assertEquals("runner-a", group.getRunnerKey(),
                "the completion must be recorded under the forwarded key, not one derived here");
        DistributedRun run = dataStore.readDistributedRun(RUN_ID);
        assertEquals(DistributedRunStatus.SEALED, run.getStatus(),
                "the only runner in the build must go on to seal it");
        assertEquals("runner-a", run.getSealedBy());
    }

    /**
     * Verify the build's one history row is the aggregated distributed row, keyed by the forwarded
     * run id, rather than the per-runner row a single-host persist writes.
     *
     * @throws Exception if the listener's run hooks fail
     */
    @Test
    void shouldRecordTheBuildsAggregatedHistoryRowAgainstTheForwardedRunId() throws Exception {
        // given
        persistSingleGroupPlan();
        givenAClaimedDistributedFork("runner-a");

        // when
        runTheListener();

        // then
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
        assertEquals(1, history.size(), "a distributed build writes one row for the whole build: "
                + history);
        assertEquals(RUN_ID, history.get(0).getRunId(),
                "the row must be the aggregated distributed one, not a runner's own");
    }

    /**
     * Verify an ordinary build's fork is untouched by any of this: with the distributed property
     * absent the listener persists on the single-host path, writing its own history row and leaving
     * every distributed row exactly as the (unrelated) planned run left it.
     *
     * @throws Exception if the listener's run hooks fail
     */
    @Test
    void shouldPersistOnTheSingleHostPathWhenTheForkIsNotDistributed() throws Exception {
        // given - a run planned and claimed by some other build, which this one must not touch
        persistSingleGroupPlan();
        dataStore.claimNextPendingGroup(RUN_ID, "another-build", System.currentTimeMillis());

        // when
        runTheListener();

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

        /**
         * @return the fixed branch these tests plan and claim against
         */
        @Override
        public String getBranchName() {
            return BRANCH;
        }

        /**
         * @return the commit the plan was pinned to, which this workspace is reported to be on
         */
        @Override
        public String getHeadCommit() {
            return PLAN_COMMIT;
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
            throw new UnsupportedOperationException("the test listener must not diff");
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
            throw new UnsupportedOperationException("the test listener must not diff");
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
            throw new UnsupportedOperationException("the test listener must not diff");
        }

        /**
         * No resources to release.
         */
        @Override
        public void close() {
        }
    }
}
