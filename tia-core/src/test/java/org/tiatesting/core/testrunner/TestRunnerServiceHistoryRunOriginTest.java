package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunHistoryEntry;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that the history row {@link TestRunnerService#persistTestRunData} writes carries the run
 * origin - the CI/local source and the executing machine - so the history table can be split by
 * where a run came from rather than by the {@code updated_db_mapping} proxy.
 */
class TestRunnerServiceHistoryRunOriginTest {

    private JdbcDataStore dataStore;
    private TestRunnerService service;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-origin-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())), BranchSchema.schemaName("test"));
        dataStore.getTiaData(true);
        service = new TestRunnerService(dataStore);

        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("initial");
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(RunEnvironment.PROP_RUN_SOURCE);
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * A history-only run - the shape a developer's local run takes - still records where it came
     * from and which machine ran it. That is the whole point of the columns: this is exactly the run
     * that has no other way to be identified.
     */
    @Test
    void aHistoryOnlyRunRecordsItsOrigin() {
        // given
        long runStart = System.currentTimeMillis();

        // when
        service.persistTestRunData(false, false, true, "abc123", "main", runStart, makeResult(), null);

        // then
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
        assertEquals(1, history.size());
        RunOrigin origin = history.get(0).getRunOrigin();
        assertNotNull(origin.getRunSource(), "the run source must be recorded");
        assertNotNull(origin.getHostName(), "the executing host must be recorded");
    }

    /**
     * The declared override reaches the stored row, which is what lets a CI system Tia does not
     * recognise label its own builds correctly.
     */
    @Test
    void theDeclaredRunSourceOverrideReachesTheStoredRow() {
        // given
        System.setProperty(RunEnvironment.PROP_RUN_SOURCE, "NIGHTLY");
        long runStart = System.currentTimeMillis();

        // when
        service.persistTestRunData(false, false, true, "abc123", "main", runStart, makeResult(), null);

        // then
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
        assertEquals("NIGHTLY", history.get(0).getRunOrigin().getRunSource());
    }

    /**
     * Build a minimal {@link TestRunResult} with a single suite and no mapped classes.
     *
     * @return a sparsely-populated TestRunResult suitable for persist tests
     */
    private TestRunResult makeResult() {
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        trackers.put("com.example.SomeTest", new TestSuiteTracker("com.example.SomeTest"));
        return new TestRunResult(
                trackers, new HashSet<>(), new HashSet<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>(), new TestStats(), null, 3, 1);
    }
}
