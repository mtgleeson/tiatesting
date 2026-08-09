package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the {@code tia_distributed_run_method_stage} staging operations against embedded H2:
 * writing one runner's observed method trackers, unioning several runners' writes for the same
 * run, and clearing a run's staged rows once the sealer has consumed them. Stage 1 created the
 * table; this class exercises the read/write/delete operations added in this task.
 */
class JdbcDataStoreMethodStagingTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database in a new temp directory and bootstrap its schema, so
     * each test starts from an isolated store with nothing staged.
     *
     * @throws Exception if the temp directory cannot be created or schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-method-stage-", "");
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
     * Verify that staging a runner's method trackers and reading them back for the same run id
     * reproduces every tracker's name and line range exactly, proving the write and read agree on
     * every column.
     */
    @Test
    void shouldRoundTripStagedTrackers() {
        // given
        Map<Integer, MethodImpactTracker> staged = new HashMap<>();
        staged.put(101, new MethodImpactTracker("com/example/A.foo.()V", 10, 20));
        staged.put(102, new MethodImpactTracker("com/example/B.bar.()V", 30, 45));

        // when
        dataStore.persistStagedMethodTrackers("run-1", staged);
        Map<Integer, MethodImpactTracker> read = dataStore.readStagedMethodTrackers("run-1");

        // then
        assertEquals(2, read.size());
        assertEquals("com/example/A.foo.()V", read.get(101).getMethodName());
        assertEquals(10, read.get(101).getLineNumberStart());
        assertEquals(20, read.get(101).getLineNumberEnd());
        assertEquals(45, read.get(102).getLineNumberEnd());
    }

    /**
     * Verify that two runners staging disjoint method ids under the same run id both survive: the
     * read returns the union of both writes, not just the most recent one. This is the property
     * the sealer depends on to rebuild the full catalogue from every runner's partial view, so the
     * assertion checks the full merged key set rather than just the size.
     */
    @Test
    void shouldUnionTrackersStagedBySeveralRunners() {
        // given
        Map<Integer, MethodImpactTracker> fromRunnerOne = new HashMap<>();
        fromRunnerOne.put(101, new MethodImpactTracker("com/example/A.foo.()V", 10, 20));
        Map<Integer, MethodImpactTracker> fromRunnerTwo = new HashMap<>();
        fromRunnerTwo.put(202, new MethodImpactTracker("com/example/C.baz.()V", 5, 9));

        // when
        dataStore.persistStagedMethodTrackers("run-1", fromRunnerOne);
        dataStore.persistStagedMethodTrackers("run-1", fromRunnerTwo);
        Map<Integer, MethodImpactTracker> read = dataStore.readStagedMethodTrackers("run-1");

        // then
        assertEquals(2, read.size());
        assertTrue(read.containsKey(101));
        assertTrue(read.containsKey(202));
        assertEquals("com/example/A.foo.()V", read.get(101).getMethodName());
        assertEquals("com/example/C.baz.()V", read.get(202).getMethodName());
    }

    /**
     * Verify that staging the same method id twice with different line numbers overwrites rather
     * than fails: the read returns the second write's values. This is the test that would fail
     * with a primary-key violation if the write were a plain INSERT instead of an upsert, and a
     * real distributed run always has overlapping method ids across runners.
     */
    @Test
    void shouldLetALaterStageOverwriteTheSameMethodId() {
        // given
        Map<Integer, MethodImpactTracker> firstStage = new HashMap<>();
        firstStage.put(101, new MethodImpactTracker("com/example/A.foo.()V", 10, 20));
        Map<Integer, MethodImpactTracker> secondStage = new HashMap<>();
        secondStage.put(101, new MethodImpactTracker("com/example/A.foo.()V", 11, 25));

        // when
        dataStore.persistStagedMethodTrackers("run-1", firstStage);
        dataStore.persistStagedMethodTrackers("run-1", secondStage);
        Map<Integer, MethodImpactTracker> read = dataStore.readStagedMethodTrackers("run-1");

        // then
        assertEquals(1, read.size());
        assertEquals(11, read.get(101).getLineNumberStart());
        assertEquals(25, read.get(101).getLineNumberEnd());
    }

    /**
     * Verify that staged trackers under two different run ids are isolated from each other: a read
     * for one run never surfaces rows staged under another, which matters because the staging
     * table has no other scoping mechanism than the run id column.
     */
    @Test
    void shouldKeepRunsIsolated() {
        // given
        Map<Integer, MethodImpactTracker> runOneStaged = new HashMap<>();
        runOneStaged.put(101, new MethodImpactTracker("com/example/A.foo.()V", 10, 20));
        Map<Integer, MethodImpactTracker> runTwoStaged = new HashMap<>();
        runTwoStaged.put(202, new MethodImpactTracker("com/example/C.baz.()V", 5, 9));

        // when
        dataStore.persistStagedMethodTrackers("run-1", runOneStaged);
        dataStore.persistStagedMethodTrackers("run-2", runTwoStaged);
        Map<Integer, MethodImpactTracker> readRunOne = dataStore.readStagedMethodTrackers("run-1");
        Map<Integer, MethodImpactTracker> readRunTwo = dataStore.readStagedMethodTrackers("run-2");

        // then
        assertEquals(1, readRunOne.size());
        assertTrue(readRunOne.containsKey(101));
        assertEquals(1, readRunTwo.size());
        assertTrue(readRunTwo.containsKey(202));
    }

    /**
     * Verify that reading an unknown run id returns an empty map rather than null or throwing, so
     * a caller can iterate the result unconditionally.
     */
    @Test
    void shouldReturnAnEmptyMapForAnUnknownRun() {
        // given
        // nothing has been staged for any run

        // when
        Map<Integer, MethodImpactTracker> read = dataStore.readStagedMethodTrackers("run-does-not-exist");

        // then
        assertTrue(read.isEmpty());
    }

    /**
     * Verify that staging an empty map does not fail, since a runner whose group produced no
     * coverage (every suite in its group was skipped, or the group itself had no impacted methods)
     * must still complete normally rather than error out.
     */
    @Test
    void shouldPersistAnEmptyStagingMapWithoutFailing() {
        // given
        Map<Integer, MethodImpactTracker> empty = new HashMap<>();

        // when
        dataStore.persistStagedMethodTrackers("run-1", empty);
        Map<Integer, MethodImpactTracker> read = dataStore.readStagedMethodTrackers("run-1");

        // then
        assertTrue(read.isEmpty());
    }

    /**
     * Verify that deleting one run's staged trackers leaves another run's staged trackers intact,
     * so the sealer's post-seal cleanup for one run can never lose a concurrently-staged peer run's
     * data.
     */
    @Test
    void shouldDeleteOnlyTheNamedRunsStagedTrackers() {
        // given
        Map<Integer, MethodImpactTracker> runOneStaged = new HashMap<>();
        runOneStaged.put(101, new MethodImpactTracker("com/example/A.foo.()V", 10, 20));
        Map<Integer, MethodImpactTracker> runTwoStaged = new HashMap<>();
        runTwoStaged.put(202, new MethodImpactTracker("com/example/C.baz.()V", 5, 9));
        dataStore.persistStagedMethodTrackers("run-1", runOneStaged);
        dataStore.persistStagedMethodTrackers("run-2", runTwoStaged);

        // when
        dataStore.deleteStagedMethodTrackers("run-1");

        // then
        assertTrue(dataStore.readStagedMethodTrackers("run-1").isEmpty());
        assertEquals(1, dataStore.readStagedMethodTrackers("run-2").size());
    }

    /**
     * Verify that deleting an unknown run id is a silent no-op rather than throwing, since the
     * sealer's cleanup call has no way to know in advance whether a given run ever staged anything.
     */
    @Test
    void shouldIgnoreDeletionOfAnUnknownRun() {
        // given
        // nothing has been staged for any run

        // when / then (must not throw)
        dataStore.deleteStagedMethodTrackers("run-does-not-exist");
    }

    /**
     * Verify that {@link DataStore#persistStagedMethodTrackers(String, Map)} bootstraps the schema
     * itself on a datastore that has never had {@code getTiaData} called on it. Every other test in
     * this class bootstraps via {@code setUp}'s {@code getTiaData(true)} call, which would mask a
     * datastore that forgot to call {@code ensureSchema} on its own staging write path - a brand
     * new per-branch schema has no tables at all, and staging is plausibly the first thing to touch
     * it in a distributed run.
     *
     * @throws Exception if the temp directory for the fresh store cannot be created
     */
    @Test
    void shouldStageWithoutARunHavingReadTheDatabaseFirst() throws Exception {
        // given
        File freshTempDir = File.createTempFile("tia-method-stage-fresh-", "");
        freshTempDir.delete();
        freshTempDir.mkdirs();
        JdbcDataStore freshDataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(freshTempDir.getAbsolutePath())),
                BranchSchema.schemaName("test"));
        try {
            Map<Integer, MethodImpactTracker> staged = new HashMap<>();
            staged.put(101, new MethodImpactTracker("com/example/A.foo.()V", 10, 20));

            // when
            freshDataStore.persistStagedMethodTrackers("run-1", staged);
            Map<Integer, MethodImpactTracker> read = freshDataStore.readStagedMethodTrackers("run-1");

            // then
            assertEquals(1, read.size());
            assertEquals("com/example/A.foo.()V", read.get(101).getMethodName());
        } finally {
            freshDataStore.close();
        }
    }
}
