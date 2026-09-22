package org.tiatesting.core.distributed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.DistributedRunStatus;
import org.tiatesting.core.persistence.DataStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Turns a test selection into a persisted distributed run plan - the point where balancing
 * ({@link TestGroupBalancer}) and persistence ({@link DataStore#persistDistributedRunPlan})
 * finally meet. The Maven {@code dist-plan} goal and the Gradle {@code tia-dist-plan} task each call {@link #plan} once
 * per build and write the returned {@link DistributedRunPlanSummary} to disk and the console;
 * nothing in this class touches a filesystem or a build-tool API, which is what keeps it
 * unit-testable against a real embedded H2 datastore without either of those.
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
     * <p>Runs, in order: (1) when {@code selection} carries no stored mapping for this branch,
     * logs why this build is a <b>seed run</b> - see {@link #logSeedRun} - since {@link #balance}
     * uses {@code seedTestSuiteProvider} to split that case across the configured group count by
     * even count, falling back to a single empty group only when the provider finds nothing on
     * disk; (2) warns about any previous run's groups that never reached {@code
     * COMPLETED}, since the persist step below clears them; (3) weights the selection's suites and
     * balances them into groups via {@link #balance}; (4) warns if the configured target run time
     * was missed - deliberately done here rather than inside {@link #balance}, since {@code
     * balance} is also the {@code select-tests} preview's entry point and a preview that logs
     * "planning did not meet its target" would claim a plan was created when nothing was; (5)
     * projects the result onto the persisted {@link DistributedRunPlan} types, carrying the
     * selection's library-impact drain result onto the run row; (6) persists the plan, which clears
     * the previous run's rows in the same transaction; (7) stages {@code selection}'s selection
     * breakdown under this run's id via {@link DataStore#persistDistributedRunSelectionDetails},
     * so the sealer can later copy it onto the build's single {@code tia_test_run_history} row;
     * and (8) returns a summary of what was persisted.
     *
     * <p>Step (5) is why the drain result matters here: {@code selection} was produced by a real
     * {@code TestSelector.selectTestsToIgnore} call, which has already drained the pending library
     * impact - deleting pending rows and advancing sequences - before this method is entered. That
     * drain cannot be repeated, and repeating it per-runner would race, so the plan row is the only
     * place its outstanding cleanup can survive this process exiting.
     *
     * <p>Step (7) deliberately keeps {@link DistributedRun} and {@link DistributedRunPlan}
     * unchanged rather than adding the breakdown as a constructor argument: those two types are
     * constructed at roughly 95 call sites across the codebase, and a run-id-keyed table staged by
     * a separate write reaches the same place - the sealer, keyed by run id - without touching any
     * of them.
     *
     * @param selection the test selection to split across runners; its {@code testsToRun} is what
     *                  the persisted plan's suite count is checked against, and its library-impact
     *                  drain result is stored on the run row for the stage that applies the cleanup
     * @param branch the VCS branch the run is planned against
     * @param commitValue the VCS commit the run is planned against
     * @param collectingCoverage whether this run will collect coverage, and therefore pay the
     *                           per-suite mapping overhead when weighting suites for balancing;
     *                           also read by {@link #logSeedRun} to warn when a seed run will not
     *                           actually record the mapping it exists to seed
     * @param createdAtMs the UTC epoch millis to record as the plan's creation time; supplied by
     *                    the caller rather than read from the clock here, so tests can assert the
     *                    persisted value exactly instead of tolerating whatever the clock said
     * @param seedTestSuiteProvider supplies the suite names found on disk for a seed run to split
     *                              across the configured groups; invoked only when {@code
     *                              selection.isRunAllTests()} is true, so a non-seed plan never
     *                              pays for the scan
     * @return a summary of the persisted plan, suitable for writing to {@code tia-run-plan.json}
     *         and the console; {@link DistributedRunPlanSummary#isSeedRun()} is true exactly when
     *         {@code selection.isRunAllTests()} was true
     * @throws IllegalStateException if the number of suites carried by the persisted plan does not
     *                                equal {@code selection.getTestsToRun().size()} on a non-seed
     *                                plan - meaning suites were lost while building the plan and the
     *                                build would otherwise silently skip them; never thrown on a
     *                                seed plan, whose suites come from {@code
     *                                seedTestSuiteProvider}, not the selection
     */
    public DistributedRunPlanSummary plan(TestSelectorResult selection, String branch,
                                           String commitValue, boolean collectingCoverage,
                                           long createdAtMs,
                                           Supplier<Set<String>> seedTestSuiteProvider) {
        boolean seedRun = selection.isRunAllTests();
        if (seedRun) {
            logSeedRun(collectingCoverage);
        }

        warnAboutIncompletePreviousRuns();

        GroupingResult result = balance(selection, collectingCoverage, config.getGroupCount(),
                config.getTargetRunTimeMs(), config.getMaxGroups(), seedTestSuiteProvider);
        warnIfTargetMissed(result, config.getTargetRunTimeMs());

        DistributedRunPlan runPlan = projectPlan(result, branch, commitValue, createdAtMs,
                selection.getLibraryImpactDrainResult(), seedRun);
        int selectedSuiteCount = countSuites(runPlan);
        // The conservation check compares the persisted plan against the selection's testsToRun,
        // which is empty on a seed run by definition - the seed's suites come from the disk scan,
        // not the selection - so the check would spuriously fire the moment a seed run fans out.
        // The disk scan is the seed run's source of truth, so it is skipped on the seed path.
        if (!seedRun && selectedSuiteCount != selection.getTestsToRun().size()) {
            throw new IllegalStateException("distributed run '" + config.getRunId()
                    + "' plan carries " + selectedSuiteCount + " suite(s) but the selection chose "
                    + selection.getTestsToRun().size()
                    + "; suites were lost while building the plan");
        }

        dataStore.persistDistributedRunPlan(runPlan);
        // Stages the selection breakdown under this run's id so the sealer can later copy it onto
        // the build's single tia_test_run_history row. Kept as a separate write rather than a new
        // field on DistributedRun / DistributedRunPlan, whose constructors are threaded through
        // roughly 95 call sites across the codebase; a seed run's selection carries
        // TestRunSelectionDetails.empty(), which is staged as-is.
        dataStore.persistDistributedRunSelectionDetails(config.getRunId(), selection.getSelectionDetails());

        // The one line that says what the pipeline must now do. The console summary and
        // tia-run-plan.json carry the same facts, but only for the plan step's own
        // output - this reaches whatever log a CI system actually keeps. A seed run has no run-time
        // data at all - see seedGroupingResult - so its ms figures are omitted entirely rather than
        // printed as zero, which would read as a measured (rather than absent) estimate.
        if (seedRun) {
            log.info("Distributed run '{}' planned for branch '{}' at commit '{}': {} suite(s) "
                            + "split across {} group(s) by even count - no run-time estimate yet "
                            + "(seed run). Start {} runner job(s).", config.getRunId(), branch,
                    commitValue, selectedSuiteCount, result.getGroupCount(), result.getGroupCount());
        } else {
            log.info("Distributed run '{}' planned for branch '{}' at commit '{}': {} suite(s) split "
                            + "across {} group(s), estimated {}ms in total with the heaviest group at "
                            + "{}ms. Start {} runner job(s).", config.getRunId(), branch, commitValue,
                    selectedSuiteCount, result.getGroupCount(), result.getTotalEstimatedMs(),
                    result.getHeaviestGroupMs(), result.getGroupCount());
        }

        return new DistributedRunPlanSummary(config.getRunId(), branch, commitValue,
                result.getGroupCount(), runPlan.getRun().getTargetRunTimeMs(), result.isTargetMet(),
                result.isClampedToMaxGroups(), result.isSingleSuiteExceedsTarget(),
                result.isFixedOverheadExceedsTarget(), result.getTotalEstimatedMs(),
                result.getHeaviestGroupMs(), selectedSuiteCount, seedRun);
    }

    /**
     * Log at INFO why this build's plan is a seed run: no stored mapping exists yet for this
     * branch, so {@link #balance} splits the suites found on disk across the configured group
     * count by even count instead of by duration, since there is no run-time data yet to balance
     * by; when nothing is found on disk it collapses to a single group with the whole suite. Also
     * logs a WARN naming {@code tiaUpdateDBMapping} when {@code collectingCoverage} is false, since
     * a seed run that does not collect coverage writes no mapping, leaving every subsequent build
     * stuck repeating the same seed run indefinitely.
     *
     * @param collectingCoverage whether this run will collect coverage and therefore write the
     *                           mapping the seed run exists to produce
     */
    private void logSeedRun(boolean collectingCoverage) {
        log.info("Distributed run '{}' has no stored mapping for this branch yet, so this build "
                        + "is a seed run: it will run every test and record the mapping the next "
                        + "build plans from. With no stored run times yet, its suites are split "
                        + "across groups by even count rather than by duration; when none are "
                        + "found on disk it collapses to a single group that runs everything.",
                config.getRunId());
        if (!collectingCoverage) {
            log.warn("Distributed run '{}' is a seed run, but tiaUpdateDBMapping is false, so "
                            + "this run will not write the mapping it exists to seed - the next "
                            + "build will be another seed run, and so on, until "
                            + "tiaUpdateDBMapping is enabled.",
                    config.getRunId());
        }
    }

    /**
     * Weight a selection's suites and balance them into groups - the whole grouping decision
     * {@link #plan} makes before it persists anything, minus the target-missed warning, which
     * {@link #plan} logs itself rather than this method (see its javadoc for why). Exposed as a
     * separate, static, non-persisting method so a caller that does not want to create a plan -
     * the {@code select-tests} grouping preview being the motivating case - can see the group
     * count and average group time the balancer would choose without a distributed run id, which
     * a preview does not have and {@link DistributedRunConfig} would otherwise force it to supply.
     *
     * <p>Mirrors {@link DistributedRunConfig}'s group-count / target-run-time mode exactly (one of
     * {@code groupCount} or {@code targetRunTimeMs} must be set, {@code maxGroups} only applies
     * alongside {@code targetRunTimeMs}) by delegating to {@link
     * DistributedRunConfig#validateGroupingShape} - the same check {@link
     * DistributedRunConfig#validated} runs - rather than a check of its own, so a config the
     * preview accepts can never be one the real plan then rejects.
     *
     * <p>When {@code selection.isRunAllTests()} is true - no stored mapping exists yet for this
     * branch - this method short-circuits to {@link #seedGroupingResult(Supplier, Integer,
     * Integer)}: the suites {@code seedTestSuiteProvider} finds on disk, split across the
     * configured group count by even count since there is no run-time data yet to balance by, or
     * a single empty group when nothing is found or no group count applies. The grouping shape is
     * still validated first, so a misconfigured {@code groupCount} / {@code targetRunTimeMs}
     * combination is still reported even on a seed run, ahead of the build that will actually need
     * it corrected.
     *
     * @param selection the test selection to balance; its per-suite run-time estimate and mapping
     *                  overhead drive the weights the balancer packs by, unless {@link
     *                  TestSelectorResult#isRunAllTests()} is true, in which case they are ignored
     * @param collectingCoverage whether the previewed or planned run will collect coverage, and
     *                           therefore pay the per-suite mapping overhead when weighting suites
     * @param groupCount the fixed number of groups to split into, or null to balance for a target
     *                    run time instead; on a seed run this (or {@code maxGroups}) is the count
     *                    the scanned suites are split across
     * @param targetRunTimeMs the target wall-clock run time in ms, or null to use a fixed group
     *                        count instead; ignored on a seed run
     * @param maxGroups an optional ceiling on the group count, used only alongside {@code
     *                  targetRunTimeMs}; null for no ceiling; on a seed run with no {@code
     *                  groupCount} this is the count the scanned suites are split across
     * @param seedTestSuiteProvider supplies the suite names found on disk for a seed run to split
     *                              across groups; invoked only when {@code
     *                              selection.isRunAllTests()} is true, so a non-seed balance never
     *                              pays for the scan
     * @return the balancer's grouping result; nothing is persisted and nothing is logged
     * @throws IllegalArgumentException if neither or both of {@code groupCount} and {@code
     *                                  targetRunTimeMs} are set; if {@code groupCount} is set and
     *                                  below 1; if {@code targetRunTimeMs} is set and not positive;
     *                                  if {@code maxGroups} is set and below 1; or if {@code
     *                                  maxGroups} is set together with a fixed {@code groupCount} -
     *                                  see {@link DistributedRunConfig#validateGroupingShape}
     */
    public static GroupingResult balance(TestSelectorResult selection, boolean collectingCoverage,
                                          Integer groupCount, Long targetRunTimeMs,
                                          Integer maxGroups,
                                          Supplier<Set<String>> seedTestSuiteProvider) {
        DistributedRunConfig.validateGroupingShape(groupCount, targetRunTimeMs, maxGroups);

        if (selection.isRunAllTests()) {
            return seedGroupingResult(seedTestSuiteProvider, groupCount, maxGroups);
        }

        Map<String, Long> weights = TestGroupBalancer.suiteWeights(
                selection.getSelectedTestRunTimesMs(), selection.getCaptureOverheadMs(),
                collectingCoverage);

        // Charged once per group rather than divided across them, and gated on the same flag the
        // capture overhead is: a run that does not collect coverage does not pay either. Zero until
        // a distributed build has measured it, which makes every figure below identical to what
        // Tia produced before the two-part model existed.
        long fixedOverheadMs = collectingCoverage ? selection.getFixedOverheadMs() : 0L;

        if (collectingCoverage) {
            log.debug("Distributed run grouping: weighting {} suite(s) with {}ms of coverage "
                            + "capture spread across them, and charging {}ms of fixed per-JVM "
                            + "overhead once to each non-empty group.{} The fixed part is "
                            + "deliberately kept out of the per-suite weights - it is the same on "
                            + "every group, so it cannot change which suites group together.",
                    weights.size(), selection.getCaptureOverheadMs(), fixedOverheadMs,
                    fixedOverheadMs == 0L
                            ? " No build has reported a per-group split yet, so the fixed part is"
                                    + " still zero and the whole overhead sits in the per-suite"
                                    + " figure; this run will supply the measurement when it seals."
                            : "");
        } else {
            log.debug("Distributed run grouping: weighting {} suite(s) by test time alone. This "
                            + "run does not collect coverage, so it pays neither the {}ms of "
                            + "capture overhead nor the {}ms of fixed per-JVM overhead the "
                            + "selection reports.", weights.size(),
                    selection.getCaptureOverheadMs(), selection.getFixedOverheadMs());
        }

        return groupCount != null
                ? TestGroupBalancer.balanceIntoGroups(weights, groupCount, fixedOverheadMs)
                : TestGroupBalancer.balanceForTargetRunTime(weights, targetRunTimeMs, maxGroups,
                        fixedOverheadMs);
    }

    /**
     * Build the grouping a seed run plans. When suites are discovered on disk and a group count is
     * available, they are split across that many groups by even count - there is no timing data
     * yet, so every suite is given a uniform 1ms weight purely so the balancer divides them by
     * quantity, and every group's {@code estimatedMs} is then rebuilt as zero before this method
     * returns, since that weight carries no real timing information and must not leak out as if it
     * were one. When nothing is discovered, or no group count applies (target-run-time mode with no
     * {@code maxGroups}, see Stage 2), the seed collapses to a single empty group whose one runner
     * runs every test. Shared by {@link #plan} and {@link #balance} so the persisted plan and the
     * {@code select-tests} preview can never disagree about what a seed run looks like.
     *
     * @param seedTestSuiteProvider supplies the suite names found on disk; invoked only here, on
     *                              the seed path, so non-seed plans never pay for the scan
     * @param groupCount the configured fixed group count, or null in target-run-time mode
     * @param maxGroups the configured ceiling used in target-run-time mode, or null
     * @return the seed grouping: an even split with every group's {@code estimatedMs} zero when a
     *         count and suites are available, otherwise a single empty group
     */
    private static GroupingResult seedGroupingResult(Supplier<Set<String>> seedTestSuiteProvider,
                                                     Integer groupCount, Integer maxGroups) {
        Integer seedGroupCount = groupCount != null ? groupCount : maxGroups;
        if (seedGroupCount == null) {
            return singleEmptySeedGroup();
        }
        Set<String> seedSuites = seedTestSuiteProvider.get();
        if (seedSuites == null || seedSuites.isEmpty()) {
            return singleEmptySeedGroup();
        }
        // Uniform weight: with no stored run times a seed run cannot balance by duration, so it
        // splits by even count instead. A non-zero weight is required - with every weight zero the
        // balancer would place every suite in group 0 - and the fixed per-JVM overhead is zero
        // because no distributed build has measured it yet.
        Map<String, Long> weights = new HashMap<>();
        for (String suite : seedSuites) {
            weights.put(suite, 1L);
        }
        GroupingResult split = TestGroupBalancer.balanceIntoGroups(weights, seedGroupCount, 0L);
        // The 1ms weight above exists only to make the balancer divide the suites evenly by count -
        // it carries no real timing information, so the resulting estimatedMs (and the totals
        // GroupingResult derives from it) would otherwise leak the suite count out as if it were a
        // genuine time estimate - see the WIKI "Distributed test runs" chapter. The split itself
        // (which suite landed in which group) is preserved; only the fabricated time figures are
        // zeroed.
        List<SuiteGroup> zeroed = new ArrayList<>(split.getGroups().size());
        for (SuiteGroup group : split.getGroups()) {
            zeroed.add(new SuiteGroup(group.getGroupNumber(), group.getSuiteNames(), 0L));
        }
        return new GroupingResult(zeroed, true, false, false, false);
    }

    /**
     * Build the single-group, empty-suite result a seed run collapses to when it has nothing to
     * split: exactly one group with no suites and an estimated time of zero, every target-related
     * flag reporting success trivially since a seed run has no target to miss. Its one runner
     * ignores nothing and runs every test it discovers.
     *
     * @return a {@link GroupingResult} with exactly one empty group
     */
    private static GroupingResult singleEmptySeedGroup() {
        List<SuiteGroup> groups = Collections.singletonList(
                new SuiteGroup(0, Collections.<String>emptyList(), 0L));
        return new GroupingResult(groups, true, false, false, false);
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
     * so this is a no-op in that mode. Called only from {@link #plan}, never from {@link #balance}:
     * a preview that logs this WARN would claim planning happened when nothing was persisted, which
     * is exactly what the preview's "(not persisted)" header exists to make clear it did not.
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
     * @param drainResult the library-impact drain the selection already performed, carried onto the
     *                    write bundle so it survives this process exiting, or null if nothing was
     *                    drained
     * @param seedRun whether this plan was collapsed to a seed run, recorded on the run row because
     *                the seal cannot tell a seed run's plan from a nothing-impacted one by its
     *                shape - see {@code DistributedRunSealer.ignoredSuiteCount}
     * @return the validated plan, ready to persist
     */
    private DistributedRunPlan projectPlan(GroupingResult result, String branch, String commitValue,
                                            long createdAtMs, LibraryImpactDrainResult drainResult,
                                            boolean seedRun) {
        Long targetRunTimeMs = config.isStaticGroups() ? null : config.getTargetRunTimeMs();
        DistributedRun run = DistributedRun.open(config.getRunId(), branch, commitValue,
                result.getGroupCount(), targetRunTimeMs, result.getTotalEstimatedMs(), createdAtMs,
                seedRun);

        List<DistributedRunGroup> groups = new ArrayList<>(result.getGroupCount());
        Map<Integer, List<String>> suitesByGroup = new HashMap<>();
        for (SuiteGroup suiteGroup : result.getGroups()) {
            groups.add(DistributedRunGroup.pending(config.getRunId(), suiteGroup.getGroupNumber(),
                    suiteGroup.getEstimatedMs()));
            suitesByGroup.put(suiteGroup.getGroupNumber(), suiteGroup.getSuiteNames());
        }

        return new DistributedRunPlan(run, groups, suitesByGroup, drainResult);
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
