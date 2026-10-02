# Forced runs and re-seed

### What the two flags do

Two runtime flags override Tia's test selection for one build:

- **`tiaSelectAllTests`** (Gradle `selectAllTests`) runs every test, while still updating the
  mapping, stats and history exactly as the other flags configure. Use it for a periodic safety run,
  or whenever you want a full run without switching Tia off.
- **`tiaReseed`** (Gradle `reseed`) runs every test **and rebuilds the stored mapping from scratch**
  when the run seals. Every piece of mapping data the run did not rewrite is deleted: suites that
  no longer run, their class and method edges, and any orphan rows left by earlier crashes or bugs.
  History rows, the Tia-level stats, and the stats of every suite the run observed are kept.
  `tiaReseed` implies `tiaSelectAllTests`.

They are meant to be set for one build, not committed:

```bash
mvn test -DtiaSelectAllTests=true
mvn test -DtiaReseed=true
./gradlew test -PtiaReseed=true
```

On Gradle a `-P` property wins over the `tia { selectAllTests = ... / reseed = ... }` extension
(`TiaRuntimeFlags`), so a CI job can force a run without editing the build script.

`tiaReseed` requires `tiaUpdateDBMapping=true` and fails the build otherwise: a re-seed exists to
rebuild the stored mapping, so a build that writes no mapping cannot perform one.
`tiaSelectAllTests` is allowed without it - that is simply a full local run with no mapping writes.

#### How they differ from the other ways to run everything

- **`tiaEnabled=false`** turns Tia off: no database reads, no agent, no coverage, no writes. It runs
  everything but refreshes nothing.
- **A static `RUN_ALL` rule** (see [Static test selection](static-test-selection.md)) is a persistent
  rule that forces a full run whenever a matching file changes. The flags are a per-build override
  with no condition attached.
- **A seed run** is the full run Tia does on its own when no mapping is stored yet for the branch.
  A forced flag on a database with no stored mapping is simply a seed.

### Selection mode

Every run carries a `SelectionMode`:

| Mode         | Meaning                                                   | Full run |
|--------------|-----------------------------------------------------------|----------|
| `SELECTIVE`  | Ordinary diff-based selection                             | no       |
| `SEED`       | No stored mapping yet (`commit_value` null)               | yes      |
| `SELECT_ALL` | `tiaSelectAllTests`                                       | yes      |
| `RESEED`     | `tiaReseed`                                               | yes      |

The mode travels with the run:

- On a single-host run it rides on the selection details (`TestRunSelectionDetails`). Those already
  cross into the forked test JVM - Maven via the selection-details file, Gradle/Spock via the
  `tiaSelectAllTests` / `tiaReseed` system properties - so the persist inside the fork knows
  whether to re-seed. See [How Tia exchanges data with the test runner](test-runner-data-exchange.md).
- On a distributed run it is recorded on `tia_distributed_run.selection_mode` by the plan step.
- Every run's history row records it in `tia_test_run_history.selection_mode` (null for rows
  written before the column existed).

### Forced selection

Under `SELECT_ALL` or `RESEED`, `TestSelector.selectTestsToIgnore` returns an empty ignore set, so
every test the runner discovers executes - including suites Tia has never seen. The ignore list is
the only filter; the "selected" set handed to the fork is bookkeeping only.

The selected set is every tracked suite the developer has not disabled, which keeps the run-time
estimate and the developer-disabled bookkeeping accurate. The VCS diff, the targeted mapping
queries and the static rules are skipped - they could only add suites that are running anyway - so a
forced select is cheaper than a normal one.

The run feeds the all-tests baseline (`all_tests_run_time` / `num_all_tests_runs`) exactly as a seed
does, and its savings are zero.

### Pending library stamps are never cleared

The library drain still runs on a forced run (see
[Library publish-time stamping](library-publish-time-stamping.md)). It costs one ledger lookup and a
stamp read, adds no tests, and reports which stamps (`publish_seq <= R`) this run applied, so the
seal deletes exactly those and advances `last_applied_seq` as on any run. Every tracked library's
`mapping_baseline_commit` advances anyway, because a full run re-covers every suite.

A re-seed deliberately does **not** clear the pending stamp tables. Stamps above R belong to library
builds the app has not resolved yet: deleting them would mean the later run that finally picks up
that build selects nothing for the changed library methods - a false green. They stay valid across
a re-seed because a method id is a hash of the fully qualified method name, not a row id, so
rebuilding `tia_source_method` gives the same method the same id.

### The re-seed clear-out

The clear-out runs **inside the seal transaction** (`JdbcDataStore.persistSealedRunData`), after the
method catalogue is written and before the unsealed flags are cleared. Nothing changes if the run
fails before sealing, a failure inside the seal rolls the whole thing back, and concurrent readers
never see an empty mapping. See [Persist flow and crash safety](persist-flow-and-crash-safety.md).

It is keyed on the `unsealed` flag. Every suite a run executes has its edges rewritten and its
`unsealed` flag set by `persistTestSuiteClasses` before the seal, so at seal time the flagged suites
are exactly the ones this run rewrote - on a single host and across every runner of a distributed
build. In order, the clear-out:

1. Deletes the classes and edges of every suite not flagged unsealed.
2. Deletes those suites' rows, **except** developer-disabled ones, which keep their row, flag and
   stats and lose only their edges.
3. Sweeps orphans across the whole mapping: class rows whose suite is gone, edges whose class is
   gone, and `tia_source_method` rows no edge references.
4. Deletes `tia_test_suites_failed` entries naming a suite that no longer exists.

It logs one INFO line with the counts removed - the re-seed's audit trail. It never touches
`tia_core`, the history and trigger tables, the library / ledger / stamp tables, `tia_id_block` or
the distributed tables.

Suites left flagged unsealed by an earlier crashed run are kept too: their edges are real coverage,
and an ordinary seal trusts them on the same basis.

#### Cost

Measured on embedded H2 with `ProfileReseedSeal` (`./gradlew :tia-core:profileReseedSeal`, run
against a `generateLargeTiaDb` database - see
[Profiling select-tests against a synthetic large DB](profiling-select-tests.md)), with half the
suites unobserved:

| Database                          | Re-seed seal | Ordinary seal |
|-----------------------------------|--------------|---------------|
| 200 suites, 1.2M edges            | 13.7 s       | 0.6 s         |
| 1,000 suites, 5.7M edges          | ~120 s       | 1.2 s         |

At the smaller size the edge delete takes 3.4 s, the class delete 1.8 s and the orphan-edge sweep
1.8 s; most of the rest is committing one large transaction. Cost grows with the rows deleted. It is
accepted because a re-seed is rare; a faster copy-and-swap would need DDL, which H2 commits
implicitly, losing the rollback guarantee.

### Retries

Only the **first attempt** re-seeds (`RunAttempt.FIRST`). A Gradle test-retry round runs only the
failed suites in a fresh JVM, so a clear-out keyed on its writes would delete nearly the whole
mapping; retries seal as a plain select-all and log that the first attempt already performed the
clear-out. Surefire's in-JVM rerun persists are gated the same way. See
[Failed-suite tracking](failed-suite-tracking.md).

### Distributed runs

`dist-plan` / `tia-dist-plan` read both flags, refuse `tiaReseed` without mapping ownership, and
record the mode on the run row. Runners take the mode from the plan; a runner given either flag logs
a warning that it is ignored there. See [Distributed test runs](distributed-test-runs.md).

- **Grouping.** A forced plan draws its suite universe from the same disk scan a seed uses, so new
  suites run. Unlike a seed's even-count split it is balanced by stored run time: tracked suites
  carry their recorded times and untracked disk-scan entries (mostly non-test classes) weigh zero.
- **Completion and seal.** Every full run - seed or forced - completes a group once it observed at
  least one suite (the disk scan over-includes, so observed can never reach assigned), ignores no
  suite at seal, and supplies no overhead-model measurement.
- **Re-seed.** The sealer passes the re-seed flag when the run row records `RESEED`, so its single
  seal transaction performs the clear-out. "Rewritten" means flagged unsealed by any group.
- **History.** The sealer stamps the run's mode on the build's one history row.
- **`tia-run-plan.json`** carries `selectionMode` (`SELECTIVE`, `SEED`, `SELECT_ALL` or `RESEED`)
  for information only; a pipeline sizes its jobs from `groupCount` alone.

### Reports

History rows show the mode only for a seed or forced run:

- In the history list (console and HTML), the Savings cell names the mode (`All tests (forced)`,
  `Re-seed`, `Seed`) where it would otherwise dash, as a rerun row does.
- The run detail pages add a `Selection:` line and replace the five all-zero selection counters with
  "Selection overridden - all tests run" (or "No stored mapping yet - all tests run" for a seed).
- The select-tests preview prints `all (selection overridden: <mode>)`, and the dist-plan summary,
  preview and `dist-status` name the mode.

### Caveats

- **Multi-fork runs** are already unsupported (one fork's seal clears another fork's flags). With a
  re-seed, the first fork to seal deletes the suites of peer forks that have not written yet; those
  suites lose their stats and are re-mapped on the next run, since untracked suites always run.
- **A crashed group in a distributed re-seed.** The accepted seed caveat - a group that crashes after
  observing at least one suite can still seal as all-tests-run - extends to every full run. Under
  `RESEED` that seal also deletes the suites the crashed group never ran. They become untracked,
  untracked suites always run, and the next run re-maps them; only their stats are lost. A stricter
  guard is not possible, because the disk-scan superset means observed can never reach assigned.
- **The `seed_run` column was replaced without a backfill.** `tia_distributed_run.selection_mode`
  defaults to `SELECTIVE` and `seed_run` is dropped. A distributed seed run left open across the
  upgrade reads as selective and must be re-planned.
