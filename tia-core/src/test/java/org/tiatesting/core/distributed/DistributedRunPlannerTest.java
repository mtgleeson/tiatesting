package org.tiatesting.core.distributed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.DistributedRunStatus;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                60000L, Collections.<String>emptySet(), 0L, runTimes, 0L, 6000L, false);
    }

    /**
     * Build the same three-suite selection as {@link #threeSuiteSelection()} but carrying a
     * library-impact drain result, so tests can assert what the planner does with the drain the
     * selection already performed.
     *
     * @param drainResult the drain outcome the selection performed before the planner was called
     * @return a selection with three suites and the supplied drain result attached
     */
    private static TestSelectorResult selectionWithDrainResult(LibraryImpactDrainResult drainResult) {
        Map<String, Long> runTimes = new HashMap<>();
        runTimes.put("com.example.ATest", 30000L);
        runTimes.put("com.example.BTest", 20000L);
        runTimes.put("com.example.CTest", 10000L);
        Set<String> testsToRun = new HashSet<>(runTimes.keySet());
        return new TestSelectorResult(testsToRun, Collections.<String>emptySet(), drainResult,
                60000L, Collections.<String>emptySet(), 0L, runTimes, 0L, 6000L, false);
    }

    /**
     * Build a selection with no suites at all, so tests can verify the planner produces a valid
     * (if trivial) plan rather than failing on an empty build.
     *
     * @return a selection with an empty {@code testsToRun}
     */
    private static TestSelectorResult emptySelection() {
        return new TestSelectorResult(Collections.<String>emptySet(), Collections.<String>emptySet(),
                null, 0L, Collections.<String>emptySet(), 0L, new HashMap<String, Long>(), 0L, 0L, false);
    }

    /**
     * Build a selection that signals "no stored mapping yet" - both {@code testsToRun} and
     * {@code testsToIgnore} empty, {@code runAllTests} true - the shape
     * {@link org.tiatesting.core.diff.diffanalyze.selector.TestSelector#selectTestsToIgnore}
     * returns on a fresh branch with nothing tracked yet.
     *
     * @return a selection with {@code runAllTests} true
     */
    private static TestSelectorResult runAllTestsSelection() {
        return new TestSelectorResult(Collections.<String>emptySet(), Collections.<String>emptySet(),
                null, 0L, Collections.<String>emptySet(), 0L, new HashMap<String, Long>(), 0L, 0L, true);
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
     * Verify that the library-impact drain the selection already performed is persisted on the run
     * row. The selection handed to {@link DistributedRunPlanner#plan} has already deleted the
     * pending rows and advanced the sequences by the time the planner sees it, and that drain
     * cannot be repeated, so the plan row is the only place the cleanup can survive the plan
     * process exiting.
     */
    @Test
    void shouldPersistTheDrainResultTheSelectionAlreadyPerformed() {
        // given
        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDrainedBatch("com.example:lib", 4L);
        drainResult.setAppliedSeq("com.example:lib", 4L);
        DistributedRunConfig config = DistributedRunConfig.validated("run-drain", 2, null, null, null);
        DistributedRunPlanner planner = new DistributedRunPlanner(dataStore, config);

        // when
        planner.plan(selectionWithDrainResult(drainResult), "main", "commit-1", false, 111222L);

        // then
        assertEquals(drainResult, dataStore.readDistributedRunDrainResult("run-drain"));
    }

    /**
     * Verify that a selection carrying no drain result - the normal case, since library impact
     * analysis is optional - leaves the run row's drain result null rather than an empty instance,
     * so the null check remains a usable "nothing was drained" signal for the stage that applies
     * the cleanup.
     */
    @Test
    void shouldPersistANullDrainResultWhenTheSelectionDrainedNothing() {
        // given
        DistributedRunConfig config = DistributedRunConfig.validated("run-nodrain", 2, null, null, null);
        DistributedRunPlanner planner = new DistributedRunPlanner(dataStore, config);

        // when
        planner.plan(threeSuiteSelection(), "main", "commit-1", false, 111222L);

        // then
        assertNull(dataStore.readDistributedRunDrainResult("run-nodrain"));
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

    /**
     * Verifies task 1's core fix: planning a selection with {@code runAllTests == true} - the
     * shape {@code TestSelector.selectTestsToIgnore} returns on a fresh branch with no stored
     * mapping - persists exactly one group with an empty suite list, ignoring the configured
     * group count entirely, rather than throwing or silently persisting N empty groups. Asserts
     * the persisted plan directly, not only the summary, since a runner claims its group from the
     * database, not from the summary or its JSON.
     */
    @Test
    void plan_seedSelection_plansExactlyOneEmptyGroupIgnoringConfiguredGroupCount() {
        // given a config asking for 8 groups, but a selection with no stored mapping yet
        DistributedRunConfig config = DistributedRunConfig.validated("run-seed", 8, null, null, null);
        DistributedRunPlanner planner = new DistributedRunPlanner(dataStore, config);
        TestSelectorResult selection = runAllTestsSelection();

        // when
        DistributedRunPlanSummary summary = planner.plan(selection, "main", "commit-seed", true, 1L);

        // then - the summary reports exactly one group and a seed run
        assertEquals(1, summary.getGroupCount());
        assertTrue(summary.isSeedRun());
        assertEquals(0, summary.getSelectedSuiteCount());

        // and - the persisted plan itself carries exactly one group with an empty suite list,
        // which is what a runner actually claims from
        DistributedRun readRun = dataStore.readDistributedRun("run-seed");
        assertEquals(1, readRun.getGroupCount());
        List<DistributedRunGroup> groups = dataStore.readDistributedRunGroups("run-seed");
        assertEquals(1, groups.size());
        assertEquals(DistributedRunGroupStatus.PENDING, groups.get(0).getStatus());
        assertTrue(dataStore.readDistributedRunGroupSuites("run-seed", 0).isEmpty());
    }

    /**
     * Verifies that a seed run ignores the configured target run time too, not only a fixed group
     * count: planning against a dynamic-groups config with a target small enough that it would
     * normally force several groups still plans exactly one, both in the summary and in the
     * persisted plan.
     */
    @Test
    void plan_seedSelection_plansExactlyOneGroupIgnoringConfiguredTargetRunTime() {
        // given a dynamic-groups config with a target so small it would normally force many groups
        DistributedRunConfig config = DistributedRunConfig.validated("run-seed-target", null, 1000L, null, null);
        DistributedRunPlanner planner = new DistributedRunPlanner(dataStore, config);
        TestSelectorResult selection = runAllTestsSelection();

        // when
        DistributedRunPlanSummary summary = planner.plan(selection, "main", "commit-seed-target", true, 1L);

        // then
        assertEquals(1, summary.getGroupCount());
        assertTrue(summary.isSeedRun());
        DistributedRun readRun = dataStore.readDistributedRun("run-seed-target");
        assertEquals(1, readRun.getGroupCount());
        List<DistributedRunGroup> groups = dataStore.readDistributedRunGroups("run-seed-target");
        assertEquals(1, groups.size());
    }

    /**
     * Verifies that {@link DistributedRunPlanSummary#isSeedRun()} is false for an ordinary,
     * non-seed plan, so a pipeline reading the summary or {@code tia-run-plan.json} can rely on
     * the flag to distinguish the two cases.
     */
    @Test
    void plan_nonSeedSelection_reportsSeedRunFalse() {
        // given an ordinary selection with a stored mapping
        DistributedRunConfig config = DistributedRunConfig.validated("run-not-seed", 2, null, null, null);
        DistributedRunPlanner planner = new DistributedRunPlanner(dataStore, config);
        TestSelectorResult selection = threeSuiteSelection();

        // when
        DistributedRunPlanSummary summary = planner.plan(selection, "main", "commit-not-seed", false, 1L);

        // then
        assertFalse(summary.isSeedRun());
    }

    /**
     * Verifies that {@code seedRun} reaches {@code tia-run-plan.json}: a seed run's summary
     * renders the field as JSON {@code true}, the signal a pipeline needs to explain why it only
     * received one job despite the configured group count.
     */
    @Test
    void plan_seedSelection_jsonReportsSeedRunTrue() {
        // given
        DistributedRunConfig config = DistributedRunConfig.validated("run-seed-json", 5, null, null, null);
        DistributedRunPlanner planner = new DistributedRunPlanner(dataStore, config);
        TestSelectorResult selection = runAllTestsSelection();

        // when
        DistributedRunPlanSummary summary = planner.plan(selection, "main", "commit-seed-json", true, 1L);

        // then
        assertTrue(summary.toJson().contains("\"seedRun\": true,"));
    }

    /**
     * Verifies that a seed run still succeeds - does not throw, and still persists a valid
     * one-group plan - when {@code collectingCoverage} is false, the condition under which {@link
     * DistributedRunPlanner} logs a WARN naming {@code tiaUpdateDBMapping} because that run will
     * not actually write the mapping the seed run exists to seed. The WARN text itself is not
     * asserted here since no log-capture harness exists in this test tree, matching this class's
     * existing convention of checking only the resulting behaviour for its other WARN condition
     * ({@link #shouldClearThePreviousRunAndLeaveOnlyTheNewOnePresent}); only the behaviour that
     * the seed plan still persists correctly is checked.
     */
    @Test
    void plan_seedSelectionWithoutCollectingCoverage_stillPersistsAValidSeedPlan() {
        // given a seed selection and collectingCoverage=false, the condition that triggers the
        // WARN naming tiaUpdateDBMapping
        DistributedRunConfig config = DistributedRunConfig.validated("run-seed-no-coverage", 3, null, null, null);
        DistributedRunPlanner planner = new DistributedRunPlanner(dataStore, config);
        TestSelectorResult selection = runAllTestsSelection();

        // when
        DistributedRunPlanSummary summary = planner.plan(selection, "main",
                "commit-seed-no-coverage", false, 1L);

        // then - the plan still succeeds and is still a one-group seed run
        assertTrue(summary.isSeedRun());
        assertEquals(1, summary.getGroupCount());
        DistributedRun readRun = dataStore.readDistributedRun("run-seed-no-coverage");
        assertEquals(1, readRun.getGroupCount());
    }

    /**
     * Verifies that {@link DistributedRunPlanner#balance} - the {@code select-tests} preview's
     * entry point - collapses a seed selection to the same single empty group {@link
     * DistributedRunPlanner#plan} would persist, so the preview and the real plan can never
     * disagree about what a seed run looks like.
     */
    @Test
    void balance_seedSelection_producesOneEmptyGroup() {
        // given a selection with no stored mapping yet, and a group count that would otherwise
        // apply
        TestSelectorResult selection = runAllTestsSelection();

        // when
        GroupingResult result = DistributedRunPlanner.balance(selection, true, 8, null, null);

        // then
        assertEquals(1, result.getGroupCount());
        assertTrue(result.getGroups().get(0).getSuiteNames().isEmpty());
        assertTrue(result.isTargetMet());
        assertEquals(0L, result.getTotalEstimatedMs());
    }

    /**
     * Verifies that {@link DistributedRunPlanner#balance} weights and balances a selection exactly
     * as {@link DistributedRunPlanner#plan} would, without needing a distributed run id - the
     * select-tests grouping preview's entry point - and, critically, persists nothing: the
     * datastore has no runs before or after the call.
     */
    @Test
    void balance_producesGroupingWithoutPersistingAnything() {
        // given
        TestSelectorResult selection = threeSuiteSelection();

        // when
        GroupingResult result = DistributedRunPlanner.balance(selection, false, 2, null, null);

        // then - a valid grouping was produced ...
        assertEquals(2, result.getGroupCount());
        assertEquals(selection.getSelectedTestRunTimesMs().values().stream().mapToLong(Long::longValue).sum(),
                result.getTotalEstimatedMs());
        // ... and nothing was persisted
        assertTrue(dataStore.readAllDistributedRuns().isEmpty());
    }

    /**
     * Verifies that {@link DistributedRunPlanner#balance} rejects a call that supplies neither or
     * both of {@code groupCount} and {@code targetRunTimeMs}, the same mutually-exclusive-mode
     * rule {@link DistributedRunConfig#validated} enforces, since this method validates that shape
     * itself rather than requiring a validated config.
     */
    @Test
    void balance_neitherGroupCountNorTargetRunTime_throws() {
        // given
        TestSelectorResult selection = threeSuiteSelection();

        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> DistributedRunPlanner.balance(selection, false, null, null, null));
    }

    /**
     * Verifies finding 1's fix directly: {@link DistributedRunPlanner#balance} now rejects
     * {@code maxGroups} combined with a fixed {@code groupCount} - a shape {@link
     * org.tiatesting.core.distributed.TestGroupBalancer} alone never checked - because it shares
     * {@link DistributedRunConfig#validateGroupingShape} with {@link DistributedRunConfig#validated}.
     * Before the fix, a {@code select-tests} preview with this exact misconfiguration would render
     * happily while the real {@code tia-dist-plan} goal failed on the same input.
     */
    @Test
    void balance_maxGroupsWithFixedGroupCount_throwsNamingBothProperties() {
        // given
        TestSelectorResult selection = threeSuiteSelection();

        // when
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DistributedRunPlanner.balance(selection, false, 4, null, 8));

        // then
        assertTrue(ex.getMessage().contains("tiaDistributedMaxGroups"),
                "message should name tiaDistributedMaxGroups, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("tiaDistributedGroupCount"),
                "message should name tiaDistributedGroupCount, was: " + ex.getMessage());
    }

    /**
     * Verifies that a {@code groupCount} below 1 is rejected by {@link DistributedRunPlanner#balance}
     * with a message naming the user-facing property {@code tiaDistributedGroupCount}, not the
     * generic internal message {@link org.tiatesting.core.distributed.TestGroupBalancer} would have
     * produced, since the shared shape check now runs before the balancer is ever reached.
     */
    @Test
    void balance_groupCountBelowOne_throwsNamingTiaDistributedGroupCount() {
        // given
        TestSelectorResult selection = threeSuiteSelection();

        // when
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DistributedRunPlanner.balance(selection, false, 0, null, null));

        // then
        assertTrue(ex.getMessage().contains("tiaDistributedGroupCount"),
                "message should name tiaDistributedGroupCount, was: " + ex.getMessage());
    }

    /**
     * Verifies the heart of finding 1: {@link DistributedRunPlanner#balance} (the preview's entry
     * point) and {@link DistributedRunConfig#validated} (the real plan's entry point) reject the
     * exact same invalid shape - both groupCount and targetRunTimeMs set - with the exact same
     * message, so a preview can never say a configuration is fine when the real plan would then
     * reject it.
     */
    @Test
    void balance_and_validated_agreeOnBothSetErrorMessage() {
        // given - both groupCount and targetRunTimeMs set, the exact preview-vs-plan mismatch
        // finding 1 describes
        TestSelectorResult selection = threeSuiteSelection();

        // when
        IllegalArgumentException fromBalance = assertThrows(IllegalArgumentException.class,
                () -> DistributedRunPlanner.balance(selection, false, 4, 60000L, null));
        IllegalArgumentException fromValidated = assertThrows(IllegalArgumentException.class,
                () -> DistributedRunConfig.validated("run-1", 4, 60000L, null, null));

        // then - both entry points produce the exact same message
        assertEquals(fromValidated.getMessage(), fromBalance.getMessage());
    }

    /**
     * Verifies the ordering {@link DistributedRunPlanner#plan} depends on: {@link
     * DataStore#readAllDistributedRuns()} must be called before {@link
     * DataStore#persistDistributedRunPlan} in the same {@code plan()} call, because the persist
     * clears the previous run's rows in the same transaction - once it runs, the evidence the read
     * exists to find is gone. Uses a dynamic-proxy {@link DataStore} wrapper that records the order
     * the two methods are invoked in, rather than a log-capture harness, since the ordering (not
     * the log text) is what {@code warnAboutIncompletePreviousRuns} exists to get right.
     *
     * <p>Bite-checked by temporarily moving the {@code warnAboutIncompletePreviousRuns()} call in
     * {@link DistributedRunPlanner#plan} to below {@code dataStore.persistDistributedRunPlan(...)}
     * and re-running this test: it failed, as expected, before the change was reverted.
     */
    @Test
    void plan_readsAllDistributedRunsBeforePersisting() {
        // given
        DistributedRunConfig config = DistributedRunConfig.validated("run-order", 2, null, null, null);
        List<String> callOrder = new ArrayList<>();
        DataStore recordingDataStore = recordingDataStore(dataStore, callOrder);
        DistributedRunPlanner planner = new DistributedRunPlanner(recordingDataStore, config);

        // when
        planner.plan(threeSuiteSelection(), "main", "commit-order", false, 1L);

        // then
        assertEquals(2, callOrder.size(), "expected exactly one read and one persist call: " + callOrder);
        assertEquals("readAllDistributedRuns", callOrder.get(0));
        assertEquals("persistDistributedRunPlan", callOrder.get(1));
    }

    /**
     * Wrap a {@link DataStore} in a JDK dynamic proxy that forwards every call to {@code delegate}
     * unchanged, but first appends the method name to {@code callOrder} for the two methods {@link
     * DistributedRunPlanner#plan}'s ordering guarantee is about - {@code readAllDistributedRuns}
     * and {@code persistDistributedRunPlan}.
     *
     * @param delegate the real data store to forward every call to
     * @param callOrder the list to append recorded method names to, in call order
     * @return a {@link DataStore} proxy that behaves exactly like {@code delegate} but records
     *         call order for the two methods named above
     */
    private static DataStore recordingDataStore(DataStore delegate, List<String> callOrder) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("readAllDistributedRuns") || name.equals("persistDistributedRunPlan")) {
                callOrder.add(name);
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (DataStore) Proxy.newProxyInstance(DataStore.class.getClassLoader(),
                new Class<?>[] { DataStore.class }, handler);
    }

    /**
     * Verifies that a previous run which reached {@code SEALED} needs no warning at all - {@link
     * DistributedRunPlanner#incompleteGroupsToWarnAbout} returns {@code null} - regardless of what
     * its groups' statuses are, since a sealed run completed cleanly and left no abandoned work
     * behind.
     */
    @Test
    void incompleteGroupsToWarnAbout_sealedRun_returnsNull() {
        // given - a SEALED run whose groups happen to still be PENDING (irrelevant once sealed)
        DistributedRun sealedRun = new DistributedRun("run-sealed", "main", "commit-1",
                DistributedRunStatus.SEALED, 1, null, 1000L, 1L, "runner-1", 2L, false);
        List<DistributedRunGroup> groups = Collections.singletonList(
                DistributedRunGroup.pending("run-sealed", 0, 1000L));

        // when
        List<String> result = DistributedRunPlanner.incompleteGroupsToWarnAbout(sealedRun, groups);

        // then
        assertNull(result, "a sealed run should need no warning at all");
    }

    /**
     * Verifies the finding this method exists to fix: a previous run whose every group reached
     * {@code COMPLETED} but whose own status is still {@code OPEN} - the sealer died after the
     * last group finished - must still be warned about, with an empty (non-null) incomplete-group
     * list, distinct from both the sealed case (no warning) and the incomplete-groups case (a
     * populated list).
     */
    @Test
    void incompleteGroupsToWarnAbout_allGroupsCompletedButRunNotSealed_returnsEmptyNonNullList() {
        // given - every group COMPLETED, but the run itself never reached SEALED
        DistributedRun unsealedRun = new DistributedRun("run-unsealed", "main", "commit-1",
                DistributedRunStatus.OPEN, 2, null, 2000L, 1L, null, null, false);
        List<DistributedRunGroup> groups = new ArrayList<>();
        groups.add(new DistributedRunGroup("run-unsealed", 0, DistributedRunGroupStatus.COMPLETED,
                "runner-1", 1L, 2L, 1000L, 900L, 5, 0, 5, 0L));
        groups.add(new DistributedRunGroup("run-unsealed", 1, DistributedRunGroupStatus.COMPLETED,
                "runner-2", 1L, 2L, 1000L, 950L, 4, 0, 4, 0L));

        // when
        List<String> result = DistributedRunPlanner.incompleteGroupsToWarnAbout(unsealedRun, groups);

        // then - warned about (non-null), but with nothing incomplete to name
        assertTrue(result != null && result.isEmpty(),
                "an unsealed run with every group completed should return an empty, non-null list");
    }

    /**
     * Verifies the pre-existing case is unaffected by the finding 4 fix: a previous run with at
     * least one group that never reached {@code COMPLETED} still returns a populated list naming
     * it, regardless of the run's own status.
     */
    @Test
    void incompleteGroupsToWarnAbout_openRunWithIncompleteGroup_returnsPopulatedList() {
        // given - one COMPLETED group, one still PENDING
        DistributedRun openRun = new DistributedRun("run-incomplete", "main", "commit-1",
                DistributedRunStatus.OPEN, 2, null, 2000L, 1L, null, null, false);
        List<DistributedRunGroup> groups = new ArrayList<>();
        groups.add(new DistributedRunGroup("run-incomplete", 0, DistributedRunGroupStatus.COMPLETED,
                "runner-1", 1L, 2L, 1000L, 900L, 5, 0, 5, 0L));
        groups.add(DistributedRunGroup.pending("run-incomplete", 1, 1000L));

        // when
        List<String> result = DistributedRunPlanner.incompleteGroupsToWarnAbout(openRun, groups);

        // then
        assertEquals(1, result.size());
        assertTrue(result.get(0).contains("group 1"), "should name the incomplete group: " + result);
        assertTrue(result.get(0).contains("PENDING"), "should name its status: " + result);
    }

}
