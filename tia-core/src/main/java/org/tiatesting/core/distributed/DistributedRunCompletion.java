package org.tiatesting.core.distributed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.persistence.DataStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The barrier release, moved to where the test JVM actually finishes. A runner completes its group
 * once, when its JVM exits, rather than once per test plan.
 *
 * <p><b>Why this exists.</b> A build tool's listener persists on every test plan that finishes, and
 * a Surefire retry of failed tests is a new test plan in the same JVM - which is why the per-JVM
 * shared run data those listeners carry exists at all. Completing the group inside that persist
 * therefore releases the barrier after the <em>first</em> test plan, and the run can then be sealed
 * while this JVM is still executing tests. The sealer rebuilds the method catalogue wholesale from
 * the suite-to-method edge table, so it would rebuild it from an edge set missing everything the
 * later test plans covered, and every method reachable only from those suites is dropped: invisible
 * to the next build's diff, and its covering suites silently stop being selected.
 *
 * <p>The mapping writes stay in the persist where they are, and so now does the group's progress -
 * duration and suite counters are reported on every persist via {@link
 * DistributedRunnerPersist#reportGroupProgress(long, int, int, int)}, accumulating across retries the
 * same way the suite rows, failed set and staged trackers do. Only the status flip, and the
 * sealer election that follows it, are held back to here.
 *
 * <p><b>Why a JVM shutdown hook.</b> Nothing tells a listener whether another test plan is coming,
 * and the launcher session callbacks fire several times per JVM for the same reason the shared run
 * data is needed. A shutdown hook is the only mechanism that fires once, after every test plan, on
 * normal JVM exit.
 *
 * <p><b>What happens when the fork is killed</b> (SIGKILL, OOM): the hook does not run, the group
 * is never completed, the barrier is never released and nothing seals. The stored commit simply
 * does not advance and the next build re-does the work - the safe direction, and the one the
 * distributed design chose everywhere else.
 *
 * <p><b>The usual hazard with a shutdown hook and a database does not arise here.</b> Racing an
 * embedded database's own hook would be a real concern, since the ordering between two hooks is
 * undefined, but {@link DistributedRunPreconditions} rejects embedded H2 for distributed runs
 * outright: the connection is always server-mode H2 or Postgres, which is a socket, and the store
 * opens a connection per operation rather than holding one open.
 *
 * <p>See the distributed test runs chapter in {@code WIKI.md} for the lifecycle this closes.
 */
public final class DistributedRunCompletion {

    private static final Logger log = LoggerFactory.getLogger(DistributedRunCompletion.class);

    /**
     * The completions waiting on this JVM's exit, keyed by the runner they belong to. A real test
     * JVM holds exactly one, since a runner claims one group; the map is what keeps the several
     * runners a test process drives from overwriting each other, and it is keyed rather than
     * single-valued so that keeping them apart never depends on a test's ordering.
     */
    private static final ConcurrentMap<String, DistributedRunCompletion> PENDING =
            new ConcurrentHashMap<String, DistributedRunCompletion>();

    /**
     * Whether this JVM's shutdown hook has been registered. Three listeners exist and a retry
     * constructs new listener instances, so the flag is static: the hook must be registered once
     * per JVM however many listeners and test plans pass through it.
     */
    private static final AtomicBoolean HOOK_REGISTERED = new AtomicBoolean(false);

    private final DataStore dataStore;
    private final DistributedRunnerContext context;

    /**
     * The build-identifying figures the test plan that recorded this reported: which of the mapping
     * DB, Tia-level stats and history row this build owns. A later test plan replaces the whole
     * recording rather than merging into it, which is correct because these values are the same
     * across every test plan in a JVM - the last one to persist is simply the freshest copy. The
     * commit and branch are no longer carried here: the seal now reads them itself from the run row,
     * which is authoritative by construction, rather than trusting a value handed back from this
     * JVM. The measured progress (duration, suites ran, suites failed) is likewise no longer carried
     * here: {@link DistributedRunnerPersist#reportGroupProgress(long, int, int, int)} records it
     * directly, on every persist, so it accumulates correctly across retries instead of being
     * replaced by whichever test plan happens to persist last.
     */
    private final RecordedFigures figures;

    /**
     * Bind a pending completion to one runner, the store it writes to and the figures the test
     * plan that created it reported.
     *
     * @param dataStore the shared datastore this runner's build writes to
     * @param context the runner's run id, identity and claimed group
     * @param figures the figures to complete with until a later test plan replaces them
     */
    private DistributedRunCompletion(final DataStore dataStore,
                                     final DistributedRunnerContext context,
                                     final RecordedFigures figures) {
        this.dataStore = dataStore;
        this.context = context;
        this.figures = figures;
    }

    /**
     * Record what this JVM's completion will need, from a test plan that has just finished
     * persisting its mapping writes and reporting its progress, and make sure the JVM exit that
     * runs it is hooked.
     *
     * <p>Called once per test plan and replaces the previous test plan's recording, which is
     * correct because what it now carries - which DB updates this build owns - is the same across
     * every test plan in the JVM, so the last one to persist is simply the freshest copy. The commit
     * and branch are not recorded here at all: the seal reads them itself from the run row rather
     * than trusting a value handed back from this JVM. The measured progress this used to carry is
     * reported directly by {@link DistributedRunnerPersist#reportGroupProgress(long, int, int, int)}
     * instead, on the same persist, since it has to accumulate across test plans rather than be
     * replaced by the last one.
     *
     * @param dataStore the shared datastore this runner's build writes to
     * @param context the runner's claimed context; a runner that claimed no group has no group to
     *                complete and must not reach here
     * @param updateDBMapping whether the build owns mapping-DB updates
     * @param updateDBStats whether the Tia-level run stats should be updated
     * @param updateDBTestRunHistory whether the build should write its one history row
     * @throws IllegalArgumentException if {@code context} holds no group
     */
    public static void recordTestPlanPersist(final DataStore dataStore,
                                             final DistributedRunnerContext context,
                                             final boolean updateDBMapping,
                                             final boolean updateDBStats,
                                             final boolean updateDBTestRunHistory) {
        if (!context.isClaimed()) {
            throw new IllegalArgumentException("a distributed runner that claimed no group has no "
                    + "group to complete when its JVM exits: " + context);
        }

        // Replaces the previous test plan's recording outright rather than updating it in place, so
        // what is published is always a complete recording - store, context and figures together -
        // and an exit landing mid-record can only ever see the whole of one test plan's or the
        // whole of the next one's. The store is taken from the latest test plan too: each listener
        // builds its own, and the newest is the one most recently proved usable.
        PENDING.put(key(context), new DistributedRunCompletion(dataStore, context,
                new RecordedFigures(updateDBMapping, updateDBStats, updateDBTestRunHistory)));

        registerShutdownHook();
        log.debug("Distributed run '{}': runner '{}' will complete group {} when its JVM exits.",
                context.getRunId(), context.getRunnerKey(), context.getGroupNumber());
    }

    /**
     * Run every completion this JVM recorded: release each group's barrier and, having released it,
     * stand for election as the build's sealer. This is the shutdown hook's body, and it is the
     * entry point a test drives directly, since a test cannot exit the JVM it runs in.
     *
     * <p>Each recording is taken before it is run, so one exit means one attempt per runner
     * whatever the outcome - a completion that fails is not left behind to be retried by a later
     * call.
     */
    public static void completePendingCompletions() {
        if (PENDING.isEmpty()) {
            return;
        }

        for (DistributedRunCompletion completion : takePending()) {
            completion.completeAndSeal();
        }
    }

    /**
     * Discard every recorded completion without running any of it.
     *
     * <p>A real test JVM records one completion and then exits, so nothing in a build needs this.
     * A test process drives many runners' persists through one JVM and closes their stores as it
     * goes, so it needs a way to drop a recording it deliberately left pending rather than have the
     * JVM's real exit reach for a store that is long closed.
     */
    public static void discardPendingCompletions() {
        PENDING.clear();
    }

    /**
     * Take every pending completion out of the map, so the caller owns them and a second exit finds
     * nothing left to do.
     *
     * @return the completions that were pending, in no particular order
     */
    private static Collection<DistributedRunCompletion> takePending() {
        List<DistributedRunCompletion> taken = new ArrayList<DistributedRunCompletion>();
        for (String key : new ArrayList<String>(PENDING.keySet())) {
            DistributedRunCompletion completion = PENDING.remove(key);
            if (completion != null) {
                taken.add(completion);
            }
        }
        return taken;
    }

    /**
     * Register this JVM's shutdown hook, once, on the first test plan that records a completion.
     * A build that is not distributed never reaches here and so never installs a hook at all.
     */
    private static void registerShutdownHook() {
        if (!HOOK_REGISTERED.compareAndSet(false, true)) {
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            /** Complete this JVM's group and attempt the build's seal as the JVM exits. */
            @Override
            public void run() {
                completePendingCompletions();
            }
        }, "tia-distributed-run-completion"));
    }

    /**
     * The map key one runner's pending completion is held under: its run, its identity and its
     * group, which is exactly what the guarded completion write is matched on.
     *
     * @param context the runner's claimed context
     * @return the key identifying that runner's completion
     */
    private static String key(final DistributedRunnerContext context) {
        return context.getRunId() + "|" + context.getRunnerKey() + "|" + context.getGroupNumber();
    }

    /**
     * Complete this runner's group and, if the completion was accepted, stand for election as the
     * build's sealer - the only moment at which a runner can turn out to be the last one to finish.
     *
     * <p>Everything is caught, including {@link Error}, because this runs inside a shutdown hook
     * where anything thrown vanishes with no stack trace and no exit code to notice. A build that
     * failed to seal must at least leave a record of why, so the failure is logged against the run
     * and group it belongs to and the JVM is allowed to exit. Nothing is retried: the run is left
     * unsealed, the stored commit stays where it was, and the next build re-does the work.
     */
    private void completeAndSeal() {
        try {
            DistributedRunnerPersist runnerPersist = new DistributedRunnerPersist(dataStore, context);
            if (runnerPersist.completeGroup(System.currentTimeMillis()) == null) {
                // The claim died while this JVM was running its tests, so the group is not complete.
                // A runner that never completed its group cannot be the last one, and its run has
                // been superseded anyway - there is nothing of this build left to seal.
                return;
            }

            new DistributedRunSealer(dataStore, context)
                    .sealIfElected(figures.updateDBMapping, figures.updateDBStats,
                            figures.updateDBTestRunHistory, System.currentTimeMillis());
        } catch (Throwable throwable) {
            log.error("Distributed run '{}': runner '{}' failed to complete group {} as its JVM was "
                            + "exiting, so this build will not be sealed and the stored commit value "
                            + "stays where it was. The next build will re-do this run's work.",
                    context.getRunId(), context.getRunnerKey(), context.getGroupNumber(), throwable);
        }
    }

    /**
     * The build-identifying figures a test plan recorded, held together as one immutable group so a
     * later test plan replaces the whole recording atomically rather than field-by-field. Every
     * field here is the same across every test plan in a JVM - which of the mapping DB, Tia-level
     * stats and history row it owns - so there is no mixture to guard against; the value of holding
     * them together is simply that replacement is one assignment rather than several. The commit and
     * branch the build ran against used to live here too, but the seal now reads them itself from
     * the run row instead of trusting a value carried through this JVM.
     */
    private static final class RecordedFigures {

        private final boolean updateDBMapping;
        private final boolean updateDBStats;
        private final boolean updateDBTestRunHistory;

        /**
         * Store the build-identifying figures a finished test plan reported.
         *
         * @param updateDBMapping whether the build owns mapping-DB updates
         * @param updateDBStats whether the Tia-level run stats should be updated
         * @param updateDBTestRunHistory whether the build should write its one history row
         */
        RecordedFigures(final boolean updateDBMapping, final boolean updateDBStats,
                        final boolean updateDBTestRunHistory) {
            this.updateDBMapping = updateDBMapping;
            this.updateDBStats = updateDBStats;
            this.updateDBTestRunHistory = updateDBTestRunHistory;
        }
    }
}
