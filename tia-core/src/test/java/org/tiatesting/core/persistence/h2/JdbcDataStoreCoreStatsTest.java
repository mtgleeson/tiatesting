package org.tiatesting.core.persistence.h2;

import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@code JdbcDataStore.persistCoreStats}: the stats-only write onto the core row that a
 * run which does not own mapping updates makes. Uses a temp-directory embedded H2 database per test
 * for isolation.
 */
class JdbcDataStoreCoreStatsTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-test-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())), BranchSchema.schemaName("test"));
        // force schema creation
        dataStore.getTiaData(true);
    }

    @AfterEach
    void tearDown() {
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * Build a core {@link TiaData} carrying a commit, a branch and a set of run stats, as a mapping
     * run would write it.
     *
     * @param commit the commit value to stamp
     * @param branch the branch to stamp
     * @param numRuns the run count to store
     * @param avgRunTime the selected-run average (ms) to store
     * @return a populated TiaData ready for {@code persistCoreData}
     */
    private TiaData coreData(final String commit, final String branch, final long numRuns,
                             final long avgRunTime) {
        TiaData tiaData = new TiaData();
        tiaData.setCommitValue(commit);
        tiaData.setBranch(branch);
        tiaData.setLastUpdated(Instant.now());
        tiaData.getTestStats().setNumRuns(numRuns);
        tiaData.getTestStats().setAvgRunTime(avgRunTime);
        return tiaData;
    }

    /**
     * Build a standalone {@link TestStats} for the stats-only write.
     *
     * @param numRuns the run count to store
     * @param avgRunTime the selected-run average (ms) to store
     * @return the populated stats
     */
    private TestStats stats(final long numRuns, final long avgRunTime) {
        TestStats testStats = new TestStats();
        testStats.setNumRuns(numRuns);
        testStats.setAvgRunTime(avgRunTime);
        return testStats;
    }

    @Test
    void persistCoreStatsUpdatesTheStatsColumns() {
        // given
        dataStore.persistCoreData(coreData("commit1", "main", 5L, 1000L));

        // when
        dataStore.persistCoreStats(stats(6L, 1200L));
        TiaData read = dataStore.getTiaCore();

        // then
        assertEquals(6L, read.getTestStats().getNumRuns());
        assertEquals(1200L, read.getTestStats().getAvgRunTime());
    }

    @Test
    void persistCoreStatsLeavesTheCommitValueAndBranchUntouched() {
        // given
        dataStore.persistCoreData(coreData("commit1", "main", 5L, 1000L));

        // when
        dataStore.persistCoreStats(stats(6L, 1200L));
        TiaData read = dataStore.getTiaCore();

        // then
        assertEquals("commit1", read.getCommitValue(),
                "a stats-only write must not touch the stored commit value");
        assertEquals("main", read.getBranch(),
                "a stats-only write must not touch the stored branch");
    }

    /**
     * The scenario the stats-only write exists for: a non-mapping run reads the core row, a
     * mapping-owning build advances the commit while it is executing, and the non-mapping run then
     * persists. Its write must carry only its stats forward, leaving the newer commit in place.
     */
    @Test
    void persistCoreStatsDoesNotRollBackACommitAdvancedConcurrently() {
        // given
        dataStore.persistCoreData(coreData("commit1", "main", 5L, 1000L));
        TiaData snapshotReadByTheNonMappingRun = dataStore.getTiaCore();
        dataStore.persistCoreData(coreData("commit2", "release", 6L, 1100L));

        // when
        snapshotReadByTheNonMappingRun.getTestStats().setNumRuns(7L);
        dataStore.persistCoreStats(snapshotReadByTheNonMappingRun.getTestStats());
        TiaData read = dataStore.getTiaCore();

        // then
        assertEquals("commit2", read.getCommitValue(),
                "the stale snapshot must not roll the stored commit back to commit1");
        assertEquals("release", read.getBranch(),
                "the stale snapshot must not roll the stored branch back to main");
        assertEquals(7L, read.getTestStats().getNumRuns());
    }

    /**
     * The commit value is the core table's primary key, so a store that has never had a mapping run
     * has no row for the stats to attach to. The write must be a no-op rather than inventing a row
     * with a fabricated commit.
     */
    @Test
    void persistCoreStatsIsANoOpWhenNoCoreRowExistsYet() {
        // given
        // no persistCoreData call: the core table is empty

        // when
        dataStore.persistCoreStats(stats(1L, 500L));
        TiaData read = dataStore.getTiaCore();

        // then
        assertNull(read.getCommitValue(), "no core row must be created by a stats-only write");
        assertEquals(0L, read.getTestStats().getNumRuns());
    }
}
