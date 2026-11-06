package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.SelectionMode;
import org.tiatesting.core.model.TestRunContribution;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers a distributed group's figures being derived from its runner JVMs' contributions, so a
 * runner that uses several JVMs reports the group's total rather than its largest JVM's share.
 */
class DistributedRunGroupFiguresTest {

    @TempDir
    File tempDir;

    private JdbcDataStore store;

    /**
     * Plan a one-group run over suites A, B and C and claim its group as {@code runner-0}.
     */
    @BeforeEach
    void setUp() {
        store = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(
                H2ConnectionSettings.embedded(tempDir.getAbsolutePath())), BranchSchema.schemaName("main", null));
        store.getTiaData();
        DistributedRun run = DistributedRun.open("run-1", "main", "c1", 1, 1, null, 1000L, 1234L,
                SelectionMode.SELECTIVE, null);
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Arrays.asList("A", "B", "C"));
        store.persistDistributedRunPlan(new DistributedRunPlan(run,
                Collections.singletonList(DistributedRunGroup.pending("run-1", 0, 1000L)), suites, null));
        store.claimNextPendingGroup("run-1", "runner-0", 2000L);
    }

    /**
     * Close the store so its embedded H2 database releases its file lock.
     */
    @AfterEach
    void tearDown() {
        store.close();
    }

    /**
     * Build one runner JVM's contribution to group 0.
     *
     * @param jvmId the JVM's id
     * @param rerun whether the contribution is the JVM's reruns
     * @param ran how many suites it ran
     * @param timeMs its plan time
     * @param rerunSuites the suites a rerun contribution retried
     * @param failed the suites that failed
     * @return the contribution
     */
    private static TestRunContribution contribution(String jvmId, boolean rerun, int ran, long timeMs,
                                                    List<String> rerunSuites, String... failed) {
        return new TestRunContribution("run-1", jvmId, rerun, 0, ran, new HashSet<>(Arrays.asList(failed)),
                new HashSet<>(rerunSuites), timeMs, 0L, 2000L, 2000L + timeMs);
    }

    /**
     * Build one runner JVM's first-attempt contribution to group 0.
     *
     * @param jvmId the JVM's id
     * @param ran how many suites it ran
     * @param timeMs its plan time
     * @param failed the suites that failed
     * @return the contribution
     */
    private static TestRunContribution fork(String jvmId, int ran, long timeMs, String... failed) {
        return contribution(jvmId, false, ran, timeMs, Collections.<String>emptyList(), failed);
    }

    @Test
    void severalForksOfOneRunnerAddUp() {
        // given two Surefire forks reporting against the runner's one group
        store.reportGroupProgress("run-1", 0, "runner-0", new HashSet<>(Arrays.asList("A")),
                Collections.singletonList(fork("jvm-a", 1, 1000L, "A")));

        // when
        store.reportGroupProgress("run-1", 0, "runner-0", new HashSet<>(Arrays.asList("B", "C")),
                Collections.singletonList(fork("jvm-b", 2, 1500L)));

        // then
        DistributedRunGroup group = store.readDistributedRunGroups("run-1").get(0);
        assertEquals(3, group.getSuitesRan());
        assertEquals(1, group.getSuitesFailed());
        assertEquals(Long.valueOf(2500L), group.getActualDurationMs());
        assertEquals(Long.valueOf(1500L), group.getWallClockMs());
        assertEquals(3, group.getSuitesObserved());
    }

    @Test
    void aLaterPlanOfTheSameJvmReplacesItsRow() {
        // given one JVM's first plan
        store.reportGroupProgress("run-1", 0, "runner-0", new HashSet<>(Arrays.asList("A")),
                Collections.singletonList(fork("jvm-a", 1, 1000L)));

        // when the same JVM restates its running totals after a second plan
        store.reportGroupProgress("run-1", 0, "runner-0", new HashSet<>(Arrays.asList("A", "B")),
                Collections.singletonList(fork("jvm-a", 2, 1800L)));

        // then
        DistributedRunGroup group = store.readDistributedRunGroups("run-1").get(0);
        assertEquals(2, group.getSuitesRan());
        assertEquals(Long.valueOf(1800L), group.getActualDurationMs());
    }

    @Test
    void aPassingRetryLeavesTheFailedCountButAddsItsTime() {
        // given a first attempt that failed A, then a retry JVM that re-ran A and passed it
        store.reportGroupProgress("run-1", 0, "runner-0", new HashSet<>(Arrays.asList("A", "B")),
                Collections.singletonList(fork("jvm-a", 2, 2000L, "A")));

        // when
        store.reportGroupProgress("run-1", 0, "runner-0", new HashSet<>(Arrays.asList("A")),
                Collections.singletonList(contribution("jvm-r", true, 1, 500L, Collections.singletonList("A"))));

        // then the retry counts no new suite and clears the failure, but its time is real time
        DistributedRunGroup group = store.readDistributedRunGroups("run-1").get(0);
        assertEquals(2, group.getSuitesRan());
        assertEquals(0, group.getSuitesFailed());
        assertEquals(Long.valueOf(2500L), group.getActualDurationMs());
    }

    @Test
    void aGroupNoJvmReportedHasNoFigures() {
        // given only the claim

        // when
        DistributedRunGroup group = store.readDistributedRunGroups("run-1").get(0);

        // then
        assertNull(group.getActualDurationMs());
        assertNull(group.getWallClockMs());
        assertEquals(0, group.getSuitesRan());
    }

    @Test
    void aSupersededRunnerWritesNoContribution() {
        // given a runner key that does not hold the group

        // when
        boolean applied = store.reportGroupProgress("run-1", 0, "someone-else", Collections.<String>emptySet(),
                Collections.singletonList(fork("jvm-x", 1, 100L)));

        // then
        assertFalse(applied);
        assertTrue(store.readTestRunContributions("run-1").isEmpty());
    }
}
