package org.tiatesting.core.distributed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.persistence.DataStore;

import java.util.Map;

/**
 * The three datastore operations that belong to a distributed runner's persist and to nothing
 * else: re-verifying that its claim is still live, staging the method trackers it observed, and
 * completing its group. The mirror of {@link DistributedRunCoordinator}, which owns the same
 * runner's pre-run half.
 *
 * <p>These sit together because the order they are used in is a correctness property of the run,
 * not a style choice, and it is stated once here rather than restated at each build tool's persist:
 *
 * <ol>
 *   <li>{@link #claimIsLive()} runs <b>before any write</b>. A runner from a superseded build whose
 *       plan rows a newer build has cleared must write nothing at all, or it leaves mapping rows
 *       from its own older commit under the commit the newer build has already stored.</li>
 *   <li>{@link #stageMethodTrackers(Map)} replaces the catalogue write a single-host run makes. The
 *       catalogue is rebuilt wholesale from the suite-to-method edge table, so writing it while
 *       another group is still running would drop every method reachable only from that group's
 *       suites. Only the run's sealer may write it, after the barrier.</li>
 *   <li>{@link #completeGroup(long, int, int, long)} is the <b>last</b> write the runner makes,
 *       because completing the group is what releases that barrier. A group marked complete ahead
 *       of its own mapping rows would let the sealer rebuild the catalogue from an edge set still
 *       missing them - silent under-selection on the next build. "Last" means last for the whole
 *       test JVM rather than for one test plan, which is why the completion is made by
 *       {@link DistributedRunCompletion} at JVM exit and not by the persist that stages.</li>
 * </ol>
 *
 * <p>See the distributed test runs chapter in {@code WIKI.md} for the full lifecycle these three
 * steps sit in.
 */
public final class DistributedRunnerPersist {

    private static final Logger log = LoggerFactory.getLogger(DistributedRunnerPersist.class);

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
     * set. This is the straggler protection from the spec, and the failure it prevents (rows from
     * an old commit sitting under a newer stored commit) is the one failure mode Tia must not have.
     *
     * <p>Read-only by necessity, since the guarded write that would prove the same thing atomically
     * is the completion, and the completion has to come last. That leaves a window between this
     * check and the writes it guards, which the guard on
     * {@link DataStore#completeGroup(String, int, String, long, long, int, int)} closes after the
     * fact: a supersession landing inside the window is detected there, and the group is never
     * marked complete, so the superseded run can never seal.
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
     * @param methodTrackersFromTestRun the trackers observed by the suites this runner executed,
     *                                  keyed by method id; may be empty, which stages nothing
     */
    public void stageMethodTrackers(final Map<Integer, MethodImpactTracker> methodTrackersFromTestRun) {
        dataStore.persistStagedMethodTrackers(context.getRunId(), methodTrackersFromTestRun);
        log.debug("Distributed run '{}': runner '{}' staged {} method tracker(s) for the sealer.",
                context.getRunId(), context.getRunnerKey(), methodTrackersFromTestRun.size());
    }

    /**
     * Mark this runner's group complete, recording the measurements the sealer aggregates into the
     * build's single history row. Must be the caller's last write, since it is what releases the
     * barrier the sealer's catalogue rebuild waits on - which is why it is called once for the test
     * JVM, from {@link DistributedRunCompletion}, and not once per finished test plan.
     *
     * <p>A null return is the straggler protection firing late: the claim died between
     * {@link #claimIsLive()} and here, so the mapping writes in between went to a run that no
     * longer exists. Nothing further can be done about those rows from here, but the group stays
     * incomplete, so this run can never elect a sealer and can never advance the stored commit -
     * which is what keeps the superseding build's commit stamp honest. The failure log names which
     * of the causes it was, read back from the group row by
     * {@link #describeRejectedCompletion()}.
     *
     * @param actualDurationMs this runner's measured test-execution time, in ms, on the same clock
     *                         a single-host run records its duration on, so the sealer's aggregate
     *                         stays comparable with non-distributed history
     * @param suitesRan the number of suites this runner executed
     * @param suitesFailed the number of this runner's suites with at least one failed test
     * @param completedAtMs UTC epoch millis to record as the completion time
     * @return the completed group, or null when this runner's claim was no longer live
     */
    public DistributedRunGroup completeGroup(final long actualDurationMs, final int suitesRan,
                                             final int suitesFailed, final long completedAtMs) {
        DistributedRunGroup completed = dataStore.completeGroup(context.getRunId(),
                context.getGroupNumber().intValue(), context.getRunnerKey(), completedAtMs,
                actualDurationMs, suitesRan, suitesFailed);

        if (completed == null) {
            log.error("Distributed run '{}': runner '{}' could not complete group {} - {}. This "
                            + "build will not be sealed under this run id, and the stored commit "
                            + "value stays where the superseding build leaves it.",
                    context.getRunId(), context.getRunnerKey(), context.getGroupNumber(),
                    describeRejectedCompletion());
            return null;
        }

        log.info("Distributed run '{}': runner '{}' completed group {} in {}ms ({} suite(s) ran, {} "
                        + "failed).", context.getRunId(), context.getRunnerKey(),
                context.getGroupNumber(), actualDurationMs, suitesRan, suitesFailed);
        return completed;
    }

    /**
     * Say what the group row actually holds after a completion the guarded write refused, so the
     * failure log names the case that happened rather than asserting the most likely one. The
     * guard's row count cannot tell the cases apart - a run superseded by a newer build's plan
     * write, a group re-claimed by another runner, and a group this runner has already completed
     * all miss the same {@code WHERE} clause - so the row is read back to find out.
     *
     * <p>Read only on the failure path, where one extra read costs nothing and a wrong explanation
     * costs an engineer an afternoon.
     *
     * @return a clause naming what the group row says, to be embedded in the failure log
     */
    String describeRejectedCompletion() {
        int groupNumber = context.getGroupNumber().intValue();

        for (DistributedRunGroup group : dataStore.readDistributedRunGroups(context.getRunId())) {
            if (group.getGroupNumber() != groupNumber) {
                continue;
            }
            if (group.getStatus() == DistributedRunGroupStatus.COMPLETED
                    && context.getRunnerKey().equals(group.getRunnerKey())) {
                return "this runner had already completed it, so the completion was made twice";
            }
            return "the group is now " + group.getStatus() + " under runner '" + group.getRunnerKey()
                    + "', so it is no longer this runner's to complete";
        }

        return "the run's group rows are gone, so a newer build's plan write superseded this run";
    }
}
