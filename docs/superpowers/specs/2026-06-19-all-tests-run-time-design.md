# Design: Track Tia-level stat `allTestsRunTime`

Date: 2026-06-19
Status: Approved

## Problem

The Tia-level `avgRunTime` (a rolling average on `TiaData.testStats`) is updated on every
stats-persisting run, including the first seed run and any run where Tia ran all tests. That
pollutes the "average run time when running Tia-selected tests" figure with full-suite runs,
so it cannot be used to show the time Tia saves.

## Goal

Separate the two run regimes into independent stats:

- **All-tests runs** - Tia ignored zero suites (the first seed run, or Tia selected every
  suite). Update a new rolling-average stat `allTestsRunTime` plus its run counter
  `numAllTestsRuns`. Do not touch `avgRunTime`.
- **Selected runs** - Tia ignored at least one suite. Update `avgRunTime` as today. Do not
  touch `allTestsRunTime` / `numAllTestsRuns`.

Outcome: `avgRunTime` becomes a clean "average time for Tia-selected runs", `allTestsRunTime`
becomes the "average time to run everything" baseline, and `numAllTestsRuns` shows how many
times the full suite ran. Both are surfaced in the summary reports.

## Trigger signal

A run counts as an all-tests run when `TestRunResult.getIgnoredTestSuiteCount() == 0`. This
covers both intended cases:

- Seed run: no stored mapping, so the selector produces an empty `testsToIgnore`.
- Tia selected every suite: `testsToIgnore` is empty even though `selectedTests` is non-empty.

`selectedTests.isEmpty()` was rejected because it would misclassify the "selected all suites"
case as a selected run.

## Approach

Chosen: add the two fields to the existing shared `TestStats` model and branch at the Tia-core
persist path via a boolean flag. Reuses the existing rolling-average machinery and persistence
plumbing; the all-tests behavior is scoped to the Tia level only.

Rejected alternatives:
- A separate `AllTestsStats` model/table - duplicates persistence/report/serialization plumbing
  for two fields.
- Deriving the baseline on read from `tia_test_run_history` rows where `num_suites_ignored == 0`
  - adds work to the hot read path and breaks if history is pruned.

## Data model - `TestStats`

File: `tia-core/src/main/java/org/tiatesting/core/model/TestStats.java`

Add two `long` fields with javadoc, getters/setters, and `toString` coverage:

- `allTestsRunTime` - rolling average (ms) over full-suite runs.
- `numAllTestsRuns` - count of full-suite runs (a useful figure on its own).

Change `incrementStats(TestStats testStats)` to `incrementStats(TestStats testStats, boolean allTestsRun)`:

- The incoming per-run duration is carried in `testStats.getAvgRunTime()` (the framework
  listeners set it via `setAvgRunTime(elapsed)`), with `numRuns == 1`, or `0` on a Surefire
  retry which remains a no-op as today.
- `allTestsRun == true`: fold the duration into `allTestsRunTime` over the current
  `numAllTestsRuns`, then increment `numAllTestsRuns`. Leave `avgRunTime` untouched.
- `allTestsRun == false`: fold the duration into `avgRunTime` over the selected sub-count
  `numRuns - numAllTestsRuns` (computed before `numRuns` is bumped). Leave `allTestsRunTime`
  untouched.
- Both branches still increment `numRuns`, `numSuccessRuns`, `numFailRuns`, so the totals and
  the existing success/fail percentages are unchanged.

`TestStats` is shared between the Tia-level stat (`TiaData.testStats`) and per-suite stats
(`TestSuiteTracker.testStats`). Per-suite callers always pass `allTestsRun = false`, so
`numAllTestsRuns` stays 0 for suites and per-suite `avgRunTime` math is byte-for-byte unchanged.

## Trigger + wiring

File: `tia-core/src/main/java/org/tiatesting/core/testrunner/TestRunnerService.java`

- `persistTestRunData` computes `boolean allTestsRun = testRunResult.getIgnoredTestSuiteCount() == 0;`
  and passes it into `updateTiaCoreData`.
- `updateTiaCoreData(...)` gains a `boolean allTestsRun` param and calls
  `tiaData.incrementStats(testStats, allTestsRun)`.

Signature changes ripple (direct changes, no overloads/shims per project rule):
- `tia-core/.../model/TiaData.java` `incrementStats(...)` - add the boolean param and forward it.
- `tia-core/.../model/TestSuiteTracker.java` `incrementStats(...)` - call per-suite stats with
  `false`.

The `mergeTestMappingStats` -> `TestSuiteTracker.incrementStats` per-suite path stays on the
`false` branch.

## Persistence - `H2DataStore` (`tia_core` only)

File: `tia-core/src/main/java/org/tiatesting/core/persistence/h2/H2DataStore.java`

- Add `COL_ALL_TESTS_RUN_TIME = "all_tests_run_time"` and `COL_NUM_ALL_TESTS_RUNS = "num_all_tests_runs"`.
- `createTiaDB` `createCoreTableSql`: add both as `BIGINT` to the `tia_core` table only (not
  `tia_test_suite`).
- `persistTiaCore` insert and update: add the columns / SET clauses sourced from
  `getAllTestsRunTime()` / `getNumAllTestsRuns()`.
- `getCoreData`: read both columns into `testStats`. NULL maps to 0 via `getLong`, the correct
  default for pre-existing rows.
- Migration: new `ensureTiaCoreStatsColumnsExist(connection)` issuing
  `ALTER TABLE tia_core ADD COLUMN IF NOT EXISTS ...` for both columns, called from
  `ensureSchema` alongside the existing `ensure*` migration calls.

## Reports (Tia-level summary only)

Add a "Number of all-tests runs" line and an "All tests run time" line
(via `ReportUtils.prettyDuration`) next to the existing "Number of runs" / "Average run time":

- `tia-core/.../report/StatusReportGenerator.java`
- `tia-core/.../report/plaintext/TextSummaryReport.java`
- `tia-core/.../report/html/HtmlSummaryReport.java`

Per-suite reports (`HtmlTestSuiteReport`, `HtmlSourceMethodReport`) are unchanged - the
all-tests stat is Tia-level only and is always 0 per suite.

## Testing

`given` / `when` / `then` style, javadoc on new/changed methods:

- `TestStats.incrementStats` rolling-average correctness: pure selected runs, pure all-tests
  runs, an interleaved sequence (the two averages and both counters stay independent), and a
  retry (`numRuns == 0` incoming) being a no-op.
- `TestRunnerService` selects the all-tests branch based on `ignoredTestSuiteCount`.
- `H2DataStore` round-trip persist/load of the two new columns, and the migration adding the
  columns to a DB created without them (old rows read back as 0).
- Fix existing tests that call the changed `incrementStats` signature.

## Delivery

New branch `track-all-tests-run-time` off `main`. Staged delivery with a review pause and a
commit-message summary after each stage:

1. Model (`TestStats` + callers) + unit tests.
2. Persistence (`H2DataStore` columns + migration) + tests.
3. Reports + tests.
