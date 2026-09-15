package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run that executed none of the suites Tia expected it to run - see
 * {@link TestRunResult#ranNoExpectedSuites()} - has observed neither the commit nor the suites, so
 * it must persist nothing that claims it has. These tests pin each write it is kept away from, and
 * each one matters in the silent, under-selecting direction:
 *
 * <ul>
 *   <li>the stored commit value, whose advance would leave the next run diffing past the suites this
 *       run never covered;</li>
 *   <li>every tracked library's mapping baseline, for the same reason;</li>
 *   <li>the failed-suite set, whose force-run entries would be dropped without a passing run;</li>
 *   <li>the tracked suite mapping, which an empty run's observations would read as wholesale
 *       deletion (no directory scan configured) or as wholesale developer-disabling (one
 *       configured).</li>
 * </ul>
 *
 * <p>Only the history row is written, because it claims nothing about the code. The stats half of the
 * guard lives in {@code TestRunnerServiceEmptyRunStatsGuardTest}.
 */
class TestRunnerServiceEmptyRunWriteGateTest {

    private static final String SUITE_A = "com.example.ATest";
    private static final String SUITE_B = "com.example.BTest";
    private static final String LIB = "com.example:lib";

    private JdbcDataStore dataStore;
    private TestRunnerService service;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-emptygate-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
        dataStore.getTiaData(true);
        service = new TestRunnerService(dataStore);

        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("commit-0");
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);

        Map<String, TestSuiteTracker> tracked = new HashMap<>();
        tracked.put(SUITE_A, new TestSuiteTracker(SUITE_A));
        tracked.put(SUITE_B, new TestSuiteTracker(SUITE_B));
        dataStore.persistTestSuites(tracked);
    }

    @AfterEach
    void tearDown() {
        dataStore.close();
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * Build an empty run: Tia selected both tracked suites, the runner still knows both exist, and
     * neither executed.
     *
     * @return the run result to persist
     */
    private TestRunResult emptyRun() {
        Set<String> selected = new HashSet<>(Arrays.asList(SUITE_A, SUITE_B));
        TestStats runStats = new TestStats();
        runStats.setNumRuns(1);
        runStats.setAvgRunTime(152L);
        runStats.setNumSuccessRuns(1);

        return new TestRunResult(new HashMap<>(), new HashSet<>(), selected, selected, selected,
                new HashMap<>(), runStats, null, 3, 0);
    }

    /**
     * The commit the empty run was against must not become the stored commit: the next run has to
     * diff from the older one and re-select the suites this run never covered. This is the same state
     * a crash before the seal leaves behind, which Tia already self-corrects.
     */
    @Test
    void emptyRun_doesNotAdvanceTheStoredCommit() {
        // given
        TestRunResult result = emptyRun();

        // when - a mapping-owning persist, which is the only kind that would seal
        service.persistTestRunData(true, false, "commit-1", "main", System.currentTimeMillis(), result, null);

        // then
        assertEquals("commit-0", dataStore.getTiaCore().getCommitValue());
    }

    /**
     * A previously-failed suite stays in the force-run set. The failed set is maintained by removing
     * the run's selection and adding back what failed, and an empty run failed nothing only because
     * it ran nothing.
     */
    @Test
    void emptyRun_leavesThePreviouslyFailedSetAlone() {
        // given - suite A failed on an earlier run and is selected again now
        dataStore.persistTestSuitesFailed(new HashSet<>(Arrays.asList(SUITE_A)));

        // when
        service.persistTestRunData(true, false, "commit-1", "main", System.currentTimeMillis(), emptyRun(), null);

        // then
        assertTrue(dataStore.getTestSuitesFailed().contains(SUITE_A),
                "a suite that failed earlier must stay force-run until a run actually passes it");
    }

    /**
     * With no test-classes directory scan configured, the runner set an empty run reports is whatever
     * its JVM observed - nothing. Read as deletion, that wipes the project's entire stored mapping,
     * which is the most expensive outcome of the misconfiguration.
     */
    @Test
    void emptyRun_doesNotDeleteTheTrackedMapping() {
        // given - an empty run whose runner set is empty, as it is with no directory scan configured
        TestRunResult result = new TestRunResult(new HashMap<>(), new HashSet<>(), new HashSet<>(),
                new HashSet<>(), new HashSet<>(Arrays.asList(SUITE_A, SUITE_B)), new HashMap<>(),
                new TestStats(), null, 3, 0);

        // when
        service.persistTestRunData(true, false, "commit-1", "main", System.currentTimeMillis(), result, null);

        // then
        Map<String, TestSuiteTracker> tracked = dataStore.getTestSuitesTracked();
        assertTrue(tracked.containsKey(SUITE_A));
        assertTrue(tracked.containsKey(SUITE_B));
    }

    /**
     * A suite Tia selected, the runner discovered, and which then did not execute normally reads as
     * disabled in source. On an empty run every selected suite matches that shape at once, so the
     * flag must not be re-derived at all - a flagged suite is treated as one that would not run
     * without Tia either.
     */
    @Test
    void emptyRun_doesNotFlagSelectedSuitesAsDeveloperDisabled() {
        // given - the runner discovered both suites (a directory scan is configured) and ran neither

        // when
        service.persistTestRunData(true, false, "commit-1", "main", System.currentTimeMillis(), emptyRun(), null);

        // then
        Map<String, TestSuiteTracker> tracked = dataStore.getTestSuitesTracked();
        assertFalse(tracked.get(SUITE_A).isDeveloperDisabled());
        assertFalse(tracked.get(SUITE_B).isDeveloperDisabled());
    }

    /**
     * The library mapping baseline is advanced by the seal, so an empty run leaves it where it was:
     * the libraries' covering suites did not run, and moving the baseline would hide their next
     * change from the diff.
     */
    @Test
    void emptyRun_doesNotAdvanceLibraryMappingBaselines() {
        // given - a tracked library with a drain result the run would otherwise apply
        TrackedLibrary library = new TrackedLibrary(LIB, "/projects/lib", null);
        library.setMappingBaselineCommit("old-baseline");
        dataStore.persistTrackedLibrary(library);
        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDrainedBatch(LIB, 5L);
        drainResult.setAppliedSeq(LIB, 5L);

        Set<String> selected = new HashSet<>(Arrays.asList(SUITE_A, SUITE_B));
        TestRunResult result = new TestRunResult(new HashMap<>(), new HashSet<>(), selected, selected,
                selected, new HashMap<>(), new TestStats(), drainResult, 3, 0);

        // when
        service.persistTestRunData(true, false, "commit-1", "main", System.currentTimeMillis(), result, null);

        // then
        TrackedLibrary stored = dataStore.readTrackedLibraries().get(LIB);
        assertEquals("old-baseline", stored.getMappingBaselineCommit());
        assertEquals(null, stored.getLastAppliedSeq(),
                "the drain cleanup is part of the seal, so it waits for a run that actually ran");
    }

    /**
     * The one write an empty run does make. The row is the audit trail - a {@code ran=0} row is how
     * the empty run stays visible - and it reports no mapping update, which is now the truth for this
     * run whatever it was configured to do.
     */
    @Test
    void emptyRun_stillWritesItsHistoryRow() {
        // given
        TestRunResult result = emptyRun();

        // when - configured to update both the mapping and the history
        service.persistTestRunData(true, true, "commit-1", "main", System.currentTimeMillis(), result, null);
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();

        // then
        assertEquals(1, history.size());
        TestRunHistoryEntry row = history.get(0);
        assertEquals(0, row.getNumSuitesRan());
        assertEquals(3, row.getNumSuitesIgnored());
        assertEquals(0L, row.getTimeSavingsMs());
        assertFalse(row.isUpdatedDbMapping(), "the run persisted no mapping update, so the row must not claim one");
    }
}
