package org.tiatesting.core.distributed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.CoreStatsIncrement;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.SealedRunDataAssembler;
import org.tiatesting.core.testrunner.RunEnvironment;
import org.tiatesting.core.report.ReportUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p><b>Why the whole build's stats and history are the sealer's job too.</b> A distributed runner
 * writes no {@code tia_core} row at all, even when the build updates stats, because the commit stamp
 * and the Tia-level stats share that row and the stamp belongs here. So this is the only place the
 * build's stats can be recorded, and it records them once, from the figures its groups add up to -
 * otherwise a distributed build would silently stop moving the Tia-level counters with nothing
 * failing to say so. The same applies to the history row, and to the all-tests-run baseline savings
 * are measured against: a single-host run advances that baseline only when it ignored zero suites,
 * and no runner in a split build ever does, so the union check here is what keeps the baseline
 * moving once a project distributes its tests.
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
     * <p>The commit and branch being sealed are no longer taken from the caller: they are read from
     * the plan's own run row, which every runner was already verified against before it claimed. That
     * makes the plan's commit authoritative by construction rather than by a runner passing back a
     * value that then has to agree with it - see {@link #recordBuild} for where that read happens.
     *
     * @param updateDBMapping whether this build owns mapping-DB updates. When false there is
     *                        nothing to seal - no runner staged anything and no commit may be
     *                        advanced - but the run is still retired
     * @param updateDBTestRunHistory whether the build should write its one row to
     *                               {@code tia_test_run_history}
     * @param sealedAtMs UTC epoch millis to record as the election time
     * @return true when this runner won the election and performed the seal, false when it did
     *         nothing
     */
    public boolean sealIfElected(final boolean updateDBMapping,
                                 final boolean updateDBTestRunHistory, final long sealedAtMs) {
        if (!dataStore.electSealer(context.getRunId(), context.getRunnerKey(), sealedAtMs)) {
            log.debug("Distributed run '{}': runner '{}' is not the sealer, so it is done.",
                    context.getRunId(), context.getRunnerKey());
            return false;
        }

        log.info("Distributed run '{}': runner '{}' finished last and was elected to seal the build.",
                context.getRunId(), context.getRunnerKey());

        if (updateDBMapping || updateDBTestRunHistory) {
            recordBuild(updateDBMapping, updateDBTestRunHistory);
        } else {
            log.info("Distributed run '{}': the build updates neither the mapping nor the "
                    + "history, so there is nothing to record.", context.getRunId());
        }

        // The run is retired whether or not it owned mapping updates. The staging table is roughly
        // the size of the method catalogue, so it is cleared here rather than left for the next
        // build's plan write to clear.
        dataStore.markDistributedRunSealed(context.getRunId());
        dataStore.deleteStagedMethodTrackers(context.getRunId());
        return true;
    }

    /**
     * Record everything that describes the build as a whole rather than any one runner: the seal
     * itself, the Tia-level run stats, and the single history row. All three are driven by the same
     * two derived values - the totals its groups add up to, and whether those groups between them
     * covered every tracked suite - so they are derived once here and shared, rather than each
     * being worked out separately and risking disagreement about what the build did.
     *
     * <p>The commit and branch being sealed are read here, from the run row this election just won,
     * rather than accepted as parameters. The read is treated as an assertion of an invariant rather
     * than a recovery path: {@link
     * DataStore#electSealer} only returns {@code true} after matching and updating that exact row
     * under this run id, so the row existed a moment earlier, and nothing but a new plan write
     * clears it - which cannot happen for this run id until this run reaches its terminal state,
     * which has not happened yet. A {@code null} read here is therefore a broken invariant, not a
     * race a caller can sensibly recover from, so it is reported as such rather than silently
     * substituting a fallback value.
     *
     * <p>When the history row is written, the selection breakdown the plan staged for this run id
     * is read here too, via {@link DataStore#readDistributedRunSelectionDetails}, and handed to
     * {@link #persistBuildHistory} to stamp onto that row and write as trigger rows - the
     * distributed build's counterpart of what a single-host run's {@code TestRunnerService} does
     * for its own row. A seed run, or any run predating this feature, staged nothing and reads
     * back {@link TestRunSelectionDetails#empty()} rather than null.
     *
     * @param updateDBMapping whether this build owns mapping-DB updates
     * @param updateDBTestRunHistory whether the build should write its history row
     * @throws IllegalStateException if the run row is gone immediately after this runner won the
     *                                election to seal it
     */
    private void recordBuild(final boolean updateDBMapping,
                             final boolean updateDBTestRunHistory) {
        DistributedRun run = dataStore.readDistributedRun(context.getRunId());
        if (run == null) {
            throw new IllegalStateException("Distributed run '" + context.getRunId() + "': the run "
                    + "row is gone immediately after runner '" + context.getRunnerKey() + "' won the "
                    + "election to seal it. This cannot happen by design: electSealer only returns "
                    + "true after matching and updating that exact row, so it existed a moment "
                    + "earlier, and nothing clears a run's row before that run reaches its terminal "
                    + "state.");
        }
        String commitValue = run.getCommitValue();
        String branch = run.getBranch();

        List<DistributedRunGroup> groups = dataStore.readDistributedRunGroups(context.getRunId());
        DistributedRunTotals totals = DistributedRunTotals.from(groups);

        // Read once and share. Both the ignored-suite count and the overhead solve are answered
        // from what the plan assigned each group rather than from what any runner reported running,
        // so they must be looking at the same assignment - and reading it twice would be one small
        // query per group for nothing.
        Map<Integer, Set<String>> assignedSuitesByGroup = readAssignedSuitesByGroup(groups);
        int ignoredSuiteCount = ignoredSuiteCount(run, assignedSuitesByGroup);

        // The all-tests-run test a single-host run applies is "Tia ignored zero suites this run".
        // The same question for a build split across runners can only be asked of the groups
        // together, which is what makes it the sealer's to answer.
        //
        // The ignored half of it comes from what the plan assigned the groups, never from
        // totals.getSuitesRan() - see ignoredSuiteCount for why the obvious
        // "selectableSuites - suitesRan" simplification is wrong. The liveness half does read
        // suitesRan, and safely: retry inflation can only make an already non-zero counter larger,
        // never make a zero one non-zero, and a build in which no group ran anything must be
        // excluded because folding it into the full-suite baseline would drive that baseline, and
        // every later savings figure, towards nothing.
        boolean allTestsRun = totals.getSuitesRan() > 0 && ignoredSuiteCount == 0;

        // The same question a single-host run asks of itself (TestRunResult#ranNoExpectedSuites),
        // asked of the build: did it execute none of the suites its plan expected? Only the plan can
        // answer the "expected" half for a split build, which is why it is derived here.
        boolean ranNoExpectedSuites = ranNoExpectedSuites(run, assignedSuitesByGroup, totals);
        if (ranNoExpectedSuites) {
            logEmptyBuild(run, assignedSuitesByGroup, updateDBMapping);
        }

        // Logged unconditionally, and with both inputs, because a wrong answer here is silent and
        // expensive: a false "all tests run" folds a partial build into the full-suite baseline and
        // advances every tracked library's mapping baseline, under-selecting on later builds with
        // nothing in the output to show why.
        log.info("Distributed run '{}': the build ran {} suite(s) across {} group(s) and left {} "
                        + "tracked suite(s) unrun, so it {} count as a full run of every test.",
                context.getRunId(), totals.getSuitesRan(), totals.getGroupCount(), ignoredSuiteCount,
                allTestsRun ? "DOES" : "does not");
        log.info("Distributed run '{}': serial-equivalent duration {}ms (the figure the stats and "
                        + "savings use), wall clock {}ms (the slowest group).", context.getRunId(),
                totals.getSerialDurationMs(), totals.getWallClockMs());

        TiaData tiaData = dataStore.getTiaCore();

        // Both stats contributions are assembled here as a delta, before the seal, so the seal is
        // left with one job - persisting - and the two land in its single transaction together. A
        // delta rather than an in-memory merge so the store accumulates against the row's value at
        // write time; see CoreStatsIncrement.
        CoreStatsIncrement statsIncrement = CoreStatsIncrement.none();
        if (updateDBMapping && !ranNoExpectedSuites) {
            statsIncrement = CoreStatsIncrement.of(buildRunStats(totals), allTestsRun);
            DistributedRunOverheadModel model =
                    solveOverheadModel(tiaData, run, groups, assignedSuitesByGroup);
            if (model != null && model.isSolved()) {
                statsIncrement = statsIncrement.withOverheadModel(model.getFixedOverheadMs(),
                        model.getCaptureOverheadPerSuiteMs());
            }
        }

        if (!ranNoExpectedSuites) {
            seal(tiaData, commitValue, branch, updateDBMapping, allTestsRun, statsIncrement);
        }

        if (updateDBTestRunHistory) {
            // The baseline this build's savings are frozen against, read from the same core data
            // the seal has just updated, exactly as the single-host persist reads it after its own
            // seal. An all-tests build saves nothing by definition, so the ordering only matters
            // for a partial build, and a partial build does not move the baseline.
            //
            // An empty build's row reports no mapping update whatever the build was configured to
            // do, because the seal above did not run - the same shape the single-host empty run's
            // row takes.
            // Read once here, where the run id is already in scope from the election this method
            // opened with, rather than inside persistBuildHistory: every other value that method
            // writes onto the row is already a parameter, and this keeps that method a pure
            // "assemble and persist" step with no datastore reads of its own. Never null - see
            // DataStore#readDistributedRunSelectionDetails - so a seed or pre-feature run reads
            // back TestRunSelectionDetails.empty() rather than forcing a null check here.
            TestRunSelectionDetails selectionDetails =
                    dataStore.readDistributedRunSelectionDetails(context.getRunId());
            persistBuildHistory(commitValue, branch, updateDBMapping && !ranNoExpectedSuites, totals,
                    ignoredSuiteCount, allTestsRun, tiaData.getTestStats().getAllTestsRunTime(),
                    run.getCreatedAtMs(), ranNoExpectedSuites, selectionDetails);
        }
    }

    /**
     * Write the build's seal: the method catalogue rebuilt from every runner's observations, the
     * library drain cleanup the plan recorded, the Tia-level run stats aggregated from every group,
     * and the commit value, in the one transaction {@link DataStore#persistSealedRunData} provides.
     * The catalogue's line numbers only mean anything in one commit's coordinate space, so they and
     * the commit value cannot be allowed to land separately.
     *
     * <p>A build that does not own mapping updates writes nothing here at all: no catalogue, no
     * drain cleanup and, deliberately, no commit or branch write. Those describe the mapping this
     * build does not own, and writing them back from the snapshot read at the start of the seal
     * would roll the stored commit backwards if a mapping-owning build advanced it meanwhile. The
     * same shape the single-host path takes.
     *
     * @param tiaData the core data read for this seal, already carrying any stats update and
     *                mutated with the commit before being written
     * @param commitValue the commit being sealed
     * @param branch the branch being sealed
     * @param updateDBMapping whether this build owns mapping-DB updates, and with them the stats
     * @param allTestsRun whether the groups between them ran every tracked suite
     * @param statsIncrement the build's contribution to the Tia-level stats, accumulated by the
     *                       store at write time
     */
    private void seal(final TiaData tiaData, final String commitValue, final String branch,
                      final boolean updateDBMapping, final boolean allTestsRun,
                      final CoreStatsIncrement statsIncrement) {
        if (!updateDBMapping) {
            log.info("Distributed run '{}': the build does not own mapping updates, so there is "
                    + "nothing to seal.", context.getRunId());
            return;
        }

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

        dataStore.persistSealedRunData(new SealedRunDataAssembler(dataStore).assemble(tiaData,
                stagedMethodTrackers, drainResult, commitValue, allTestsRun, statsIncrement));

        log.info("Distributed run '{}': sealed at commit '{}' with {} method(s) in the catalogue.",
                context.getRunId(), commitValue, tiaData.getMethodsTracked().size());
    }

    /**
     * Write the one history row a distributed build produces, in place of the row per runner that
     * would otherwise multiply the history - and every savings total computed from it - by the
     * build's fan-out.
     *
     * <p>The row's duration is the serial-equivalent time, so it means the same thing as the
     * duration on the single-host rows either side of it and the savings frozen onto it stay
     * comparable with theirs - which is why it charges the runners' fixed per-JVM overhead once
     * rather than once per group; see {@link DistributedRunTotals}. The wall clock the build
     * actually took is carried in its own column alongside. The row is stamped with the time the run was planned rather than with any runner's
     * own start time, since that is the one timestamp every runner in the build shares.
     *
     * <p>The row also carries the build's selection breakdown, copied from what the plan staged
     * for this run id: {@code selectionDetails}'s five scalar counters are folded into the entry
     * via {@link TestRunHistoryEntry#createForDistributedRun}, and its per-method/per-rule
     * triggers are persisted separately via {@link DataStore#persistTestRunTriggers} keyed on the
     * entry's id - the same split a single-host run's {@code TestRunnerService} makes for its own
     * row. {@code selectionDetails} is never null - {@link
     * DataStore#readDistributedRunSelectionDetails} returns {@link TestRunSelectionDetails#empty()}
     * at worst - so a seed run or a run predating this feature writes zero counters and no
     * trigger rows rather than nulls.
     *
     * @param commitValue the commit the build ran against
     * @param branch the branch the build ran against
     * @param updateDBMapping whether the build persisted mapping updates, stamped on the row
     * @param totals the figures the build's groups add up to
     * @param ignoredSuiteCount the tracked suites the build did not run
     * @param allTestsRun whether the groups between them ran every tracked suite, which is what
     *                    makes this build's savings zero
     * @param allTestsRunTimeMs the full-suite baseline to freeze this build's savings against
     * @param runTimestampMs UTC epoch millis when the run's plan was written, read from the same
     *                       run row {@link #recordBuild} already read the commit and branch from,
     *                       so the row is read once per seal rather than once per figure
     * @param ranNoExpectedSuites whether the build executed none of the suites its plan expected; such
     *                            a build still gets its row - a {@code ran=0} row is how it stays
     *                            visible - but is credited no savings, having finished early because
     *                            it ran nothing rather than because Tia deselected anything
     * @param selectionDetails the build-level selection breakdown staged at plan time, copied onto
     *                         the row's counters and trigger rows; never null
     */
    private void persistBuildHistory(final String commitValue, final String branch,
                                     final boolean updateDBMapping, final DistributedRunTotals totals,
                                     final int ignoredSuiteCount, final boolean allTestsRun,
                                     final long allTestsRunTimeMs, final long runTimestampMs,
                                     final boolean ranNoExpectedSuites,
                                     final TestRunSelectionDetails selectionDetails) {
        long timeSavingsMs = ReportUtils.runSavingsMs(allTestsRunTimeMs,
                totals.getSerialDurationMs(), allTestsRun || ranNoExpectedSuites);
        int savingsPercent = (int) ReportUtils.percentOfTotal(timeSavingsMs, allTestsRunTimeMs);

        TestRunHistoryEntry entry = TestRunHistoryEntry.createForDistributedRun(branch, commitValue,
                context.getRunId(), runTimestampMs, totals.getSuitesRan(), ignoredSuiteCount,
                totals.getSuitesFailed(), totals.getSerialDurationMs(), updateDBMapping,
                timeSavingsMs, savingsPercent, totals.getWallClockMs(), totals.getGroupCount(),
                RunEnvironment.distributedRunOrigin(), selectionDetails);
        dataStore.persistTestRunHistoryEntry(entry);
        // Best-effort: the row and its counters are already saved and the per-trigger rows are
        // diagnostic detail only, so a failure writing them (for example an overlong trigger name)
        // is logged and swallowed rather than failing a build that has already sealed.
        try {
            dataStore.persistTestRunTriggers(entry.getId(), selectionDetails.getTriggers());
        } catch (RuntimeException e) {
            log.warn("Distributed run '{}': failed to persist the selection-trigger breakdown for "
                    + "history row {}; the row and its counters were saved, only the per-trigger "
                    + "detail is missing.", context.getRunId(), entry.getId(), e);
        }

        log.info("Distributed run '{}': recorded the build's history row {} (groups={}, ran={}, "
                        + "ignored={}, failed={}, serialMs={}, wallClockMs={}, "
                        + "fixedOverheadChargedOnceMs={}, savingsMs={}).",
                context.getRunId(), entry.getId(), totals.getGroupCount(), totals.getSuitesRan(),
                ignoredSuiteCount, totals.getSuitesFailed(), totals.getSerialDurationMs(),
                totals.getWallClockMs(), totals.getFixedOverheadMs(), timeSavingsMs);
    }

    /**
     * Whether the build executed none of the suites its plan expected it to - the distributed shape of
     * the empty run a misconfigured project produces, and the reason such a build seals nothing and
     * records no stats.
     *
     * <p>The execution half is {@code DistributedRunTotals.getSuitesRan() == 0}, and reading zero from
     * that counter is safe in a way reading a non-zero value would not be: it accumulates across every
     * test plan in every runner's JVM, so Surefire retries can only inflate it, never turn a genuine
     * zero into something else. If it is zero, no group ran a suite on any attempt.
     *
     * <p>The expectation half comes from the plan, never from the counter, and turns on the same
     * distinction {@link #ignoredSuiteCount} has to make between the two opposite builds that plan an
     * empty assignment. A <em>seed run</em> expected to run everything, so executing nothing makes it
     * empty. A <em>nothing-impacted</em> build - a real selection that chose no suites - expected to
     * run nothing, so executing nothing is Tia working as intended and its stats and savings are
     * recorded as usual. Any build with suites assigned to a group expected those suites.
     *
     * @param run the run row this seal already read, carrying the planner's seed-run flag
     * @param assignedSuitesByGroup the suite names the plan assigned each group
     * @param totals the figures the build's groups add up to
     * @return true when no group ran a suite though the plan expected at least one to
     */
    private boolean ranNoExpectedSuites(final DistributedRun run,
                                        final Map<Integer, Set<String>> assignedSuitesByGroup,
                                        final DistributedRunTotals totals) {
        if (totals.getSuitesRan() > 0) {
            return false;
        }

        return run.isSeedRun() || assignedAnySuite(assignedSuitesByGroup);
    }

    /**
     * Whether the plan assigned any suite to any group, which is what separates a build that expected
     * to run named suites from one that was assigned nothing at all.
     *
     * @param assignedSuitesByGroup the suite names the plan assigned each group
     * @return true when at least one group was assigned at least one suite
     */
    private boolean assignedAnySuite(final Map<Integer, Set<String>> assignedSuitesByGroup) {
        for (Set<String> groupSuites : assignedSuitesByGroup.values()) {
            if (!groupSuites.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Warn that the build executed none of the suites its plan expected, and say what the seal is
     * doing about it. At WARN for the reason the single-host path warns: no suite ran, so no suite
     * failed, and every runner's build reports itself as a pass - the only other symptom is a
     * suspiciously fast build.
     *
     * <p>Says only what applies, in both directions. A build that was not updating the mapping had no
     * seal, stats or commit stamp to withhold in the first place, so it is told what it is missing
     * rather than what was taken away. And no single cause is named: a broken test framework is the
     * reported one, but a filter that excluded every runner's share, or an assignment whose suites are
     * all disabled in source, produce the same shape and are not distinguishable from here.
     *
     * @param run the run row this seal already read, carrying the planner's seed-run flag
     * @param assignedSuitesByGroup the suite names the plan assigned each group, counted for the
     *                              warning so it names what was expected
     * @param updateDBMapping whether this build owned mapping-DB updates
     */
    private void logEmptyBuild(final DistributedRun run,
                               final Map<Integer, Set<String>> assignedSuitesByGroup,
                               final boolean updateDBMapping) {
        Set<String> assignedSuites = new HashSet<>();
        for (Set<String> groupSuites : assignedSuitesByGroup.values()) {
            assignedSuites.addAll(groupSuites);
        }
        String expected = run.isSeedRun() && assignedSuites.isEmpty()
                ? "every test suite"
                : assignedSuites.size() + " selected test suite(s)";

        String consequence = updateDBMapping
                ? "The build is treated as an empty run: nothing is sealed, so it contributes no run "
                        + "stats, moves no run-time average, is credited no savings and does not "
                        + "advance the stored commit value - the next build will diff against the "
                        + "previous commit and re-select this build's tests."
                : "This build was not updating the mapping DB, so it had no seal or stats to record "
                        + "either way; only its history row is written, credited no savings.";

        log.warn("Distributed run '{}': no group executed a single test suite, though the plan "
                        + "assigned {} to run across {} group(s). {} Common causes: a test framework "
                        + "that is not wired up correctly (a missing or mismatched test dependency), "
                        + "a build-tool filter that excluded the runners' shares, or every assigned "
                        + "suite being disabled in source.",
                context.getRunId(), expected, assignedSuitesByGroup.size(), consequence);
    }

    /**
     * How many tracked suites the build did not run - Tia's selection decision for the build as a
     * whole, and the same quantity a single-host run reports from its selector's ignore list.
     *
     * <p><b>Read from the plan's assignment, not from the execution counter.</b> The obvious
     * simplification - tracked selectable suites minus {@code DistributedRunTotals.getSuitesRan()} -
     * is wrong, and wrong in the silent direction. {@code suites_ran} is deliberately an
     * accumulating counter of <em>executions</em>: {@link DataStore#reportGroupProgress} adds to it
     * on every persist, so a Surefire retry within one runner's JVM legitimately sums into it. A
     * partial build with enough reruns therefore drives a difference-based ignored count to zero and
     * flips {@code allTestsRun} to {@code true}, which folds the partial build's duration into the
     * all-tests baseline permanently and advances every tracked library's mapping baseline commit as
     * though every suite had just been re-covered - an under-selection path, and the exact failure
     * class the distributed feature exists to close off. What the plan assigned each group cannot be
     * moved by any number of retries, so it is what this counts against.
     *
     * <p><b>A seed run returns zero up front.</b> A seed run runs every test, so it ignores nothing.
     * Its assignment shape does not matter: a fanned-out seed carries disk-scanned suite names and a
     * collapsed seed carries none, but neither is a reliable tracked-vs-assigned basis, and the
     * barrier only released once every group observed at least one suite. Deciding this from {@link
     * DistributedRun#isSeedRun()} rather than from the assignment also makes it robust to a tracked
     * suite whose name the disk scan did not enumerate (a {@code @Nested} binary name, say), which
     * the general path below would otherwise miscount as ignored.
     *
     * <p>For a non-seed build the count is read from the plan's assignment, never from the execution
     * counter - see the {@code suites_ran} caveat below.
     *
     * <p>Getting that the wrong way round on a nothing-impacted build is not merely a wrong number in
     * the history row. With the ignored count reported as zero, {@code allTestsRun} rests entirely on
     * {@code suitesRan > 0}, and a single suite in the workspace that is in neither the tracked map
     * nor the plan is on nobody's ignore list and runs anyway - enough to declare a build that ran
     * one suite an all-tests run, overwrite the full-suite savings baseline with its duration and
     * advance every tracked library's mapping baseline commit. That is under-selection on the
     * libraries' next build, which is the failure class the distributed feature exists to close off.
     *
     * <p>Suites the developer disabled in source are excluded, matching {@code
     * TestSelector.getTestsToIgnore}: they would not run without Tia either, so counting them would
     * hold the all-tests baseline down permanently in any project that has one.
     *
     * <p>One small query per group, at seal time only - never on the hot read path.
     *
     * @param run the run row this seal already read, carrying the planner's seed-run flag
     * @param assignedSuitesByGroup the suite names the plan assigned each group, keyed by group
     *                              number
     * @return the number of tracked, non-developer-disabled suites the plan did not assign to any
     *         group; always zero for a seed run
     */
    private int ignoredSuiteCount(final DistributedRun run,
                                  final Map<Integer, Set<String>> assignedSuitesByGroup) {
        // A seed run runs every test by definition, and its group assignments come from a disk scan
        // that over-includes non-test classes - not a reliable basis for a tracked-vs-assigned
        // difference. Whether it fanned out across groups (assigned real suite names) or collapsed to
        // a single empty group (assigned nothing), a sealed seed run ignored nothing: the barrier
        // only released once every group had observed at least one suite. See the distributed test
        // runs chapter in WIKI.md.
        if (run.isSeedRun()) {
            return 0;
        }

        Set<String> assignedSuites = new HashSet<>();
        for (Set<String> groupSuites : assignedSuitesByGroup.values()) {
            assignedSuites.addAll(groupSuites);
        }

        if (assignedSuites.isEmpty()) {
            return countTrackedSelectableSuites();
        }

        int ignoredSuites = 0;
        for (TestSuiteTracker tracker : dataStore.getTestSuitesTracked().values()) {
            if (!tracker.isDeveloperDisabled() && !assignedSuites.contains(tracker.getName())) {
                ignoredSuites++;
            }
        }
        return ignoredSuites;
    }

    /**
     * Count the tracked suites Tia could have selected for this build: every tracked suite the
     * developer has not disabled in source. This is what a build that was assigned no suites at all
     * ignored, and the same population {@link #ignoredSuiteCount} measures a non-empty assignment
     * against, kept here so the two cannot disagree about which suites are selectable.
     *
     * @return the number of tracked, non-developer-disabled suites
     */
    private int countTrackedSelectableSuites() {
        int selectable = 0;
        for (TestSuiteTracker tracker : dataStore.getTestSuitesTracked().values()) {
            if (!tracker.isDeveloperDisabled()) {
                selectable++;
            }
        }
        return selectable;
    }

    /**
     * Read what the plan assigned every group, keyed by group number.
     *
     * <p>One small query per group, at seal time only - never on the hot read path - and the single
     * read both the ignored-suite count and the overhead solve are answered from.
     *
     * @param groups the run's groups, as read back from the datastore after the barrier
     * @return each group's assigned suite names, keyed by group number; a group assigned nothing
     *         maps to an empty set rather than being absent
     */
    private Map<Integer, Set<String>> readAssignedSuitesByGroup(
            final List<DistributedRunGroup> groups) {
        Map<Integer, Set<String>> assignedSuitesByGroup = new HashMap<>();
        for (DistributedRunGroup group : groups) {
            assignedSuitesByGroup.put(Integer.valueOf(group.getGroupNumber()),
                    new HashSet<>(dataStore.readDistributedRunGroupSuites(context.getRunId(),
                            group.getGroupNumber())));
        }
        return assignedSuitesByGroup;
    }

    /**
     * Solve this build's overhead into its fixed per-JVM part and its per-suite capture part, and
     * fold the pair into the Tia-level rolling averages.
     *
     * <p>This is the only place the two can be separated. A single-host run gives one equation for
     * two unknowns; a distributed build gives a second one at a different suite count, from the
     * per-group split every runner already reports. See {@link DistributedRunOverheadModel} for the
     * algebra and for why every failure is a skip rather than an approximation.
     *
     * <p><b>A seed run is skipped outright</b>, before the solve is even attempted. Its single group
     * is assigned no suite names at all - it runs everything by definition - so its assignment count
     * of zero is not a measurement of a group that ran nothing, and feeding it in would attribute
     * the whole of a full-suite run's overhead to the fixed term. It is also degenerate on its own
     * terms: one group covering every suite makes the second equation a restatement of the first.
     * The same empty assignment on a <em>non</em>-seed build means the opposite thing and is a real
     * measurement, which is why the persisted flag decides rather than the assignment's shape - the
     * same distinction {@link #ignoredSuiteCount} turns on.
     *
     * @param tiaData the core data being sealed, read for the all-tests baseline the solve needs
     * @param run the run row this seal already read, carrying the planner's seed-run flag
     * @param groups the run's groups, as read back from the datastore after the barrier
     * @param assignedSuitesByGroup the suite names the plan assigned each group, keyed by group
     *                              number
     * @return the solved model, or null when this build supplies no measurement at all. The caller
     *         checks {@link DistributedRunOverheadModel#isSolved()} before folding it in - an
     *         unsolved model must leave the stored averages untouched rather than contribute a zero
     */
    private DistributedRunOverheadModel solveOverheadModel(final TiaData tiaData, final DistributedRun run,
                                   final List<DistributedRunGroup> groups,
                                   final Map<Integer, Set<String>> assignedSuitesByGroup) {
        if (run.isSeedRun()) {
            log.debug("Distributed run '{}': this was a seed run, whose one group is assigned no "
                    + "suite names because it runs everything, so it supplies no second equation "
                    + "for the overhead model.", context.getRunId());
            return null;
        }

        Map<String, TestSuiteTracker> tracked = dataStore.getTestSuitesTracked();
        long summedSuiteAveragesMs = 0L;
        for (TestSuiteTracker tracker : tracked.values()) {
            summedSuiteAveragesMs += tracker.getTestStats().getAvgRunTime();
        }
        long wholeRunOverheadMs =
                tiaData.getTestStats().getAllTestsRunTime() - summedSuiteAveragesMs;

        Map<Integer, Integer> assignedSuiteCounts = new HashMap<>();
        for (Map.Entry<Integer, Set<String>> entry : assignedSuitesByGroup.entrySet()) {
            assignedSuiteCounts.put(entry.getKey(), Integer.valueOf(entry.getValue().size()));
        }

        DistributedRunOverheadModel model = DistributedRunOverheadModel.solve(wholeRunOverheadMs,
                tracked.size(), groups, assignedSuiteCounts);

        if (!model.isSolved()) {
            log.debug("Distributed run '{}': the overhead model was not solved, so the stored "
                            + "averages are left as they are. Reason: {}.", context.getRunId(),
                    model.getSkipReason());
            return model;
        }

        log.info("Distributed run '{}': measured a fixed per-JVM overhead of {}ms and a per-suite "
                        + "capture overhead of {}ms from a whole-run overhead of {}ms across {} "
                        + "tracked suite(s). The pair is folded into the stored rolling averages at "
                        + "the seal.", context.getRunId(), model.getFixedOverheadMs(),
                model.getCaptureOverheadPerSuiteMs(), wholeRunOverheadMs, tracked.size());
        return model;
    }

    /**
     * Build the run stats the whole build contributes to the Tia-level counters: one run, taking
     * the serial-equivalent time, that succeeded only if no group reported a failed suite. This is
     * the shape a single-host run reports for itself, so a project's stats read the same either
     * side of the point where it switched distributed mode on.
     *
     * @param totals the figures the build's groups add up to
     * @return the stats to fold into the core row
     */
    private TestStats buildRunStats(final DistributedRunTotals totals) {
        TestStats stats = new TestStats();
        stats.setNumRuns(1);
        stats.setAvgRunTime(totals.getSerialDurationMs());
        stats.setNumSuccessRuns(totals.getSuitesFailed() == 0 ? 1 : 0);
        stats.setNumFailRuns(totals.getSuitesFailed() == 0 ? 0 : 1);
        return stats;
    }
}
