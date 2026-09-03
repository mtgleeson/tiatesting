# Persist flow and crash safety

### The problem this chapter explains

The Tia DB stores two things that must stay consistent with each other:

1. The **commit value** (or Perforce changelist): a single string on the `tia_core` row that says "the suite-to-method mapping is up to date as of commit X."
2. The **mapping**: rows in `tia_test_suite`, `tia_source_class`, `tia_source_class_method`, and `tia_source_method` that say "test suite A exercises methods M1, M2, M3."

If those two get out of sync - the stored commit claims X but the mapping reflects an earlier state - the next `select-tests` run computes a diff against X, sees no changes between "what's mapped" and "what's stored as the head," and **under-selects**. Tests that should run, don't. Silent correctness bug.

This chapter explains how Tia avoids that state, what failure modes remain, and how Tia self-recovers from them on the next run.

### One persist per run

A Tia-instrumented test run accumulates state in-process (suite trackers, method-id trackers, failed-suite set, drain results) and persists once at the end via `TestRunnerService.persistTestRunData`. The listener invokes it from:

- **JUnit 5**: `TiaTestExecutionListener.testPlanExecutionFinished`.
- **JUnit 4**: `TiaJunit4Listener.testRunFinished`.
- **Spock**: `TiaSpockRunListener.finishAllTests`, called from the global extension's `stop()` once per JVM.

On Surefire retries, `persistTestRunData` is called per attempt for JUnit 5 / JUnit 4 (each retry's listener writes its own row). Spock collapses retries naturally - one call per JVM. See the "Test-run history log" chapter for the per-attempt counters that decouple retry semantics from the mapping accumulator.

### Write sequence: the seal-last invariant

`persistTestRunData` sequences the DB writes so that the **commit value is written as part of the last mapping-related write, the seal**:

```
1. updateTestSuiteMapping  - tia_test_suite + tia_source_class + tia_source_class_method
                              (persistTestSuites, for the suites this run TOUCHED; skipped
                               entirely on a non-mapping run)
2. updateTestSuitesFailed  - tia_test_suites_failed
3. sealRun (SEAL)          - one atomic bundle via persistSealedRunData:
                                - tia_source_method (the method catalogue)
                                - the library drain cleanup (pending rows deleted, tracked-library
                                  baselines advanced)
                                - clearUnsealedTestSuites (every currently-flagged suite)
                                - tia_core (commit value, branch, last-updated, optional stats)
4. persistTestRunHistory   - tia_test_run_history (audit row)
```

The invariant: **if commit X is the stored value, every mapping write for X has completed.**

Step 3 is genuinely atomic - see "Per-call atomicity" below. Steps 1 and 2 are not wrapped in that
transaction, or in each other; they precede the seal so that if the run stops before reaching it,
the stored commit value is still the prior one and nothing downstream trusts the not-yet-sealed
rows as being "as of" that prior commit. Consistency between the commit value and steps 1-2 comes
from **ordering plus the `unsealed` flag** (see "The `unsealed` flag" below), not from a single
enclosing transaction across the whole sequence.

### Per-call atomicity inside JdbcDataStore

Each individual persist call is internally atomic:

- **The seal bundle (`persistSealedRunData`, tia_source_method + library drain cleanup + `clearUnsealedTestSuites` + tia_core)**: one transaction. `writeSourceMethods` rewrites `tia_source_method`, the drained library batches are deleted and tracked-library baselines updated, every currently-flagged suite has its `unsealed` column cleared, and `persistTiaCore` writes the commit value - all under the same `connection.setAutoCommit(false)` / `connection.commit()`, with any exception (caught broadly, not just `SQLException`) triggering a `connection.rollback()` before the exception is rethrown. Either the whole bundle is visible after a crash, or none of it is.
- **`persistTestSuiteClasses` (tia_source_class + tia_source_class_method, one suite)**: per-suite `DELETE` + `INSERT` wrapped in one transaction, with the suite's `unsealed` flag set to `TRUE` inside that same transaction, right before the commit - the edge rewrite and the flag that says "trust this only provisionally" can never land apart. A failure mid-rewrite of one suite's edges leaves that suite's previous mappings, and its previous flag state, intact. Wrapping the entire outer `persistTestSuites` loop in one transaction would put potentially millions of edges in one transaction and risk MVStore undo-log blow-up on H2; per-suite is the right balance - each suite is internally consistent, and at worst a partial outer-loop failure leaves some suites updated and some not (the same outcome that would happen anyway).
- **`persistTestSuites` (tia_test_suite, the row itself)**: `MERGE` per suite via `SqlDialect.upsert`. Each MERGE is an atomic UPSERT. `unsealed` is deliberately never part of this column list - it is set only by `persistTestSuiteClasses` and cleared only by `clearUnsealedTestSuites`, never touched by the suite-row upsert itself.
- **The persist writes the suites the run touched, not every suite it read.** The full map is still read, because deletion and the developer-disabled flag both need to see every tracked suite, but only the executed suites plus any whose flag this run's observations changed are written. Writing the whole map back made the persist a read-modify-write over the entire table: a build committing inside another build's persist window had its increments overwritten on suites the writing build had never run - the two did not need to share a single suite, only to overlap in time. Absence from the map is therefore *not* a deletion; `deleteTestSuites` is the separate call that removes rows. Measured on embedded H2 with 2,000 tracked suites and 5 executed, the suite write drops from ~184ms to ~2ms; on a server-mode or Postgres datastore, where each statement is a round trip, the gap is wider still.
- **Still outstanding: two builds that ran the *same* suite.** The surviving write is absolute rather than accumulating, so one of the two increments is lost. Narrowing the write removed the blast radius around that case but not the case itself; closing it means an accumulating upsert (H2 `MERGE ... USING ... WHEN MATCHED`, Postgres `ON CONFLICT DO UPDATE` with expressions), which is a separate change.
- **`persistTestSuitesFailed` (tia_test_suites_failed)**: clear-out + bulk insert in one transaction; idempotent on subsequent runs.
- **`persistCoreData` (tia_core, the standalone entry point)**: single `INSERT` or `UPDATE` of the one core row, commit value and branch included. Atomic. Used directly by the standalone seeding path. A run that does not own mapping updates writes no core row at all - see the next bullet.
- **The run stats ride on `updateDBMapping`; there is no separate stats flag.** A run that owns the mapping records the stats, and a run that does not writes no core row at all. The two were separate flags until it became clear that `updateDBStats=true, updateDBMapping=false` - a developer's machine writing stats to the shared DB - was the configuration that poisoned the averages: `TestStats.incrementStats` is a plain running mean with no weighting or source tag, and a local run that ignores zero suites folds its duration into `all_tests_run_time`, the baseline every run's reported savings is measured against. Coupling them makes that configuration unrepresentable rather than merely discouraged, and narrows the set of writers to the builds a project already never runs concurrently on one branch.
- **The stats accumulate in SQL, not in memory.** The seal's core write stamps the commit value, branch and last-updated from the caller's snapshot - those are the run's own to declare - but writes the stats columns as `column = column + ?`, and the two rolling averages as the same arithmetic `TiaData.incrementStats` performs transcribed into SQL. The distinction matters because the stats are a running total several builds contribute to: writing them from the snapshot made the update a read-modify-write spanning the entire mapping persist, so a build committing inside that window had its increment silently overwritten. Moving the read to write time, inside the seal's transaction, closes it. Every reference to a stats column on the right-hand side sees the row's pre-update value, which is what keeps the two assignments making up an average consistent with each other. A run contributing nothing (a Surefire retry) leaves the stats columns out of the statement entirely rather than adding zero, and the very first seal on a database with no core row falls back to the INSERT path, where the increment simply is the absolute value.
- **`persistTestRunHistoryEntry` (tia_test_run_history)**: single `MERGE` keyed by a deterministic id derived from `branch|commit|runStartTimestampMs`. Idempotent - re-persisting the same logical run is a no-op.

**The clear-out inside a rewrite is dialect-specific, and the obvious choice was wrong on H2.**
Both `writeSourceMethods` (tia_source_method) and `persistTestSuitesFailed`
(tia_test_suites_failed) need to clear a table and then repopulate it, with the clear undone if the
repopulate fails. The natural-looking statement, `TRUNCATE TABLE`, does **not** give that guarantee
on H2: H2 2.2.224 implements `TRUNCATE TABLE` as DDL that implicitly commits and is not undone by a
later `rollback()`. This was measured directly, not inferred from documentation:

```
DELETE FROM t inside a transaction + rollback() -> rows restored (3)
TRUNCATE TABLE t inside a transaction + rollback() -> rows NOT restored (0)
```

So the two clear-then-repopulate call sites use `SqlDialect.clearTableTransactionallySql`, which
resolves per-vendor: `H2Dialect` returns `DELETE FROM <table>`, which genuinely rolls back inside a
transaction; `PostgresDialect` keeps `TRUNCATE TABLE <table>`, because Postgres `TRUNCATE` really is
transactional and is the cheaper statement there. A hard-coded `TRUNCATE TABLE` on H2 would silently
escape the transaction, unable to be undone if the insert (or a later step in the same seal) failed.

That correctness fix has a measured cost on H2, timed clearing a populated `tia_source_method` and
re-inserting, embedded H2, averaged over warm repetitions:

| Rows | `DELETE FROM` | `TRUNCATE TABLE` | Delta |
|---|---|---|---|
| 50,000 | 131 ms | 65 ms | +67 ms |
| 200,000 | 432 ms | 189 ms | +243 ms |
| 500,000 | 1705 ms | 871 ms | +834 ms |

Linear in both variants; the delta is under 1-3% of a full mapping persist, which runs in tens of
seconds. Correctness fix, cost accepted.

The original measurement used a temporary, uncommitted profiler, so the exact figures above are
not independently reproducible from the repo history. The `DELETE FROM` side (the code path Tia
actually runs on H2) can be reproduced with the committed profiler: `./gradlew
:tia-core:profileMethodCatalogueClear -Prows=200000 -Prepetitions=5` - see
`tia-core/src/test/java/org/tiatesting/core/perf/ProfileMethodCatalogueClear.java`.

### The `unsealed` flag

`tia_test_suite.unsealed` narrows the window described above, for suite mapping rows specifically.
When `persistTestSuiteClasses` rewrites a suite's classes and methods, it sets `unsealed = TRUE` on
that suite in the same transaction as the edge rewrite. The only statement that clears it is
`clearUnsealedTestSuites`, run inside the seal transaction, right before the commit-value write.
`TestSelector.addUnsealedTests` reads the flag on every selection and force-selects any suite still
carrying it, regardless of what the VCS diff says - the mapping rows for that suite are not trusted
against the stored commit until a seal has vouched for them.

**This narrows the window; it does not close it.** Two documented ways the flag can be cleared
without the suite's coverage actually having been recaptured against the sealed commit:

- `persistTestSuiteClasses` only runs for suites with non-empty `classesImpacted` - i.e. suites that
  actually executed and produced coverage this run. A suite that was force-selected (by this same
  flag, by the failed-suite set, or by a library forced-selection) but then filtered back out never
  reaches `persistTestSuiteClasses`, so its edges are not rewritten. But `clearUnsealedTestSuites`
  in the seal is unconditional, so if that run reaches its seal the flag is cleared anyway, leaving
  the suite with the crashed run's mapping rows, no flag, and a stored commit that claims they are
  current. A suite that runs and legitimately returns empty coverage has the identical effect: no
  edge rewrite, but the seal still clears whatever flag was already set.

  The filters that actually cause this are the ones Tia cannot see:

  - **Tag and group filters** - JUnit 5 `includeTags` / `excludeTags`, Surefire `<groups>`, TestNG
    groups. Nothing checks these, so Tia stays enabled, persists and seals as normal. This is the
    likeliest real-world instance, because tag filters are routine in CI.
  - **Filters configured in the build file rather than on the command line** - Surefire
    `<includes>` / `<excludes>`, or a Gradle `test { filter { ... } }` block.
  - **Fail-fast aborts** - the run stops early and suites past that point never execute, but the
    listener still reaches its persist and its seal.

  An explicit **command-line** test filter is *not* an instance of this escape, because Tia disables
  itself entirely rather than running a filtered selection: `AbstractTiaAgentMojo` and both
  `TiaTestExecutionListener` and `TiaJunit4Listener` check `System.getProperty("test")` for Maven's
  `-Dtest`, and `TiaSpockGitGradlePluginTestExtension` checks
  `DefaultTestFilter.getCommandLineIncludePatterns()` for Gradle's `--tests`. When disabled, the
  listener returns before persisting, so there is no seal and therefore no clear - the flag survives
  and the next full run still force-runs the suite.

  A Surefire/Failsafe retry is **not** an instance of this escape, despite `persistTestRunData`
  running once per attempt (see "One persist per run" above). The suite trackers are shared across
  attempts: `SharedTestRunData` is a `private static final` field on `TiaLauncherSessionListener`,
  so its `testSuiteTrackers` map lives for the JVM, and JUnit 4 gets the same effect because
  Surefire reuses the same `TiaJunit4Listener` instance across re-runs. That sharing exists so a
  re-run - which only covers the retried subset - cannot overwrite a suite's mapping with that
  subset. It has the side effect of keeping every suite the earlier attempt covered in the later
  attempt's persist, so those suites are re-written and re-flagged, and the seal that follows clears
  their flags legitimately. The escape would apply to a retry in a *fresh* JVM, where the statics
  are new - but that is the multi-fork case already documented below under "Multi-fork persist".
- The clear is deliberately unscoped - `UPDATE tia_test_suite SET unsealed = FALSE WHERE unsealed = TRUE`,
  not restricted to the suites this run touched. With a single sequential test run this is harmless
  (every flag it clears is one this run's own edge write just re-accounted for). With multiple
  forks or test tasks writing to one database, it is not: one fork's seal clears flags that a
  different fork set for suites *that fork* never touched. This intersects the known multi-fork
  persist-corruption limitation documented below under "Multi-fork persist" - the flag mechanism
  inherits that same lack of per-fork isolation.

Both gaps are "best-effort narrowing," not "closes the window" - a run's next diff can still land on
a suite whose flag was cleared without its coverage having been rewritten, in which case the
original class-of-bug this flag was built to shrink is possible again for that suite. What the flag
does guarantee: on the common path - a suite runs to completion in the same JVM that seals - the
suite's mapping rows and the commit value they are cleared against are consistent.

**Only a mapping-owning run can clear the flag.** `sealRun` returns without writing anything when
`updateDBMapping = false` - that path never reaches `persistSealedRunData`, so it never reaches
`clearUnsealedTestSuites` either.
Only the read side, `TestSelector.addUnsealedTests`, is unconditional. So once a mapping build has
crashed mid-persist and left suites flagged, a project that subsequently runs only preview or
non-mapping builds will force-run those flagged suites on every single build, indefinitely, until a
build that owns the mapping (`updateDBMapping = true`) completes and seals. This is the safe
direction to fail in - it costs selectivity, not correctness - but it is a standing cost worth
naming rather than a self-healing one.

**"A crash before the seal leaves exactly the suites that ran flagged" is slightly too strong.**
The suite-row `MERGE` in `persistTestSuites` (name + stats columns) commits in the connection's
default autocommit mode, before `persistTestSuiteClasses` opens its own per-suite transaction for
that suite's edges and flag. A crash landing in the narrow window between those two steps - after
the `MERGE` for a brand-new suite commits but before its `persistTestSuiteClasses` transaction opens
or commits - leaves that suite tracked with zero source-class edges and `unsealed = FALSE` (the
column's default), rather than flagged. From then on it reads identically to a suite Tia has always
found no coverage for: no diff can select it, because nothing maps to it, and it is not flagged, so
`getTestsToIgnore` puts it in the ignore list on every run. It stays there until its own test file
changes, it enters the failed-suite set, a static rule or library forced-selection names it, or an
all-tests run happens. Before the crash it was *untracked*, and an untracked suite always runs -
so the effect of the partial write is to move it from always-run to always-ignored.

The flag cannot cover this case by construction. Keeping `unsealed` out of the suite-row upsert is
precisely what makes the persist path physically unable to write `FALSE` (see above), so it cannot
be set in the same statement as the `MERGE`; setting it in a separate statement would add a round
trip per suite to the persist path. This is pre-existing (not introduced by the work that added the
flag) and the window is one statement wide, but it is a genuine counterexample to "exactly the
suites that ran are flagged".

**`SerializedDataStore` implements `clearUnsealedTestSuites()`, but nothing ever sets the flag
there.** The `unsealed = TRUE` write lives in `JdbcDataStore.persistTestSuiteClasses` only; the
serialized (in-memory / flat-file) backend has no equivalent write path, so every
`TestSuiteTracker.isUnsealed()` it produces is permanently `false` and the clear it performs on
seal is a no-op against rows that were never flagged. The mechanism is entirely inert on that
backend. This is a description, not a gap to close: `SerializedDataStore` has no plugin wiring (no
Maven or Gradle mojo/task constructs one) and is effectively dead in production - its only
references in the codebase are the interface's own javadoc and its own test.

### Id allocation: tia_id_block

`tia_source_class` rows need application-assigned ids so the class and edge inserts can be batched
into multi-row statements rather than one round trip per row (see `persistTestSuiteClasses`). That
id used to come from an in-memory `SELECT MAX(id)` read followed by an `ALTER TABLE ... RESTART WITH`
DDL statement to advance the table's identity counter - a read-then-write with no lock between them,
so two concurrent writers could read the same max, compute the same next id, and collide on the
primary key.

Ids now come from `tia_id_block`: one row per named counter (currently just `tia_source_class`,
keyed by `block_name`), holding the next id to hand out in `next_value`.
`allocateSourceClassIdBlock` locks that row with `SELECT ... FOR UPDATE`, reads the current value,
advances it by exactly the block size the calling persist needs, and commits - all inside the same
transaction, so two concurrent allocations serialize on the row lock and always receive disjoint
ranges. The `ALTER TABLE ... RESTART WITH` statement is gone from the persist path entirely. A
writer that dies after allocating leaves its reserved block unused, which is a harmless gap in the
id space - ids carry no meaning beyond identity.

### Failure-mode taxonomy

#### Pre-seal crash: mapping ahead of the commit (crash in step 1 or 2)

The stored commit value remains the **prior** value. The mapping tables may be:

- Partially updated. Some suites have new edges (one of the suites finished its per-suite transaction), others still have their prior edges (their per-suite transaction never ran).
- Ahead of what the stored commit claims. The mapping reflects newer state than the commit-value stamp suggests.

The next `select-tests` reads the prior commit value and diffs `workspace HEAD ↔ prior commit`. What
happens next differs by what kind of data is ahead:

- **Coverage edges** (`tia_source_class_method`) are self-correcting, with no under-selection. They
  carry no line coordinates - just "suite S covers method M" - so a suite's edges being ahead only
  means the diff computes a possibly-oversized impacted set and re-runs a superset of what's
  strictly needed. And crucially, a suite's edges can only be ahead if that suite actually ran and
  wrote them; a suite that didn't run isn't rewritten, so its stale edges are exactly as trustworthy
  against the prior commit as before.
- **The method catalogue** (`tia_source_method`) did **not** have that property before this table's
  write was folded into the seal transaction. `MethodImpactAnalyzer` matches the diff's *original-side*
  line numbers against each tracked method's stored `line_number_start` / `line_number_end`. If the
  catalogue reflects a later commit than the one the diff is read against, its line ranges sit in
  the wrong coordinate space for that diff - a method that moved or was resized between the stored
  commit and the actually-written catalogue could be missed by the line-range match, which is a
  real under-selection, not a merely-oversized one. This window is now closed: the catalogue write
  and the commit-value write are part of the same atomic seal bundle, so the catalogue can never be
  ahead of the stored commit - either both advanced, or neither did. The same reasoning applies to
  each tracked library's mapping baseline stamp, also written inside the seal bundle: it is a claim
  about the commit being sealed, and can no longer be ahead of it either.
- **Suite mapping rows** (`tia_test_suite` + `tia_source_class` + `tia_source_class_method`) being
  ahead of the commit is handled by the `unsealed` flag described above: any suite whose rows were
  written but not yet vouched for by a seal is force-selected on the next run, recapturing its
  coverage against whatever commit that next run seals - subject to the narrowing caveats above,
  not a hard guarantee.

#### Post-seal crash: commit ahead of the trailing writes (crash during or after step 4)

The commit value advanced to the new value, the catalogue and library baselines are consistent with
it, and every suite flagged unsealed as of this run's own edge writes has been cleared. The only
thing that didn't complete is the history-row write. **Not a correctness concern** - the mapping is
fully consistent; the audit log just misses one entry. The next run writes a new history row
whenever it next persists.

### Self-recovery and orphan handling

The "partially updated, orphan rows" state described under a pre-seal crash is real for edges written in step
1 whose corresponding `tia_source_method` catalogue entry does not yet exist on disk - for example a
first mapping run that crashes after some suites' edges are written but before the seal's catalogue
rewrite runs at all. The orphan-skip in `TestRunnerService.updateMethodTracker` exists specifically
to recover from it:

> The id is referenced from `tia_source_class_method` but neither this run's JaCoCo results nor the `tia_source_method` table on disk knows about it. Most likely an orphan left behind by an earlier run that aborted between updating the join table and the seal's rewrite of `tia_source_method`. Skip the orphan rather than NPE downstream in `persistSourceMethods`.

So even when this partial state exists, the next persist sees the orphan, logs it once at `ERROR`, and drops the dangling reference. The orphan disappears from the DB on the next clean persist (because `tia_source_class_method` is rewritten per-suite, replacing any rows that reference the orphan id).

Failed-tests recovery: if step 2 (`updateTestSuitesFailed`) ran but step 3 (the seal) didn't, the new failed-tests set is on disk but the commit value is still old. The next run reads the old commit + the new failed-tests, and the failed tests get force-re-run on the next attempt. If they now pass, they're cleared; if they still fail, no change. Self-correcting.

Library drain recovery: this is now folded into the seal's atomicity guarantee. Either the drain cleanup (deleted pending rows, advanced tracked-library baselines) and the commit-value advance both happened, or neither did - there is no longer a window where the pendings are gone but the commit value didn't advance, or vice versa.

### What is intentionally NOT mitigated

- **Cross-call atomicity across the whole of `persistTestRunData`**: still not implemented, and not needed - steps 1-2 (suite mapping, failed set) are safe to be ahead of the stored commit by construction (self-correcting edges, the `unsealed` flag, and an incremental failed-set), so only step 3 needed to become atomic, and it now is. A wrapping transaction across steps 1-2 as well would risk MVStore undo-log blow-up on large mapping updates for no additional correctness benefit.
- **Multi-fork persist (Gradle `maxParallelForks > 1`, `forkEvery > 0`)**: not supported. Tia relies on tests running sequentially in one JVM so JaCoCo coverage can be attributed per-suite. Running multiple forks concurrently will produce mappings polluted with cross-suite coverage and races between fork persists - not a partial-write issue but a fundamental architectural one. Use a single fork when Tia is updating the mapping DB. The unsealed-flag clear inherits this limitation directly: it is unscoped across all suites, so one fork's seal clears flags a peer fork set for suites that fork never touched (see "The `unsealed` flag" above).
- **Exactly-once semantics for history rows**: a crash between the seal and `persistTestRunHistoryEntry` means the run was sealed but no audit row exists for it. The mapping is consistent; only the audit log is short. Tia uses an idempotent MERGE on the deterministic id, so this could be addressed by retrying the history write - but the current code accepts the gap because the audit log isn't load-bearing for select-tests.

### Why a wrapping transaction across the whole persist isn't needed today

Three triggers would justify wrapping steps 1-2 into the seal's transaction as well:

1. The orphan-skip log messages (currently `ERROR` level) become noisy enough in production to mask real errors.
2. Audit-grade guarantees on history rows become a hard requirement.
3. The storage layer changes (e.g. moving to a shared multi-host DB) so per-call atomicity guarantees shift and need a different approach.

Until one of those triggers fires, the seal-last ordering plus the atomic seal bundle plus the
`unsealed` flag plus the orphan-skip fallback provides correct-by-construction behaviour for the
case that actually matters: keeping the stored commit value, the method catalogue and library
baselines, and (subject to the flag's documented narrowing, not closing) the suite mapping rows in
agreement.

The renderer is `TestRunHistoryConsoleFormatter` in `tia-core`; both the Maven `AbstractHistoryMojo` and the Gradle `TiaHistoryTask` are thin shells over `DataStore.readTestRunHistory()` and the formatter, so the output is identical from either build tool.

---


---

Prev: [Database schema (tables and relationships)](database-schema.md) | [Back to the Wiki index](../WIKI.md) | Next: [Distributed test runs (group assignment and the run lifecycle)](distributed-test-runs.md)
