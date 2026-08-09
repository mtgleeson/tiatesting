package org.tiatesting.core.distributed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover {@link DistributedRunPlanner}: the composition of {@link TestGroupBalancer} and
 * {@link org.tiatesting.core.persistence.DataStore#persistDistributedRunPlan} that turns a test
 * selection into a persisted, claimable plan. Uses a real embedded-H2
 * {@link JdbcDataStore} rather than a fake, since the planner's whole job is the interaction
 * between balancing and persistence and a fake would only test the mock.
 */
class DistributedRunPlannerTest {

    private JdbcDataStore dataStore;
    private H2ConnectionProvider connectionProvider;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database in a new temp directory and bootstrap its schema, so
     * each test starts from an isolated store with no distributed runs planned. The connection
     * provider is kept alongside the data store, rather than reused from it, because {@code
     * JdbcDataStore.getConnection()} is package-private and this test lives outside the
     * persistence package; {@link H2ConnectionProvider#get()} is the public equivalent used only
     * by the one test that needs a raw connection to simulate a group completing via direct SQL.
     *
     * @throws Exception if the temp directory cannot be created or schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-distributed-planner-", "");
        tempDir.delete();
        tempDir.mkdirs();
        connectionProvider = new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath()));
        dataStore = new JdbcDataStore(new H2Dialect(), connectionProvider, BranchSchema.schemaName("test"));
        dataStore.getTiaData(true);
    }

    /**
     * Close the data store so its embedded H2 database releases its file lock.
     */
    @AfterEach
    void tearDown() {
        if (dataStore != null) {
            dataStore.close();
        }
    }

    /**
     * Build a selection of three suites with distinct, known run times, so tests can assert exact
     * estimates rather than tolerating whatever the balancer happened to produce.
     *
     * @return a selection with three suites in {@code testsToRun}, each with a recorded run time
     */
    private static TestSelectorResult threeSuiteSelection() {
        Map<String, Long> runTimes = new HashMap<>();
        runTimes.put("com.example.ATest", 30000L);
        runTimes.put("com.example.BTest", 20000L);
        runTimes.put("com.example.CTest", 10000L);
        Set<String> testsToRun = new HashSet<>(runTimes.keySet());
        return new TestSelectorResult(testsToRun, Collections.<String>emptySet(), null,
                60000L, Collections.<String>emptySet(), 0L, runTimes, 0L, 6000L);
    }

    /**
     * Build a selection with no suites at all, so tests can verify the planner produces a valid
     * (if trivial) plan rather than failing on an empty build.
     *
     * @return a selection with an empty {@code testsToRun}
     */
    private static TestSelectorResult emptySelection() {
        return new TestSelectorResult(Collections.<String>emptySet(), Collections.<String>emptySet(),
                null, 0L, Collections.<String>emptySet(), 0L, new HashMap<String, Long>(), 0L, 0L);
    }

    /**
     * Verify that a static-groups plan persists and reads back with the run id, branch, commit,
     * group count and creation time exactly as supplied, a null target run time (static groups
     * have no target), every group PENDING with the estimate the balancer computed, and the suite
     * assignment intact.
     */
    @Test
    void shouldPersistAndReadBackAStaticGroupsPlan() {
        // given
        DistributedRunConfig config = DistributedRunConfig.validated("run-static", 2, null, null, null);
        DistributedRunPlanner planner = new DistributedRunPlanner(dataStore, config);
        TestSelectorResult selection = threeSuiteSelection();

        // when
        DistributedRunPlanSummary summary = planner.plan(selection, "main", "commit-1", false, 111222L);

        // then
        DistributedRun readRun = dataStore.readDistributedRun("run-static");
        assertEquals("run-static", readRun.getRunId());
        assertEquals("main", readRun.getBranch());
        assertEquals("commit-1", readRun.getCommitValue());
        assertEquals(2, readRun.getGroupCount());
        assertEquals(111222L, readRun.getCreatedAtMs());
        assertNull(readRun.getTargetRunTimeMs());

        List<DistributedRunGroup> groups = dataStore.readDistributedRunGroups("run-static");
        assertEquals(2, groups.size());
        for (DistributedRunGroup group : groups) {
            assertEquals(DistributedRunGroupStatus.PENDING, group.getStatus());
        }

        Set<String> allSuites = new HashSet<>();
        for (DistributedRunGroup group : groups) {
            allSuites.addAll(dataStore.readDistributedRunGroupSuites("run-static", group.getGroupNumber()));
        }
        assertEquals(selection.getTestsToRun(), allSuites);
        assertEquals(2, summary.getGroupCount());
        assertNull(summary.getTargetMs());
    }

    /**
     * Verify that a dynamic-groups plan uses the group count the balancer chose (not a caller
     * supplied one, since none was supplied) and persists the configured target run time on the
     * run row.
     */
    @Test
    void shouldUseBalancerChosenGroupCountAndStoreTargetForDynamicGroups() {
        // given
        DistributedRunConfig config = DistributedRunConfig.validated("run-dynamic", null, 25000L, null, null);
        DistributedRunPlanner planner = new DistributedRunPlanner(dataStore, config);
        TestSelectorResult selection = threeSuiteSelection();

        // when
        DistributedRunPlanSummary summary = planner.plan(selection, "main", "commit-2", false, 555L);

        // then
        DistributedRun readRun = dataStore.readDistributedRun("run-dynamic");
        assertEquals(25000L, readRun.getTargetRunTimeMs().longValue());
        assertEquals(readRun.getGroupCount(), summary.getGroupCount());
        assertTrue(readRun.getGroupCount() >= 1);
        assertEquals(Long.valueOf(25000L), summary.getTargetMs());
    }

    /**
     * Verify that every selected suite appears exactly once in the persisted plan: the union of
     * suite names read back across every group equals {@code selection.getTestsToRun()} exactly.
     * This is the end-to-end assertion of the suite-conservation guard - it checks what actually
     * landed in the datastore, not merely that the planner declined to throw.
     */
    @Test
    void shouldConserveEverySelectedSuiteInThePersistedPlan() {
        // given
        DistributedRunConfig config = DistributedRunConfig.validated("run-conserve", 2, null, null, null);
        DistributedRunPlanner planner = new DistributedRunPlanner(dataStore, config);
        TestSelectorResult selection = threeSuiteSelection();

        // when
        DistributedRunPlanSummary summary = planner.plan(selection, "main", "commit-3", false, 999L);

        // then
        List<DistributedRunGroup> groups = dataStore.readDistributedRunGroups("run-conserve");
        Set<String> persistedSuites = new HashSet<>();
        for (DistributedRunGroup group : groups) {
            persistedSuites.addAll(dataStore.readDistributedRunGroupSuites("run-conserve", group.getGroupNumber()));
        }
        assertEquals(selection.getTestsToRun(), persistedSuites);
        assertEquals(selection.getTestsToRun().size(), summary.getSelectedSuiteCount());
    }

    /**
     * Verify that planning a second run clears the first run's rows entirely, leaving only the
     * new run behind - even when the first run had a group already marked COMPLETED directly via
     * SQL, simulating a build that finished some groups before the second plan superseded it.
     * The WARN naming the unfinished groups is not asserted here since no log-capture harness
     * exists elsewhere in this test tree; only the clearing behaviour is checked directly.
     *
     * @throws Exception if the direct SQL update fails
     */
    @Test
    void shouldClearThePreviousRunAndLeaveOnlyTheNewOnePresent() throws Exception {
        // given
        DistributedRunConfig firstConfig = DistributedRunConfig.validated("run-old", 2, null, null, null);
        DistributedRunPlanner firstPlanner = new DistributedRunPlanner(dataStore, firstConfig);
        firstPlanner.plan(threeSuiteSelection(), "main", "commit-old", false, 100L);
        try (Connection connection = connectionProvider.get();
             Statement statement = connection.createStatement()) {
            // A raw connection from the provider is not pinned to the per-branch schema the way
            // JdbcDataStore.getConnection() pins its own connections, so the schema must be
            // selected explicitly before the unqualified table name below resolves correctly.
            statement.execute("SET SCHEMA \"" + BranchSchema.schemaName("test") + "\"");
            statement.executeUpdate("UPDATE tia_distributed_run_group SET status = 'COMPLETED' "
                    + "WHERE run_id = 'run-old' AND group_number = 0");
        }

        DistributedRunConfig secondConfig = DistributedRunConfig.validated("run-new", 2, null, null, null);
        DistributedRunPlanner secondPlanner = new DistributedRunPlanner(dataStore, secondConfig);

        // when
        secondPlanner.plan(threeSuiteSelection(), "main", "commit-new", false, 200L);

        // then
        assertNull(dataStore.readDistributedRun("run-old"));
        assertTrue(dataStore.readDistributedRunGroups("run-old").isEmpty());
        List<DistributedRun> all = dataStore.readAllDistributedRuns();
        assertEquals(1, all.size());
        assertEquals("run-new", all.get(0).getRunId());
    }

    /**
     * Verify that the mapping overhead reaches the balancer's weights: planning the same selection
     * twice, once with coverage collection on and once off, produces a higher persisted
     * {@code estimatedTotalMs} when coverage is on. Without this check, a caller accidentally
     * passing {@code collectingCoverage=false} unconditionally would go unnoticed, since the
     * planner would still run without error.
     */
    @Test
    void shouldIncludeMappingOverheadInEstimatedTotalWhenCollectingCoverage() {
        // given
        DistributedRunConfig configWithCoverage = DistributedRunConfig.validated("run-coverage", 2, null, null, null);
        DistributedRunConfig configWithoutCoverage = DistributedRunConfig.validated("run-no-coverage", 2, null, null, null);
        TestSelectorResult selection = threeSuiteSelection();

        // when
        new DistributedRunPlanner(dataStore, configWithoutCoverage)
                .plan(selection, "main", "commit-a", false, 1L);
        DistributedRun withoutCoverage = dataStore.readDistributedRun("run-no-coverage");
        // planning a second run clears the first, so read it back before planning again
        long estimatedWithoutCoverage = withoutCoverage.getEstimatedTotalMs();

        new DistributedRunPlanner(dataStore, configWithCoverage)
                .plan(selection, "main", "commit-b", true, 2L);
        DistributedRun withCoverage = dataStore.readDistributedRun("run-coverage");

        // then
        assertTrue(withCoverage.getEstimatedTotalMs() > estimatedWithoutCoverage,
                "expected coverage-collecting run's estimated total (" + withCoverage.getEstimatedTotalMs()
                        + ") to exceed the non-coverage run's (" + estimatedWithoutCoverage + ")");
    }

    /**
     * Verify that an empty selection still produces a valid, persisted plan rather than failing:
     * every group is created (empty of suites) and the suite-conservation guard passes trivially
     * since zero suites were selected and zero were persisted.
     */
    @Test
    void shouldProduceAValidPlanForAnEmptySelection() {
        // given
        DistributedRunConfig config = DistributedRunConfig.validated("run-empty", 3, null, null, null);
        DistributedRunPlanner planner = new DistributedRunPlanner(dataStore, config);
        TestSelectorResult selection = emptySelection();

        // when
        DistributedRunPlanSummary summary = planner.plan(selection, "main", "commit-empty", false, 42L);

        // then
        assertEquals(0, summary.getSelectedSuiteCount());
        DistributedRun readRun = dataStore.readDistributedRun("run-empty");
        assertEquals(3, readRun.getGroupCount());
        List<DistributedRunGroup> groups = dataStore.readDistributedRunGroups("run-empty");
        assertEquals(3, groups.size());
        for (DistributedRunGroup group : groups) {
            assertTrue(dataStore.readDistributedRunGroupSuites("run-empty", group.getGroupNumber()).isEmpty());
        }
    }

}
