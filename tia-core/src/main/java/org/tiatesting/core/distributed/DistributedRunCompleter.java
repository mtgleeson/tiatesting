package org.tiatesting.core.distributed;

import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.persistence.DataStore;

/**
 * The one sequence every distributed runner performs to close out its share of a build: complete
 * its claimed group and, if that completion is accepted, stand for election to seal the run. Both
 * build tools' completion step - the Maven {@code dist-complete} goal ({@code AbstractTiaDistCompleteMojo})
 * and the Gradle task ({@code TiaDistCompleteTask}) - call this once, after their own build-specific
 * mechanism has established that no more retries of this runner's tests are coming. That is what
 * used to be decided by a JVM shutdown hook inside the forked test JVM: nothing inside a fork can
 * tell whether another test plan is coming, since a retry is a new test plan in the same JVM, so the
 * hook was the best a fork could do on its own - and it was still wrong, firing before a build's
 * other workers had finished. See the distributed test runs chapter in {@code WIKI.md} for the
 * lifecycle this closes.
 *
 * <p><b>Failures are not caught here.</b> A shutdown hook had to swallow everything, including
 * {@link Error}, because anything it threw vanished with no stack trace and no exit code to notice.
 * A build tool step has no such problem - it is expected to fail loudly - and both callers already
 * wrap a failure in their own build-tool exception with a message saying the run was NOT sealed.
 * Swallowing here would take that reporting away from them. The one thing this class does add on
 * top of simply letting exceptions propagate is {@link SealFailedAfterCompletionException}: without
 * it, a caller catching a plain failure after this call cannot tell "the group was never completed"
 * apart from "the group completed but the seal that followed it failed" - and an operator reading
 * "could not complete group N" in the first case would go looking for a row that is, in the second
 * case, already marked {@code COMPLETED}.
 */
public final class DistributedRunCompleter {

    private DistributedRunCompleter() {
    }

    /**
     * Complete this runner's claimed group and, if the completion is accepted, stand for election
     * to seal the build - in the order the run's correctness depends on: the group must flip to
     * {@code COMPLETED}, releasing the barrier the sealer's catalogue rebuild waits on, before this
     * runner may stand for election.
     *
     * <p>A rejected completion - {@link DistributedRunnerPersist#completeGroup} returning null
     * because the claim was superseded, the group was already completed, or the completeness guard
     * is not satisfied - is a normal outcome, not a failure: {@code DistributedRunnerPersist} has
     * already logged which of the three it was, and there is no group left for this runner to seal
     * from, so this method simply returns.
     *
     * @param dataStore the shared datastore this build writes to; opened and closed by the caller,
     *                  since the caller also needs it open for whatever it does around this call
     * @param context the claimed runner context to complete and, on success, seal under
     * @param updateDBMapping whether this build owns mapping-DB updates
     * @param updateDBStats whether the Tia-level run stats should be updated
     * @param updateDBTestRunHistory whether the build should write its one history row
     * @param nowMs UTC epoch millis to record as the completion time and, if elected, the seal time
     * @return {@code true} if this call was the one that flipped the group to {@code COMPLETED};
     *         {@code false} if the completion was rejected as a normal no-op (superseded, already
     *         completed, or the completeness guard was not satisfied) - lets a caller whose own
     *         resource cleanup fails after this method has already returned successfully tell "the
     *         group completed on this call" apart from "there was never anything to complete",
     *         rather than reporting a failure that happens after this method returns as if it were
     *         a completion failure
     * @throws SealFailedAfterCompletionException if the group was completed but the election or
     *                                             seal that follows it then failed; wraps the
     *                                             original failure as its cause
     * @throws RuntimeException if completing the group itself fails; propagates unwrapped, since
     *                          the group in that case is exactly as it was before this call
     */
    public static boolean completeAndSeal(final DataStore dataStore, final DistributedRunnerContext context,
                                          final boolean updateDBMapping, final boolean updateDBStats,
                                          final boolean updateDBTestRunHistory, final long nowMs) {
        DistributedRunnerPersist runnerPersist = new DistributedRunnerPersist(dataStore, context);
        DistributedRunGroup completed = runnerPersist.completeGroup(nowMs);

        if (completed == null) {
            return false;
        }

        try {
            new DistributedRunSealer(dataStore, context).sealIfElected(updateDBMapping, updateDBStats,
                    updateDBTestRunHistory, nowMs);
        } catch (RuntimeException e) {
            throw new SealFailedAfterCompletionException(e);
        }

        return true;
    }

    /**
     * Marks that a runner's group had already flipped to {@code COMPLETED} when the election or
     * seal that follows completion then failed. Thrown only from {@link #completeAndSeal}, and only
     * once {@link DistributedRunnerPersist#completeGroup} has returned successfully - a failure
     * completing the group itself is never wrapped in this, and propagates as whatever the
     * datastore threw, since the group in that case is unchanged from before the call.
     */
    public static final class SealFailedAfterCompletionException extends RuntimeException {

        /**
         * Wrap the failure that happened after this runner's group had already completed.
         *
         * @param cause the failure thrown while electing or sealing
         */
        private SealFailedAfterCompletionException(final Throwable cause) {
            super(cause);
        }
    }
}
