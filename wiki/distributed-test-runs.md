# Distributed test runs (group assignment and the run lifecycle)

Tia normally assumes it is the only thing running: one build computes one selection, runs it, and
writes one set of results. Splitting that across CI runners naively gives N independent Tia runs
that each compute their own selection and each try to write their own mapping - wasteful, and for
the shared database actively unsafe.

A distributed test run makes Tia aware of the topology instead: **one logical build, N runners, one
shared database**. A planning step computes the selection once and splits it into groups; each
runner claims exactly one group from the database and runs only that group's suites; the runner
that finishes last is elected to seal the build.

What this deliberately does not change: tests still run **sequentially inside each runner**. The
parallelism is across hosts only, because Tia relies on one-suite-at-a-time execution in a single
JVM so JaCoCo can attribute coverage to the right suite (see "Multi-fork persist" in the
[persist flow and crash safety](persist-flow-and-crash-safety.md) chapter).

This chapter covers the mechanism. For setting a distributed run up - the `dist-plan` /
`dist-complete` / `dist-status` goals, the configuration properties and their Gradle equivalents,
and a copy-paste CI pipeline - see the README's
[Distributed test runs](../README.md#distributed-test-runs) section.

The run's state lives in four tables - `tia_distributed_run` (one row per logical build),
`tia_distributed_run_group` (one row per group), `tia_distributed_run_group_suite` (which suites
belong to which group), and `tia_distributed_run_method_stage` (the runners' staged method
trackers). A run row moves `OPEN -> SEALED`; a group row moves `PENDING -> CLAIMED -> COMPLETED`
and never backwards.

## Group assignment

### What the plan step does

The plan is written by the Maven `dist-plan` goal (`AbstractTiaDistPlanMojo`) or the Gradle
`tia-dist-plan` task (`TiaDistPlanTask`). The two are deliberately the same sequence, and share
every piece of logic that produces a value:

1. `DistributedRunPreconditions.check` - the four rules the plugin layer owns (Tia enabled,
   single-project build, shared database, `tiaCheckLocalChanges` off). See "Multi-module is not
   supported" below for the second one.
2. `DistributedRunConfig.validated` - exactly one of `tiaDistributedGroupCount` or
   `tiaDistributedTargetRunTime`, with `tiaDistributedMaxGroups` only alongside the target.
3. The **real** test selection: `TestSelector.selectTestsToIgnore`, with `updateDBMapping` set to
   the run's own configured value rather than the always-`false` the `select-tests` preview passes.
   This is the one selection the whole build gets - one VCS diff, one static-rule pass, one
   library-impact drain, rather than N of each.
4. `DistributedRunPlanner.plan` - weight, balance, project onto the persisted types, persist.
5. Print `DistributedRunPlanSummary.toConsoleSummary()` and write `toJson()` to
   `<tiaBuildDir>/tia-run-plan.json` via `DistributedRunPlanWriter`.

Step 3 is why the plan row carries a library-impact drain result. The selection has already
deleted the pending stamp rows and advanced the publish sequences by the time the planner is
entered; that drain cannot be repeated, and repeating it per-runner would race, so the plan row is
the only place its outstanding cleanup can survive the planning process exiting. The sealer applies
it at the end of the build. See the drain-rule section of the
[library publish-time stamping](library-publish-time-stamping.md) chapter for what the cleanup is.

The write itself (`DataStore.persistDistributedRunPlan`) clears the previous run's rows in the same
transaction as its own insert, so there is only ever one plan in a branch's schema. Because that
clear destroys the evidence, `DistributedRunPlanner` reads the previous run **first** and logs a
WARN naming any group that never reached `COMPLETED` - that log line is the only trace an abandoned
run leaves. A previous run that did reach `SEALED` is an ordinary supersession and is not warned
about; a run whose groups all completed but which never sealed gets its own, distinct warning,
since there are no incomplete groups to name.

One check is worth knowing about because it fails the build rather than degrading: after projecting
the balancer's result onto the persisted types, `plan` re-counts the suites carried by the plan
against `selection.getTestsToRun().size()` and throws if they differ. A suite lost between selection
and plan is a suite no runner is watching for, and the build would otherwise report success having
silently skipped it.

### How suites are packed into groups

`TestGroupBalancer` is pure - no I/O, no database, no global state - so the whole grouping policy is
unit-testable in isolation. It is fed weights, not raw times: `suiteWeights` takes the per-suite
run-time estimate Tia already computes for the selection (median fallback for never-run suites
already applied) and adds the mapping overhead, supplied as a total for the whole selection and
divided back out per suite, and only for runs that collect coverage. See the
[select-tests run-time estimate](select-tests-run-time-estimate.md) chapter for what that overhead
is and why it is not in the per-suite average.

The two modes solve different problems and use different algorithms:

- **Fixed group count** (`tiaDistributedGroupCount`) is makespan scheduling: the count is given, and
  the goal is to minimise the heaviest group. `balanceIntoGroups` walks the suites heaviest-first
  and drops each into the currently-lightest group - LPT (longest processing time first). Groups
  beyond the number of suites come back empty rather than being dropped, because the pipeline was
  told to start that many jobs.
- **Target run time** (`tiaDistributedTargetRunTime`) is bin packing: the capacity is given, and the
  group **count** is what is being minimised. `balanceForTargetRunTime` uses FFD (first-fit
  decreasing) to choose the count, then re-balances with LPT at that same count and keeps whichever
  packing has the lighter heaviest group. FFD fills groups to capacity while LPT spreads them, so
  the re-balance usually finishes sooner for the same number of runners - but not always, which is
  why both are computed and compared rather than one being assumed better.

Target mode therefore **minimises runners, it does not maximise speed**. A target of 25 minutes with
`tiaDistributedMaxGroups: 12` means "the fewest runners that get the tests under 25 minutes", not
"always use 12". The ceiling is a spend limit, not a goal; the lever for speed is the target itself.
Expect the group count to vary between builds - a one-line change selects fewer tests and needs
fewer runners than a dependency bump does. That is the feature working.

**Meeting the target is best effort, and missing it never fails the build or drops tests.** Two
independent causes, both reported on the summary and in `tia-run-plan.json`:

| Cause | Flag | What helps |
|---|---|---|
| `tiaDistributedMaxGroups` is lower than the target needs | `clampedToMaxGroups: true` | Raise the ceiling, or accept the longer build. |
| A single suite is longer than the whole target | `singleSuiteExceedsTarget: true` | Nothing about the group count - no amount of splitting divides one suite. Split the suite or raise the target. |

The second case also changes the packing: the capacity is raised to `max(target, heaviestSuite)`, so
a suite that puts the target out of reach no matter what does not also inflate the runner count.
Same makespan, fewer runners.

Every ordering decision is broken deterministically - by weight descending, then by suite name
ascending. Two runners deriving different groupings from the same selection would be undebuggable.

### The seed run

The first distributed build on a branch has no stored mapping to plan from. `DistributedRunPlanner`
short-circuits to a single group with **no** suite names, ignoring the configured group count and
target run time entirely, and logs at INFO why. That runner ignores nothing, runs everything, and
records the mapping the next build plans from. `tia-run-plan.json` carries `"seedRun": true` so a
pipeline can explain the single job.

The grouping shape is still validated on a seed run, so a misconfigured
`groupCount`/`targetRunTime` pair is reported ahead of the build that will need it corrected rather
than on it. And if `tiaUpdateDBMapping` is off, the plan step logs a WARN naming that property: a
seed run that records no mapping leaves the next build another seed run, indefinitely.

### The claim protocol

No runner is told which group it is. Each runner claims one, and `JdbcDataStore.claimNextPendingGroup`
is the operation that decides which. Three steps:

- **Step 0 - does this runner key already hold a group in this run?** If so, that group is returned
  unchanged and no new claim is attempted. This is what makes a CI job retry idempotent: the retried
  job re-claims *its own* group and can still complete it. It is also why
  `tiaDistributedRunnerKey` matters - see that section below.
- **Step 1 - read the lowest-numbered `PENDING` group.** Always the lowest, so every runner converges
  on the same claim order rather than fanning out unpredictably. A null result means every group is
  taken, and the claim returns nothing.
- **Step 2 - the compare-and-swap.** A single-row `UPDATE ... WHERE run_id = ? AND group_number = ?
  AND status = 'PENDING'`. That predicate is the *entire* safety mechanism: two runners racing for
  the same candidate both issue this update, the database serialises them, and only one sees a row
  count of 1. The loser sees 0 and loops back to step 1.

Nothing here is wrapped in a transaction, deliberately. The safety does not come from isolation, it
comes from the `status = 'PENDING'` predicate, which the database always evaluates against the
latest committed row regardless of which transaction issued it. A wrapping transaction would only
add lock contention between racing runners without changing the outcome.

The retry loop is bounded by the run's total group count, which is its natural bound: every failed
attempt permanently removes one group from this runner's candidate pool, since a group that loses
`PENDING` never regains it. Exceeding the bound therefore means the group table is not converging as
expected rather than ordinary contention, and is reported as a `TiaPersistenceException` rather than
spinning forever.

Around that, `DistributedRunCoordinator.claim` adds two checks that fail the build, and one outcome
that does not:

- **No run row under the configured `tiaRunId`** - this build was superseded by a later one whose
  plan write cleared these rows, or the plan step was never run for this id. Its tests were never
  going to run under this id.
- **A commit mismatch** between the plan and the runner's workspace - the plan's suite lists were
  chosen by diffing one commit, so running them against another would test different code than they
  were selected for.

Both throw. A runner is a CI job that reports pass or fail; it has no way to say "I could not tell
whether I was supposed to run anything", so the only outcome allowed to be quiet is the one where
quiet is the truth.

### Which suites a runner skips

`DistributedRunCoordinator.deriveTestsToIgnore` applies one rule:

```
(every tracked suite  UNION  every suite in the plan)  MINUS  this group's suites
```

The union with the plan's own suites is not redundant. A brand-new test class has no mapping yet, so
it appears in the plan but not in the tracked set; without the union it would be missing from every
runner's ignore list and **every runner would run it**, turning one new suite into as many duplicate
executions as there are groups.

A group number that is not in the plan throws, rather than being tolerated: subtracting nothing from
the union would leave the runner ignoring every planned suite and running none of them, while still
reporting success.

### Surplus runners

A pipeline that fans out to more jobs than the plan has groups produces **surplus runners** - jobs
that find every group already claimed. This is a normal state, not an error, and it is what lets a
fixed-size runner pool tolerate a plan that needed fewer groups than the pool holds.

A surplus runner carries a null group number all the way through: `DistributedForkProperties`
omits the group-number property rather than emitting it empty, `DistributedRunnerContext.surplusRunner`
records the absence explicitly, `deriveTestsToIgnore(null, ...)` subtracts nothing so the union
itself becomes the ignore list, and the persist and completion steps both no-op with an INFO log.
The one thing a surplus runner must **not** do is fall onto the single-host path, which is why it
still resolves a distributed context: a fork that thought it was a single host would rebuild the
method catalogue and stamp the commit for itself while the real runners were still going.

The asymmetry is worth stating plainly. **Starting more jobs than the plan asks for is harmless.
Starting fewer is the one way a distributed build can report green while skipping tests** - the
surplus groups are never claimed and their suites never run. That is why the group count is handed
to the pipeline explicitly in `tia-run-plan.json` rather than inferred, and why the fan-out step is
the integration requirement with a correctness consequence attached.

## The run lifecycle

### End to end, and which process each step happens in

| Step | Where it happens | What it does |
|---|---|---|
| Plan | the planning job's build JVM | one selection, balanced into groups, persisted `OPEN` with every group `PENDING`; `tia-run-plan.json` written |
| Claim | **each runner's build JVM** (Maven build JVM; Gradle daemon) | one group flips `PENDING -> CLAIMED` under this runner's key |
| Run | the forked test JVM | executes its group's suites; every other suite is on its ignore list |
| Report progress | the forked test JVM, once per test plan | mapping rows, failed set, staged method trackers, and a guarded progress update on the group row |
| Complete | **each runner's build JVM**, the completion step | the group flips `CLAIMED -> COMPLETED`, releasing the barrier |
| Elect | each runner's build JVM, immediately after its completion | one conditional `UPDATE` that only one runner can win |
| Seal | the winning runner's build JVM | catalogue, drain cleanup, stats, commit value, one history row; run flips to `SEALED` |

**On both build tools the claim happens in the build JVM, and the fork never claims.** The claim
produces two values the fork cannot reconstruct - the runner key (which the coordinator may have
derived) and the group number (known only to whoever won it) - so both are forwarded across the fork
boundary by `DistributedForkProperties.forkProperties(runId, runnerKey, groupNumber)`, which writes
`tiaDistributed`, `tiaRunId`, `tiaDistributedRunnerKey` and (when a group was claimed)
`tiaDistributedGroupNumber`. Either way the fork resolves them with
`DistributedForkProperties.contextFromSystemProperties()`, which is what tells its persist to take
the distributed path rather than the single-host one. How the properties travel, and where the two
suite lists get derived, follows each build tool's existing handoff - see the
[test-runner data exchange](test-runner-data-exchange.md) chapter:

- **Maven** claims in `prepare-agent` (`AbstractTiaAgentMojo`), before Surefire forks, via
  `DistributedRunnerAssignment.claim` - which claims *and* derives the two suite lists in the build
  JVM, since that is where Maven already writes `ignored-tests.txt` and `selected-tests.txt` for the
  fork to read. The claim's own values go into `${tiaBuildDir}/fork.properties`, which the Tia agent
  republishes as system properties at `premain` time, before any listener constructs.
- **Gradle** claims in the daemon, inside the test task's `doFirst` action
  (`TiaSpockGitGradlePluginTestExtension.claimDistributedRun`), before the test task forks, and sets
  the values as ordinary `Test` task system properties, which Gradle forwards into the forked JVM
  itself. It stops at `DistributedRunCoordinator.claim`'s `ClaimOutcome` rather than deriving suite
  lists nothing in the daemon would read; the fork derives them for itself with
  `DistributedRunnerAssignment.forClaimedRunner(...)` (`TiaSpockGlobalExtension`), which is the same
  derivation Maven's claim path runs - a hand-written second copy is exactly what would let the two
  build tools silently disagree about which suites a runner skips. The claim is also recorded in
  this build's `DistributedClaimRegistry`, keyed by test task path, because the daemon-side
  completion step needs to read it back after the fork has exited.

Gradle used to claim inside the forked test JVM. That made it claim once per *forked JVM* rather
than once per test task - a build with `maxParallelForks > 1` could claim several groups for what is
meant to be one runner - and left the daemon with no record of which group a task held. Claiming in
the daemon fixes both, and makes Gradle symmetric with Maven, which has always claimed in the build
JVM.

A runner **never re-runs the selection**. The plan already ran the VCS diff, the static rules and the
library-impact drain once, and its output is in the shared database; a runner that re-selected would
pay for the diff again and, worse, drain the pending library rows a second time, racing with every
other runner doing the same. The runner reads the plan and claims - it never selects.

### What a runner persists, and what it does not

`TestRunnerService.persistDistributedRunnerData` is the distributed counterpart of the single-host
persist. It writes its suites' mapping rows, its contribution to the failed set, and its observed
method trackers - staged, not written to the catalogue. It writes **no** `tia_core` row, so no
commit value and no Tia-level stats, and **no** history row. All three describe the whole build and
belong to whichever runner finishes last.

Two orderings in there are correctness properties rather than tidiness:

- `DistributedRunnerPersist.claimIsLive()` runs **before any write** (see "Straggler protection"
  below).
- The group's status is **not** flipped by the persist, though its progress is reported (see "Suite
  retries" below).

Method trackers are staged rather than written because no single runner sees the whole build's
trackers. Staging them early is safe because a method id hashes the class, method and descriptor
only - it carries no line numbers - so a tracker staged by the first group to finish is still valid
against the catalogue the sealer writes at the end.

### Suite retries

`DistributedRunnerPersist.reportGroupProgress` is called on **every** persist, and a persist happens
once per finished test plan - which means several times per JVM when Surefire retries failed tests.
The figures it carries fall into three kinds that must not be treated alike, and
`JdbcDataStore.reportGroupProgress` writes each differently:

- `actual_duration_ms` and `suites_ran` are **counters**: `COALESCE(column, 0) + ?`, so several test
  plans in one JVM sum to the JVM's total instead of the last one silently overwriting the ones
  before it.
- `suites_failed` is **current state**: a plain `= ?`, because a suite that passes on retry must be
  able to leave the failed set. Accumulating it would leave a fixed suite recorded as permanently
  failed.
- `suites_observed` is written as `GREATEST(COALESCE(column, 0), ?)` - not accumulated, because the
  set it comes from is *already* cumulative across every test plan in the JVM, so summing would
  double-count. `GREATEST` rather than a plain replace is what stops a later, smaller report from
  regressing the stored value.

The status flip is deliberately not made here. A retry is another test plan in the same JVM, so
completing the group from the persist would release the barrier after the first test plan while the
runner is still executing tests - and the sealer would then rebuild the catalogue from an edge set
missing everything the later test plans covered. Only the build tool knows that no more retries are
coming, which is precisely why completion is a build-tool step.

`suites_observed` also has a precondition worth knowing: **one JVM works one group end to end**.
Maven `forkCount > 1` / `reuseForks=false` breaks it, because one `fork.properties` file carrying
one group number and runner key is read by every Surefire fork for the module, so several
independent JVMs report against the same group with their own smaller observed sets and `GREATEST`
converges on the largest single fork's count rather than the true union. That is strictly safer than
a plain replace, but it does not make multi-fork correct - multi-fork is already unsupported for the
mapping write itself.

Gradle `maxParallelForks > 1` / `forkEvery > 0` breaks the same precondition, for the same structural
reason: the claim happens once in the daemon and the resulting run id, runner key and group number
are forwarded to the test task as system properties, which Gradle hands to every worker JVM. Gradle's
case is the worse of the two, because Gradle really does split the group's suites across its workers.
No single worker ever observes the whole group, so `GREATEST` settles on the largest worker's count -
strictly less than the group's assigned total - the completeness guard never passes, the group never
completes and the run never seals. What the operator would see is a run stuck in `OPEN` with a group
still `CLAIMED`, a stored commit value that never advanced, and a **green build**, because
`completeAndSeal` returning `false` is an ordinary no-op to both build tools. Rather than let that
happen, the Gradle plugin refuses either setting when the test task starts, with an explanation. The
check runs in the test task's own action rather than at configuration time, so it reads the final
value of `maxParallelForks` after everything that configures the task has had its say.

### Straggler protection

Two things guard against a runner from a **superseded** build writing into the run that superseded
it - a straggler whose plan rows a newer build's plan write has already cleared.

Before its first mapping write, a runner calls `claimIsLive()`: is my group still `CLAIMED`, under
my runner key? A false answer means the caller must write **nothing at all** - not the suite
mapping, not the staged trackers, not the failed set - because persisting them would leave rows
describing this runner's older commit sitting under the commit the newer build has already stored.
That is the one failure mode Tia must not have.

That check is read-only by necessity - the guarded write that would prove the same thing atomically
is the completion, and the completion has to come last - so it leaves a window. The window is closed
after the fact by the guard on `completeGroup`, whose `WHERE` clause carries the same
`status = 'CLAIMED' AND runner_key = ?` predicate: a supersession landing inside the window is
detected there, the group is never marked complete, and the superseded run can therefore never elect
a sealer or advance the stored commit. The straggler's mapping rows are still there, but the commit
stamp they would have been trusted against never moves, so the next build re-selects and re-runs
that work.

### The seal-last invariant

The invariant a distributed run preserves is the same one a single-host run does:

> **If commit X is the stored value, every mapping write for X has completed.**

The [persist flow and crash safety](persist-flow-and-crash-safety.md) chapter is the full account of
why - including which failure modes remain and how each self-corrects. What matters here is *why the
direction is asymmetric*, because it is the reason for every barrier and guard in this chapter:

- **Under-writing the mapping is the silent bug.** If the mapping is missing edges - or the catalogue
  is missing methods - that the stored commit claims are current, the next build's diff finds nothing
  to select for that code. Tests that should run, don't. Every build stays green, and nothing
  anywhere reports a problem.
- **Over-writing self-corrects.** A mapping that is *ahead* of the stored commit only makes the next
  diff compute an oversized impacted set and re-run a superset of what was strictly needed. Wasted
  time, correct answer.

So every ambiguity in a distributed run is resolved towards over-running. A group that never
completes leaves the run unsealed; an unsealed run leaves the stored commit where it was; the next
build re-selects everything that build was going to cover. The one case that escapes this is a
pipeline that starts fewer jobs than the plan asked for, which is outside Tia's control - hence the
explicit group count.

The commit and branch that get stamped are read by the sealer from the **plan's own run row**, not
passed back by any runner. Every runner was verified against that commit before it was allowed to
claim, so the plan's value is authoritative by construction rather than by a runner's copy having to
agree with it.

### What only the sealer can do

`DataStore.electSealer` is one conditional single-row `UPDATE`, and its row count is the entire
answer:

```sql
UPDATE tia_distributed_run SET sealed_by = ?, sealed_at = ?
 WHERE run_id = ? AND sealed_by IS NULL
   AND NOT EXISTS (SELECT 1 FROM tia_distributed_run_group
                    WHERE run_id = ? AND status <> 'COMPLETED')
```

`sealed_by IS NULL` makes at most one runner win. The `NOT EXISTS` makes that runner the **last**
one, and that is the barrier the whole design turns on.

**The catalogue rebuild is the reason the barrier exists.** `tia_source_method` is rebuilt wholesale
from the distinct method ids on the suite-to-method edge table, and any id that query omits is
dropped. Each runner writes only its own suites' edges. So running the rebuild while a group is
still going answers with an edge set missing that group's suites: every method reachable only from
them is dropped from the catalogue, becomes invisible to the next build's diff, and the tests
covering it silently stop being selected. **That is why a group must not complete before its suites
have all reported** - and why the completion is the last write a runner makes.

Nothing is read before or instead of that `UPDATE`, deliberately. A row count of 0 covers both
"another runner won" and "my run no longer exists because a newer build superseded it", and nothing
distinguishes the two afterwards. A straggler sealer that fell back to a read and proceeded anyway
would find the staging table empty - the superseding plan write cleared it - resolve every method id
from the stored catalogue, and drop from the catalogue every id the stored catalogue lacks.

Having won, `DistributedRunSealer` does for the whole build what a single-host run does for itself:

- rebuilds the method catalogue from the union of every runner's staged trackers;
- applies the library-impact drain cleanup the plan row recorded;
- aggregates the Tia-level run stats from the groups (a distributed runner writes no core row at
  all, so this is the only place the build's stats can be recorded);
- advances the stored commit value - all four in the one atomic `persistSealedRunData` transaction;
- writes the build's single history row;
- marks the run `SEALED` and deletes its staged trackers.

An id that resolves from the stored catalogue rather than from a staged tracker is correct, not an
error to harden against: a method's line numbers can only shift if its file changed; if its file
changed its covering suites were selected; if they were selected some group ran them and staged a
fresh tracker. The sealer is only reached once every group completed, so there is no gap in that
chain. An id in neither is an orphan and is dropped, exactly as on the single-host path.

### Reporting: two durations, one history row

A distributed build writes **one** history row, by the sealer, in place of the row per runner that
would otherwise multiply the history - and every savings total computed from it - by the build's
fan-out. `DistributedRunTotals` computes the figures from the group rows, and it carries two
durations that are not interchangeable:

- The **serial-equivalent** duration is what the same selection would have cost on one host: every
  group's suite-execution time, plus the fixed per-JVM overhead **once**. This is the primary
  figure: the stats and the savings are computed from it, so "time saved by not running unimpacted
  tests" keeps meaning the same thing and stays comparable with the project's pre-distributed
  history.
- The **wall clock** is the slowest group, recorded alongside so the user can see what the build
  actually took and whether the target was met. Making it primary would credit Tia with the
  parallelism the CI system provided and would quietly redefine `avgRunTime`.

The row is stamped with the time the run was *planned*, since that is the one timestamp every runner
in the build shares. See the [test-run history log](test-run-history.md) chapter for the table
itself.

#### The fixed overhead is charged once, not once per group

The serial-equivalent figure is deliberately **not** a plain sum of the group durations. Every runner
pays a fixed cost inside its measured window - engine start-up, class loading, the gaps between
suites, the coverage dump - that a single-host run of the same suites would pay once. Summing
charges it once per group, so a build fanned out ten ways looks minutes slower than the same
selection run serially. That is not cosmetic: it under-reports savings on a partial build, and on an
all-tests build it inflates the full-suite baseline that *every later* savings figure is measured
against.

So each runner reports the split rather than just the total. Alongside `actual_duration_ms` it
records `suites_duration_ms` - the summed run time of the suites it timed - and the remainder is its
measured overhead:

```
overhead(group)  = actual_duration_ms - suites_duration_ms   (clamped at 0)
fixedOverhead    = min over groups of overhead(group)
serialEquivalent = Σ actual_duration_ms - (measured groups - 1) × fixedOverhead
```

The **minimum** is the estimator, not the mean or the maximum: it is the largest amount every runner
demonstrably paid, so subtracting `N-1` copies can never remove time that was not there. It also
leaves the variable part of each group's overhead in the total, which is what a build with a
Surefire retry needs - a retry re-runs failed tests without timing a fresh suite, so its wall time
lands in that group's overhead remainder, and it is real time the build spent rather than a
duplicated fixed cost.

`suites_duration_ms` is written via `GREATEST` rather than accumulated, unlike `actual_duration_ms`.
The runner sums it from its shared suite-tracker map, which already carries every suite timed by
every test plan the JVM has made, so a retry re-reports the same total; accumulating it would
double-count the first attempt's suites. That asymmetry is what keeps the retry's cost outside the
suite-attributable total and inside the overhead remainder.

**When the split is not available, nothing is corrected.** Suite times are only measured with
`tiaUpdateDBStats` on, so a group can complete having run suites and report zero suite time. Reading
that as "this group was pure overhead" would make the fixed overhead the whole of the fastest
group's duration and gut the total, so any group that ran suites without reporting suite time
disqualifies the whole build and the serial figure falls back to the plain sum. Falling back
over-states the duration, which under-states savings; the alternative under-states the duration,
which inflates the baseline - and only one of those two is safe to be wrong about.

The overhead charged once is logged with the seal (`fixedOverheadChargedOnceMs`), so a build
reporting `0` there is one where the fall back applied.

The all-tests-run baseline that savings are measured against needs the sealer too. A single-host run
advances that baseline only when it ignored zero suites, and no runner in a split build ever does -
so the sealer asks the question of the groups together (`suitesRan > 0` and zero ignored), which is
what keeps the baseline moving once a project distributes its tests.

The ignored half of that comes from what the plan **assigned** the groups, never from the
accumulating `suites_ran` counter, which a retry within one JVM legitimately inflates. Where the
assignment is empty it is answered from the run row's `seed_run` flag rather than from the plan's
shape: a seed run's single group carries no suite names and ignored nothing, a nothing-impacted
build's groups carry no suite names and ignored every tracked suite, and by seal time the two plans
are indistinguishable - the seed run's own runners have already populated the tracked suite map.

## The CI step

The shape is the same on both build tools and in every CI system: **run the plan step, read
`groupCount` out of `tia-run-plan.json`, start that many identical jobs.** The jobs are identical -
no index, no group number, no test list. Any matrix index the CI generates exists purely to make it
spawn the right number of jobs; it is never passed to Tia as anything other than a runner key.

`tiaRunId` must be the same value for every job in one build and different for every build. Every CI
system exposes one: `${{ github.run_id }}`, `$CI_PIPELINE_ID`, `$BUILD_TAG`, `$CIRCLE_WORKFLOW_ID`,
`$BUILDKITE_BUILD_ID`.

### Maven: the completion must be its own always-run step

**Maven aborts the lifecycle when the test goal fails.** A pipeline that chains goals in one
command (`mvn verify tia-junit5-git:dist-complete`) will never reach the completion on a runner
whose tests failed. That runner's group stays `CLAIMED`, the barrier never opens, and **the run never
seals**, even though every other runner did its job. The build then looks like a plain test failure
while quietly having thrown away the whole run's mapping work.

So the pipeline must invoke the completion step explicitly, in its own step, **whatever the test
result**:

```yaml
# GitHub Actions - the runner job
- name: Run this runner's group
  run: >
    mvn verify
    -DtiaDistributed=true
    -DtiaRunId=${{ github.run_id }}
    -DtiaDistributedRunnerKey=${{ matrix.group }}

- name: Complete this runner's group
  if: always()          # <- the whole point: runs even when the tests failed
  run: mvn tia-junit5-git:dist-complete
```

and the planning job that produced the matrix:

```yaml
- name: Plan
  run: >
    mvn tia-junit5-git:dist-plan
    -DtiaDistributed=true
    -DtiaRunId=${{ github.run_id }}
    -DtiaDistributedTargetRunTime=1500000
    -DtiaDistributedMaxGroups=10
- id: plan
  run: echo "groups=$(jq -c '[range(.groupCount)]' target/tia/tia-run-plan.json)" >> $GITHUB_OUTPUT
```

The completion goal is safe to run unconditionally: with no `fork.properties`, or a file carrying no
distributed handoff, there is nothing to complete and the goal logs that and exits successfully. It
reads the run id, runner key, group number and update-DB flags back out of `fork.properties` rather
than re-deriving any of them - a runner key it derived for itself would carry a different process
id, match no claimed row, and leave the group open forever. Its own configuration only has to supply
`tiaEnabled`, `tiaBuildDir` and the database connection settings, which normally live in the pom.
Once it knows it is closing out a claimed runner, it checks one thing before opening anything: that
it was pointed at a shared database. A separate `mvn` invocation that omitted the connection
settings would otherwise open a private embedded database, find no claimed row, and exit as if the
group were already complete - leaving the run unsealed with nothing telling the user.

**The GitLab trap.** Do not put the completion in `after_script`. GitLab swallows failures there and
applies its own separate timeout to it, so a completion that fails or runs long is invisible and the
run silently never seals. Put it in a normal `script` step and let it fail loudly:

```yaml
test:
  script:
    - set +e; mvn verify -DtiaDistributed=true -DtiaRunId=$CI_PIPELINE_ID
                         -DtiaDistributedRunnerKey=$CI_NODE_INDEX; rc=$?; set -e
    - mvn tia-junit5-git:dist-complete    # always runs, and its failure is visible
    - exit $rc
```

### Gradle: no pipeline change needed

Gradle needs none of the above. `TiaBasePlugin.createDistCompleteTask` registers the
`tia-dist-complete` task and wires it as `testTask.finalizedBy(...)`, and **a finalizer runs even
when the task it finalizes fails**. The plan step is still an ordinary task:

```yaml
- name: Plan
  run: ./gradlew tia-dist-plan
- name: Run this runner's group          # the finalizer completes the group either way
  run: ./gradlew test -Ptia.runnerKey=${{ matrix.group }}
```

with the run id and runner key wired into the `tia { ... }` extension in the build script - the
Gradle names are the Maven ones minus the `tia` prefix (`distributed`, `runId`,
`distributedGroupCount`, `distributedTargetRunTime`, `distributedMaxGroups`,
`distributedRunnerKey`), matching how `tiaEnabled`/`enabled` already work:

```groovy
tia {
    distributed = true
    runId = System.getenv('GITHUB_RUN_ID')
    distributedRunnerKey = project.findProperty('tia.runnerKey')
    distributedTargetRunTime = 1_500_000
    distributedMaxGroups = 10
}
```

The task is registered only for a distributed build, so an ordinary Gradle build gains no task and
no finalizer. It reads its claim back from the build's `DistributedClaimRegistry` by test task path;
with no claim recorded it logs at INFO and exits successfully.

## The completeness guard

The guard is the second predicate on the completion `UPDATE`, evaluated in the same statement as the
status flip so the check and the flip are atomic:

```sql
UPDATE tia_distributed_run_group SET status = 'COMPLETED', completed_at = ?
 WHERE run_id = ? AND group_number = ? AND status = 'CLAIMED' AND runner_key = ?
   AND suites_observed >= (SELECT COUNT(*) FROM tia_distributed_run_group_suite
                            WHERE run_id = ? AND group_number = ?)
```

In words: **a group may only complete once it has observed at least as many suites as the plan
assigned to it.** Those are the two numbers the status command puts side by side as its `Observed`
and `Assigned` columns, since they are what a run's progress actually comes down to. This is what
stands in for the crash protection a JVM shutdown hook used to provide. Without it, a JVM killed mid-run (SIGKILL, OOM) after reporting only part of its group
could still have its group completed by the build tool step, and the build would seal on a catalogue
missing whatever that JVM never got to run.

It reads `suites_observed`, not `suites_ran`, and the distinction is load-bearing. `suites_ran`
counts only suites that **finished**. A class-level `@Disabled` suite, one excluded by a
Surefire/Gradle filter, or one deleted since the last mapping run never finishes even though the
planner still assigned it to the group - guarding on `suites_ran` would block that group forever on
a suite Tia never expected to run. `suites_observed` counts every suite the runner's own JVM saw
finish **or** saw skipped, so such a group completes correctly. (`>=` rather than `=` because a
retry's report can run past the originally assigned total.)

`suites_observed` is fed from the intersection of the JVM's observed set with **this group's own
assigned suites**, not from the raw observed set. On Maven, Tia's group-based deselection injects
`@Disabled`/`@Ignore` onto every suite outside the runner's group; those classes are still
discovered and loaded, so each fires `executionSkipped` and lands in the observed set exactly like
one of the group's own suites. A 500-suite project split into 10 groups of 50 would otherwise let
group 0 see ~450 foreign suites and satisfy `observed >= assigned` on its very first persist.
Comparing the same set on both sides is what makes the guard exact by construction.

### A run stuck in OPEN: what it means and what to do

You will meet this as: the tests all ran, the pipeline is green (or one runner is red), and yet
`tia_distributed_run.status` is still `OPEN` and the stored commit value has not moved.

**What it means: at least one group never reached `COMPLETED`.** The barrier in `electSealer` never
opened, so nobody was elected and nothing was sealed. The status command reports exactly this -
`mvn <plugin>:dist-status`, or `gradle tia-dist-status` - and its outstanding block names each
group still in the way along with what to do about it. It reads the same rows this section
describes, so what follows is what it is telling you.

Reading `tia_distributed_run_group` directly instead, the groups that are not `COMPLETED` are:

| Group state | What happened |
|---|---|
| `PENDING`, never claimed | The pipeline started fewer jobs than the plan's `groupCount`. **Those suites did not run.** |
| `CLAIMED`, never completed | The runner died, or its completion step never ran (the Maven lifecycle-abort trap above), or the completion was refused. |

When the completion was *refused*, the log says so, and it says which of four cases it was.
`DistributedRunnerPersist.describeRejectedCompletion` reads the group row back on the failure path
specifically so that the message names what actually happened rather than the most likely thing -
the guard's row count alone cannot tell the cases apart, since all four miss the same `WHERE`
clause. The four clauses it can produce:

- *"this runner already completed this group, so there is nothing further to write for it"* - a
  duplicate completion. Harmless.
- *"this runner has observed only N of M assigned suite(s) so far, so the group is not complete
  enough to close"* - the completeness guard. The runner did not get through its group.
- *"the group is now `<STATUS>` under runner '`<key>`', so it is no longer this runner's to
  complete"* - another runner holds it. Usually a runner-key collision or a re-claim.
- *"the run's group rows are gone, so a newer build's plan write superseded this run"* - a
  concurrent primary build on the same branch planned over this one.

**The guard is deliberately conservative.** It would rather leave a run unsealed than seal a
catalogue built from a partial edge set, because the first costs a rebuild and the second silently
stops selecting tests. So there is no "force complete" and no override.

**What to do: nothing, in almost every case.** An unsealed run is the safe direction - the stored
commit stayed where it was, so the next build re-selects and re-runs that work. The next
The plan step clears the stale run's rows outright (warning about it first, naming the incomplete
groups), and the build proceeds normally. The only thing worth acting on is the *cause*: a
`PENDING` group means the fan-out step is reading the group count wrong, and a `CLAIMED` group that
never completed usually means the completion step is not wired to always run.

## Multi-module is not supported

A distributed run requires a **single-project build**, and `DistributedRunPreconditions` rule 4
enforces it at **both** plan time and claim time.

Both entry points need the rule because neither is an aggregator. The Maven `dist-plan` goal and
the Gradle plan task are bound per module, and Maven's `prepare-agent` - where the claim happens -
is bound to the `INITIALIZE` phase, so Maven runs it once per reactor module. That means:

- **On the planning side**, each project's plan write clears the previous project's plan from the
  shared tables before inserting its own. The last project to run leaves its plan behind; every
  project planned before it has suites assigned to groups no runner is watching for, and the build
  reports success having silently dropped that work.
- **On the claim side**, each module's `prepare-agent` execution would claim its own group, so a
  runner process ends up holding several groups instead of the one it is meant to hold.

The claim path is independently reachable - `mvn -pl <module> dist-plan` followed by `mvn test`
at the parent plans against a reactor of one and then claims against a reactor of several - which is
why guarding only the plan step is not enough.

**Inheritance is not aggregation, and only aggregation is refused.** The two get confused constantly:

- A project with a `<parent>` pom - a corporate parent, a Spring Boot starter parent, your own
  shared parent - is a **single-project reactor**. Reactor size 1. Completely unaffected; distributed
  runs work normally.
- A project with a `<modules>` section, whose build produces a reactor of more than one project, is
  an **aggregator**. That is what is refused.

The rule is about the reactor's *project count*, nothing else. If you have an aggregator but only
want one module's tests distributed, the escape hatch is `mvn -pl <module>`: it builds a reactor of
one, so both the plan and the claim see a single project and both pass. Use the same `-pl` on the
planning invocation, the test invocation and the completion invocation.

Multi-module support is future work, not something the configuration can be adjusted to fix, and the
failure message says so.

## `tiaDistributedRunnerKey`

Set it, to something your CI keeps stable across a job's retry attempts. The natural choice is a
node or matrix index - `$CI_NODE_INDEX`, `${{ matrix.group }}`, `$BUILDKITE_PARALLEL_JOB` - and
explicitly **not** a build or attempt number, which changes on retry.

| CI | Set `tiaDistributedRunnerKey` to |
|---|---|
| GitHub Actions | `${{ matrix.group }}` (the matrix index the fan-out already produced) |
| GitLab CI | `$CI_NODE_INDEX` |
| Jenkins (matrix/parallel) | the branch/axis label of the parallel stage |
| CircleCI | `$CIRCLE_NODE_INDEX` |
| Buildkite | `$BUILDKITE_PARALLEL_JOB` |

**The failure it prevents.** With no key configured, `DistributedRunCoordinator.resolveRunnerKey`
derives one as `runId + hostname + pid`. That is unique per runner, but it contains a **process id**,
so it cannot survive a job retry: a retried Maven job starts a fresh build JVM, derives a different
key, finds no `PENDING` group left to claim (its own group is still `CLAIMED` under the *old* key),
and exits as a no-op. The barrier stays closed and the run never seals. The build is still safe -
nothing seals, the next build re-runs - but the retry cannot rescue it, which is usually the whole
reason the retry exists.

With a stable key, step 0 of the claim protocol hands the retried job back **its own** group, which
it can then complete, opening the barrier late and letting the build seal normally.

One asymmetry between the build tools is worth knowing, because it is an accident rather than a
guarantee. On Maven the claiming process is the build JVM, which Maven starts fresh for every
invocation, so a retry *always* derives a new key and *always* no-ops. On Gradle the claim now runs
in the daemon, and a daemon commonly outlives a build; if the same warm daemon serves the retry, the
hostname and pid - and therefore the derived key - are identical to the first attempt's, so the
retry re-claims by accident. That is still safe with respect to other runners' suites, but it
depends on whether a daemon happened to be warm. Setting the key is what makes a retry's identity
deliberate on both build tools.

## One distributed test task per runner (Gradle)

A Gradle build may configure **exactly one** test task as distributed. `./gradlew test integrationTest`
with distributed enabled is refused, at configuration time, with an explanation.

The immediate reason is the derived runner key. Now that the claim happens in the daemon, the pid in
that key is the *daemon's*, shared by every test task in the build - so the second test task's claim
would hit step 0 of the claim protocol, be handed back the group the first task already holds, and
the two tasks would run and persist the same group's suites while a different group sat `PENDING`
forever. The run would never seal, and nothing would say why.

Fixing the key collision would not make it work. The plan groups suites across the **whole project**,
so a group can hold suites belonging to a source set a different test task owns and could never
run, and that task's claim would never satisfy the completeness guard either way.

The refusal is enforced twice, at two different times, because each catches a case the other cannot:

- `TiaSpockGitGradlePluginTestExtension.wireDistCompleteFinalizer` throws at **configuration time**
  when a second distributed test task would need a second `tia-dist-complete` task. Without this,
  Gradle's own "a task with that name already exists" error would stand in for it, saying nothing
  about why two distributed test tasks cannot work.
- `DistributedClaimRegistry.recordClaim` throws at **execution time** when a second test task
  attempts a claim in the same build, with the same explanation. It is `synchronized`, so two
  `doFirst` actions racing in a parallel build cannot both observe an empty registry.

**What to do instead:** configure one test task as distributed per runner, and run the other test
task's share of the plan as a **separate runner** - a separate CI job or process, with its own
workspace and its own claim.

## `removeDeletedTestSuites` on a runner

The mapping persist prunes suites that no longer exist: any suite tracked in the DB but absent from
the set the test runner discovered is treated as deleted and removed. That still runs on a
distributed runner, which sounds alarming - a runner only runs its own group's suites, so surely it
would prune every other group's suites as deleted?

It does not, and the reason is that the set it compares against is **not** the runner's group. It is
`TestRunResult.getRunnerTestSuites()` - the suites the test runner *discovered*: executed, skipped
and filtered. Tia deselects the other groups' suites by disabling them, not by hiding them, so they
are still discovered and still in that set. Every runner therefore sees the full suite set, and each
one independently reaches the same, correct conclusion about which suites have genuinely been
deleted from the source tree.

The dependency is worth naming because it is invisible from the pruning code itself: this is safe
**only** while every runner observes the whole suite set. Anything that made a runner's discovery
scan narrow to its own group would turn this into a mapping-wide deletion on every distributed
build.

## Security note: `fork.properties` holds the database password

On Maven, `${tiaBuildDir}/fork.properties` contains `tiaDBPassword` in plaintext, because the forked
test JVM needs it to reach the shared database and the file is how the build JVM hands it over. The
same file also carries the distributed handoff.

The consequences, plainly:

- **Do not archive the build directory as a CI artifact** on a job that ran Tia against a
  password-protected database.
- The file's lifetime is the length of the build, on the runner's own workspace. It is not written
  anywhere shared and is not read by anything but that build's fork and its completion step.

The other side of that boundary is a deliberate design choice worth stating, because it is what
makes a safe pipeline possible: **the completion step reads its connection settings from its own
parameters, never from `fork.properties`.** It reads only the run id, runner key, group number and
the three update-DB flags out of that file - the values that describe the claim and cannot be
re-derived - and takes `tiaDBUrl`, `tiaDBUser` and `tiaDBPassword` from its own configuration. So a
pipeline can keep the password in CI variables or `settings.xml` and never has to read it back off
disk. It also never publishes the file's contents into its own system properties, which would leak
test-fork configuration into a build JVM that is not a fork.

---

Prev: [Persist flow and crash safety](persist-flow-and-crash-safety.md) | [Back to the Wiki index](../WIKI.md) | Next: [Embedded vs server-mode H2 connections](h2-connection-modes.md)
