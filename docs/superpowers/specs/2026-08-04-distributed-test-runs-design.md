# Distributed test runs

Date: 2026-08-04
Status: Design approved, pending spec review

## Problem

A large project running Tia takes about two hours to execute all 1000 test suites (~5000 tests).
It already cuts wall clock by splitting the suites across multiple cloud build runners: each
runner executes a subset sequentially (sequential within a runner is deliberate - it avoids
flaky failures caused by tests racing each other), and the runners execute in parallel. Because
the builds are in the cloud, the number of runners can be scaled up or down freely.

Tia has no concept of this topology. Every Tia run assumes it is the only one:

- `TestSelector.selectTestsToIgnore` computes the whole selection, so N runners would each pay
  for a full VCS diff and each could compute a *different* selection if the workspace or HEAD
  moved between them.
- `TestRunnerService.persistTestRunData` writes the commit value seal, the aggregate stats and a
  history row on every run, so N runners produce N seals, N stats increments and N history rows.
- `updateMethodsTracked` rebuilds the entire `tia_source_method` table from a global read. Two
  runners doing that concurrently drop each other's method ids.
- `persistTestSuites` assigns `tia_source_class.id` values from `MAX(id) + 1` held in memory.
  Concurrent runners hand out colliding ids.
- `allTestsRunTime` only advances on a run that ignores zero suites. No runner in a split build
  ever does, so the savings baseline would freeze permanently.
- `getTestsToIgnore` iterates only *tracked* suites, so a brand-new untracked test suite would
  run on every runner.
- The library drain deletes pending rows and advances sequences. Run per-runner it would race
  and double-apply.

## Goal

Support a distributed test run: one logical build whose selected tests are split across N
independent runners that share only a database.

- Groups are balanced by expected run time.
- The group count is either **static** (user specifies N) or **dynamic** (user specifies a
  target run time and Tia derives N, optionally capped).
- `select-tests` reports the group count and the average group time.
- Group assignment is automatic - no runner is told which group it is.
- The stored commit value and the mapping advance only if *every* group completed.

## Terminology

Used consistently below and in the WIKI chapter.

- **Coverage edges** - rows in `tia_source_class_method`. Each is one (source-class row, method
  id) pair. The database-schema chapter already uses this term.
- **Suite mapping rows** - a suite's `tia_source_class` rows plus its coverage edges. This is
  the unit a runner owns and rewrites.
- **Method catalogue** - `tia_source_method`: every tracked method with its line range.
- **The seal** - writing `tia_core.commit_value`. The final mapping-related write, per the
  persist-flow chapter.
- **Category A** - the failure mode named in `wiki/persist-flow-and-crash-safety.md`: a crash
  *before* the seal, leaving the stored commit at its prior value while mapping rows are
  partially updated and ahead of what that commit claims.
- **Run** - one logical distributed build, identified by a CI-supplied `tiaRunId`.
- **Group** - one runner's slice of that run.

## The core insight

Tia's existing safety property is an **ordering** constraint, not an atomicity one: *if commit X
is the stored value, every mapping write for X has completed*. Over-writing is safe (the mapping
is newer than the commit claims, so the next run over-selects and self-corrects). Under-writing
is the silent correctness bug.

The distributed generalisation is therefore: **the seal must happen-after every group's mapping
writes, and exactly once.** Nothing needs to be in one transaction with the mapping writes,
which is fortunate - wrapping millions of edge rows across five hosts in a single transaction is
not buildable.

That reduces to inserting a **barrier** into the existing six-step persist sequence, between
step 1 and step 2:

| Step | Today | Distributed |
|---|---|---|
| 1. `updateTestSuiteMapping` | the whole run | each runner, its own suites only |
| 3. `updateTestSuitesFailed` | the whole run | each runner, already incremental and multi-host safe |
| - | - | **barrier: all groups COMPLETED** |
| 2. `updateMethodsTracked` | the whole run | sealer only, from staged trackers |
| 4. `applyLibraryImpactDrainResult` | the whole run | sealer only |
| 5. `updateTiaCoreData` (seal) | the whole run | sealer only |
| 6. `persistTestRunHistory` | the whole run | sealer only, one aggregated row |

So the distributed persist is the existing sequence with a barrier inserted, not a new flow.

## Chosen approach

### Coordination substrate: the database

Three places the coordination could live:

1. **An external orchestrator (CI).** It knows how many jobs it spawned and whether they passed.
   Rejected as the primary mechanism: Tia's correctness would depend on the user's pipeline YAML.
2. **A coordination service (a Tia daemon).** Rejected outright - Tia is a build-time library
   with no server, and adding one changes the product.
3. **The shared database.** Chosen. Tia already has a shared DB on exactly the machines
   involved, and the DB is already the thing that must be correct. Putting the barrier anywhere
   else lets the barrier and the data it protects disagree. The claim and the barrier are each a
   single conditional `UPDATE` with a row-count check, which behaves identically on server-mode
   H2 and Postgres.

**Hard prerequisite:** a shared database. Server-mode H2 or Postgres. Embedded H2 is rejected at
config validation with an explicit message.

### Run identity

A single CI-supplied `tiaRunId` shared by every job in the build. Every CI system exposes one
(`GITHUB_RUN_ID`, `BUILD_NUMBER`, `CIRCLE_WORKFLOW_ID`), so it is one line of config rather than
a per-runner input.

Rejected: auto-joining the newest open plan for (branch, commit). Zero config, but two
concurrent builds on the same commit - a rebuild, a retry, two pipelines on one SHA - interleave
into each other's plans, and a runner from build 2 can complete a group belonging to build 1,
letting build 1 seal on tests build 2 ran.

The **runner key** identifies one job across attempts, making claiming idempotent so a retried CI
job re-claims its own group instead of stealing an unrun one. That only works if the key is
stable across attempts, which `hostname + pid` is not. So:

- If the user sets `tiaDistributedRunnerKey` to a stable per-job value (a CI node/matrix index is
  the natural choice), a retried job re-claims its own group and can complete it, opening the
  barrier late.
- The fallback default is `runId + hostname + pid`. It is unique but not stable, so a retried job
  finds no `PENDING` group, exits as a no-op, and the barrier stays closed. Safe direction, but
  retries cannot rescue the run.

The distinction is documented rather than hidden behind a default that only appears to work.

### Group assignment: claimed, never passed in

No runner is told its group number. Each claims one with a conditional UPDATE. This is what
makes the topology automatic and keeps the CI config to a single run id.

### Two ways to trigger planning

The planner component is identical either way; only the trigger differs.

- **`tia-plan` goal/task.** Runs selection once, writes the plan, prints the group count and
  average group time, and emits `${tiaBuildDir}/tia-run-plan.json` for the pipeline to fan out
  on. Gives exactly N runners and no idle VM cost.
- **Lazy first-runner planning.** No planning job. Runners race to `INSERT` the run row in
  status `PLANNING`; exactly one wins and plans, the losers poll until it flips to `OPEN` and
  then claim. Surplus runners in a static pool find no group and exit as a no-op. Pipeline is a
  plain static matrix.

The lazy path exists because dynamic job counts are awkward in several major CI systems (GitHub
Actions needs matrix-from-job-output, GitLab needs parent-child pipelines). It has one footgun:
if the computed N exceeds the static pool size, groups go unclaimed and **those tests never
run** while the build reports green. `tiaDistributedMaxGroups` is what prevents this, so lazy
planning without it logs a prominent warning.

`select-tests` is unaffected by either: it stays a read-only preview and merely *displays* the
grouping it would produce.

### Balancing

Per-suite weight is the stored `avgRunTime` (median fallback for untimed suites, as today) plus
the per-suite mapping overhead from `TestSelector.computeOverheadPerSuiteMs` when the run
collects coverage. No allowance is made for VM or JVM startup - Tia has no data for it, and
inventing a config knob for it was rejected.

- **Static mode:** N is `tiaDistributedGroupCount`.
- **Dynamic mode:** `N = clamp(ceil(totalWeight / tiaDistributedTargetRunTime), 1, tiaDistributedMaxGroups)`.

Assignment is LPT (longest processing time first) bin-packing: sort suites by weight descending,
assign each to the currently-lightest group, ties broken by suite name so the plan is
deterministic and reproducible. LPT is within 4/3 of optimal, which is the right complexity here.

The planner reports `targetMet` (is the heaviest group within target) and warns when a single
suite alone exceeds the target, since no amount of scaling fixes that.

### Sealing: last runner self-elects

Whichever runner transitions the final group to `COMPLETED` wins one conditional UPDATE and
performs the seal. Fully automatic, no extra CI step. A group that dies or is never claimed
simply blocks the barrier, so nothing seals - the safe direction.

Rejected: a mandatory `tia-finalize` fan-in job (correctness would depend on pipeline config,
and forgetting it means the mapping silently never advances) and lease/heartbeat reclaim (the
other runners have usually exited, so there is rarely anyone left to reclaim; it costs a
heartbeat thread inside the forked test JVM for a narrow window of benefit).

### Stats and history

The sealer writes **one** build-level history row.

- **Serial-equivalent duration** (the sum of every group's test-execution time) feeds `tia_core`
  stats and the savings calculation, so savings continue to mean "time saved by not running
  unimpacted tests" and stay comparable with non-distributed history.
- **Wall-clock duration** (the slowest group) is stored in a new column and displayed alongside,
  so the user can see actual build time and whether the target was met.

Rejected: wall-clock as the primary duration. It would conflate selection savings with
parallelism savings - which Tia did not provide - and would silently change the meaning of
`avgRunTime` the moment distributed mode was switched on.

This also repairs the `allTestsRunTime` baseline. The sealer knows whether the union of groups
covered every tracked suite, and records the serial-equivalent duration when it did, so the
full-suite baseline keeps advancing.

## Correctness analysis

### Why coverage edges are safe when a build fails

Stored commit A, some suites' mapping rows rewritten at B, no seal. Two reasons this does not
under-select:

1. **Edges carry no coordinates.** An edge is `(classId, methodId)` where the method id is
   `Objects.hash(methodName)` over `class.method.descriptor`
   (`MethodImpactTracker.hashCode()`). There is no coordinate space to mismatch. A B-generation
   edge read under an A baseline means exactly what it says.
2. **A suite's edges are only ahead if that suite actually ran at B.**
   `persistTestSuiteClasses` rewrites a suite's rows only when it has coverage this run
   (`!testSuite.getClassesImpacted().isEmpty()`). So the suites whose rows moved to B are
   precisely those that executed at B, and they executed because the A→B diff selected them.
   The A→B work for those suites was already done and tested; only the record of it was lost.
   The next build re-selects them from the still-stored A baseline and re-runs them. Over-work,
   not under-testing. Suites in groups that never completed were left untouched at A, aligned
   with the stored commit.

The one theoretical under-selection vector is an edge *removal*: suite S covered method M at A
but not at B, and a later change to M fails to select S. But S genuinely stopped covering M at
B, and coverage is a function of the code, so at C it still does not - the B-generation edge is
the more accurate one. Constructing a real failure needs coverage to change at B and change back
before C. Corner case, not the general case.

That argument rests on "coverage is a function of the code", which is not reliably true in
practice. Recorded as an open concern in
`docs/notes/2026-08-04-edge-removal-under-selection.md`, which also notes that the broader
version of the problem is neither distributed-specific nor unsealed-build-specific. Out of scope
here; tracked for separate investigation.

### Why the method catalogue is NOT safe the same way, and what we do about it

`MethodImpactAnalyzer` diffs the original file content fetched at the **stored commit** against
the new content, parses the unified diff, and takes the hunk's **original-side** line numbers
(`HUNK_DIFF_ORIG_LINE_START_GROUP_INDEX`). It overlaps those against the stored
`line_number_start` / `line_number_end`. So the stored line ranges must be in the same
coordinate space as the stored commit.

Under Category A they are not. Method M spans 10-20 at A; lines are inserted above it so it
spans 30-40 at B; the crashed run leaves 30-40 stored under commit A. The next build diffs A→C,
produces a hunk inside M at A-coordinates near 10-20, compares against the stored 30-40, finds
no overlap, and does not select M's covering suites. Under-selection, bounded by the line drift:
methods shorter than the drift can be missed, longer ones still overlap.

**This is a pre-existing exposure**, not one distribution introduces, and the persist-flow
chapter's claim of "no under-selection" in Category A is optimistic on this dimension.

It is fixed in its own spec,
[mapping write integrity](2026-08-04-mapping-write-integrity-design.md), by making the catalogue
write atomic with the seal so the catalogue can never be ahead of the commit. **That spec is a
prerequisite for this one.**

This design extends that fix rather than duplicating it. With N runners there is no single
process holding the run's method trackers, so the catalogue write moves to the sealer and
runners stage their trackers in a per-run table. The sealer then performs the catalogue write
and the seal as one transaction, exactly as a single-host run does. An unsealed distributed
build leaves suite mapping rows ahead (bounded, edge-dimension risk only) and coordinates
exactly aligned.

The enabler is that method ids are line-independent, so edges written by a runner stay valid
against a catalogue written later by the sealer.

### Why the sealer's disk fallback is correct

At step 8 below, a method id present in the edge set but absent from the staged trackers falls
back to its on-disk row. This is sound: a method's line numbers can only shift if its file
changed; if its file changed its covering suites were selected; if they were selected some group
ran them and staged a fresh tracker. And the sealer is only reached when every group completed,
so there is no gap in that chain. Anything falling through to disk is genuinely unchanged.

An id in neither hits the existing orphan skip and ERROR log in `updateMethodTracker`, unchanged.

### Source-class id allocation is a real concurrency bug

`persistTestSuites` reads `readMaxSourceClassId() + 1`, assigns `tia_source_class.id`
application-side per row, and finishes with `ALTER TABLE ... RESTART WITH`. Two concurrent
runners read the same MAX and hand out the same ids. Because `id` is
`BIGINT AUTO_INCREMENT PRIMARY KEY` (H2) / `BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY`
(Postgres), collisions surface as primary key violations - loud, not silent corruption - but
distributed runs would fail at random.

Fixed in [mapping write integrity](2026-08-04-mapping-write-integrity-design.md) by replacing
`MAX(id) + 1` with atomic block allocation. **Prerequisite for this design**, but not caused by
it: any two concurrent writers hit it, which already includes the known Gradle multi-fork case.

### Straggler protection

A runner from an abandoned build may still be alive. If it finishes its suites at commit A and
persists while a newer build seals commit B, those suite mapping rows are from A while the
stored commit claims B - the one failure mode Tia must not have.

Mitigation: a runner re-verifies its claim is live immediately before persisting and skips its
mapping writes entirely if its plan has been superseded or deleted. This narrows exposure to a
seconds-wide window, recorded below as residual risk.

## Data model

### New tables

**`tia_distributed_run`** - one row per logical build.

| Column | Notes |
|---|---|
| `run_id` | PK, CI-supplied |
| `branch`, `commit_value` | the plan's commit; runners must match it |
| `status` | `PLANNING` / `OPEN` / `SEALED` |
| `group_count` | |
| `target_run_time_ms`, `estimated_total_ms` | |
| `created_at`, `sealed_by`, `sealed_at` | |
| `drain_result` | the planner's serialized `LibraryImpactDrainResult`; precedent exists in the `drain-result.ser` handoff |

**`tia_distributed_run_group`** - one row per group.

| Column | Notes |
|---|---|
| `run_id`, `group_number` | PK |
| `status` | `PENDING` / `CLAIMED` / `COMPLETED` |
| `runner_key`, `claimed_at`, `completed_at` | |
| `estimated_ms`, `actual_duration_ms` | |
| `suites_ran`, `suites_failed` | feed the sealer's aggregation |

**`tia_distributed_run_group_suite`** - `(run_id, group_number, suite_name)`. The assignment.

**`tia_distributed_run_method_stage`** - `(run_id, method_id)` with `method_name`,
`line_number_start`, `line_number_end`. Per-run staging for the sealer's catalogue rewrite;
deleted at seal. Roughly the size of `tia_source_method`, live only for the duration of a run.

### Changed table

**`tia_test_run_history`** gains `wall_clock_ms`, `group_count` and a nullable `run_id`.

### No storage needed for the ignore list

A runner derives it as `(tracked suites ∪ every suite in the plan) − my group's suites`. The
union with the plan's suites is what stops a brand-new untracked test suite from running on all
N runners, which today's `getTestsToIgnore` would do since it iterates tracked suites only.

## Components

In `tia-core`:

- `DistributedRunConfig` - the validated config bundle.
- `TestGroupBalancer` - weighting plus LPT bin-packing. Pure, no I/O, unit-testable standalone.
- `DistributedRunPlanner` - selection, balancing, plan persistence, stampede guard.
- `DistributedRunCoordinator` - claim, liveness re-verify, group completion, barrier, sealer
  election.
- `DataStore` additions for all of the above, implemented for H2 and Postgres.

Plugin surface:

- Maven: claim in `AbstractTiaAgentMojo` before surefire forks; the runner key reaches the forked
  JVM via the existing `fork.properties` mechanism.
- Gradle/Spock: claim inside the test JVM in `TiaSpockTestRunInitializer`, where selection
  already happens.
- Group completion and sealing happen in `TestRunnerService` inside the test JVM on both paths.

## Lifecycle

**Plan** (via `tia-plan`, or the first runner under the `PLANNING` stampede lock):

1. Reconcile tracked libraries, run the VCS diff, static rules and the library drain **once**.
   A significant side benefit: today N runners would each pay for a full diff, and on Perforce
   the profiling chapter notes content fetch dominates `select-tests` cost.
2. Balance into groups; write the run, group and suite rows; flip status to `OPEN`.
3. Emit `tia-run-plan.json` and the console summary.

**Claim:**

4. One conditional `UPDATE ... WHERE status = 'PENDING'`, idempotent on `runner_key`.
5. Verify the workspace commit matches the plan's commit; abort loudly on mismatch. The whole
   design rests on every runner producing line numbers for the same commit.
6. No group available means a surplus pool runner: log and exit as a no-op.

**Run and persist** (per runner):

7. Persist its own suites' mapping rows (step 1, scoped).
8. Upsert its method trackers into the staging table. Duplicates across runners are identical
   because every runner is verified at the plan's commit, so last-write-wins is safe.
9. Update the incremental failed set (step 3).
10. Re-verify the claim is live, then mark the group `COMPLETED` with its duration and counters.

**Barrier and seal:**

11. One conditional `UPDATE ... WHERE sealed_by IS NULL AND (no incomplete groups)`. Exactly one
    winner.
12. The sealer then runs the tail of today's sequence: read `SELECT DISTINCT
    tia_source_method_id FROM tia_source_class_method` (now complete, which is *why* the barrier
    sits here), resolve against staged trackers with disk fallback, rewrite the catalogue
    (step 2), apply the drain cleanup (step 4), write the seal (step 5), write one aggregated
    history row (step 6), flip the run to `SEALED`, and delete the staging rows.

**Supersede:** when a later build plans a run for the same branch, older unsealed runs are
deleted with a WARN naming exactly which groups never completed. The plan tables are operational
state; `tia_test_run_history` is the audit log.

## Failure semantics

| Failure | Result |
|---|---|
| A runner dies mid-run | Barrier never opens, nothing seals. Completed groups' rows are ahead of the stored commit - Category A, self-corrects by over-selecting next run. |
| A group is never claimed (pool smaller than N) | Same, plus those tests did not run. `tiaDistributedMaxGroups` prevents it; lazy planning without it warns. |
| Tests fail in a group | The group still completes. The run still seals, exactly as a single-host run with failures does today. Failed suites land in `tia_test_suites_failed` for forced re-run. |
| Sealer dies mid-seal | Seal-last ordering means the commit value has not advanced. Safe direction. |
| Runner passes `-Dtest=...` | Tia disables itself (existing behaviour), so no claim, so no seal. Logged clearly rather than looking like a hang. |
| Straggler from a superseded run | Pre-persist liveness check makes it skip its mapping writes. |

## Configuration

| Property | Purpose |
|---|---|
| `tiaDistributed` | master switch, default false |
| `tiaRunId` | CI-supplied, shared across the build's jobs |
| `tiaDistributedGroupCount` | static mode |
| `tiaDistributedTargetRunTime` | dynamic mode |
| `tiaDistributedMaxGroups` | ceiling in dynamic mode; effectively mandatory for lazy planning |
| `tiaDistributedRunnerKey` | stable per-job identity; falls back to `runId + hostname + pid` |

Mirrored on Maven and Gradle. Validation errors: distributed without a run id; distributed on
embedded H2; both group count and target time set; neither set; distributed with
`tiaCheckLocalChanges=true` (distributed runs are primary builds diffing a committed baseline,
and every runner must share the plan's commit).

`tiaUpdateDBMapping` is independent. With mapping updates off, splitting still works and the
sealer still runs, but it does only the stats aggregation and the history row - there is no
catalogue rewrite, no drain cleanup and no commit seal, matching the non-distributed behaviour
of those flags.

Config must be uniform across runners and Tia cannot enforce that - a runner accidentally set to
`tiaDistributed=false` would run the full selection alongside the group runners. WIKI warning,
not code.

## Output

`select-tests` gains the grouping preview: group count, average group time, and whether the
target is met.

`tia-plan` prints the same and writes `${tiaBuildDir}/tia-run-plan.json`:

```json
{
  "runId": "gh-1284471",
  "branch": "main",
  "commit": "87a5110",
  "groupCount": 5,
  "avgGroupMs": 1380000,
  "targetMs": 1500000,
  "targetMet": true,
  "clampedToMaxGroups": false,
  "totalEstimatedMs": 6900000,
  "selectedSuiteCount": 412
}
```

The pipeline reads it once and fans out. The matrix value is never passed to Tia - it exists
purely to make CI spawn the right number of jobs; which group each runs is still claimed from
the DB.

## Testing

Per project convention, every code change gets unit tests in `// given` / `// when` / `// then`
style.

- `TestGroupBalancer`: static and dynamic modes, the `maxGroups` clamp, determinism of the
  tie-break, a single suite exceeding the target, empty and single-suite selections, untimed
  suites taking the median.
- Claim protocol: idempotent re-claim on the same runner key, no-op exit when nothing is
  pending, liveness check rejecting a superseded plan.
- Barrier and sealer election: exactly one winner under concurrent completion, no seal while any
  group is incomplete.
- Catalogue staging: sealer union across several runners' staged trackers, disk fallback, orphan
  skip.
- Id block allocation: disjoint ranges under concurrency.
- Both dialects: every new `DataStore` operation against H2 and Postgres.

## Delivery stages

**Prerequisite:** [mapping write integrity](2026-08-04-mapping-write-integrity-design.md) ships
first, in full. Both of its stages are load-bearing here - concurrent runners cannot persist
suite mapping rows without block-allocated ids, and the sealer's catalogue write depends on the
catalogue/seal atomicity established there.

Per project convention, each stage stops for review before the next begins.

1. Schema, model objects and `DataStore` plan operations (H2 + Postgres). No behaviour change.
2. `TestGroupBalancer` and the run-time weighting, unit tested standalone.
3. Method catalogue staging: the per-run staging table, and routing the sealer's catalogue write
   through it instead of in-process trackers.
4. `DistributedRunPlanner`, the `tia-plan` goal/task, the JSON output, and the grouping display
   in `select-tests`.
5. Claim protocol and runner-side ignore-list derivation, Maven then Gradle/Spock.
6. Barrier, sealer election, seal bundle, aggregated stats and history.
7. Lazy first-runner planning with the stampede guard.
8. WIKI chapter.

## Residual risks and non-goals

- **Concurrent primary builds on one branch.** The pre-persist liveness check narrows the
  straggler window to seconds but does not close it. The existing advice not to run concurrent
  primary builds on a branch still applies.
- **Uniform config across runners** is assumed and unenforceable.
- **Multi-fork within a runner** remains unsupported, unchanged: Tia still relies on tests
  running sequentially in one JVM so JaCoCo can attribute coverage per suite. Distribution is
  across hosts, not within one.
- **Estimate accuracy.** Balancing is only as good as the stored `avgRunTime`. A suite whose
  time changes sharply skews one group until its stats catch up. Accepted; the dynamic
  work-queue alternative that would fix it was rejected as too deep a change to the
  agent/instrumentation path.
- **Startup time is not modelled** in the target, by decision. Users targeting a wall clock
  should set the target below their true budget.
