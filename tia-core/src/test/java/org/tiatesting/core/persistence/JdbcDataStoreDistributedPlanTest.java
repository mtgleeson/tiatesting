package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.library.LibraryImpactDrainResult;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * Verify that schema bootstrap creates all four distributed-run tables - not just some of
     * them - each empty. {@code setUp} triggers bootstrap via {@code getTiaData(true)}; this test
     * checks the outcome by counting rows in each table directly rather than through the plan
     * operations, so a missing table fails here with a clear table-not-found error instead of
     * surfacing later as a confusing failure in an unrelated read/write test.
     *
     * @throws Exception if any row-count query fails
     */
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
     * Verify that {@link DataStore#persistDistributedRunPlan(DistributedRunPlan)} and
     * {@link DataStore#readDistributedRun(String)} both bootstrap the schema themselves on a
     * datastore that has never had {@code getTiaData} called on it. Every other test in this class
     * bootstraps via {@code setUp}'s {@code getTiaData(true)} call, which would mask a datastore
     * that forgot to call {@code ensureSchema} on its own read/write paths - on a real build, the
     * first thing to touch a brand-new per-branch schema could be the distributed-run planner
     * rather than the ordinary mapping read, and it must not fail with a table-not-found error.
     *
     * @throws Exception if the temp directory for the fresh store cannot be created
     */
    @Test
    void shouldBootstrapItsOwnSchemaWithoutAPriorGetTiaDataCall() throws Exception {
        // given
        File freshTempDir = File.createTempFile("tia-distributed-fresh-", "");
        freshTempDir.delete();
        freshTempDir.mkdirs();
        JdbcDataStore freshDataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(freshTempDir.getAbsolutePath())),
                BranchSchema.schemaName("test"));
        try {
            DistributedRunPlan plan = samplePlan("run-1", null);

            // when
            freshDataStore.persistDistributedRunPlan(plan);
            DistributedRun read = freshDataStore.readDistributedRun("run-1");

            // then
            assertEquals("run-1", read.getRunId());
        } finally {
            freshDataStore.close();
        }
    }

    /**
     * Build a two-group plan with three suites for use across the read/write tests.
     *
     * @param runId the run identifier to plan under
     * @param drainResult the library-impact drain the plan performed, or null when the plan drained
     *                    nothing
     * @return a valid plan, unsaved
     */
    private static DistributedRunPlan samplePlan(String runId, LibraryImpactDrainResult drainResult) {
        DistributedRun run = DistributedRun.open(runId, "main", "commit-1", 2, 60000L, 90000L, 1234L, false);
        List<DistributedRunGroup> groups = Arrays.asList(
                DistributedRunGroup.pending(runId, 0, 50000L),
                DistributedRunGroup.pending(runId, 1, 40000L));
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Arrays.asList("com.example.BTest", "com.example.ATest"));
        suites.put(1, Arrays.asList("com.example.CTest"));
        return new DistributedRunPlan(run, groups, suites, drainResult);
    }

    /**
     * Verify that persisting a plan and reading the run back by id reproduces the exact run
     * object that was planned, proving the write and read agree on every column.
     */
    @Test
    void shouldRoundTripAPlannedRun() {
        // given
        DistributedRunPlan plan = samplePlan("run-1", null);

        // when
        dataStore.persistDistributedRunPlan(plan);
        DistributedRun read = dataStore.readDistributedRun("run-1");

        // then
        assertEquals(plan.getRun(), read);
    }

    /**
     * Build the drain result a plan that drained library impact would carry: two drained batches,
     * one drained forced-selection batch and one applied sequence, so the round-trip assertions
     * cover every collection the drain result holds rather than a single field.
     *
     * @return a populated drain result, unsaved
     */
    private static LibraryImpactDrainResult sampleDrainResult() {
        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDrainedBatch("com.example:lib", 1L);
        drainResult.addDrainedBatch("com.example:lib", 2L);
        drainResult.addDrainedForcedBatch("com.example:other", 7L);
        drainResult.setAppliedSeq("com.example:lib", 2L);
        return drainResult;
    }

    /**
     * Verify that the library-impact drain the plan already performed survives the round trip into
     * the run row. The plan's selection deletes pending rows and advances sequences before the plan
     * is written, and that drain cannot be repeated: if the run row does not carry the drain result,
     * no later runner or sealer can reconstruct which batches to delete, and the cleanup is lost for
     * good.
     */
    @Test
    void shouldRoundTripTheDrainResultThePlanPerformed() {
        // given
        DistributedRunPlan plan = samplePlan("run-drain", sampleDrainResult());

        // when
        dataStore.persistDistributedRunPlan(plan);
        DistributedRun read = dataStore.readDistributedRun("run-drain");
        LibraryImpactDrainResult readDrain = dataStore.readDistributedRunDrainResult("run-drain");

        // then
        assertEquals(plan.getRun(), read);
        assertEquals(2, readDrain.getDrainedBatchKeys().size());
        assertEquals("com.example:lib", readDrain.getDrainedBatchKeys().get(0).getGroupArtifact());
        assertEquals(1L, readDrain.getDrainedBatchKeys().get(0).getPublishSeq());
        assertEquals(2L, readDrain.getDrainedBatchKeys().get(1).getPublishSeq());
        assertEquals(1, readDrain.getDrainedForcedBatchKeys().size());
        assertEquals(7L, readDrain.getDrainedForcedBatchKeys().get(0).getPublishSeq());
        assertEquals(Long.valueOf(2L), readDrain.getAppliedSeqByLibrary().get("com.example:lib"));
    }

    /**
     * Verify that a plan carrying no drain result - the normal case, since library impact analysis
     * is optional - reads back a null drain result rather than an empty instance or a failure, so a
     * later stage can use the null check as the "nothing to clean up" signal.
     */
    @Test
    void shouldReadBackANullDrainResultWhenThePlanDrainedNothing() {
        // given
        DistributedRunPlan plan = samplePlan("run-nodrain", null);

        // when
        dataStore.persistDistributedRunPlan(plan);

        // then
        assertNull(dataStore.readDistributedRunDrainResult("run-nodrain"));
    }

    /**
     * The reason the drain result is read on its own rather than as part of the run row: a stored
     * blob that cannot be deserialized - one written by a planner running a different Tia version,
     * say - must not break the run-row read. Every runner in the build reads that row to claim a
     * group and none of them look at the drain result, so decoding it there would fail the entire
     * build over a value only the sealer needs. Plants an undecodable blob and asserts the split:
     * the run row still reads, and only the dedicated drain read fails.
     *
     * @throws Exception if the corrupting update cannot be applied
     */
    @Test
    void shouldStillReadTheRunRowWhenTheStoredDrainResultCannotBeDeserialized() throws Exception {
        // given
        dataStore.persistDistributedRunPlan(samplePlan("run-corrupt", sampleDrainResult()));
        try (Connection connection = dataStore.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE tia_distributed_run SET drain_result = X'00010203' "
                    + "WHERE run_id = 'run-corrupt'");
        }

        // when
        DistributedRun read = dataStore.readDistributedRun("run-corrupt");

        // then
        assertEquals("run-corrupt", read.getRunId());
        assertThrows(TiaPersistenceException.class,
                () -> dataStore.readDistributedRunDrainResult("run-corrupt"));
    }

    /**
     * Verify that asking for the drain result of a run that was never planned returns null rather
     * than throwing, so a sealer reading a cleared run cannot mistake "no such run" for a
     * persistence failure.
     */
    @Test
    void shouldReadBackANullDrainResultForAnUnknownRun() {
        // given
        dataStore.persistDistributedRunPlan(samplePlan("run-1", sampleDrainResult()));

        // when
        LibraryImpactDrainResult read = dataStore.readDistributedRunDrainResult("no-such-run");

        // then
        assertNull(read);
    }

    /**
     * Verify that the groups of a persisted plan are read back ordered by group number - the
     * order the claim protocol (a later stage) depends on - and that a freshly-planned group's
     * PENDING fields (status, no runner, no claim timestamp) survive the round trip.
     */
    @Test
    void shouldRoundTripGroupsInGroupNumberOrder() {
        // given
        DistributedRunPlan plan = samplePlan("run-1", null);
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
        dataStore.persistDistributedRunPlan(samplePlan("run-1", null));

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
        dataStore.persistDistributedRunPlan(samplePlan("run-1", null));

        // when
        DistributedRun read = dataStore.readDistributedRun("run-does-not-exist");

        // then
        assertNull(read);
    }

    /**
     * Build the plan a seed run produces: one group, no suites assigned to it, and the run row's
     * seed-run flag set.
     *
     * @param runId the run identifier to plan under
     * @return a valid seed-run plan, unsaved
     */
    private static DistributedRunPlan seedRunPlan(String runId) {
        DistributedRun run = DistributedRun.open(runId, "main", "commit-1", 1, null, 0L, 7L, true);
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Collections.<String>emptyList());
        return new DistributedRunPlan(run,
                Arrays.asList(DistributedRunGroup.pending(runId, 0, 0L)), suites, null);
    }

    /**
     * Verify that the {@code seed_run} column is backfilled onto a run table created before the
     * column existed. {@code CREATE TABLE IF NOT EXISTS} never alters an existing table, so without
     * the {@code ADD COLUMN IF NOT EXISTS} migration every plan write against a database an earlier
     * build wrote would fail with "column not found" - the whole distributed feature would stop
     * working on exactly the databases already using it.
     *
     * <p>The pre-migration shape is recreated literally rather than by disabling the migration,
     * and the plan is written through a second datastore instance because schema bootstrap is
     * memoized per instance: the store this test's {@code setUp} built has already ensured the
     * schema and would not re-run the DDL.
     *
     * @throws Exception if the pre-migration table cannot be recreated
     */
    @Test
    void shouldBackfillTheSeedRunColumnOntoARunTableThatPredatesIt() throws Exception {
        // given - the run table exactly as an earlier build of the branch created it
        try (Connection connection = dataStore.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE tia_distributed_run");
            statement.executeUpdate("CREATE TABLE tia_distributed_run ("
                    + "run_id VARCHAR(255) NOT NULL PRIMARY KEY, branch VARCHAR(255) NOT NULL, "
                    + "commit_value VARCHAR(255) NOT NULL, status VARCHAR(16) NOT NULL, "
                    + "group_count INT NOT NULL, target_run_time_ms BIGINT, "
                    + "estimated_total_ms BIGINT NOT NULL, created_at BIGINT NOT NULL, "
                    + "sealed_by VARCHAR(255), sealed_at BIGINT, drain_result BLOB)");
        }
        JdbcDataStore migratedStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test"));

        try {
            // when
            migratedStore.persistDistributedRunPlan(seedRunPlan("run-migrated"));

            // then
            assertTrue(migratedStore.readDistributedRun("run-migrated").isSeedRun(),
                    "the migration must add seed_run to a table that predates it, and the plan "
                            + "write must then store the flag as usual");
        } finally {
            migratedStore.close();
        }
    }

    /**
     * Verify that a freshly planned group starts with a zero {@code suites_duration_ms}, the value
     * the group table's DDL defaults it to. Nothing has reported progress yet, so there is no
     * suite-time split to record, and the sealer must read the zero as "no split was recorded" -
     * falling back to the plain sum of the group durations - not as "this group was pure overhead",
     * which would gut the build's serial duration.
     */
    @Test
    void shouldPlanAGroupWithAZeroSuitesDuration() {
        // given
        DistributedRunPlan plan = samplePlan("run-planned-group", null);

        // when
        dataStore.persistDistributedRunPlan(plan);

        // then
        assertEquals(0L,
                dataStore.readDistributedRunGroups("run-planned-group").get(0).getSuitesDurationMs(),
                "a planned group must start at 0 until a runner reports its suite time");
    }

    /**
     * Verify that the planner's seed-run flag survives the round trip into the run row. The seal
     * reads it back to tell a seed run - one group, no assigned suites, ran everything - from a
     * nothing-impacted build, whose plan carries empty suite lists too but ignored every tracked
     * suite. Nothing in the persisted shape separates the two, so if the flag were not stored the
     * seal would have to guess, and guessing "seed run" on a nothing-impacted build advances the
     * full-suite baseline and every tracked library's mapping baseline off a build that ran nothing.
     */
    @Test
    void shouldRoundTripTheSeedRunFlag() {
        // given - a seed run's plan: one group, no suites assigned to it at all
        DistributedRunPlan plan = seedRunPlan("run-seed");

        // when
        dataStore.persistDistributedRunPlan(plan);
        DistributedRun read = dataStore.readDistributedRun("run-seed");

        // then
        assertTrue(read.isSeedRun(), "a seed run's plan must read back as a seed run");
    }

    /**
     * Verify that an ordinary plan reads back as not a seed run, so the flag distinguishes the two
     * cases rather than being written as true unconditionally.
     */
    @Test
    void shouldRoundTripANonSeedRunAsNotASeedRun() {
        // given
        DistributedRunPlan plan = samplePlan("run-1", null);

        // when
        dataStore.persistDistributedRunPlan(plan);
        DistributedRun read = dataStore.readDistributedRun("run-1");

        // then
        assertFalse(read.isSeedRun(), "an ordinary plan must not read back as a seed run");
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
        DistributedRun run = DistributedRun.open("run-static", "main", "commit-1", 1, null, 10L, 7L, false);
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Arrays.asList("com.example.ATest"));
        DistributedRunPlan plan = new DistributedRunPlan(run,
                Arrays.asList(DistributedRunGroup.pending("run-static", 0, 10L)), suites, null);

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
        dataStore.persistDistributedRunPlan(samplePlan("run-1", null));

        // when
        dataStore.persistDistributedRunPlan(samplePlan("run-2", null));

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
        dataStore.persistDistributedRunPlan(samplePlan("run-1", null));
        try (Connection connection = dataStore.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO tia_distributed_run_method_stage "
                    + "(run_id, id, method_name, line_number_start, line_number_end) "
                    + "VALUES ('run-1', 42, 'com.example.A.foo()V', 10, 20)");
        }
        assertEquals(1L, countRows("tia_distributed_run_method_stage"));

        // when
        dataStore.persistDistributedRunPlan(samplePlan("run-2", null));

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
        dataStore.persistDistributedRunPlan(samplePlan("run-1", null));

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
        dataStore.persistDistributedRunPlan(samplePlan("run-1", null));
        StringBuilder oversizedName = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            oversizedName.append("com.example.VeryLongSuiteName");
        }
        DistributedRun run = DistributedRun.open("run-bad", "main", "commit-1", 1, null, 10L, 7L, false);
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Arrays.asList(oversizedName.toString()));
        DistributedRunPlan badPlan = new DistributedRunPlan(run,
                Arrays.asList(DistributedRunGroup.pending("run-bad", 0, 10L)), suites, null);

        // when
        assertThrows(TiaPersistenceException.class, () -> dataStore.persistDistributedRunPlan(badPlan));

        // then
        // the clear must have rolled back too, not just the failed inserts
        assertEquals("run-1", dataStore.readDistributedRun("run-1").getRunId());
        assertEquals(2, dataStore.readDistributedRunGroups("run-1").size());
        assertEquals(1L, countRows("tia_distributed_run"));
    }
}
