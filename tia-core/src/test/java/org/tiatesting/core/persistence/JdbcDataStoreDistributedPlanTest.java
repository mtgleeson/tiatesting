package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the distributed-run schema and the plan read/write operations against embedded H2.
 * Stage 1 adds the tables and the plan store only - claiming, the barrier and sealing arrive in
 * later stages and are not exercised here.
 */
class JdbcDataStoreDistributedPlanTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database in a new temp directory and bootstrap its schema, so
     * each test starts from an isolated store with no distributed runs planned.
     *
     * @throws Exception if the temp directory cannot be created or schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-distributed-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test"));
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
     * Count the rows in a table, so a test can assert what schema bootstrap created without
     * depending on the plan operations that arrive in the next task.
     *
     * @param table the table to count
     * @return the number of rows
     * @throws Exception if the query fails
     */
    private long countRows(String table) throws Exception {
        try (Connection connection = dataStore.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    @Test
    void shouldCreateAllFourDistributedTablesOnSchemaBootstrap() throws Exception {
        // given
        // setUp bootstrapped the schema

        // when
        long runs = countRows("tia_distributed_run");
        long groups = countRows("tia_distributed_run_group");
        long groupSuites = countRows("tia_distributed_run_group_suite");
        long methodStage = countRows("tia_distributed_run_method_stage");

        // then
        assertEquals(0L, runs);
        assertEquals(0L, groups);
        assertEquals(0L, groupSuites);
        assertEquals(0L, methodStage);
    }

    /**
     * Build a two-group plan with three suites for use across the read/write tests.
     *
     * @param runId the run identifier to plan under
     * @return a valid plan, unsaved
     */
    private static DistributedRunPlan samplePlan(String runId) {
        DistributedRun run = DistributedRun.open(runId, "main", "commit-1", 2, 60000L, 90000L, 1234L);
        List<DistributedRunGroup> groups = Arrays.asList(
                DistributedRunGroup.pending(runId, 0, 50000L),
                DistributedRunGroup.pending(runId, 1, 40000L));
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Arrays.asList("com.example.BTest", "com.example.ATest"));
        suites.put(1, Arrays.asList("com.example.CTest"));
        return new DistributedRunPlan(run, groups, suites);
    }

    /**
     * Verify that persisting a plan and reading the run back by id reproduces the exact run
     * object that was planned, proving the write and read agree on every column.
     */
    @Test
    void shouldRoundTripAPlannedRun() {
        // given
        DistributedRunPlan plan = samplePlan("run-1");

        // when
        dataStore.persistDistributedRunPlan(plan);
        DistributedRun read = dataStore.readDistributedRun("run-1");

        // then
        assertEquals(plan.getRun(), read);
    }

    /**
     * Verify that the groups of a persisted plan are read back ordered by group number - the
     * order the claim protocol (a later stage) depends on - and that a freshly-planned group's
     * PENDING fields (status, no runner, no claim timestamp) survive the round trip.
     */
    @Test
    void shouldRoundTripGroupsInGroupNumberOrder() {
        // given
        DistributedRunPlan plan = samplePlan("run-1");
        dataStore.persistDistributedRunPlan(plan);

        // when
        List<DistributedRunGroup> groups = dataStore.readDistributedRunGroups("run-1");

        // then
        assertEquals(2, groups.size());
        assertEquals(0, groups.get(0).getGroupNumber());
        assertEquals(1, groups.get(1).getGroupNumber());
        assertEquals(DistributedRunGroupStatus.PENDING, groups.get(0).getStatus());
        assertEquals(50000L, groups.get(0).getEstimatedMs());
        assertNull(groups.get(0).getRunnerKey());
        assertNull(groups.get(0).getClaimedAtMs());
        assertNull(groups.get(0).getCompletedAtMs());
        assertNull(groups.get(0).getActualDurationMs());
    }

    /**
     * Verify that each group's suite assignment is read back in suite-name order (not insertion
     * order), so a runner claiming a group gets a deterministic suite list regardless of how the
     * planner built the map.
     */
    @Test
    void shouldRoundTripSuiteAssignment() {
        // given
        dataStore.persistDistributedRunPlan(samplePlan("run-1"));

        // when
        List<String> groupZero = dataStore.readDistributedRunGroupSuites("run-1", 0);
        List<String> groupOne = dataStore.readDistributedRunGroupSuites("run-1", 1);

        // then
        assertEquals(Arrays.asList("com.example.ATest", "com.example.BTest"), groupZero);
        assertEquals(Arrays.asList("com.example.CTest"), groupOne);
    }

    /**
     * Verify that looking up a run id nobody has planned returns null rather than throwing or
     * returning a default instance, so callers can use the null check as the "not planned" signal.
     */
    @Test
    void shouldReturnNullForAnUnknownRun() {
        // given
        dataStore.persistDistributedRunPlan(samplePlan("run-1"));

        // when
        DistributedRun read = dataStore.readDistributedRun("run-does-not-exist");

        // then
        assertNull(read);
    }

    /**
     * Verify that a run planned in static-groups mode (no configured target run time) round-trips
     * a null {@code targetRunTimeMs} rather than a coerced zero, since a JDBC nullable-long read
     * that only checked {@code getLong} would silently turn "not configured" into "configured as
     * zero".
     */
    @Test
    void shouldPreserveANullTargetRunTimeForStaticGroupsMode() {
        // given
        DistributedRun run = DistributedRun.open("run-static", "main", "commit-1", 1, null, 10L, 7L);
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Arrays.asList("com.example.ATest"));
        DistributedRunPlan plan = new DistributedRunPlan(run,
                Arrays.asList(DistributedRunGroup.pending("run-static", 0, 10L)), suites);

        // when
        dataStore.persistDistributedRunPlan(plan);
        DistributedRun read = dataStore.readDistributedRun("run-static");

        // then
        assertNull(read.getTargetRunTimeMs());
    }

    /**
     * Verify that writing a second plan clears every trace of the first: the old run id no longer
     * resolves, its groups and suites are gone, and only the new run's rows remain. This is the
     * core retention behaviour - the plan tables hold exactly one run because Tia isolates each
     * branch in its own schema, so nothing needs to be filtered on write, only cleared.
     *
     * @throws Exception if a row-count query fails
     */
    @Test
    void shouldClearThePreviousRunWhenANewPlanIsWritten() throws Exception {
        // given
        dataStore.persistDistributedRunPlan(samplePlan("run-1"));

        // when
        dataStore.persistDistributedRunPlan(samplePlan("run-2"));

        // then
        assertNull(dataStore.readDistributedRun("run-1"));
        assertTrue(dataStore.readDistributedRunGroups("run-1").isEmpty());
        assertTrue(dataStore.readDistributedRunGroupSuites("run-1", 0).isEmpty());
        assertEquals(1L, countRows("tia_distributed_run"));
        assertEquals(2L, countRows("tia_distributed_run_group"));
        assertEquals(3L, countRows("tia_distributed_run_group_suite"));
        assertEquals("run-2", dataStore.readDistributedRun("run-2").getRunId());
    }

    /**
     * Verify that leftover rows in {@code tia_distributed_run_method_stage} - simulating a
     * previous build that staged methods and then crashed before sealing - are swept away by the
     * next plan write. This table is roughly the size of {@code tia_source_method}, so leaked
     * staging rows from abandoned runs would be real, unbounded growth if the clear missed it.
     *
     * @throws Exception if the direct insert or a row-count query fails
     */
    @Test
    void shouldClearLeftoverMethodStagingRowsFromAnAbandonedRun() throws Exception {
        // given
        // simulate a previous build that staged methods and then died before sealing
        dataStore.persistDistributedRunPlan(samplePlan("run-1"));
        try (Connection connection = dataStore.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO tia_distributed_run_method_stage "
                    + "(run_id, id, method_name, line_number_start, line_number_end) "
                    + "VALUES ('run-1', 42, 'com.example.A.foo()V', 10, 20)");
        }
        assertEquals(1L, countRows("tia_distributed_run_method_stage"));

        // when
        dataStore.persistDistributedRunPlan(samplePlan("run-2"));

        // then
        assertEquals(0L, countRows("tia_distributed_run_method_stage"));
    }

    /**
     * Verify that {@link DataStore#readAllDistributedRuns()} surfaces a planned run before it gets
     * cleared, since this is what lets the planner (a later stage) log a warning naming groups
     * that never completed rather than have an abandoned run vanish silently.
     */
    @Test
    void shouldReturnEveryPlannedRunSoThePlannerCanWarnBeforeClearing() {
        // given
        dataStore.persistDistributedRunPlan(samplePlan("run-1"));

        // when
        List<DistributedRun> all = dataStore.readAllDistributedRuns();

        // then
        assertEquals(1, all.size());
        assertEquals("run-1", all.get(0).getRunId());
    }

    /**
     * Verify that a fresh schema with no plan ever written returns an empty list rather than null
     * or throwing, so a caller can iterate the result unconditionally.
     */
    @Test
    void shouldReturnNoRunsOnAFreshDatabase() {
        // given
        // setUp bootstrapped an empty schema

        // when
        List<DistributedRun> all = dataStore.readAllDistributedRuns();

        // then
        assertTrue(all.isEmpty());
    }

    /**
     * Verify that when the new plan's insert fails partway through, the previous plan is left
     * fully intact rather than partially cleared. This is the test that catches the clear running
     * outside the write transaction: an oversized suite name (the suite-name column is
     * VARCHAR(500)) fails the insert after the clear has already run, so only a single transaction
     * wrapping both the clear and the inserts can guarantee the old plan survives.
     *
     * @throws Exception if reading back the surviving plan fails
     */
    @Test
    void shouldLeaveThePreviousPlanIntactWhenANewPlanWriteFails() throws Exception {
        // given
        dataStore.persistDistributedRunPlan(samplePlan("run-1"));
        StringBuilder oversizedName = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            oversizedName.append("com.example.VeryLongSuiteName");
        }
        DistributedRun run = DistributedRun.open("run-bad", "main", "commit-1", 1, null, 10L, 7L);
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Arrays.asList(oversizedName.toString()));
        DistributedRunPlan badPlan = new DistributedRunPlan(run,
                Arrays.asList(DistributedRunGroup.pending("run-bad", 0, 10L)), suites);

        // when
        assertThrows(TiaPersistenceException.class, () -> dataStore.persistDistributedRunPlan(badPlan));

        // then
        // the clear must have rolled back too, not just the failed inserts
        assertEquals("run-1", dataStore.readDistributedRun("run-1").getRunId());
        assertEquals(2, dataStore.readDistributedRunGroups("run-1").size());
        assertEquals(1L, countRows("tia_distributed_run"));
    }
}
