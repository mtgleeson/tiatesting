package org.tiatesting.core.distributed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.SealedRunDataAssembler;

import java.time.Instant;
import java.util.Map;

/**
 * The end of a distributed build. Every runner that completes its group attempts the election; the
 * one that finishes last wins it and then does, once and for the whole build, what a single-host run
 * does for itself - rebuild the method catalogue, apply the library drain cleanup the plan still
 * owed, advance the stored commit value, and retire the run.
 *
 * <p><b>Why the catalogue rebuild is the sealer's job and nobody else's.</b> The catalogue is
 * rebuilt wholesale from the distinct method ids on the suite-to-method edge table, and any id that
 * query omits is dropped. Each runner writes only its own suites' edges, so running the query while
 * a group is still going answers with an edge set missing that group's suites: every method
 * reachable only from them is dropped from the catalogue, becomes invisible to the next build's
 * diff, and the tests that should have run do not. That is silent under-selection, and the barrier
 * inside {@link DataStore#electSealer(String, String, long)} - which only elects once every group is
 * {@code COMPLETED} - is what makes the id set complete before it is read.
 *
 * <p><b>Why resolving an id from the stored catalogue is correct</b>, and must not be hardened into
 * an error: a method's line numbers can only shift if its file changed; if its file changed then its
 * covering suites were selected; if they were selected then some group ran them and staged a fresh
 * tracker for them. The sealer is only reached once every group completed, so there is no gap in
 * that chain, and anything falling through to the stored row is genuinely unchanged. An id in
 * neither is an orphan and is dropped, exactly as on the single-host path.
 *
 * <p><b>Why a lost election means do nothing at all.</b> A {@code false} from the election covers
 * both "another runner won" and "my run no longer exists because a newer build superseded it", and
 * nothing distinguishes the two after the fact. A straggler sealer that proceeded anyway would find
 * the staging table empty - the superseding plan write cleared it - resolve every method id from the
 * stored catalogue, and silently drop from the catalogue every id the stored catalogue lacks.
 *
 * <p>See the distributed test runs chapter in {@code WIKI.md} for the lifecycle this closes.
 */
public final class DistributedRunSealer {

    private static final Logger log = LoggerFactory.getLogger(DistributedRunSealer.class);

    private final DataStore dataStore;
    private final DistributedRunnerContext context;

    /**
     * Bind the seal to one datastore and one runner's identity.
     *
     * @param dataStore the shared datastore holding the run; must be the same store every other
     *                  runner in this distributed run writes to
     * @param context the calling runner's run id and identity, as recorded when it claimed
     */
    public DistributedRunSealer(final DataStore dataStore, final DistributedRunnerContext context) {
        this.dataStore = dataStore;
        this.context = context;
    }

    /**
     * Stand for election as the run's one sealer and, on winning, seal the build.
     *
     * <p>Called by every runner immediately after it completes its group, which is the only point at
     * which a runner can be the last one. Losing is the ordinary outcome for all but one runner in
     * the build and is not an error: a loser returns without reading or writing anything.
     *
     * @param commitValue the commit the build ran against, which becomes the stored commit value.
     *                    Every runner was verified against the plan's commit before it claimed, so
     *                    the winner's own commit is the commit every group's mapping rows describe
     * @param branch the branch the build ran against, recorded alongside the commit
     * @param updateDBMapping whether this build owns mapping-DB updates. When false there is
     *                        nothing to seal - no runner staged anything and no commit may be
     *                        advanced - but the run is still retired
     * @param sealedAtMs UTC epoch millis to record as the election time
     * @return true when this runner won the election and performed the seal, false when it did
     *         nothing
     */
    public boolean sealIfElected(final String commitValue, final String branch,
                                 final boolean updateDBMapping, final long sealedAtMs) {
        if (!dataStore.electSealer(context.getRunId(), context.getRunnerKey(), sealedAtMs)) {
            log.debug("Distributed run '{}': runner '{}' is not the sealer, so it is done.",
                    context.getRunId(), context.getRunnerKey());
            return false;
        }

        log.info("Distributed run '{}': runner '{}' finished last and was elected to seal the build.",
                context.getRunId(), context.getRunnerKey());

        if (updateDBMapping) {
            sealMapping(commitValue, branch);
        } else {
            log.info("Distributed run '{}': the build does not own mapping updates, so there is "
                    + "nothing to seal.", context.getRunId());
        }

        // The run is retired whether or not it owned mapping updates. The staging table is roughly
        // the size of the method catalogue, so it is cleared here rather than left for the next
        // build's plan write to clear.
        dataStore.markDistributedRunSealed(context.getRunId());
        dataStore.deleteStagedMethodTrackers(context.getRunId());
        return true;
    }

    /**
     * Write the build's seal: the method catalogue rebuilt from every runner's observations, the
     * library drain cleanup the plan recorded, and the commit value, in the one transaction
     * {@link DataStore#persistSealedRunData} provides. The catalogue's line numbers only mean
     * anything in one commit's coordinate space, so they and the commit value cannot be allowed to
     * land separately.
     *
     * @param commitValue the commit being sealed
     * @param branch the branch being sealed
     */
    private void sealMapping(final String commitValue, final String branch) {
        TiaData tiaData = dataStore.getTiaCore();
        tiaData.setCommitValue(commitValue);
        tiaData.setBranch(branch);
        tiaData.setLastUpdated(Instant.now());

        // The union of every runner's observations stands in for the single-host run's own
        // trackers. Method ids hash the class, method and descriptor only, so a tracker staged by
        // the first group to finish is still valid against the catalogue written here.
        Map<Integer, MethodImpactTracker> stagedMethodTrackers =
                dataStore.readStagedMethodTrackers(context.getRunId());

        // The plan's own test selection drained the library-impact ledger before any runner started
        // and then exited. That drain cannot be repeated, so the result stored with the plan is the
        // only record of the cleanup it still owes.
        LibraryImpactDrainResult drainResult =
                dataStore.readDistributedRunDrainResult(context.getRunId());

        // Not treated as an all-tests run: a distributed build splits its selection across groups,
        // so no runner ignores zero suites and the union is worked out from the completed groups by
        // the stage that aggregates the build's stats. Reporting false here only holds library
        // mapping baselines at their existing commit, which over-selects on the next build rather
        // than under-selecting.
        dataStore.persistSealedRunData(new SealedRunDataAssembler(dataStore).assemble(tiaData,
                stagedMethodTrackers, drainResult, commitValue, false));

        log.info("Distributed run '{}': sealed at commit '{}' with {} method(s) in the catalogue.",
                context.getRunId(), commitValue, tiaData.getMethodsTracked().size());
    }
}
