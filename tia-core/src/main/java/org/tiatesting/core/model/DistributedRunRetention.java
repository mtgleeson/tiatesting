package org.tiatesting.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decides which existing distributed runs a new plan deletes. Several distributed builds can be in
 * flight against one schema: one primary build that owns the mapping, and any number of preflight
 * builds that only read it. So a plan cannot clear the tables wholesale; it deletes:
 * <ul>
 *   <li>a run planned under its own run id (a replan);</li>
 *   <li>every mapping-owning run, when the new run owns the mapping too - genuine supersession,
 *       which stops a stale primary build from sealing an older commit over a newer one;</li>
 *   <li>a run that does not own the mapping once it has been sealed for more than {@link
 *       #SEALED_GRACE_MS}, or has stayed unsealed for more than {@link #ABANDONED_AGE_MS}.</li>
 * </ul>
 * Nothing else is touched, so a preflight plan never deletes a primary run and no plan deletes a
 * young preflight run of another build. Pure and stateless, so the planner (to decide what to warn
 * about) and the plan write (to decide what to delete, inside its transaction) apply the same rule.
 *
 * <p>See the "Concurrent distributed builds" chapter in {@code WIKI.md}.
 */
public final class DistributedRunRetention {

    /**
     * How long a sealed run that does not own the mapping is kept. Covers a late surplus runner,
     * which finds the sealed run with no group left and exits quietly, and a status call made
     * shortly after the build.
     */
    public static final long SEALED_GRACE_MS = 60L * 60L * 1000L;

    /** How long an unsealed run that does not own the mapping is kept before it is reaped. */
    public static final long ABANDONED_AGE_MS = 24L * 60L * 60L * 1000L;

    private final List<String> runIdsToDelete;
    private final List<DistributedRun> supersededUnsealed;
    private final List<DistributedRun> abandoned;

    /**
     * Hold one decision's outcome.
     *
     * @param runIdsToDelete every run id to delete, sorted
     * @param supersededUnsealed the unsealed runs deleted by supersession or replan
     * @param abandoned the unsealed runs deleted by age
     */
    private DistributedRunRetention(List<String> runIdsToDelete, List<DistributedRun> supersededUnsealed,
                                    List<DistributedRun> abandoned) {
        this.runIdsToDelete = Collections.unmodifiableList(runIdsToDelete);
        this.supersededUnsealed = Collections.unmodifiableList(supersededUnsealed);
        this.abandoned = Collections.unmodifiableList(abandoned);
    }

    /**
     * Decide which of {@code existingRuns} a new plan deletes, and which of those deserve a warning
     * because they never sealed. A sealed run is deleted silently: it completed and left no
     * abandoned work. A sealed run with no seal time recorded falls back to the abandoned age, so a
     * malformed row is still reaped eventually.
     *
     * @param existingRuns every run currently in {@code tia_distributed_run}
     * @param newRunId the run id the new plan is written under
     * @param newRunUpdatesMapping whether the new plan's build owns mapping updates
     * @param nowMs the UTC epoch millis to measure ages against - the new plan's creation time
     * @return the decision
     */
    public static DistributedRunRetention decide(List<DistributedRun> existingRuns, String newRunId,
                                                 boolean newRunUpdatesMapping, long nowMs) {
        List<String> toDelete = new ArrayList<>();
        List<DistributedRun> superseded = new ArrayList<>();
        List<DistributedRun> abandoned = new ArrayList<>();
        for (DistributedRun run : existingRuns) {
            boolean sealed = run.getStatus() == DistributedRunStatus.SEALED;
            if (run.getRunId().equals(newRunId) || (newRunUpdatesMapping && run.isUpdatesMapping())) {
                toDelete.add(run.getRunId());
                if (!sealed) {
                    superseded.add(run);
                }
            } else if (!run.isUpdatesMapping()) {
                if (sealed && run.getSealedAtMs() != null) {
                    if (nowMs - run.getSealedAtMs() > SEALED_GRACE_MS) {
                        toDelete.add(run.getRunId());
                    }
                } else if (nowMs - run.getCreatedAtMs() > ABANDONED_AGE_MS) {
                    toDelete.add(run.getRunId());
                    if (!sealed) {
                        abandoned.add(run);
                    }
                }
            }
        }
        Collections.sort(toDelete);
        return new DistributedRunRetention(toDelete, superseded, abandoned);
    }

    /**
     * The run ids to delete, sorted so concurrent plan writes lock rows in the same order.
     *
     * @return the run ids, possibly empty
     */
    public List<String> getRunIdsToDelete() { return runIdsToDelete; }

    /**
     * The unsealed runs deleted because the new plan supersedes them (or replans their id).
     *
     * @return the runs, possibly empty
     */
    public List<DistributedRun> getSupersededUnsealed() { return supersededUnsealed; }

    /**
     * The unsealed runs that do not own the mapping, deleted because they exceeded {@link
     * #ABANDONED_AGE_MS}.
     *
     * @return the runs, possibly empty
     */
    public List<DistributedRun> getAbandoned() { return abandoned; }
}
