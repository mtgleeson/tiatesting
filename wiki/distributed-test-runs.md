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
   single-project build, shared database, and `tiaCheckLocalChanges` not combined with
   `tiaUpdateDBMapping`). See "Multi-module is not supported" below for the second one, and
   "Local-change checking without mapping updates" below for the fourth.
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

**Meeting the target is best effort, and missing it never fails the build or drops tests.** Three
independent causes, all reported on the summary and in `tia-run-plan.json`. Any can apply without
the others, and more than one can apply at once, so all that apply are listed:

| Cause | Flag | What helps |
|---|---|---|
| `tiaDistributedMaxGroups` is lower than the target needs | `clampedToMaxGroups: true` | Raise the ceiling, or accept the longer build. |
| The fixed per-JVM cost alone meets or exceeds the target | `fixedOverheadExceedsTarget: true` | Nothing you can do to the tests. Every runner pays this before it executes a suite, so adding runners only adds copies of it. Raise the target. |
| A single suite is longer than what is left of the target after that cost | `singleSuiteExceedsTarget: true` | Nothing about the group count - no amount of splitting divides one suite. Split the suite or raise the target. |

**The target is a budget for the whole group, not just its suites.** A runner pays the fixed per-JVM
cost - engine start-up, class loading, the final coverage dump - before it runs anything, so the
budget available for suites is `target - fixed`, and a group meets the target only when its suites
plus that cost come in under it. See ["The two-part overhead
model"](#the-two-part-overhead-model) for where the fixed figure comes from; until a project has run
one distributed build it is zero and the target behaves exactly as it did before.

The last two cases also change the packing: the capacity is raised to `max(target - fixed,
heaviestSuite)`, so anything that puts the target out of reach no matter what does not also inflate
the runner count. Same makespan, fewer runners.

Every ordering decision is broken deterministically - by weight descending, then by suite name
ascending. Two runners deriving different groupings from the same selection would be undebuggable.

### The seed run

The first distributed build on a branch has no stored mapping to plan from, so there are no run
times yet to balance suites by. `DistributedRunPlanner` handles this by scanning the compiled
test-class directories directly (`TestClassScanner`) for the suite universe, rather than reading it
off the selection, and splits that universe across groups by even count - every suite gets the same
weight, since there is nothing to balance by duration.

Which groups it splits across depends on the configured mode:

- **Fixed group count** splits the scanned suites across `tiaDistributedGroupCount`, the same count
  a normal run uses.
- **Target run time** cannot honour a real target with no timings to check it against, so it splits
  across `tiaDistributedMaxGroups` instead when that is configured, and otherwise stays a single
  group.
- If the scan finds nothing on disk - or neither of the above applies - the run falls back to a
  single group with **no** suite names, ignoring the configured group count and target run time
  entirely. That runner ignores nothing and runs everything.

Either way `DistributedRunPlanner` logs at INFO why the build is a seed run, and records the mapping
the next build plans from. `tia-run-plan.json` still carries `"seedRun": true`, whether the run
landed as a single job or several, so a pipeline can explain what it is looking at.

The scan is deliberately a **superset** of the suite names Tia tracks - the test framework's binary
class names. It includes every compiled class name, so a JUnit5 `@Nested` class's `Outer$Nested`
name is included rather than dropped. That is what keeps every tracked suite present in some
group's assignment: over-inclusion is safe - a name the framework never runs just sits unexecuted
in some group's list - while under-inclusion would leave a real suite assigned to no group and
running on every runner at once.
That superset property is what guarantees no suite runs on more than one runner when a seed run is
split, and it is what lets the seal still record `allTestsRun: true` and full savings for a split
seed run, the same as it always has for the single-group case.

The grouping shape is still validated on a seed run, so a misconfigured
`groupCount`/`targetRunTime` pair is reported ahead of the build that will need it corrected rather
than on it. And if `tiaUpdateDBMapping` is off, the plan step logs a WARN naming that property: a
seed run that records no mapping leaves the next build another seed run, indefinitely.

### Seed-run completion

A seed run's `Assigned` count comes from the disk scan above, not a stored mapping, so it is a
superset that can include non-test classes JUnit itself never observes. Guarding completion on
`observed >= assigned`, the way a normal run does, would leave `Assigned` permanently out of
`Observed`'s reach even though the runner genuinely ran everything JUnit was ever going to run for
that group.

The completeness guard (see "The completeness guard" below) is loosened for a seed run: it
completes on `observed >= LEAST(1, assigned)` rather than `observed >= assigned`. A group assigned
real suites must have observed at least one to close; a group assigned nothing - the fallback
single-group case above - closes trivially, the same as it always has. `describeRejectedCompletion`
and the status report's footer are both seed-aware to match: a seed group rejected as incomplete is
reported as having observed nothing rather than an "N of M assigned" fraction that would misstate
what the disk-scan superset means, and the status footer explains that `Observed` may legitimately
stay below `Assigned` on a seed run instead of claiming the two must meet.

The seal is seed-aware for the same reason: `DistributedRunSealer` treats a seed run as ignoring
nothing, so `allTestsRun` rests on whether any suite ran rather than on the assigned-vs-tracked
comparison a normal run uses, which the disk-scan superset would otherwise fail.

This loosened guard still assumes the same completion step the rest of this chapter does: the
`dist-complete` step must run whether the test step passed or failed (see "Maven: the completion
must be its own always-run step" below). A crashed or failed test step that never reaches
`dist-complete` still leaves the group `CLAIMED` forever, seed run or not - the loosened threshold
only changes how few suites a *reporting* runner needs to have observed, not whether it needs to
report at all.

**Caveat: a partially-run seed group can seal as all-tests-run.** The loosened threshold is also
what a *partial* seed run passes. `dist-complete` is designed to run whether the test step passed
or failed - the Gradle finalizer runs even when the test task it finalizes fails
(`TiaSpockGitGradlePluginTestExtension.wireDistCompleteFinalizer` wires `testTask.finalizedBy(...)`),
and the Maven completion is documented as an `if: always()` step (see "Maven: the completion must
be its own always-run step" below). So if a test step runs some but not all of its assigned suites
and then crashes or is killed (a fork crash, an OOM, `--fail-fast`, a CI timeout), `dist-complete`
still runs and completes the group on `observed >= LEAST(1, assigned)`, and the seed-aware seal -
which treats a seed run as ignoring nothing - can then seal the run as all-tests-run even though
that group ran only part of its share. The `observed >= 1` per-group guard only blocks the
all-nothing case; it cannot tell a partially-run seed group from a fully-run one, because a seed run
has no stored mapping to compare `observed` against - the very reason the guard is loosened. A
non-seed run is not exposed: its `observed >= assigned` guard leaves a partially-run group
`CLAIMED`. This is left as a documented caveat rather than papered over with build-tool-specific
crash detection.

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

### `tia-run-plan.json` is a published contract

`DistributedRunPlanWriter` writes it to `<tiaBuildDir>/tia-run-plan.json`, and
`DistributedRunPlanSummary.toJson()` builds it by hand rather than through a JSON library, so the
field names, their order and their shape are fixed by that one method. A user's pipeline parses this
file to decide how many jobs to start, so **changing a field name, dropping one, or altering a
rendering is a breaking change to every consumer** - adding one at the end is not. The exact-document
test in `DistributedRunPlanSummaryTest` exists to make an accidental change fail loudly rather than
silently reshaping the contract.

Two renderings are deliberate and must not be "tidied":

- `targetMs` is the bare JSON literal `null` in static-groups mode, never `0`. A zero would read as
  an (impossible) target of zero milliseconds rather than the absence of a target.
- `avgGroupMs` is derived (`totalEstimatedMs / groupCount`), not measured. It describes the shape of
  the split; `heaviestGroupMs` is what a job timeout should be based on, because uneven packing is
  precisely what an average hides.

The field-by-field reference for pipeline authors lives in the README, under the distributed test
runs section, alongside a worked example.

## What the post-plan steps need from the version control system

Only the plan step reads the VCS for what it is for - the diff the selection is made from. Every step
after it needs exactly two scalars, and neither has to come from a repository:

| Value | Why it cannot come from the database | Where each step gets it |
|---|---|---|
| **branch** | it selects the schema, so it must be known before the first connection | `tiaBranch` / `tia.branch` when set, otherwise the VCS. `dist-complete` takes it from `fork.properties`; the Gradle finalizer from the recorded claim |
| **commit** | it is one side of the claim's comparison against the plan's commit, so reading it from the plan row would compare a value with itself | `tiaCommitValue` / `tia.commitValue` when set, otherwise the VCS |

`WorkspaceIdentity` owns that resolution, per value and lazily: a configured value never causes a
reader to be constructed, which is what lets a runner hold nothing but a checked-out tree. The
distinction matters more than it looks - `GitReader` opens a JGit repository and `P4Reader` opens a
**Perforce server connection** in its constructor, so "resolve the branch" is a network call on
Perforce, not a file read.

Nothing is mandatory. An unset value falls back to the VCS exactly as before, so an existing build
needs no configuration change; a runner that has no repository and was given no value fails naming
the property to set, rather than on a missing `.git` directory.

**The two values that cross the fork boundary are resolved once, in the build JVM.** Maven writes
them into `fork.properties` and Gradle sets them as test task system properties, for every build
rather than only a distributed one, so no test JVM resolves either for itself - see the
[test-runner data exchange](test-runner-data-exchange.md) chapter. That is not only about machines
without repositories: a fork that resolved its own branch could disagree with the build JVM about
which schema the run belongs to, and write its mapping somewhere no later build reads.

### The commit guard is weaker than it was, and the docs must not overstate it

Reading real `HEAD` observes the tree. A `tiaCommitValue` passed by a pipeline reports what the
pipeline *believes* it checked out. The guard still catches the wrong branch, a stale pinned SHA and
a mismatched job re-run - as long as the value comes from the CI system's own checkout variable
(`$GITHUB_SHA` and equivalents) rather than being echoed back from `tia-run-plan.json`, which would
compare a value with itself. It cannot catch a pipeline that reports one commit and checks out
another.

That trade is worth making because of what the guard protects. A runner on different code than the
plan was built from runs a selection chosen for code it does not have, and the sealer then stamps the
mapping with the **plan's** commit regardless - so the coverage and line numbers captured from one
tree are stored as describing another. Worse, when the runner is *behind* the plan, the seal advances
the stored commit past the gap: those changes were never covered by the run that just happened and
are now behind the diff baseline, so no future build looks at them again.

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
- The **wall clock** is the slowest group: what the build actually took and whether the target
  was met. It is what the history table shows, but it never feeds the stats - folding it into
  `avgRunTime` would quietly redefine that counter.

Each duration has its own savings, both frozen onto the row. The **serial savings** are the
full-suite baseline minus the serial-equivalent duration. The **wall-clock savings** are the
baseline spread across the groups the build had *available* minus its wall clock, so the CI
system's parallelism is not credited to Tia: a build that needed one of its six groups is compared
against the full suite run on all six. The planner records the groups available on the run row
(`groups_available`: the fixed group count, else `tiaDistributedMaxGroups`, else the groups
planned), since the sealer never sees the configuration. See "Wall-clock savings" in the
[test-run history log](test-run-history.md) chapter.

The row is stamped with the time the run was *planned*, since that is the one timestamp every runner
in the build shares. See the [test-run history log](test-run-history.md) chapter for the table
itself, which shows the wall clock and wall-clock savings; the serial duration and savings are on
each run's detail page.

The same pair surfaces once more, aggregated, in the Stats block of the three Tia-level summary
reports - the `status` console output, the plain-text report and the HTML report's landing page -
all built from one `SummaryStats` model so they cannot drift. Under Test Run Duration, `Average
run time` is the **wall clock** averaged across every history row, and under Partial Test Runs
the same average across the rows that ignored at least one suite. Both are measured against the
all-tests run time spread across the groups the last all-tests run used, and both are derived from
the history, because a wall clock only exists per run. `TestStats.avgRunTime` - the serial
average a distributed build contributes its serial-equivalent duration to
(`DistributedRunSealer.buildRunStats`) - is still maintained but no longer shown. The Savings
section's group lines - `Group savings` and `Groups used` - average `group_count` and
`groups_available` over the distributed rows, and only appear once there is one.

The same two durations, under the same two names, are what the *estimate* side reports before the
run. `select-tests` prints them in its estimate block:

```
Estimated total run time (serial equivalent): 497ms (75%)
Estimated savings: 167ms (25%)
Estimated distributed run time: 275ms (41%) - the heaviest group, which is what the build waits for.
```

The first line is deliberately the same number a non-distributed build would print, since it is the
figure savings are computed from either way. Left unlabelled beside a grouping whose heaviest group
is lighter, it reads as an estimate that ignored the distribution. It has not - the two figures
answer different questions, and only the wall clock changes when the build is split - so the label
and the third line are both added whenever a grouping is balanced for the preview. The plan step
prints no estimate block, so its console summary names the two itself via `DistributedRunDurations`.

The grouping preview beneath the estimate reports the **shape** of the split only - group count,
average and heaviest weight, target verdict. Its heaviest figure is the number the target verdict is
a verdict on; the same number's meaning as a duration is the estimate block's job, and stating it in
both places said the same thing twice, three lines apart.

**The distributed estimate is a floor.** The heaviest group's weight already carries its suites'
share of the mapping overhead - `TestGroupBalancer.suiteWeights` divides the selection's total
overhead across its suites - but each runner also re-pays the fixed per-JVM start-up cost that a
single-host run pays once. That fixed part cannot be separated at estimate time:
`TestSelector.computeOverheadPerSuiteMs` derives the overhead as
`allTestsRunTime - Σ per-suite averages`, which is `fixed + perSuiteCapture × N` collapsed into one
number, and dividing it by the suite count amortises the fixed part away. Only the seal side can
measure the split, from `actual_duration_ms - suites_duration_ms` per group, which is exactly what
the serial-equivalent correction above uses.

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
a mapping-owning build, so a group can complete having run suites and report zero suite time. Reading
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
shape: a seed run that fell back to a single group carries no suite names and ignored nothing, a
nothing-impacted build's groups carry no suite names and ignored every tracked suite, and by seal
time the two plans are indistinguishable - the seed run's own runners have already populated the
tracked suite map. A split seed run never hits this empty-assignment case at all: its groups carry
real suite names, so the general (non-empty) path applies, and the disk scan's superset property is
what keeps its ignored count at zero there too - every tracked suite is guaranteed to be in some
group's assignment.

### The two-part overhead model

A distributed build is the only thing that can tell Tia what a test JVM costs to start, and it does
so as a side effect of the durations it already records.

Tia's run-time estimate long modelled overhead as a single per-suite number: the full-suite baseline
minus the sum of the tracked suite averages, divided by the tracked suite count. That is the model

```
runTotal = Σ suite averages + capture × suites
```

with a term missing. Real overhead is `fixed + capture × suites`, where `fixed` is what a JVM pays
once - engine start-up, class loading, the final coverage dump - and `capture` is JaCoCo's per-suite
collection. Dividing the whole overhead by the suite count keeps only `capture` and amortises
`fixed` away. On a single host that is self-consistent: divide by the tracked count, multiply back
up by the selected count, and the total comes out right. **The moment a build is split it is wrong**,
because each runner is its own JVM and pays `fixed` in full - the cost is duplicated per group while
the estimate divided it. On the fixture this was first measured against, two runners paid 519ms of
overhead between them where one host paid 300ms, and the estimate spread that 300ms three ways.

One equation cannot separate two unknowns, which is why the split was never available. A distributed
build supplies a second one at a different suite count:

```
wholeRunOverhead  = fixed + capture × trackedSuites      (from the all-tests baseline)
meanGroupOverhead = fixed + capture × meanGroupSuites    (from this build's group rows)
```

Nothing new is measured. Every runner already reports `suites_duration_ms` alongside
`actual_duration_ms`, precisely so the sealer can charge the fixed cost once when computing the
serial-equivalent duration; `DistributedRunOverheadModel` combines the same numbers a second way.
The solve runs at seal time and folds its answer into rolling averages on `tia_core`
(`fixed_overhead_ms`, `capture_overhead_per_suite_ms`, `num_overhead_measurements`).

**A group's suite count comes from the plan, not from `suites_ran`.** That counter accumulates
executions, so a Surefire retry inside a runner's JVM sums into it - and here it would inflate the
suite count on exactly the group whose overhead the retry also inflated, corrupting both sides of the
equation at once. What the plan assigned a group cannot be moved by any number of retries.

**A seed run is excluded, whether it fell back to a single group or was split.** The `seed_run` flag
decides this outright, not the shape of the assignment: a single-group seed run is assigned no suite
names, and reading that as a group that ran nothing would push a full-suite run's entire overhead
into `fixed`; a split seed run does carry suite names, but they are split by even count rather than
by measured duration, so its groups say nothing real about how overhead scales with suite count. It
is degenerate in any case - covering every suite between them restates the whole-run equation rather
than adding a genuine second one to it.

**Every failure to solve is a skip, never a guess.** A group that ran suites without timing any of
them (the same disqualification `DistributedRunTotals` applies), a build whose groups average the
full tracked suite count, or a solve that comes out negative all leave the stored averages exactly as
they were. Writing a zero instead would drag both constants towards nothing and undo every earlier
measurement - which matters more here than for a trend, because the pair is consumed as constants.

**The mean here, the minimum in `DistributedRunTotals`.** The two read the same per-group
measurement and estimate it differently on purpose, and neither should be "corrected" to match the
other. The totals subtract from a duration the build actually took, so they need the largest amount
every runner *demonstrably* paid - a floor. This is a forecast, and a forecast wants the expected
value.

Both constants are `0` for a project that has never distributed a build, which is the signal to fall
back to the single-number model. No configuration, no threshold: the estimate self-corrects after
the first distributed run. See ["The select-tests run-time estimate and its overhead
model"](select-tests-run-time-estimate.md) for how the pair is then consumed, and ["How suites are
packed into groups"](#how-suites-are-packed-into-groups) for why `fixed` is charged once per group
and never enters the packing weights.

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

(On a seed run that assigned-count subquery is wrapped in `LEAST(1, ...)`, so the predicate becomes
`suites_observed >= LEAST(1, assignedCount)` - see "Seed-run completion" above for why.)

In words: **a group may only complete once it has observed at least as many suites as the plan
assigned to it.** Those are the two numbers the status command puts side by side as its `Observed`
and `Assigned` columns, since they are what a run's progress actually comes down to. That rule is
unqualified only for a non-seed run; a seed run's assigned suites come from a disk scan that
over-includes non-test classes the runner never observes, so its guard is loosened to
`suites_observed >= LEAST(1, assignedCount)` - a group assigned real suites need only have observed
at least one, and a group assigned nothing completes trivially. See "Seed-run completion" above for
the full reasoning. This is what
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

When the completion was *refused*, the log says so, and it says which of five cases it was.
`DistributedRunnerPersist.describeRejectedCompletion` reads the group row back on the failure path
specifically so that the message names what actually happened rather than the most likely thing -
the guard's row count alone cannot tell the cases apart, since all five miss the same `WHERE`
clause. The five clauses it can produce:

- *"this runner already completed this group, so there is nothing further to write for it"* - a
  duplicate completion. Harmless.
- *"this is a seed run and this runner has observed no suites yet (N), so the group has run nothing
  to complete"* - the seed-aware completeness guard (see "Seed-run completion" above). Since a seed
  run's assigned count is a disk-scan superset, this case is reported as having observed nothing
  rather than an "N of M assigned" fraction, which would misstate what the superset means. The
  runner has not reported any progress on its group yet.
- *"this runner has observed only N of M assigned suite(s) so far, so the group is not complete
  enough to close"* - the completeness guard on a non-seed run. The runner did not get through its
  group.
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

## Local-change checking without mapping updates

`DistributedRunPreconditions` rule 3 allows `tiaCheckLocalChanges` in a distributed run **as long as
`tiaUpdateDBMapping` is off**, and rejects only the combination of both. This is looser than it once
was - the rule used to forbid local-change checking outright - and the reason the old blanket ban was
wrong is worth stating, because it is the same reasoning that bounds the new rule.

**Runners do not diff.** The selection is computed once, at plan time, by the single
`TestSelector.selectTestsToIgnore` call in step 3 above; the plan persists suite-name-to-group
assignments, and each runner claims a group and runs the suite names it was handed without diffing
anything itself (see "Which suites a runner skips"). So a runner never computes line numbers of its
own, and divergent working copies across runners cannot corrupt the plan they all share. The old
justification - "every runner must produce the same line numbers for the same commit" - described a
model where each runner selects, which is not how the run works.

**The real hazard is at seal time, and only when the mapping is written.** When the run collects
coverage (`tiaUpdateDBMapping` on), each runner stages a fresh method-to-line mapping for its suites,
and the sealer folds that into the mapping keyed to the committed baseline (see "What only the sealer
can do"). If that coverage was measured against uncommitted local edits, it would be stored as if it
belonged to the commit, silently poisoning every later build's selection. That is the one genuinely
unsafe combination, so it is the one the precondition rejects. A run with `tiaUpdateDBMapping=false`
stages and writes no mapping, so there is nothing to poison.

**Why fail fast rather than silently disable.** The non-distributed path resolves the same conflict by
quietly forcing `tiaCheckLocalChanges` off when `tiaUpdateDBMapping` is on (see the
`isCheckLocalChanges()` helper in the Maven agent mojo). The distributed path throws instead: a CI job
that deliberately asked to test uncommitted changes should be told its configuration is contradictory,
not have Tia quietly run the committed baseline behind its back and report success. The rule is checked
at both plan time and claim time, the same way rule 4 is, so a runner whose configuration disagrees with
the plan's is refused rather than left to do the wrong thing.

**The use case, and its operational requirement.** The allowed mode - distributed with
`tiaCheckLocalChanges=true` and `tiaUpdateDBMapping=false` - lets a build that has not committed its
changes (a CI job testing a working tree, for example) fan the impacted tests out across runners purely
for speed, without writing to the mapping. The plan step selects against the local workspace once; every
runner then physically runs the impacted code, so every runner must have that **same working copy - the
same uncommitted changes - checked out**. Tia has no way to verify that the runners' trees match the
plan's, so distributing the workspace (not just the commit) is the pipeline's responsibility. Get it
wrong and a runner runs stale code for the suites it was assigned; Tia cannot detect it.

## `tiaDistributedRunnerKey`

### What the key actually does

It is the identity a runner claims under, and it does two jobs. The second is the reason to set it;
the first is why it exists at all.

**It is the ownership token on every write a runner makes to its group.** Claiming stamps the key
onto the group row, and from then on it is a predicate on the writes that follow:

| Operation | Guard |
|---|---|
| `reportGroupProgress` | `WHERE run_id = ? AND group_number = ? AND status = 'CLAIMED' AND runner_key = ?` |
| `completeGroup` | the same predicate, plus the `suites_observed` completeness check |

That is the straggler protection. A runner whose claim is no longer live - superseded, or the run
replanned underneath it - matches zero rows and writes nothing, instead of overwriting a group some
other runner now owns. `DistributedRunnerPersist` makes the same comparison in Java before it
persists at all, and logs *"the group is now `<status>` under runner '`<key>`'"* when the group has
moved on. Without a per-runner identity there would be nothing to distinguish "my group" from
"a group", and a slow runner returning from the dead would corrupt whichever group had taken its
place.

The seal is the exception: `electSealer` does **not** filter on the key. It is settled by
`sealed_by IS NULL AND NOT EXISTS (a group that is not COMPLETED)`, so exactly one runner wins
whatever its identity; the key is written into `sealed_by` afterwards to record *who* won.

**It is also the retry identity**, which is what the rest of this chapter is about. Step 0 of
`claimNextPendingGroup` looks the key up - `WHERE run_id = ? AND runner_key = ?` - before claiming
anything, so a key already holding a `CLAIMED` group is recognised as a retried CI job and handed
that group back rather than a second one.

Both uses are scoped to one run: every lookup is keyed on `(run_id, runner_key)`. **The key must be
unique per runner within a run id, and is meant to be reused across builds** - `job-1` in every build
is the intended pattern. Two concurrent runners sharing a key within one run would have the second
find the first's claimed group and be handed it as its own.

### Setting it

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

## Security note: `fork.properties` carries a reference, not the password

On Maven, `${tiaBuildDir}/fork.properties` carries the distributed handoff and the forked test JVM's
connection settings. It does **not** contain the database password, and cannot: `ForkSystemProperties.write`
refuses any key whose name looks like a credential rather than a reference to one.

That refusal is structural rather than conventional because the failure it prevents is invisible.
The Tia agent's `premain` republishes every key of this file as a system property in the fork, and
Surefire dumps the fork's system properties into `target/surefire-reports/TEST-*.xml` - the artifact
CI ingests and routinely publishes. A password written here would therefore be published with
nothing failing or warning. An earlier version of Tia did exactly that.

What the file carries instead is `tiaDBPasswordFile`, a path. Where that path points depends on how
the build supplied the password: at the file the user already owns, if they configured one; or at a
file Tia staged, if the password was configured in the build or came from a `settings.xml`
`<server>` entry. A staged file is owner-only (`0600`), is created outside the build directory, and
is deleted when the build JVM exits - the Maven JVM outlives every Surefire fork, so its lifetime
really is the length of the build. A build that supplies the password through `TIA_DB_PASSWORD`
forwards no path at all, because the fork inherits the build JVM's environment.

An earlier version of this note claimed the password file's "lifetime is the length of the build".
That was never true of `fork.properties`: nothing deleted it, so it survived in `target/` until the
next `mvn clean`, and archiving the build directory captured it. It is true of the staged password
file, which is both deleted and outside the archived directory.

See [Keeping the password out of checked-in config](../README.md#keeping-the-password-out-of-checked-in-config)
for the channels a build can supply the password through.

The other side of that boundary is a deliberate design choice worth stating, because it is what
makes a safe pipeline possible: **the completion step reads its connection settings from its own
parameters, never from `fork.properties`.** It reads only the run id, runner key, group number and
the three update-DB flags out of that file - the values that describe the claim and cannot be
re-derived - and resolves `tiaDBUrl`, `tiaDBUser` and the password from its own configuration. So a
pipeline can keep the password in CI variables or `settings.xml` and never has to read it back off
disk. It also never publishes the file's contents into its own system properties, which would leak
test-fork configuration into a build JVM that is not a fork.

---

Prev: [Persist flow and crash safety](persist-flow-and-crash-safety.md) | [Back to the Wiki index](../WIKI.md) | Next: [Embedded vs server-mode H2 connections](h2-connection-modes.md)
