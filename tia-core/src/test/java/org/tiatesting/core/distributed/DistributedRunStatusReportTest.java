package org.tiatesting.core.distributed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover {@link DistributedRunStatusReport}: the read-only report behind the Maven {@code
 * dist-status} goal and the Gradle {@code tia-dist-status} task. The report exists to answer why a
 * run has not sealed, so these tests are mostly about the states that block a seal - a group nobody
 * claimed, a runner still short of its assigned suites, and a run whose groups all completed while
 * the run row stayed OPEN - rather than about the happy path, which is the least interesting thing
 * the command prints.
 *
 * <p>Uses a real embedded-H2 {@link JdbcDataStore} rather than a fake, following {@link
 * DistributedRunnerAssignmentTest}'s fixture: every state under test is one the plan tables can
 * genuinely be in, reached through the same claim/progress/complete/seal calls the runners use, so
 * a state the datastore could never produce cannot be asserted here by accident.
 */
class DistributedRunStatusReportTest {

    private static final String LINE_SEP = "\n";

    /** A fixed "now" so every rendered age and elapsed time in these tests is exact. */
    private static final long NOW_MS = 1_000_000L;

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database in a new temp directory and bootstrap its schema, so each
     * test starts with no distributed run planned and cannot see another test's rows.
     *
     * @throws Exception if the temp directory cannot be created or schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-distributed-status-", "");
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
     * Verify a branch that has never planned a distributed run gets an explanation and the command
     * that would create one, rather than an empty report or a stack trace - this is what a developer
     * running the command for the first time sees.
     */
    @Test
    void shouldExplainThatNoRunHasBeenPlannedWhenThePlanTablesAreEmpty() {
        // given - nothing planned

        // when
        String report = DistributedRunStatusReport.format(dataStore, null, false, NOW_MS, LINE_SEP);

        // then
        assertTrue(report.contains("No distributed run has been planned on this branch"), report);
        assertTrue(report.contains("Maven dist-plan, Gradle tia-dist-plan"), report);
    }

    /**
     * Verify that asking for a run id the plan tables do not hold names the ids they do hold. The
     * usual cause is a later build's plan write having cleared the requested run, and a user told
     * only "not found" has no way to see that a newer id took its place.
     */
    @Test
    void shouldNameTheRunsPresentWhenTheRequestedRunIdIsNotOneOfThem() {
        // given
        persistPlan("build-99", "commit-1", twoGroups(), false);

        // when
        String report = DistributedRunStatusReport.format(dataStore, "build-1", false, NOW_MS, LINE_SEP);

        // then
        assertTrue(report.contains("No distributed run is planned under run id 'build-1'"), report);
        assertTrue(report.contains("superseded by a later plan"), report);
        assertTrue(report.contains("'build-99' (OPEN, 2 group(s), commit commit-1)"), report);
    }

    /**
     * Verify the run-level block reports the run's identity, what it was planned against, how far
     * through it is and that it has not sealed - the summary a user reads before looking at any
     * individual runner.
     */
    @Test
    void shouldReportTheRunsIdentityProgressAndUnsealedState() {
        // given
        persistPlan("build-1", "commit-abc", twoGroups(), false);
        claimAndComplete("build-1", 0, "runner-a", 2, 100L);

        // when
        String report = DistributedRunStatusReport.format(dataStore, "build-1", false, NOW_MS, LINE_SEP);

        // then
        assertTrue(report.contains("Distributed run 'build-1'"), report);
        assertTrue(report.contains("Branch:     main"), report);
        assertTrue(report.contains("Commit:     commit-abc"), report);
        assertTrue(report.contains("Status:     OPEN - 1 of 2 group(s) completed"), report);
        assertTrue(report.contains("Sealed:     not sealed (open for"), report);
    }

    /**
     * Verify a group nobody claimed is named as unclaimable rather than merely listed as incomplete.
     * A PENDING group means the pipeline fanned out fewer jobs than the plan has groups, so nothing
     * will ever complete it - a permanently stuck run, not one still in progress, and the two need
     * telling apart by anyone deciding whether to wait or to re-plan.
     */
    @Test
    void shouldNameAPendingGroupAsOneNothingWillEverComplete() {
        // given - group 0 is claimed and finished, group 1 was never claimed by any runner
        persistPlan("build-1", "commit-abc", twoGroups(), false);
        claimAndComplete("build-1", 0, "runner-a", 2, 100L);

        // when
        String report = DistributedRunStatusReport.format(dataStore, "build-1", false, NOW_MS, LINE_SEP);

        // then
        assertTrue(report.contains("This run is not sealed yet. Outstanding:"), report);
        assertTrue(report.contains("Group 1: PENDING - no runner has claimed it"), report);
        assertTrue(report.contains("nothing will ever complete this one and the run cannot seal"),
                report);
        assertFalse(report.contains("Group 0:"), "a completed group is not outstanding: " + report);
    }

    /**
     * Verify a claimed group still working is reported against the number that actually gates its
     * completion: observed suites versus assigned suites. That comparison is the completion guard in
     * {@code DataStore.completeGroup}, so it is the only figure that says whether the runner is on
     * course to close its group or is stuck short of it.
     */
    @Test
    void shouldReportAClaimedGroupsProgressAgainstItsAssignedSuiteCount() {
        // given - runner-b has claimed group 0 and reported one of its two suites so far
        persistPlan("build-1", "commit-abc", twoGroups(), false);
        dataStore.claimNextPendingGroup("build-1", "runner-b", NOW_MS - 30_000L);
        dataStore.reportGroupProgress("build-1", 0, "runner-b", 5_000L, 1, 0, 1, 4_000L);

        // when
        String report = DistributedRunStatusReport.format(dataStore, "build-1", false, NOW_MS, LINE_SEP);

        // then
        assertTrue(report.contains("Group 0: CLAIMED by 'runner-b' (running for 30s)"), report);
        assertTrue(report.contains("observed 1 of 2 assigned suite(s)"), report);
    }

    /**
     * Verify a seed run's group reports its assigned suites as {@code all}, not {@code 0}. The plan
     * of a seed run deliberately carries no suite names - there is no stored mapping to draw them
     * from - while its runner executes every suite it discovers. Rendering the raw zero would repeat
     * the seed-run claim log's mistake of reporting the one run that executes the entire suite as
     * having nothing to do.
     */
    @Test
    void shouldReportASeedRunsGroupAsCoveringEverySuiteRatherThanNone() {
        // given - a seed run: one group, no assigned suite names, flagged on the run row
        persistPlan("build-1", "commit-abc",
                singleGroup(Collections.<String>emptyList()), true);

        // when
        String report = DistributedRunStatusReport.format(dataStore, "build-1", true, NOW_MS, LINE_SEP);

        // then
        assertTrue(report.contains("Seed run:   yes"), report);
        assertTrue(report.contains("0     | PENDING | -      | all "),
                "the assigned column should read 'all': " + report);
        assertFalse(report.contains("Group 0 (0):"), report);
        assertTrue(report.contains("a seed run's group covers every suite its runner discovers"),
                report);
    }

    /**
     * Verify a nothing-impacted run - whose single group is just as empty as a seed run's, but which
     * is not flagged as one - reports its assigned count as the literal 0. This is the trap the seed
     * flag exists for: the two runs have identical group shapes, and only the persisted flag
     * separates a runner that will execute everything from one that will execute nothing.
     */
    @Test
    void shouldReportANothingImpactedRunsEmptyGroupAsZeroRatherThanAll() {
        // given - nothing was impacted, so the group is empty, but this is not a seed run
        persistPlan("build-1", "commit-abc",
                singleGroup(Collections.<String>emptyList()), false);

        // when
        String report = DistributedRunStatusReport.format(dataStore, "build-1", true, NOW_MS, LINE_SEP);

        // then
        assertFalse(report.contains("Seed run:"), report);
        assertTrue(report.contains("0     | PENDING | -      | 0 "),
                "an empty non-seed group's assigned count is 0, not 'all': " + report);
        assertTrue(report.contains("none - the plan assigned this group no suites"), report);
    }

    /**
     * Verify a group that has reported nothing dashes its measurement columns instead of showing
     * zeros. A PENDING group's zeros are indistinguishable from a runner that took the group and ran
     * none of it, and those are opposite situations.
     */
    @Test
    void shouldDashTheMeasurementColumnsOfAGroupThatHasReportedNothing() {
        // given
        persistPlan("build-1", "commit-abc", twoGroups(), false);

        // when
        String report = DistributedRunStatusReport.format(dataStore, "build-1", false, NOW_MS, LINE_SEP);

        // then - every column after the assigned count is dashed for both unclaimed groups
        assertTrue(report.contains("0     | PENDING | -      | 2        | -        | -   | -      "),
                report);
        assertTrue(report.contains("1     | PENDING | -      | 1        | -        | -   | -      "),
                report);
    }

    /**
     * Verify a sealed run reports who sealed it and how long after the plan, and prints no
     * outstanding block - a sealed run has nothing outstanding by definition, and an "Outstanding:"
     * heading with nothing under it would read as a defect.
     */
    @Test
    void shouldReportASealedRunWithItsSealerAndNoOutstandingBlock() {
        // given - both groups completed and runner-a won the seal
        persistPlan("build-1", "commit-abc", twoGroups(), false);
        claimAndComplete("build-1", 0, "runner-a", 2, 100L);
        claimAndComplete("build-1", 1, "runner-b", 1, 200L);
        assertTrue(dataStore.electSealer("build-1", "runner-a", NOW_MS - 60_000L));
        dataStore.markDistributedRunSealed("build-1");

        // when
        String report = DistributedRunStatusReport.format(dataStore, "build-1", false, NOW_MS, LINE_SEP);

        // then
        assertTrue(report.contains("Status:     SEALED - 2 of 2 group(s) completed"), report);
        assertTrue(report.contains("Sealed:     by runner 'runner-a' at "), report);
        assertTrue(report.contains(" after the plan)"), report);
        assertFalse(report.contains("Outstanding"), report);
        assertFalse(report.contains("not sealed"), report);
    }

    /**
     * Verify the one anomaly the outstanding list cannot express: every group completed, so nothing
     * is outstanding, yet the run row is still OPEN - meaning the completion barrier was reached and
     * the seal itself failed. Left to the generic path this would print an empty outstanding block
     * and read as a healthy run, when in fact the build's work is about to be thrown away.
     */
    @Test
    void shouldNameASealThatDidNotHappenWhenEveryGroupCompletedButTheRunIsStillOpen() {
        // given - both groups completed, but no sealer was ever elected
        persistPlan("build-1", "commit-abc", twoGroups(), false);
        claimAndComplete("build-1", 0, "runner-a", 2, 100L);
        claimAndComplete("build-1", 1, "runner-b", 1, 200L);

        // when
        String report = DistributedRunStatusReport.format(dataStore, "build-1", false, NOW_MS, LINE_SEP);

        // then
        assertTrue(report.contains("Every group has completed but this run is still OPEN"), report);
        assertTrue(report.contains("the seal did not happen"), report);
        assertTrue(report.contains("the next build's plan step will clear these rows and redo the "
                + "run's work"), report);
    }

    /**
     * Verify each group's assigned suite names are listed when the caller asks for them, and are
     * left out when it does not - the list is unbounded in size, so it has to be opt-in even though
     * the names are read either way.
     */
    @Test
    void shouldListEachGroupsAssignedSuiteNamesOnlyWhenAsked() {
        // given
        persistPlan("build-1", "commit-abc", twoGroups(), false);

        // when
        String withNames = DistributedRunStatusReport.format(dataStore, "build-1", true, NOW_MS, LINE_SEP);
        String withoutNames = DistributedRunStatusReport.format(dataStore, "build-1", false, NOW_MS,
                LINE_SEP);

        // then
        assertTrue(withNames.contains("Assigned suites:"), withNames);
        assertTrue(withNames.contains("Group 0 (2):"), withNames);
        assertTrue(withNames.contains("com.example.ATest"), withNames);
        assertTrue(withNames.contains("com.example.CTest"), withNames);
        assertFalse(withoutNames.contains("Assigned suites:"), withoutNames);
        assertFalse(withoutNames.contains("com.example.ATest"), withoutNames);
    }

    /**
     * Verify that with no run id given the most recently planned run is reported. Each plan write
     * normally clears the previous run, so this is the case a developer hits every time they run the
     * command without arguments.
     */
    @Test
    void shouldReportTheMostRecentlyPlannedRunWhenNoRunIdIsGiven() {
        // given - the second plan write supersedes the first
        persistPlan("build-1", "commit-abc", twoGroups(), false);
        persistPlan("build-2", "commit-def", twoGroups(), false);

        // when
        String report = DistributedRunStatusReport.format(dataStore, null, false, NOW_MS, LINE_SEP);

        // then
        assertTrue(report.contains("Distributed run 'build-2'"), report);
        assertFalse(report.contains("Distributed run 'build-1'"), report);
    }

    /**
     * Verify no negative duration is rendered when the report's clock is behind the timestamps it is
     * describing. CI runners and the planning host are different machines with independently drifting
     * clocks, so this is a real state, and "-1m 4s ago" would read as a Tia defect rather than as the
     * clock skew it is.
     */
    @Test
    void shouldNotRenderANegativeAgeWhenThisHostsClockIsBehindThePlannersClock() {
        // given - the run was planned, and claimed, in this host's future
        persistPlan("build-1", "commit-abc", twoGroups(), false);
        dataStore.claimNextPendingGroup("build-1", "runner-b", NOW_MS + 60_000L);

        // when - the report is rendered against a clock earlier than every persisted timestamp
        String report = DistributedRunStatusReport.format(dataStore, "build-1", false, NOW_MS - 500_000L,
                LINE_SEP);

        // then - every age and elapsed value is dropped rather than rendered negative
        assertFalse(report.contains(" ago)"), report);
        assertTrue(report.contains("Sealed:     not sealed" + LINE_SEP), report);
        assertTrue(report.contains("Group 0: CLAIMED by 'runner-b' - observed"), report);
    }

    /**
     * Persist a run plan with an exact, caller-chosen suite-to-group assignment and seed-run flag,
     * so a test asserts against the shape it wrote rather than one the balancer chose.
     *
     * @param runId the run identifier to plan under
     * @param commitValue the VCS commit the plan is pinned to
     * @param suitesByGroup the suite names each group number owns; group numbers must run from 0
     *                      upwards with no gaps, since the group rows are derived from this map
     * @param seedRun whether the planner recorded this run as a seed run
     */
    private void persistPlan(final String runId, final String commitValue,
                              final Map<Integer, List<String>> suitesByGroup, final boolean seedRun) {
        List<DistributedRunGroup> groups = new ArrayList<>();
        for (int groupNumber = 0; groupNumber < suitesByGroup.size(); groupNumber++) {
            groups.add(DistributedRunGroup.pending(runId, groupNumber, 1000L));
        }
        DistributedRun run = DistributedRun.open(runId, "main", commitValue, groups.size(), 5000L,
                1000L * groups.size(), NOW_MS - 120_000L, seedRun);
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(run, groups, suitesByGroup, null));
    }

    /**
     * Take the run's next pending group as the given runner, report enough observed suites to pass
     * the completion guard, and complete it - the sequence a healthy runner performs, so a test
     * reaches a COMPLETED group through the same writes a real build would rather than by inserting
     * one.
     *
     * @param runId the run whose next pending group is claimed
     * @param expectedGroupNumber the group number the claim is expected to yield, asserted so a test
     *                            reading a specific group's row cannot silently be pointed at another
     * @param runnerKey the identity to claim, report and complete under
     * @param suiteCount the number of suites to report as observed and ran; must be at least the
     *                   group's assigned suite count or the completion guard will refuse to close it
     * @param durationMs the measured test-execution time to report for the group
     */
    private void claimAndComplete(final String runId, final int expectedGroupNumber,
                                  final String runnerKey, final int suiteCount, final long durationMs) {
        DistributedRunGroup claimed = dataStore.claimNextPendingGroup(runId, runnerKey, NOW_MS - 90_000L);
        assertNotNull(claimed, "expected a pending group to claim for " + runnerKey);
        assertTrue(claimed.getGroupNumber() == expectedGroupNumber,
                "expected to claim group " + expectedGroupNumber + " but claimed "
                        + claimed.getGroupNumber());
        assertTrue(dataStore.reportGroupProgress(runId, expectedGroupNumber, runnerKey, durationMs,
                suiteCount, 0, suiteCount, durationMs));
        assertNotNull(dataStore.completeGroup(runId, expectedGroupNumber, runnerKey, NOW_MS - 80_000L),
                "expected group " + expectedGroupNumber + " to complete");
    }

    /**
     * Build the suite-to-group assignment used by most of these tests: group 0 owns {@code ATest}
     * and {@code BTest}, group 1 owns {@code CTest}, so the two groups have different assigned
     * counts and a test cannot pass by reading the wrong one.
     *
     * @return a two-group suite assignment
     */
    private static Map<Integer, List<String>> twoGroups() {
        Map<Integer, List<String>> suitesByGroup = new LinkedHashMap<>();
        suitesByGroup.put(0, Arrays.asList("com.example.ATest", "com.example.BTest"));
        suitesByGroup.put(1, Collections.singletonList("com.example.CTest"));
        return suitesByGroup;
    }

    /**
     * Build a single-group assignment carrying the given suite names, for the seed-run and
     * nothing-impacted cases whose plans both have exactly one group.
     *
     * @param suites the suite names group 0 owns; empty for both of those cases
     * @return a one-group suite assignment
     */
    private static Map<Integer, List<String>> singleGroup(final List<String> suites) {
        Map<Integer, List<String>> suitesByGroup = new LinkedHashMap<>();
        suitesByGroup.put(0, suites);
        return suitesByGroup;
    }
}
