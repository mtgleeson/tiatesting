package org.tiatesting.core.distributed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.SelectionMode;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.testrunner.TestSuiteRunnabilityClassifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The four datastore operations that belong to a distributed runner's persist and to nothing
 * else: re-verifying that its claim is still live, staging the method trackers it observed,
 * reporting its progress and completing its group. The mirror of {@link DistributedRunCoordinator},
 * which owns the same runner's pre-run half.
 *
 * <p>These sit together because the order they are used in is a correctness property of the run,
 * not a style choice, and it is stated once here rather than restated at each build tool's persist:
 *
 * <ol>
 *   <li>{@link #claimIsLive()} runs <b>before any write</b>. A runner from a superseded build whose
 *       plan rows a newer build has cleared must write nothing at all, or it leaves mapping rows
 *       from its own older commit under the commit the newer build has already stored.</li>
 *   <li>{@link #stageMethodTrackers(Map, Set)} replaces the catalogue write a single-host run makes. The
 *       catalogue is rebuilt wholesale from the suite-to-method edge table, so writing it while
 *       another group is still running would drop every method reachable only from that group's
 *       suites. Only the run's sealer may write it, after the barrier.</li>
 *   <li>{@link #reportGroupProgress(long, int, int, Set, long)} is called on every persist, at the point
 *       in the persist that used to record the same measurements as part of the combined
 *       completion call. It is not subject to the ordering below, since it never releases the
 *       barrier - only the status flip does. It also assumes one JVM works one group end to end;
 *       see its own javadoc for what several JVMs reporting against the same group do to that
 *       assumption.</li>
 *   <li>{@link #completeGroup(long, TestSuiteRunnabilityClassifier)} is the <b>last</b> write the runner makes, because completing
 *       the group is what releases that barrier. A group marked complete ahead of its own mapping
 *       rows would let the sealer rebuild the catalogue from an edge set still missing them -
 *       silent under-selection on the next build. "Last" means last for the whole build, not for
 *       one test plan: only the build tool knows when the last retry of this runner's tests has
 *       finished, so it is made once, from the build JVM, by the build tool's completion step -
 *       see {@link DistributedRunCompleter#completeAndSeal} - rather than
 *       by the persist that stages, which runs once per test plan inside the forked test JVM. See
 *       the distributed test runs chapter in {@code WIKI.md} for the mechanism that arranges
 *       this.</li>
 * </ol>
 *
 * <p>See the distributed test runs chapter in {@code WIKI.md} for the full lifecycle these steps
 * sit in.
 */
public final class DistributedRunnerPersist {

    private static final Logger log = LoggerFactory.getLogger(DistributedRunnerPersist.class);
    // How many suite names a message lists before summarising the rest as a count. Shared with the
    // planner's supersession WARN and the status report, so every place that names a group's
    // missing suites caps the list the same way.
    static final int MAX_SUITES_NAMED = 20;

    private final DataStore dataStore;
    private final DistributedRunnerContext context;

    /**
     * Bind the operations to one datastore and one runner's context.
     *
     * @param dataStore the shared datastore holding the run; must be the same store every other
     *                  runner in this distributed run writes to
     * @param context the calling runner's run id, identity and claimed group; must be a claimed
     *                context, since a surplus runner has nothing to verify, stage or complete
     * @throws IllegalArgumentException if {@code context} holds no group
     */
    public DistributedRunnerPersist(final DataStore dataStore, final DistributedRunnerContext context) {
        if (!context.isClaimed()) {
            throw new IllegalArgumentException("a distributed runner that claimed no group has "
                    + "nothing to persist and no group to complete: " + context);
        }
        this.dataStore = dataStore;
        this.context = context;
    }

    /**
     * Re-verify, immediately before the runner's first mapping write, that this runner still holds
     * the group it claimed. A false answer means the run was superseded - a newer build's plan
     * write cleared these rows - or the group has moved on to another runner, and the caller must
     * then write <b>nothing</b>: not the suite mapping, not the staged trackers, not the failed
     * set. This is the straggler protection described in the "Distributed test runs" WIKI chapter,
     * and the failure it prevents (rows from an old commit sitting under a newer stored commit) is
     * the one failure mode Tia must not have.
     *
     * <p>Read-only by necessity, since the guarded write that would prove the same thing atomically
     * is the completion, and the completion has to come last. That leaves a window between this
     * check and the writes it guards, which the guard on
     * {@link DataStore#completeGroup(String, int, String, long, Set)} closes after the fact: a
     * supersession landing inside the window is detected there, and the group is never marked
     * complete, so the superseded run can never seal.
     *
     * <p>Reading the group rows is enough on its own - the run cannot have been sealed while this
     * runner's group is still {@code CLAIMED}, because election requires every group to be
     * {@code COMPLETED} first.
     *
     * @return true when this runner's group is still {@code CLAIMED} under this runner's key
     */
    public boolean claimIsLive() {
        int groupNumber = context.getGroupNumber().intValue();

        for (DistributedRunGroup group : dataStore.readDistributedRunGroups(context.getRunId())) {
            if (group.getGroupNumber() == groupNumber) {
                boolean live = group.getStatus() == DistributedRunGroupStatus.CLAIMED
                        && context.getRunnerKey().equals(group.getRunnerKey());
                if (!live) {
                    log.warn("Distributed run '{}': group {} is now {} held by '{}', not CLAIMED by "
                                    + "this runner '{}'. Skipping every mapping write - persisting "
                                    + "them would leave this build's rows under another build's "
                                    + "commit.",
                            context.getRunId(), groupNumber, group.getStatus(), group.getRunnerKey(),
                            context.getRunnerKey());
                }
                return live;
            }
        }

        log.warn("Distributed run '{}': group {} no longer exists, so this build was superseded by a "
                        + "later one whose plan write cleared these rows. Runner '{}' is skipping "
                        + "every mapping write - its results describe a commit that is no longer the "
                        + "one being sealed.",
                context.getRunId(), groupNumber, context.getRunnerKey());
        return false;
    }

    /**
     * Stage the method trackers this runner observed, for the sealer to rebuild the catalogue from
     * after the barrier. This is what a distributed runner does instead of building and writing the
     * catalogue itself: no single runner sees the whole run's trackers, and the ones it does see
     * are valid to write early because method ids hash the class, method and descriptor only, so
     * they are independent of the line numbers the catalogue stores.
     *
     * <p>The ids of the methods this runner's suites executed are staged alongside, for the sealer
     * to count each method's executed runs from - see the "Method run stats" chapter in
     * {@code WIKI.md}.
     *
     * @param methodTrackersFromTestRun the trackers observed by the suites this runner executed,
     *                                  keyed by method id; may be empty, which stages nothing
     * @param coveredMethodIds the ids of the methods this runner's suites executed; may be empty
     */
    public void stageMethodTrackers(final Map<Integer, MethodImpactTracker> methodTrackersFromTestRun,
                                    final Set<Integer> coveredMethodIds) {
        dataStore.persistStagedMethodTrackers(context.getRunId(), methodTrackersFromTestRun, coveredMethodIds);
        log.debug("Distributed run '{}': runner '{}' staged {} method tracker(s) and {} covered method "
                        + "id(s) for the sealer.", context.getRunId(), context.getRunnerKey(),
                methodTrackersFromTestRun.size(), coveredMethodIds.size());
    }

    /**
     * Report this test plan's progress on the runner's group, without releasing the barrier. Called
     * on every persist - potentially several times per JVM, once per Surefire retry - so {@code
     * actualDurationMs} accumulates onto whatever is already stored while {@code suitesFailed}
     * replaces it outright, {@code suitesRan} is written via {@code GREATEST}, and each suite in
     * {@code suitesObserved} has its row flagged observed, exactly as {@link
     * DataStore#reportGroupProgress} documents - {@code suitesFailed} because it is current state,
     * {@code suitesRan} because the set it comes from is already cumulative per JVM, so summing it
     * would double-count, and the observed flags because a flag set once stays set, so reports add
     * up to their union. The flags are what {@link #completeGroup(long, TestSuiteRunnabilityClassifier)}'s completeness guard later
     * counts to decide whether the group's mapping rows are believed to cover everything it was
     * assigned - observed suites, not {@code suitesRan}, since a suite the group observed but never
     * executed (a disabled class, a filter, a deletion) still counts toward completeness.
     *
     * <p>A false return is the same straggler protection {@link #completeGroup(long, TestSuiteRunnabilityClassifier)} guards on,
     * seen early: the claim died before this call, so there is nothing further to persist for this
     * group from here. Logged rather than escalated, since the caller's own {@link #claimIsLive()}
     * check is the one meant to catch this before any write - a false return here means a
     * supersession landed in the narrow window after that check passed.
     *
     * @param actualDurationMs this call's measured test-execution time, in ms, on the same clock a
     *                         single-host run records its duration on, so the sealer's aggregate
     *                         stays comparable with non-distributed history
     * @param suitesRan the number of distinct suites this runner has executed so far, cumulative
     *                  across every test plan in this JVM
     * @param suitesFailed the number of this runner's suites currently failing
     * @param suitesObserved the suites this runner has observed so far (finished or skipped) that
     *                       are also assigned to this group, cumulative across every test plan in
     *                       this JVM. The caller (see {@code TestRunnerService#observedSuitesInGroup})
     *                       has already intersected
     *                       {@link org.tiatesting.core.testrunner.TestRunResult#getSuitesObserved()}
     *                       with this group's own assigned suites, so the report never carries the
     *                       foreign suites this JVM observed only because Tia's own deselection
     *                       disabled them
     * @param suitesDurationMs the summed measured run time, in ms, of every suite this runner has
     *                         timed so far - cumulative across every test plan in this JVM, and
     *                         written via {@code GREATEST} for the same reason {@code suitesRan} is. Subtracted from {@code actualDurationMs} by the
     *                         sealer to recover this runner's fixed per-JVM overhead, which the
     *                         build pays once rather than once per group - see
     *                         {@link DistributedRunTotals}
     * @return true when the guarded update applied, false when this runner's claim is no longer
     *         live
     */
    public boolean reportGroupProgress(final long actualDurationMs, final int suitesRan,
                                       final int suitesFailed, final Set<String> suitesObserved,
                                       final long suitesDurationMs) {
        boolean applied = dataStore.reportGroupProgress(context.getRunId(),
                context.getGroupNumber().intValue(), context.getRunnerKey(), actualDurationMs,
                suitesRan, suitesFailed, suitesObserved, suitesDurationMs);

        if (!applied) {
            log.warn("Distributed run '{}': runner '{}' could not report progress on group {} - {}. "
                            + "Its mapping writes are unaffected, but this test plan's figures will "
                            + "not reach the group's totals.",
                    context.getRunId(), context.getRunnerKey(), context.getGroupNumber(),
                    describeRejectedCompletion(Collections.<String>emptySet()));
        } else {
            log.debug("Distributed run '{}': runner '{}' reported {} suite(s) ran, {} observed ({} "
                            + "failed, {}ms) on group {}.", context.getRunId(), context.getRunnerKey(),
                    suitesRan, suitesObserved.size(), suitesFailed, actualDurationMs,
                    context.getGroupNumber());
        }
        return applied;
    }

    /**
     * Mark this runner's group complete, first excusing any assigned suite this runner never
     * observed that its compiled test classes prove can never run - a helper or abstract class
     * added under a test dir, an interface, or a class with no class file. Must be the caller's
     * last write, since it is what releases the barrier the sealer's catalogue rebuild waits on -
     * which is why it is called once for the whole build tool step, from {@link
     * DistributedRunCompleter#completeAndSeal}, and not once per finished test plan. Carries no
     * measurements of its own; {@link #reportGroupProgress(long, int, int, Set, long)} records those
     * on every persist, and this call only flags never-runnable suites and flips the group's status.
     *
     * <p>Only the unobserved suites are classified, so a classifier mistake can at worst excuse the
     * one suite it misjudged - never stand in for some other suite. On a non-full run the
     * unobserved list is normally empty, so no class file is read. On a full run nothing is
     * classified at all: its assignment is a disk-scan superset and its guard is already loosened.
     * Every excused suite is named in a WARN with the reason. See "The completeness guard" in the
     * distributed test runs chapter of {@code WIKI.md}.
     *
     * <p>A null return is either the straggler protection firing late - the claim died between
     * {@link #claimIsLive()} and here, so the mapping writes in between went to a run that no
     * longer exists - or the group is still missing a suite that could have run. Nothing further
     * can be done about those rows from here, but the group stays incomplete, so this run can never
     * elect a sealer and can never advance the stored commit - which is what keeps the superseding
     * build's commit stamp honest. The failure log names which of the causes it was, and the
     * suites still missing, read back by {@link #describeRejectedCompletion(Set)}.
     *
     * @param completedAtMs UTC epoch millis to record as the completion time
     * @param classifier classifies this runner's unobserved suites against its compiled test
     *                   classes; a disabled classifier excuses nothing
     * @return the completed group, or null when this runner's claim was no longer live or the
     *         group is still missing a suite that could have run
     */
    public DistributedRunGroup completeGroup(final long completedAtMs,
                                             final TestSuiteRunnabilityClassifier classifier) {
        int groupNumber = context.getGroupNumber().intValue();
        Set<String> unrunnable = findUnrunnableUnobservedSuites(groupNumber, classifier);

        DistributedRunGroup completed = dataStore.completeGroup(context.getRunId(), groupNumber,
                context.getRunnerKey(), completedAtMs, unrunnable);

        if (completed == null) {
            log.error("Distributed run '{}': runner '{}' could not complete group {} - {}. This "
                            + "build will not be sealed under this run id, and the stored commit "
                            + "value stays where the superseding build leaves it.",
                    context.getRunId(), context.getRunnerKey(), context.getGroupNumber(),
                    describeRejectedCompletion(unrunnable));
            return null;
        }

        log.info("Distributed run '{}': runner '{}' completed group {} in {}ms ({} suite(s) ran, {} "
                        + "failed).", context.getRunId(), context.getRunnerKey(),
                context.getGroupNumber(), completed.getActualDurationMs(), completed.getSuitesRan(),
                completed.getSuitesFailed());
        return completed;
    }

    /**
     * Classify the group's assigned suites that no progress report flagged observed, and return
     * the ones that can never run, logging each with its reason. Nothing is classified on a full
     * run or when the run row is gone (a superseded run, whose completion will be refused anyway).
     *
     * @param groupNumber this runner's group
     * @param classifier classifies suites against this runner's compiled test classes
     * @return the unobserved suites proven never runnable, in name order; empty when there are none
     */
    private Set<String> findUnrunnableUnobservedSuites(final int groupNumber,
                                                       final TestSuiteRunnabilityClassifier classifier) {
        Set<String> unrunnable = new LinkedHashSet<>();
        DistributedRun run = dataStore.readDistributedRun(context.getRunId());
        if (run == null || run.isFullRun()) {
            return unrunnable;
        }

        List<String> reasons = new ArrayList<>();
        for (String suite : dataStore.readUnobservedGroupSuites(context.getRunId(), groupNumber)) {
            TestSuiteRunnabilityClassifier.Verdict verdict = classifier.classify(suite);
            if (verdict.isNeverRunnable()) {
                unrunnable.add(suite);
                reasons.add(suite + " (" + verdict.getReason() + ")");
            }
        }

        if (!unrunnable.isEmpty()) {
            log.warn("Distributed run '{}': group {}: {} assigned suite(s) are not runnable tests and "
                            + "are counted as observed: {}", context.getRunId(), groupNumber,
                    unrunnable.size(), reasons);
        }
        return unrunnable;
    }

    /**
     * Say what the group row actually holds after a guarded write this class made was refused, so
     * the failure log names the case that happened rather than asserting the most likely one. Used
     * on both {@link #completeGroup(long, TestSuiteRunnabilityClassifier)}'s and {@link #reportGroupProgress(long, int, int, Set, long)}'s
     * failure paths, so its wording is deliberately guard-neutral rather than assuming a completion
     * was attempted. The guard's row count cannot tell the cases apart - a run superseded by a newer
     * build's plan write, a group re-claimed by another runner, a group this runner has already
     * completed, and a group this runner still holds but has not yet observed enough of all miss
     * the same {@code WHERE} clause - so the row is read back to find out.
     *
     * <p>Read only on the failure path, where one extra read costs nothing and a wrong explanation
     * costs an engineer an afternoon.
     *
     * <p>On a seed run the incomplete case reports that the group observed nothing rather than an
     * observed-of-assigned fraction, since the assigned count is a disk-scan superset there. On any
     * other run it names the suites still missing - those neither observed nor excused - up to
     * the first 20 of them.
     *
     * @param excusedSuites the suites the caller tried to excuse as never runnable, which a refused
     *                      completion rolled back; they are reported apart from the missing ones.
     *                      Empty on the progress path
     * @return a clause naming what the group row says, to be embedded in the failure log
     */
    String describeRejectedCompletion(final Set<String> excusedSuites) {
        int groupNumber = context.getGroupNumber().intValue();

        for (DistributedRunGroup group : dataStore.readDistributedRunGroups(context.getRunId())) {
            if (group.getGroupNumber() != groupNumber) {
                continue;
            }
            boolean heldByThisRunner = context.getRunnerKey().equals(group.getRunnerKey());
            if (group.getStatus() == DistributedRunGroupStatus.COMPLETED && heldByThisRunner) {
                return "this runner already completed this group, so there is nothing further to "
                        + "write for it";
            }
            if (group.getStatus() == DistributedRunGroupStatus.CLAIMED && heldByThisRunner) {
                DistributedRun run = dataStore.readDistributedRun(context.getRunId());
                if (run.isFullRun()) {
                    return "this is a " + fullRunName(run) + " and this runner has observed no suites yet ("
                            + group.getSuitesObserved() + "), so the group has run nothing to "
                            + "complete";
                }
                int assigned = dataStore.readDistributedRunGroupSuites(context.getRunId(), groupNumber)
                        .size();
                List<String> missing = new ArrayList<>(
                        dataStore.readUnobservedGroupSuites(context.getRunId(), groupNumber));
                missing.removeAll(excusedSuites);
                return "this runner has observed only " + group.getSuitesObserved() + " of "
                        + assigned + " assigned suite(s) so far"
                        + (excusedSuites.isEmpty() ? ""
                                : " (" + excusedSuites.size() + " more are not runnable tests)")
                        + ", so the group is not complete enough to close - not observed: "
                        + nameAtMost(missing, MAX_SUITES_NAMED);
            }
            return "the group is now " + group.getStatus() + " under runner '" + group.getRunnerKey()
                    + "', so it is no longer this runner's to complete";
        }

        return "the run's group rows are gone, so a newer build's plan write superseded this run";
    }

    /**
     * List suite names for a log message, naming at most {@code limit} of them and summarising the
     * rest as a count, so a group that ran almost nothing does not print hundreds of names.
     *
     * @param suites the suite names to list
     * @param limit how many to name
     * @return e.g. {@code [a.FooTest, a.BarTest]} or {@code [a.FooTest, ...] and 5 more}
     */
    static String nameAtMost(final List<String> suites, final int limit) {
        if (suites.size() <= limit) {
            return suites.toString();
        }
        return suites.subList(0, limit).toString() + " and " + (suites.size() - limit) + " more";
    }

    /**
     * Name a full run for a log or rejection message: a seed run, or a forced run named by its
     * mode, so a forced run is never described as having no stored mapping.
     *
     * @param run the full run
     * @return "seed run", or e.g. "full run (Re-seed)"
     */
    static String fullRunName(final DistributedRun run) {
        return run.getSelectionMode() == SelectionMode.SEED ? "seed run"
                : "full run (" + run.getSelectionMode().getLabel() + ")";
    }
}
