package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunContribution;
import org.tiatesting.core.model.TestRunContributionTotals;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunSelectionDetails;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Postgres arm of {@link TestRunContributionRepositoryTest}: the run lock, the idempotent
 * contribution upsert and the history rebuild are exercised against a real Postgres, since the lock
 * and the {@code ON CONFLICT} upsert are vendor-specific. Skipped (not failed) when no Postgres is
 * reachable on {@code localhost:5432}, like {@link PostgresClaimTest}.
 */
class PostgresTestRunContributionTest {

    private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/tiaperf";
    private static final String POSTGRES_USER = "tia";
    private static final String POSTGRES_PASSWORD = "tia";
    private static final String BRANCH = "main";

    private DataStore postgresStore;

    /**
     * Skip the test when the local Postgres instance is not reachable.
     */
    private static void assumePg() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 500);
        } catch (IOException e) {
            assumeTrue(false, "spike Postgres not running");
        }
    }

    /**
     * Drop the per-branch schema so each test starts from an empty store with the current DDL.
     *
     * @throws SQLException if the cleanup fails
     */
    private static void dropBranchSchema() throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP SCHEMA IF EXISTS " + BranchSchema.schemaName(BRANCH, null) + " CASCADE");
        }
    }

    /**
     * Skip when Postgres is unreachable, drop the branch schema and open a fresh store.
     *
     * @throws Exception if the guard, cleanup or store construction fails
     */
    @BeforeEach
    void setUp() throws Exception {
        assumePg();
        dropBranchSchema();
        postgresStore = DataStoreFactory.fromConfig(null, POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD,
                null, BRANCH, null);
        postgresStore.getTiaData();
    }

    /**
     * Close the store opened by the test.
     */
    @AfterEach
    void tearDown() {
        if (postgresStore != null) {
            postgresStore.close();
        }
    }

    private static TestRunContribution contribution(String jvmId, int ran, String... failed) {
        return new TestRunContribution("run-1", jvmId, false, null, ran, new HashSet<>(Arrays.asList(failed)),
                Collections.<String>emptySet(), 100L * ran, 0L, 1000L, 1000L + 100L * ran);
    }

    /**
     * A history builder writing one row whose ran count is the sum over every contribution.
     *
     * @return the builder
     */
    private static Function<List<TestRunContribution>, List<TestRunHistoryEntry>> summingHistory() {
        return all -> Collections.singletonList(TestRunHistoryEntry.create("main", "c1", String.valueOf(1000L), false, 1000L,
                TestRunContributionTotals.of(all).getSuitesRan(), 0, 0, 1L, 1L, false, 0L, 0,
                RunOrigin.of(RunOrigin.SOURCE_LOCAL, "host"), TestRunSelectionDetails.empty()));
    }

    @Test
    void restatingAJvmsContributionReplacesItOnPostgres() {
        // given a JVM that persists after its first and its second test plan
        postgresStore.persistTestRunContribution("run-1", 1000L,
                Collections.singletonList(contribution("jvm-a", 1)), null);

        // when
        postgresStore.persistTestRunContribution("run-1", 1000L,
                Collections.singletonList(contribution("jvm-a", 2, "A")), null);

        // then
        List<TestRunContribution> stored = postgresStore.readTestRunContributions("run-1");
        assertEquals(1, stored.size());
        assertEquals(2, stored.get(0).getSuitesRan());
    }

    @Test
    void theHistoryBuilderSeesEveryJvmsContributionOnPostgres() {
        // given the first fork has persisted
        Set<String> createdByFirst = postgresStore.persistTestRunContribution("run-1", 1000L,
                Collections.singletonList(contribution("jvm-a", 3)), summingHistory());

        // when the second fork persists
        Set<String> createdBySecond = postgresStore.persistTestRunContribution("run-1", 1000L,
                Collections.singletonList(contribution("jvm-b", 4)), summingHistory());

        // then
        List<TestRunHistoryEntry> rows = postgresStore.readTestRunHistory();
        assertEquals(1, rows.size());
        assertEquals(7, rows.get(0).getNumSuitesRan());
        assertEquals(1, createdByFirst.size());
        assertTrue(createdBySecond.isEmpty());
    }

    @Test
    void failedSuitesExcludeTheAskingJvmOnPostgres() {
        // given
        postgresStore.persistTestRunContribution("run-1", 1000L,
                Arrays.asList(contribution("jvm-a", 1, "A"), contribution("jvm-b", 1, "B")), null);

        // when
        Set<String> failed = postgresStore.readTestRunFailedSuites("run-1", null, "jvm-a");

        // then
        assertEquals(Collections.singleton("B"), failed);
    }

    @Test
    void concurrentForksEndWithAConsistentAggregateOnPostgres() throws Exception {
        // given eight forks, each with its own store, persisting at the same moment
        int forks = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(forks);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < forks; i++) {
            final String jvmId = "jvm-" + i;
            futures.add(pool.submit(() -> {
                try (DataStore store = DataStoreFactory.fromConfig(null, POSTGRES_URL, POSTGRES_USER,
                        POSTGRES_PASSWORD, null, BRANCH, null)) {
                    start.await();
                    store.persistTestRunContribution("run-1", 1000L,
                            Collections.singletonList(contribution(jvmId, 1)), summingHistory());
                }
                return null;
            }));
        }

        // when
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        // then
        assertEquals(forks, postgresStore.readTestRunHistory().get(0).getNumSuitesRan());
    }

    @Test
    void aRecordedCoverageFailureIsReadBackAndTheFirstIsKeptOnPostgres() {
        // given a run no JVM has persisted for yet, and another run with a contribution
        postgresStore.persistTestRunContribution("run-2", 1000L,
                Collections.singletonList(new TestRunContribution("run-2", "jvm-a", false, null, 1,
                        Collections.<String>emptySet(), Collections.<String>emptySet(), 100L, 0L, 1000L, 1100L)),
                null);

        // when two JVMs of the run record a failure in turn
        postgresStore.markTestRunCoverageFailed("run-1", 1000L, "at startup: java.io.IOException: refused");
        postgresStore.markTestRunCoverageFailed("run-1", 1000L, "second");

        // then the first is kept, and a run with none recorded - or with no row - reads as none
        assertEquals("at startup: java.io.IOException: refused", postgresStore.readTestRunCoverageFailure("run-1"));
        assertNull(postgresStore.readTestRunCoverageFailure("run-2"));
        assertNull(postgresStore.readTestRunCoverageFailure("run-3"));
    }
}
