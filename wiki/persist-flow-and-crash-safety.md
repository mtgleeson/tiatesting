# Persist flow and crash safety

### The problem this chapter explains

The Tia DB stores two things that must stay consistent with each other:

1. The **commit value** (or Perforce changelist): a single string on the `tia_core` row that says "the suite-to-method mapping is up to date as of commit X."
2. The **mapping**: rows in `tia_test_suite`, `tia_source_class`, `tia_source_class_method`, and `tia_source_method` that say "test suite A exercises methods M1, M2, M3."

If those two get out of sync — the stored commit claims X but the mapping reflects an earlier state — the next `select-tests` run computes a diff against X, sees no changes between "what's mapped" and "what's stored as the head," and **under-selects**. Tests that should run, don't. Silent correctness bug.

This chapter explains how Tia avoids that state, what failure modes remain, and how Tia self-recovers from them on the next run.

### One persist per run

A Tia-instrumented test run accumulates state in-process (suite trackers, method-id trackers, failed-suite set, drain results) and persists once at the end via `TestRunnerService.persistTestRunData`. The listener invokes it from:

- **JUnit 5**: `TiaTestExecutionListener.testPlanExecutionFinished`.
- **JUnit 4**: `TiaJunit4Listener.testRunFinished`.
- **Spock**: `TiaSpockRunListener.finishAllTests`, called from the global extension's `stop()` once per JVM.

On Surefire retries, `persistTestRunData` is called per attempt for JUnit 5 / JUnit 4 (each retry's listener writes its own row). Spock collapses retries naturally — one call per JVM. See the "Test-run history log" chapter for the per-attempt counters that decouple retry semantics from the mapping accumulator.

### Write sequence: the seal-last invariant

`persistTestRunData` sequences the DB writes so that the **commit value is the last mapping-related write**:

```
1. updateTestSuiteMapping      — tia_test_suite + tia_source_class + tia_source_class_method
2. updateMethodsTracked        — tia_source_method (reads tia_source_class_method, so depends on #1)
3. updateTestSuitesFailed      — tia_test_suites_failed
4. applyLibraryImpactDrainResult — drain pending rows + update tracked-library version stamps
5. updateTiaCoreData (SEAL)    — tia_core (commit value, last-updated, optional stats)
6. persistTestRunHistory       — tia_test_run_history (audit row)
```

The invariant: **if commit X is the stored value, every mapping write for X has completed.**

That's it. There's no enclosing transaction across the six steps — each `dataStore.persistX(...)` call manages its own connection lifecycle, and inside H2 each step has its own per-call atomicity guarantees (see below). Consistency between the commit value and the mapping comes from the **ordering**, not from a single atomic commit.

### Per-call atomicity inside H2DataStore

Each individual persist call is internally atomic in H2's MVStore:

- **`persistSourceMethods` (tia_source_method)**: `TRUNCATE` + `INSERT` are wrapped in one transaction. H2's `TRUNCATE` is transactional (unlike MySQL/InnoDB), so an exception during the `INSERT` rolls the truncate back too — the previous source-method rows survive. See `H2DataStore.persistSourceMethods` (the TRUNCATE-INSERT atomicity block).
- **`persistTestSuiteClasses` (tia_source_class + tia_source_class_method)**: per-suite `DELETE` + `INSERT` is wrapped in one transaction. A failure mid-rewrite of one suite's class/method edges leaves that suite's previous mappings intact. Wrapping the entire outer `persistTestSuites` loop in one transaction would put potentially millions of edges in one transaction and risk MVStore undo-log blow-up; per-suite is the right balance — each suite is internally consistent, and at worst a partial outer-loop failure leaves some suites updated and some not (the same outcome that would happen anyway).
- **`persistTestSuites` (tia_test_suite)**: `MERGE` per suite. Each MERGE is an atomic UPSERT.
- **`persistTestSuitesFailed` (tia_test_suites_failed)**: bulk delete + insert; idempotent on subsequent runs.
- **`persistCoreData` (tia_core)**: single `INSERT` or `UPDATE` of the one core row. Atomic.
- **`persistTestRunHistoryEntry` (tia_test_run_history)**: single `MERGE` keyed by a deterministic id derived from `branch|commit|runStartTimestampMs`. Idempotent — re-persisting the same logical run is a no-op.

What's NOT atomic is the *cross-call* sequence in `persistTestRunData`. That's where the seal-last ordering does its work.

### Failure-mode taxonomy

#### A. Crash *before* the seal (anywhere in steps 1-4)

The stored commit value remains the **prior** value. The mapping tables may be:

- Partially updated. Some suites have new edges (one of the suites finished its per-suite transaction), others still have their prior edges (their per-suite transaction never ran).
- Ahead of what the stored commit claims. The mapping reflects newer state than the commit-value stamp suggests.
- Carrying orphan `tia_source_class_method` rows whose corresponding `tia_source_method` tracker entries weren't written (the per-suite write at step 1 succeeded, but step 2 — which rewrites the whole `tia_source_method` table — never ran).

The next `select-tests` reads the prior commit value and diffs `workspace HEAD ↔ prior commit`. The mapping is "newer than claimed," so it covers strictly more code than the stored commit value suggests. **Net result:** the next run computes a (possibly slightly oversized) diff and re-runs the impacted tests. Self-correcting. No under-selection.

#### B. Crash *after* the seal (in or after step 6)

The commit value advanced to the new value. The only thing that didn't complete is the history-row write. **Not a correctness concern** — the mapping is fully consistent; the audit log just misses one entry. The next run writes a new history row whenever it next persists.

### Self-recovery and orphan handling

The "partially updated, orphan rows" state described in category A is real. The orphan-skip in `TestRunnerService.updateMethodTracker` exists specifically to recover from it:

> The id is referenced from `tia_source_class_method` but neither this run's JaCoCo results nor the `tia_source_method` table on disk knows about it. Most likely an orphan left behind by an earlier run that aborted between updating the join table and the truncate+insert of `tia_source_method`. Skip the orphan rather than NPE downstream.

So even when category-A partial state exists, the next persist sees the orphan, logs it once at `ERROR`, and drops the dangling reference. The orphan disappears from the DB on the next clean persist (because `tia_source_class_method` is rewritten per-suite, replacing any rows that reference the orphan id).

Failed-tests recovery: if step 3 (`updateTestSuitesFailed`) ran but step 5 (the seal) didn't, the new failed-tests set is on disk but the commit value is still old. The next run reads the old commit + the new failed-tests, and the failed tests get force-re-run on the next attempt. If they now pass, they're cleared; if they still fail, no change. Self-correcting.

Library drain recovery: if step 4 ran but step 5 didn't, the drained pending rows are gone but the commit value didn't advance. The next run computes its diff against the prior commit and proceeds without those pending rows — the library impact for those drained batches has already been reflected in the (persisted) suite mapping, so dropping the pendings is consistent. If step 4 didn't run, the pendings stay in the DB; the next run re-drains them on its own successful persist. Either way the system converges.

### What is intentionally NOT mitigated

- **Cross-call atomicity (one transaction wrapping all of `persistTestRunData`)**: not implemented. The seal-last ordering makes correctness-relevant failure modes safe-by-default (always over-select on recovery, never under-select). A wrapping transaction would convert orphan-row warnings into "never happens" but would also risk MVStore undo-log blow-up on large mapping updates. The cost/value isn't currently justified. See the residual-risk note in the `TestRunnerService.persistTestRunData` javadoc.
- **Multi-fork persist (Gradle `maxParallelForks > 1`, `forkEvery > 0`)**: not supported. Tia relies on tests running sequentially in one JVM so JaCoCo coverage can be attributed per-suite. Running multiple forks concurrently will produce mappings polluted with cross-suite coverage and races between fork persists — not a partial-write issue but a fundamental architectural one. Use a single fork when Tia is updating the mapping DB.
- **Exactly-once semantics for history rows**: a crash between the seal and `persistTestRunHistoryEntry` means the run was sealed but no audit row exists for it. The mapping is consistent; only the audit log is short. Tia uses an idempotent MERGE on the deterministic id, so this could be addressed by retrying the history write — but the current code accepts the gap because the audit log isn't load-bearing for select-tests.

### Why a wrapping transaction isn't needed today

Three triggers would justify wrapping all of `persistTestRunData` in a single H2 transaction:

1. The orphan-skip log messages (currently `ERROR` level) become noisy enough in production to mask real errors.
2. Audit-grade guarantees on history rows become a hard requirement.
3. The storage layer changes (e.g. moving to a shared multi-host DB) so per-call atomicity guarantees shift and need a different approach.

Until one of those triggers fires, the seal-last ordering plus per-call H2 atomicity plus the orphan-skip fallback provides correct-by-construction behaviour for the case that actually matters: keeping the stored commit value and the mapping in agreement.

The renderer is `TestRunHistoryConsoleFormatter` in `tia-core`; both the Maven `AbstractHistoryMojo` and the Gradle `TiaHistoryTask` are thin shells over `DataStore.readTestRunHistory()` and the formatter, so the output is identical from either build tool.

---


---

Prev: [Database schema (tables and relationships)](database-schema.md) | [Back to the Wiki index](../WIKI.md) | Next: [Embedded vs server-mode H2 connections](h2-connection-modes.md)
