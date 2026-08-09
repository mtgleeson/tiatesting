package org.tiatesting.core.distributed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.DistributedRunStatus;
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
     * <p>Runs, in order: (1) refuses to plan when {@code selection} carries no stored mapping,
     * since distributing "run everything" across runners is meaningless; (2) warns about any
     * previous run's groups that never reached {@code COMPLETED}, since the persist step below
     * clears them; (3) weights the selection's suites and balances them into groups via {@link
     * #balance}; (4) projects the result onto the persisted {@link DistributedRunPlan} types; (5)
     * persists the plan, which clears the previous run's rows in the same transaction; and (6)
     * returns a summary of what was persisted.
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
     * @throws IllegalStateException if {@code selection.isRunAllTests()} is true, meaning there is
     *                                no stored mapping for this branch yet; or if the number of
     *                                suites carried by the persisted plan does not equal {@code
     *                                selection.getTestsToRun().size()} - meaning suites were lost
     *                                while building the plan and the build would otherwise
     *                                silently skip them
     */
    public DistributedRunPlanSummary plan(TestSelectorResult selection, String branch,
                                           String commitValue, boolean collectingCoverage,
                                           long createdAtMs) {
        if (selection.isRunAllTests()) {
            throw new IllegalStateException("distributed run '" + config.getRunId()
                    + "' has no stored mapping for this branch yet, so there is nothing to split "
                    + "across runners. Run one non-distributed build first to seed the mapping, "
                    + "then retry the distributed run.");
        }

        warnAboutIncompletePreviousRuns();

        GroupingResult result = balance(selection, collectingCoverage, config.getGroupCount(),
                config.getTargetRunTimeMs(), config.getMaxGroups());

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
                result.getTotalEstimatedMs(), result.getHeaviestGroupMs(), selectedSuiteCount);
    }

    /**
     * Weight a selection's suites and balance them into groups, warning if a configured target
     * run time was missed - the whole grouping decision {@link #plan} makes before it persists
     * anything. Exposed as a separate, static, non-persisting method so a caller that does not
     * want to create a plan - the {@code select-tests} grouping preview being the motivating case
     * - can see the group count and average group time the balancer would choose without a
     * distributed run id, which a preview does not have and {@link DistributedRunConfig} would
     * otherwise force it to supply.
     *
     * <p>Mirrors {@link DistributedRunConfig}'s group-count / target-run-time mode exactly (one of
     * {@code groupCount} or {@code targetRunTimeMs} must be set, {@code maxGroups} only applies
     * alongside {@code targetRunTimeMs}) but validates that shape itself rather than requiring a
     * validated config, since building one requires a run id this method must not need.
     *
     * @param selection the test selection to balance; its per-suite run-time estimate and mapping
     *                  overhead drive the weights the balancer packs by
     * @param collectingCoverage whether the previewed or planned run will collect coverage, and
     *                           therefore pay the per-suite mapping overhead when weighting suites
     * @param groupCount the fixed number of groups to split into, or null to balance for a target
     *                    run time instead
     * @param targetRunTimeMs the target wall-clock run time in ms, or null to use a fixed group
     *                        count instead
     * @param maxGroups an optional ceiling on the group count, used only alongside {@code
     *                  targetRunTimeMs}; null for no ceiling
     * @return the balancer's grouping result; nothing is persisted
     * @throws IllegalArgumentException if neither or both of {@code groupCount} and {@code
     *                                  targetRunTimeMs} are set
     */
    public static GroupingResult balance(TestSelectorResult selection, boolean collectingCoverage,
                                          Integer groupCount, Long targetRunTimeMs,
                                          Integer maxGroups) {
        if ((groupCount == null) == (targetRunTimeMs == null)) {
            throw new IllegalArgumentException(
                    "exactly one of groupCount or targetRunTimeMs must be set");
        }

        Map<String, Long> weights = TestGroupBalancer.suiteWeights(
                selection.getSelectedTestRunTimesMs(), selection.getMappingOverheadMs(),
                collectingCoverage);

        GroupingResult result = groupCount != null
                ? TestGroupBalancer.balanceIntoGroups(weights, groupCount)
                : TestGroupBalancer.balanceForTargetRunTime(weights, targetRunTimeMs, maxGroups);

        warnIfTargetMissed(result, targetRunTimeMs);

        return result;
    }

    /**
     * Read every distributed run currently in the plan tables and log a WARN for any run that has
     * not reached {@code SEALED} - before this build's plan write clears those rows in the same
     * transaction as its insert. A run that reached {@code SEALED} is an ordinary supersession and
     * is not warned about, since it completed cleanly and left no abandoned work behind.
     *
     * <p>An unsealed run is warned about in one of two ways: when at least one group never reached
     * {@code COMPLETED}, the group numbers are named explicitly, since this log line is the only
     * trace that abandoned work leaves once the persist below clears the rows; when every group
     * did reach {@code COMPLETED} but the run itself is still not {@code SEALED}, the sealer died
     * after the last group finished - the build still failed and its rows are still being deleted,
     * so it is warned about too, distinctly, since there are no incomplete groups to name.
     */
    private void warnAboutIncompletePreviousRuns() {
        for (DistributedRun previousRun : dataStore.readAllDistributedRuns()) {
            List<DistributedRunGroup> groups = dataStore.readDistributedRunGroups(previousRun.getRunId());
            List<String> incomplete = incompleteGroupsToWarnAbout(previousRun, groups);
            if (incomplete == null) {
                continue;
            }

            if (incomplete.isEmpty()) {
                log.warn("Distributed run '{}' is being superseded before it sealed, even though "
                                + "every group reached COMPLETED - the sealer may have failed "
                                + "after the last group finished.",
                        previousRun.getRunId());
            } else {
                log.warn("Distributed run '{}' is being superseded before completing - {} did not "
                                + "reach COMPLETED: {}",
                        previousRun.getRunId(),
                        incomplete.size() == 1 ? "this group" : "these groups",
                        incomplete);
            }
        }
    }

    /**
     * Decide whether a previous run warrants a warning before {@link #plan}'s persist clears it,
     * and if so, which groups (if any) never reached {@code COMPLETED}. Package-private and
     * static so the decision {@link #warnAboutIncompletePreviousRuns} exists to make is
     * unit-testable directly against the three cases that matter - sealed, unsealed with
     * incomplete groups, and unsealed with every group complete - without a log-capture harness.
     *
     * @param previousRun the previous run being read before this build's plan clears it
     * @param groups the previous run's groups
     * @return {@code null} if {@code previousRun} reached {@code SEALED} and needs no warning at
     *         all, since it completed cleanly; otherwise the {@code "group N (STATUS)"}
     *         description of each group that did not reach {@code COMPLETED}, in group order - an
     *         empty (non-null) list means every group completed but the run itself never sealed,
     *         which still warrants a warning
     */
    static List<String> incompleteGroupsToWarnAbout(DistributedRun previousRun,
                                                     List<DistributedRunGroup> groups) {
        if (previousRun.getStatus() == DistributedRunStatus.SEALED) {
            return null;
        }
        List<String> incomplete = new ArrayList<>();
        for (DistributedRunGroup group : groups) {
            if (group.getStatus() != DistributedRunGroupStatus.COMPLETED) {
                incomplete.add("group " + group.getGroupNumber() + " (" + group.getStatus() + ")");
            }
        }
        return incomplete;
    }

    /**
     * Log a WARN when the balancer could not meet the configured target run time, naming which
     * configuration lever - or levers, since both can apply at once - would help close the gap.
     * Static-groups mode always reports {@code targetMet == true} since it has no target to miss,
     * so this is a no-op in that mode.
     *
     * @param result the balancer's outcome for this plan
     * @param targetRunTimeMs the configured target run time in ms; only read when {@code result}
     *                        reports the target was missed, so callers in static-groups mode (no
     *                        target) may pass the value that mode carries (null) safely
     */
    private static void warnIfTargetMissed(GroupingResult result, Long targetRunTimeMs) {
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
        log.warn("Distributed run planning did not meet its target run time of {}ms - the "
                        + "heaviest group is {}ms. {}",
                targetRunTimeMs, result.getHeaviestGroupMs(), reasons.toString());
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
