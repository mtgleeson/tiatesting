package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestRunContribution;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link TestRunnerService#persistTestRunData} recording each JVM's contribution and
 * resolving each plan's attempt from the run's contributions.
 */
class TestRunnerServiceContributionTest {

    private File dbDir;

    /**
     * Create the embedded database directory.
     *
     * @throws Exception if the directory cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        dbDir = Files.createTempDirectory("tia-contribution-").toFile();
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
     * Build a mapping-off result for one plan of the shared fixture run.
     *
     * @param jvm the JVM the plan ran in
     * @param ran the suites the plan ran
     * @param failed the suites that failed
     * @return the result
     */
    private static TestRunResult result(TestJvmContribution jvm, Set<String> ran, Set<String> failed) {
        Set<String> empty = Collections.emptySet();
        return new TestRunResult(new HashMap<>(), failed, empty, ran, ran, new HashMap<>(), new TestStats(),
                null, 1, TestRunResultFixture.planOutcome(ran, failed, 1000L), TestRunSelectionDetails.empty(),
                jvm, TestRunResultFixture.runIdentity(TestRunResultFixture.RUN_ID));
    }

    /**
     * Persist one plan through the service, as a test JVM does at the end of a test plan.
     *
     * @param jvm the JVM the plan ran in
     * @param ran the suites the plan ran
     * @param failed the suites that failed
     */
    private void persist(TestJvmContribution jvm, Set<String> ran, Set<String> failed) {
        new TestRunnerService(openStore()).persistTestRunData(false, false, "c1", "main", 1000L,
                result(jvm, ran, failed), null);
    }

    /**
     * @return the shared fixture run's stored contributions
     */
    private List<TestRunContribution> storedContributions() {
        try (JdbcDataStore store = openStore()) {
            return store.readTestRunContributions(TestRunResultFixture.RUN_ID);
        }
    }

    @Test
    void twoForksEachRecordTheirOwnFirstAttempt() {
        // given two forks of one run
        TestJvmContribution forkA = new TestJvmContribution();
        TestJvmContribution forkB = new TestJvmContribution();

        // when
        persist(forkA, names("A"), names());
        persist(forkB, names("B"), names("B"));

        // then
        List<TestRunContribution> rows = storedContributions();
        assertEquals(2, rows.size());
        for (TestRunContribution row : rows) {
            assertFalse(row.isRerun());
        }
    }

    @Test
    void aRetryJvmIsRecordedAsARerun() {
        // given a first JVM that failed A
        persist(new TestJvmContribution(), names("A", "B"), names("A"));

        // when a fresh JVM re-runs only A
        persist(new TestJvmContribution(), names("A"), names());

        // then
        List<TestRunContribution> rows = storedContributions();
        long reruns = rows.stream().filter(TestRunContribution::isRerun).count();
        assertEquals(1L, reruns);
        assertTrue(rows.stream().anyMatch(r -> r.isRerun() && r.getRerunSuites().contains("A")));
    }
}
