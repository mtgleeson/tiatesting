package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Covers one history row per run when a run's suites are split across test JVMs and test plans -
 * Surefire {@code forkCount > 1}, which also runs each class as its own plan - with at most one
 * more row for its reruns.
 */
class TestRunnerServiceMultiJvmHistoryTest {

    private static final long BASELINE_MS = 60_000L;

    private File dbDir;

    /**
     * Create the embedded database and store a full-suite baseline, so savings are measurable.
     *
     * @throws Exception if the directory cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        dbDir = Files.createTempDirectory("tia-multi-jvm-history-").toFile();
        try (JdbcDataStore store = openStore()) {
            TiaData tiaData = store.getTiaData();
            tiaData.setCommitValue("c0");
            tiaData.setLastUpdated(Instant.now());
            tiaData.getTestStats().setAllTestsRunTime(BASELINE_MS);
            store.persistCoreData(tiaData);
        }
    }

    /**
     * Remove the embedded database directory.
     */
    @AfterEach
    void tearDown() {
        File[] files = dbDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        dbDir.delete();
    }

    /**
     * @return a store on the test's embedded database
     */
    private JdbcDataStore openStore() {
        return new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(
                H2ConnectionSettings.embedded(dbDir.getAbsolutePath())), BranchSchema.schemaName("main", null));
    }

    private static Set<String> names(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    /**
     * Persist one plan of the shared fixture run, mapping off and history on, as a fork does at the
     * end of each test plan.
     *
     * @param jvm the fork the plan ran in
     * @param ran the suites the plan ran
     * @param failed the suites that failed
     */
    private void persist(TestJvmContribution jvm, Set<String> ran, Set<String> failed) {
        Set<String> empty = Collections.emptySet();
        TestRunResult result = new TestRunResult(new HashMap<>(), failed, empty, ran, names("A1", "A2", "B1",
                "B2", "C1", "C2"), new HashMap<>(), new TestStats(), null, 4,
                TestRunResultFixture.planOutcome(ran, failed),
                TestRunSelectionDetails.empty(), jvm, TestRunResultFixture.runIdentity(TestRunResultFixture.RUN_ID));
        new TestRunnerService(openStore()).persistTestRunData(false, true, "c1", "main",
                System.currentTimeMillis(), result, null);
    }

    /**
     * @return the stored history rows, newest first
     */
    private List<TestRunHistoryEntry> history() {
        try (JdbcDataStore store = openStore()) {
            return store.readTestRunHistory();
        }
    }

    @Test
    void perClassPlansInThreeForksWriteOneRow() {
        // given three forks, two classes each, one test plan per class
        TestJvmContribution a = new TestJvmContribution();
        TestJvmContribution b = new TestJvmContribution();
        TestJvmContribution c = new TestJvmContribution();

        // when
        persist(a, names("A1"), names());
        persist(b, names("B1"), names());
        persist(a, names("A2"), names());
        persist(c, names("C1"), names("C1"));
        persist(b, names("B2"), names());
        persist(c, names("C2"), names());

        // then
        List<TestRunHistoryEntry> history = history();
        assertEquals(1, history.size());
        assertEquals(6, history.get(0).getNumSuitesRan());
        assertEquals(1, history.get(0).getNumSuitesFailed());
        assertFalse(history.get(0).isRerun());
        assertEquals(BASELINE_MS - history.get(0).getDurationMs(), history.get(0).getTimeSavingsMs());
    }

    @Test
    void aSurefireRerunAddsOneRerunRow() {
        // given a fork whose class failed
        TestJvmContribution a = new TestJvmContribution();
        persist(a, names("A1"), names("A1"));

        // when Surefire re-runs it in the same JVM
        persist(a, names("A1"), names());

        // then
        List<TestRunHistoryEntry> history = history();
        assertEquals(2, history.size());
        assertEquals(1L, history.stream().filter(TestRunHistoryEntry::isRerun).count());
        assertEquals(0L, history.stream().filter(TestRunHistoryEntry::isRerun).findFirst().get().getTimeSavingsMs());
    }
}
