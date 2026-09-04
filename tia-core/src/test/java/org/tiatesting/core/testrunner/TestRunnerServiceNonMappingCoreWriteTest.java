package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Lock the core-row write a run that does NOT own mapping updates is allowed to make.
 *
 * <p>The stored commit value and branch describe the mapping, and only a mapping-owning build has
 * standing to write them. A local developer run or any other {@code updateDBMapping=false} build
 * reads the core row at the start of its persist and finishes some minutes later; writing the whole
 * row back at that point stamps the commit it read, silently rolling the stored commit backwards if
 * a mapping-owning build advanced it meanwhile. The next mapping build would then diff from the
 * older commit and re-do work it had already sealed.
 *
 * <p>These tests drive {@link TestRunnerService#persistTestRunData} against a real embedded H2
 * store, interleaving a concurrent commit advance the way a CI build would.
 */
class TestRunnerServiceNonMappingCoreWriteTest {

    private CountingDataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-non-mapping-core-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new CountingDataStore(tempDir);
        dataStore.getTiaData(true);

        // Seed the core row the way a mapping-owning build leaves it.
        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("ci-commit-1");
        tiaData.setBranch("main");
        tiaData.setLastUpdated(Instant.now());
        tiaData.getTestStats().setNumRuns(10L);
        tiaData.getTestStats().setAvgRunTime(2000L);
        dataStore.persistCoreData(tiaData);
        dataStore.callOrder.clear();
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
     * A run that does not own the mapping has nothing to contribute to the core row - not the
     * commit, not the branch, and (since stats follow the mapping) not the stats either - so it must
     * not write the row at all.
     */
    @Test
    void nonMappingRunMakesNoCoreRowWrite() {
        // given
        TestRunnerService service = new TestRunnerService(dataStore);

        // when
        service.persistTestRunData(false, true, "dev-commit", "feature/x",
                System.currentTimeMillis(), makeResult(), null);

        // then
        assertEquals(0, Collections.frequency(dataStore.callOrder, "persistCoreData"),
                "a non-mapping run must not write the core row");
    }

    /**
     * The regression this fix exists for: CI seals a newer commit after the run under test has read
     * the core row but before it persists. The run must leave the newer commit and branch in place.
     */
    @Test
    void historyOnlyRunDoesNotRollBackACommitAdvancedByCi() {
        // given - CI seals mid-run, after this run's own core read
        TestRunnerService service = new TestRunnerService(dataStore);
        dataStore.afterNextCoreRead = () -> advanceStoredCommitAsCiWould("ci-commit-2", "main");

        // when
        service.persistTestRunData(false, true, "dev-commit", "feature/x",
                System.currentTimeMillis(), makeResult(), null);

        // then
        TiaData read = dataStore.getTiaCore();
        assertEquals("ci-commit-2", read.getCommitValue(),
                "a history-only run must not roll the stored commit back");
        assertEquals("main", read.getBranch(),
                "a history-only run must not roll the stored branch back");
    }

    /**
     * A mapping-owning run is unaffected: it still advances the commit and branch through the seal.
     */
    @Test
    void mappingRunStillAdvancesTheCommitAndBranch() {
        // given
        TestRunnerService service = new TestRunnerService(dataStore);

        // when
        service.persistTestRunData(true, false, "ci-commit-2", "release",
                System.currentTimeMillis(), makeResult(), null);

        // then
        TiaData read = dataStore.getTiaCore();
        assertEquals("ci-commit-2", read.getCommitValue());
        assertEquals("release", read.getBranch());
    }

    /**
     * Stand in for a mapping-owning CI build sealing a newer commit while the run under test is
     * still executing. Reads the core row through the superclass so the hook that invokes this does
     * not re-trigger itself.
     *
     * @param commitValue the commit CI seals
     * @param branch the branch CI seals
     */
    private void advanceStoredCommitAsCiWould(final String commitValue, final String branch) {
        TiaData ciCore = dataStore.readCoreDirect();
        ciCore.setCommitValue(commitValue);
        ciCore.setBranch(branch);
        ciCore.setLastUpdated(Instant.now());
        dataStore.persistCoreData(ciCore);
        dataStore.callOrder.clear();
    }

    /**
     * Build a minimal {@link TestRunResult} with a single suite and no mapped classes - enough to
     * drive a persist without exercising the mapping edges.
     *
     * @return a sparsely-populated TestRunResult suitable for persist tests
     */
    private TestRunResult makeResult() {
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        trackers.put("com.example.SomeTest", new TestSuiteTracker("com.example.SomeTest"));
        return new TestRunResult(
                trackers, new HashSet<>(), new HashSet<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>(), new TestStats(), null, 0, 1);
    }

    /**
     * Real embedded H2 store that additionally records which core-row write path each persist took.
     * Subclassing rather than delegating keeps the distributed and mapping reads these tests rely on
     * working with no boilerplate to drift out of date.
     */
    private static final class CountingDataStore extends JdbcDataStore {

        private final List<String> callOrder = new ArrayList<>();

        /**
         * Runs once, immediately after the next {@link #getTiaCore()}, then clears itself. Lets a
         * test interleave a competing write into the window between the run's core read and its
         * core write - the window the whole-row write is unsafe in.
         */
        private Runnable afterNextCoreRead;

        /**
         * Open an embedded H2 store under a test-owned directory, in the same per-branch schema the
         * other persistence tests use.
         *
         * @param databaseDir the directory to hold the embedded database files
         */
        CountingDataStore(final File databaseDir) {
            super(new H2Dialect(),
                    new H2ConnectionProvider(H2ConnectionSettings.embedded(databaseDir.getAbsolutePath())),
                    BranchSchema.schemaName("test"));
        }

        /**
         * Read the core row, then fire the interleaving hook if a test armed one.
         *
         * @return the stored core data
         */
        @Override
        public TiaData getTiaCore() {
            TiaData tiaData = super.getTiaCore();

            if (afterNextCoreRead != null) {
                Runnable hook = afterNextCoreRead;
                afterNextCoreRead = null;
                hook.run();
            }

            return tiaData;
        }

        /**
         * Read the core row without firing the interleaving hook, for the test helper that stands in
         * for the competing CI build.
         *
         * @return the stored core data
         */
        TiaData readCoreDirect() {
            return super.getTiaCore();
        }

        /**
         * Record and delegate the whole-row core write.
         *
         * @param tiaData the core data to write
         */
        @Override
        public void persistCoreData(final TiaData tiaData) {
            callOrder.add("persistCoreData");
            super.persistCoreData(tiaData);
        }

    }
}
