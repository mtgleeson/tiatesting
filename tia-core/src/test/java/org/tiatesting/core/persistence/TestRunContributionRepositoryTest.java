package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunContribution;
import org.tiatesting.core.model.TestRunContributionTotals;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunSelectionDetails;

import java.nio.file.Path;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link TestRunContributionRepository}: idempotent contribution upserts, the run lock,
 * and history rows rebuilt from every contribution in the same transaction.
 */
class TestRunContributionRepositoryTest {

    private static TestRunContribution contribution(String jvmId, boolean rerun, int ran, String... failed) {
        return new TestRunContribution("run-1", jvmId, rerun, null, ran, new HashSet<>(Arrays.asList(failed)),
                Collections.<String>emptySet(), 100L * ran, 0L, 1000L, 1000L + 100L * ran);
    }

    private static TestRunContributionRepository repository(H2RepositoryFixture db) {
        return new TestRunContributionRepository(db.dialect, db.connections, db.schemaManager);
    }

    private static TestRunHistoryRepository historyRepository(H2RepositoryFixture db) {
        return new TestRunHistoryRepository(db.dialect, db.connections, db.schemaManager);
    }

    /**
     * A history builder that writes one row whose ran count is the sum over every contribution, so
     * a test can see which contributions the builder was handed.
     *
     * @return the builder
     */
    private static Function<List<TestRunContribution>, List<TestRunHistoryEntry>> summingHistory() {
        return all -> Collections.singletonList(TestRunHistoryEntry.create("main", "c1", String.valueOf(1000L), false, 1000L,
                TestRunContributionTotals.of(all).getSuitesRan(), 0, 0, 1L, 1L, false, 0L, 0,
                RunOrigin.of(RunOrigin.SOURCE_LOCAL, "host"), TestRunSelectionDetails.empty()));
    }

    @Test
    void restatingAJvmsContributionReplacesIt(@TempDir Path dir) {
        try (H2RepositoryFixture db = new H2RepositoryFixture(dir).withSchema()) {
            // given a JVM that persists after its first and its second test plan
            TestRunContributionRepository repository = repository(db);
            repository.persistContribution("run-1", 1000L,
                    Collections.singletonList(contribution("jvm-a", false, 1)), null, historyRepository(db));

            // when
            repository.persistContribution("run-1", 1000L,
                    Collections.singletonList(contribution("jvm-a", false, 2, "A")), null, historyRepository(db));

            // then
            List<TestRunContribution> stored = repository.readContributions("run-1");
            assertEquals(1, stored.size());
            assertEquals(2, stored.get(0).getSuitesRan());
            assertEquals(Collections.singleton("A"), stored.get(0).getSuitesFailed());
        }
    }

    @Test
    void theHistoryBuilderSeesEveryJvmsContribution(@TempDir Path dir) {
        try (H2RepositoryFixture db = new H2RepositoryFixture(dir).withSchema()) {
            // given two forks of one run
            TestRunContributionRepository repository = repository(db);
            TestRunHistoryRepository history = historyRepository(db);
            Set<String> createdByFirst = repository.persistContribution("run-1", 1000L,
                    Collections.singletonList(contribution("jvm-a", false, 3)), summingHistory(), history);

            // when the second fork persists
            Set<String> createdBySecond = repository.persistContribution("run-1", 1000L,
                    Collections.singletonList(contribution("jvm-b", false, 4)), summingHistory(), history);

            // then one row holds both forks, created by the first persist only
            List<TestRunHistoryEntry> rows = history.readTestRunHistory();
            assertEquals(1, rows.size());
            assertEquals(7, rows.get(0).getNumSuitesRan());
            assertEquals(1, createdByFirst.size());
            assertTrue(createdBySecond.isEmpty());
        }
    }

    @Test
    void failedSuitesExcludeTheAskingJvm(@TempDir Path dir) {
        try (H2RepositoryFixture db = new H2RepositoryFixture(dir).withSchema()) {
            // given two JVMs that each recorded a failure
            TestRunContributionRepository repository = repository(db);
            repository.persistContribution("run-1", 1000L, Arrays.asList(
                    contribution("jvm-a", false, 1, "A"), contribution("jvm-b", false, 1, "B")),
                    null, historyRepository(db));

            // when
            Set<String> failed = repository.readFailedSuites("run-1", "jvm-a");

            // then
            assertEquals(Collections.singleton("B"), failed);
        }
    }

    @Test
    void concurrentForksEndWithAConsistentAggregate(@TempDir Path dir) throws Exception {
        try (H2RepositoryFixture db = new H2RepositoryFixture(dir).withSchema()) {
            // given eight forks persisting at the same moment
            TestRunContributionRepository repository = repository(db);
            TestRunHistoryRepository history = historyRepository(db);
            int forks = 8;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(forks);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < forks; i++) {
                final String jvmId = "jvm-" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    repository.persistContribution("run-1", 1000L,
                            Collections.singletonList(contribution(jvmId, false, 1)), summingHistory(), history);
                    return null;
                }));
            }

            // when
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
            pool.shutdown();

            // then the last writer saw every fork
            assertEquals(forks, history.readTestRunHistory().get(0).getNumSuitesRan());
        }
    }
}
