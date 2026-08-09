package org.tiatesting.core.distributed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.persistence.DataStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a test selection into a persisted distributed run plan - the point where balancing
 * ({@link TestGroupBalancer}) and persistence ({@link DataStore#persistDistributedRunPlan})
 * finally meet. Stage 4b's Maven goal and Gradle task each call {@link #plan} once per build and
 * write the returned {@link DistributedRunPlanSummary} to disk and the console; nothing in this
 * class touches a filesystem or a build-tool API, which is what keeps it unit-testable against a
 * real embedded H2 datastore without either of those.
 *
 * <p>Because {@link DataStore#persistDistributedRunPlan} clears the previous run's rows in the
 * same transaction as the new plan's insert, {@link #plan} reads and logs the previous run's
 * unfinished groups before that write happens - once the write runs, that evidence is gone and an
 * abandoned run leaves no trace beyond the log line this class emits.
 */
public final class DistributedRunPlanner {

    private static final Logger log = LoggerFactory.getLogger(DistributedRunPlanner.class);

    private final DataStore dataStore;
    private final DistributedRunConfig config;

    /**
     * Build a planner bound to one datastore and one validated run configuration.
     *
     * @param dataStore the datastore the plan is read from and written to; must be reachable by
     *                  every runner in the distributed run
     * @param config the validated run configuration driving the run id and the group-count /
     *               target-run-time mode the balancer uses
     */
    public DistributedRunPlanner(DataStore dataStore, DistributedRunConfig config) {
        this.dataStore = dataStore;
        this.config = config;
    }

    /**
     * Turn a test selection into a persisted, claimable distributed run plan.
     *
     * <p>Runs, in order: (1) warns about any previous run's groups that never reached {@code
     * COMPLETED}, since the persist step below clears them; (2) weights the selection's suites
     * via {@link TestGroupBalancer#suiteWeights}; (3) balances those weights into groups, using
     * {@link TestGroupBalancer#balanceIntoGroups} for a fixed group count or {@link
     * TestGroupBalancer#balanceForTargetRunTime} for a target run time, depending on {@code
     * config}'s mode; (4) warns if the balancer could not meet a configured target; (5) projects
     * the result onto the persisted {@link DistributedRunPlan} types; (6) persists the plan, which
     * clears the previous run's rows in the same transaction; and (7) returns a summary of what
     * was persisted.
     *
     * @param selection the test selection to split across runners; its {@code testsToRun} is what
     *                  the persisted plan's suite count is checked against
     * @param branch the VCS branch the run is planned against
     * @param commitValue the VCS commit the run is planned against
     * @param collectingCoverage whether this run will collect coverage, and therefore pay the
     *                           per-suite mapping overhead when weighting suites for balancing
     * @param createdAtMs the UTC epoch millis to record as the plan's creation time; supplied by
     *                    the caller rather than read from the clock here, so tests can assert the
     *                    persisted value exactly instead of tolerating whatever the clock said
     * @return a summary of the persisted plan, suitable for writing to {@code tia-run-plan.json}
     *         and the console
     * @throws IllegalStateException if the number of suites carried by the persisted plan does not
     *                                equal {@code selection.getTestsToRun().size()} - meaning
     *                                suites were lost while building the plan and the build would
     *                                otherwise silently skip them
     */
    public DistributedRunPlanSummary plan(TestSelectorResult selection, String branch,
                                           String commitValue, boolean collectingCoverage,
                                           long createdAtMs) {
        warnAboutIncompletePreviousRuns();

        Map<String, Long> weights = TestGroupBalancer.suiteWeights(
                selection.getSelectedTestRunTimesMs(), selection.getMappingOverheadMs(),
                collectingCoverage);

        GroupingResult result = config.isStaticGroups()
                ? TestGroupBalancer.balanceIntoGroups(weights, config.getGroupCount())
                : TestGroupBalancer.balanceForTargetRunTime(weights, config.getTargetRunTimeMs(),
                        config.getMaxGroups());

        warnIfTargetMissed(result);

        DistributedRunPlan runPlan = projectPlan(result, branch, commitValue, createdAtMs);
        int selectedSuiteCount = countSuites(runPlan);
        if (selectedSuiteCount != selection.getTestsToRun().size()) {
            throw new IllegalStateException("distributed run '" + config.getRunId()
                    + "' plan carries " + selectedSuiteCount + " suite(s) but the selection chose "
                    + selection.getTestsToRun().size()
                    + "; suites were lost while building the plan");
        }

        dataStore.persistDistributedRunPlan(runPlan);

        return new DistributedRunPlanSummary(config.getRunId(), branch, commitValue,
                result.getGroupCount(), runPlan.getRun().getTargetRunTimeMs(), result.isTargetMet(),
                result.isClampedToMaxGroups(), result.isSingleSuiteExceedsTarget(),
                result.getTotalEstimatedMs(), selectedSuiteCount);
    }

    /**
     * Read every distributed run currently in the plan tables and log a WARN naming, by number,
     * any group that never reached {@code COMPLETED} - before this build's plan write clears those
     * rows in the same transaction as its insert. A run whose every group already completed is an
     * ordinary supersession and is not warned about, since it leaves no abandoned work behind; a
     * run with at least one incomplete group is the one case where this log line is the only trace
     * the abandoned work leaves, which is why the group numbers (not just "a previous run was
     * superseded") are named explicitly.
     */
    private void warnAboutIncompletePreviousRuns() {
        for (DistributedRun previousRun : dataStore.readAllDistributedRuns()) {
            List<DistributedRunGroup> groups = dataStore.readDistributedRunGroups(previousRun.getRunId());
            List<String> incomplete = new ArrayList<>();
            for (DistributedRunGroup group : groups) {
                if (group.getStatus() != DistributedRunGroupStatus.COMPLETED) {
                    incomplete.add("group " + group.getGroupNumber() + " (" + group.getStatus() + ")");
                }
            }
            if (!incomplete.isEmpty()) {
                log.warn("Distributed run '{}' is being superseded before completing - {} did not "
                                + "reach COMPLETED: {}",
                        previousRun.getRunId(),
                        incomplete.size() == 1 ? "this group" : "these groups",
                        incomplete);
            }
        }
    }

    /**
     * Log a WARN when the balancer could not meet the configured target run time, naming which
     * configuration lever - or levers, since both can apply at once - would help close the gap.
     * Static-groups mode always reports {@code targetMet == true} since it has no target to miss,
     * so this is a no-op in that mode.
     *
     * @param result the balancer's outcome for this plan
     */
    private void warnIfTargetMissed(GroupingResult result) {
        if (result.isTargetMet()) {
            return;
        }
        StringBuilder reasons = new StringBuilder();
        if (result.isClampedToMaxGroups()) {
            reasons.append("raising tiaDistributedMaxGroups would allow more groups; ");
        }
        if (result.isSingleSuiteExceedsTarget()) {
            reasons.append("a single suite is longer than the whole target, so no group count "
                    + "can fix it; ");
        }
        log.warn("Distributed run '{}' did not meet its target run time of {}ms - the heaviest "
                        + "group is {}ms. {}",
                config.getRunId(), config.getTargetRunTimeMs(), result.getHeaviestGroupMs(),
                reasons.toString());
    }

    /**
     * Project the balancer's grouping result onto the persisted plan types: one {@link
     * DistributedRun}, one PENDING {@link DistributedRunGroup} per group, and the suite-name
     * assignment keyed by group number. This is the one place a suite could be dropped between
     * selection and the persisted plan, which is why {@link #plan} re-counts the projected suites
     * against the selection immediately after this method returns.
     *
     * @param result the balancer's outcome, whose groups and their suite names are copied onto the
     *               persisted types
     * @param branch the VCS branch the run is planned against
     * @param commitValue the VCS commit the run is planned against
     * @param createdAtMs the UTC epoch millis to record as the plan's creation time
     * @return the validated plan, ready to persist
     */
    private DistributedRunPlan projectPlan(GroupingResult result, String branch, String commitValue,
                                            long createdAtMs) {
        Long targetRunTimeMs = config.isStaticGroups() ? null : config.getTargetRunTimeMs();
        DistributedRun run = DistributedRun.open(config.getRunId(), branch, commitValue,
                result.getGroupCount(), targetRunTimeMs, result.getTotalEstimatedMs(), createdAtMs);

        List<DistributedRunGroup> groups = new ArrayList<>(result.getGroupCount());
        Map<Integer, List<String>> suitesByGroup = new HashMap<>();
        for (SuiteGroup suiteGroup : result.getGroups()) {
            groups.add(DistributedRunGroup.pending(config.getRunId(), suiteGroup.getGroupNumber(),
                    suiteGroup.getEstimatedMs()));
            suitesByGroup.put(suiteGroup.getGroupNumber(), suiteGroup.getSuiteNames());
        }

        return new DistributedRunPlan(run, groups, suitesByGroup);
    }

    /**
     * Count the suites carried by a projected plan, summing each group's suite list. Used as the
     * actual side of the suite-conservation check in {@link #plan}; {@link DistributedRunPlan}'s
     * own constructor already guarantees no suite is counted under more than one group, so this
     * sum equals the number of distinct suites in the plan.
     *
     * @param runPlan the plan to count
     * @return the total number of suites assigned across all of the plan's groups
     */
    private static int countSuites(DistributedRunPlan runPlan) {
        int count = 0;
        for (List<String> suites : runPlan.getSuitesByGroup().values()) {
            count += suites.size();
        }
        return count;
    }
}
