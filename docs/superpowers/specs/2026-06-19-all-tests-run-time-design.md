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

### Developer-disabled suites must not block the all-tests classification

`getIgnoredTestSuiteCount()` is computed at selection time as `testsToIgnore.size()`, where
`TestSelector.getTestsToIgnore()` returns every tracked suite that wasn't selected to run
(`tracked - testsToRun`). That set is *not* limited to suites Tia genuinely skipped to save
time - it also picks up any suite that ran before (so it has a mapping row), then the developer
`@Disabled`/`@Ignore`d, whose row we keep in the DB. On any run where such a suite isn't
impacted it lands in `testsToIgnore` and bumps the count above zero.

Consequence: once even one previously-tracked suite is developer-disabled, `getIgnoredTestSuiteCount() == 0`
is essentially never true again (except on a seed/DB wipe), so `allTestsRunTime` would almost
never update. Developers commonly disable tests for spans covering many Tia runs, so this is the
normal case, not an edge case.

Fix: Tia records, per suite, whether the suite is developer-disabled (Stage 1 below), and
`getTestsToIgnore()` excludes developer-disabled suites from the ignore set. The trigger then
stays `getIgnoredTestSuiteCount() == 0`, but the count now reflects only suites Tia chose to
skip that *could* otherwise have run. Equivalently: a developer-disabled suite never counts as
a Tia-ignored suite.

The runtime determination cannot distinguish a developer-disabled suite from a Tia-disabled one
on a run where Tia *also* ignored it (Tia force-applies the same `@Disabled`/`@Ignore`
annotation), so the developer-disabled state is observed when it is unambiguous and persisted
for later runs. See Stage 1.

## Approach

Chosen: add the two fields to the existing shared `TestStats` model and branch at the Tia-core
persist path via a boolean flag. Reuses the existing rolling-average machinery and persistence
plumbing; the all-tests behavior is scoped to the Tia level only.

Rejected alternatives:
- A separate `AllTestsStats` model/table - duplicates persistence/report/serialization plumbing
  for two fields.
- Deriving the baseline on read from `tia_test_run_history` rows where `num_suites_ignored == 0`
  - adds work to the hot read path and breaks if history is pruned.

## Stage 1 - Developer-disabled suite flag

Goal: give each tracked suite a persisted `developerDisabled` flag, maintain it from the test
run, and have the selector exclude flagged suites from the ignore set. This must land before the
stats stages so the `getIgnoredTestSuiteCount() == 0` trigger is trustworthy.

### Model - `TestSuiteTracker`

File: `tia-core/src/main/java/org/tiatesting/core/model/TestSuiteTracker.java`

Add a `boolean developerDisabled` field with javadoc, getter/setter, and `toString` coverage.
Default `false`. This is suite-level metadata, distinct from the per-suite `TestStats`.

### Detection - where the flag is set/cleared

The determination is made at persist time, against the merged tracked map, using three signals
already on `TestRunResult`:

- `getRunnerTestSuites()` - every suite the runner discovered (executed + skipped + filtered).
- `getTestSuiteTrackers()` keys - suites that actually executed this run (a skipped suite
  produces no tracker).
- `getSelectedTests()` - the suites Tia selected to run (so the suites Tia did *not* disable).

For a tracked suite X, per run:

- `X` executed (in `getTestSuiteTrackers()` keys) -> set `developerDisabled = false`. Executing
  proves it is not disabled, so clearing is always safe.
- else `X` in `getSelectedTests()` and in `getRunnerTestSuites()` but did not execute -> set
  `developerDisabled = true`. Tia selected it (so Tia did not disable it) yet the engine
  discovered it and skipped it: the developer disabled it.
- else (Tia ignored X, or X wasn't discovered this run) -> leave the flag unchanged; carry the
  stored value forward through the merge.

This is self-maintaining because toggling `@Disabled`/`@Ignore` modifies the test file, and
`TestSelector.addModifiedTestFilesToRunList` puts any tracked modified test file into
`testsToRun`. So the disabling commit selects the suite on that very run (observed disabled ->
flag set), and the re-enabling commit selects it again (observed running -> flag cleared). A
suite can then stay disabled across many later runs without ever being re-impacted and the flag
persists untouched.

Notes / limitations:

- Flag maintenance rides on `updateDBMapping` runs only (the flag is mapping metadata persisted
  via `persistTestSuites`). Stats-only / select-only runs leave it untouched. Same constraint as
  coverage updates.
- Suites disabled before they ever ran have no mapping row, so they never enter `testsToIgnore`
  and need no flag - no special handling.
- A suite Tia selected that the engine skips for a *non-disable* reason (e.g. a Surefire
  `groups`/tag filter) is a false positive: it would be flagged disabled. The effect is bounded
  (it is excluded from the ignore count and self-corrects the next time it runs and clears the
  flag), and is accepted for now.

### Trigger + wiring - `TestRunnerService`

File: `tia-core/src/main/java/org/tiatesting/core/testrunner/TestRunnerService.java`

- `persistTestRunData` (the `updateDBMapping` branch) applies the per-suite determination above
  to `tiaData.getTestSuitesTracked()` after the mapping merge and before `persistTestSuites`,
  using `testRunResult.getSelectedTests()`, `getRunnerTestSuites()`, and the executed-suite key
  set. A small private helper (`updateDeveloperDisabledFlags(...)`, javadoc'd) does the work.
- `mergeTestMappingMaps` must carry the stored `developerDisabled` value forward for suites not
  re-run this attempt, so the "leave unchanged" case above holds.
- Retention already works: `removeDeletedTestSuites` keys off `runnerTestSuites` (discovered),
  not executed, so a discovered-but-skipped suite's row is kept. No change needed there; this
  stage adds the flag, not the retention.

### Persistence - `H2DataStore` (`tia_test_suite`)

File: `tia-core/src/main/java/org/tiatesting/core/persistence/h2/H2DataStore.java`

- Add `COL_DEVELOPER_DISABLED = "developer_disabled"`.
- `createTestSuiteTableSql`: add `developer_disabled BOOLEAN DEFAULT FALSE` to `tia_test_suite`.
- `persistTestSuites` insert/update: write the column from `isDeveloperDisabled()`.
- `getTestSuitesTracked` (the suite names+stats read used by both the selector and the persist
  merge): hydrate the flag into each `TestSuiteTracker`. A NULL on a pre-existing row reads back
  as `false`.
- Migration: `ensureTestSuiteDisabledColumnExists(connection)` issuing
  `ALTER TABLE tia_test_suite ADD COLUMN IF NOT EXISTS developer_disabled BOOLEAN DEFAULT FALSE`,
  called from `ensureSchema` alongside the existing `ensure*` migrations.
- `persistTestSuiteStatsOnly` is unchanged - the flag is not a stats column.

### Selection-side consumption - `TestSelector`

File: `tia-core/src/main/java/org/tiatesting/core/diff/diffanalyze/selector/TestSelector.java`

- `getTestsToIgnore(testSuitesTracked, testsToRun)` skips any tracked suite whose tracker has
  `developerDisabled == true`. The ignore set (and therefore `tiaIgnoredTestSuiteCount` and the
  history `num_suites_ignored`) then excludes developer-disabled suites, making
  `getIgnoredTestSuiteCount() == 0` the correct all-tests trigger.

### Testing (Stage 1)

`given` / `when` / `then`, javadoc on new/changed methods:

- `TestSuiteTracker` flag default, getter/setter, `toString`.
- The detection helper: executed -> cleared; selected-and-discovered-but-skipped -> set;
  Tia-ignored or not-discovered -> unchanged (stored value carried forward).
- `mergeTestMappingMaps` carries the stored flag forward for suites not re-run.
- `TestSelector.getTestsToIgnore` excludes developer-disabled tracked suites.
- `H2DataStore` round-trip persist/load of the column and the migration adding it to a DB
  created without it (old rows read back as `false`).

## Stage 2 - Data model - `TestStats`

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

## Stage 2 - Trigger + wiring

File: `tia-core/src/main/java/org/tiatesting/core/testrunner/TestRunnerService.java`

- `persistTestRunData` computes `boolean allTestsRun = testRunResult.getIgnoredTestSuiteCount() == 0;`
  and passes it into `updateTiaCoreData`. With Stage 1 in place, `getIgnoredTestSuiteCount()`
  already excludes developer-disabled suites, so this stays a plain `== 0` check.
- `updateTiaCoreData(...)` gains a `boolean allTestsRun` param and calls
  `tiaData.incrementStats(testStats, allTestsRun)`.

Signature changes ripple (direct changes, no overloads/shims per project rule):
- `tia-core/.../model/TiaData.java` `incrementStats(...)` - add the boolean param and forward it.
- `tia-core/.../model/TestSuiteTracker.java` `incrementStats(...)` - call per-suite stats with
  `false`.

The `mergeTestMappingStats` -> `TestSuiteTracker.incrementStats` per-suite path stays on the
`false` branch.

## Stage 3 - Persistence - `H2DataStore` (`tia_core` only)

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

## Stage 4 - Reports (Tia-level summary only)

Add a "Number of all-tests runs" line and an "All tests run time" line
(via `ReportUtils.prettyDuration`) next to the existing "Number of runs" / "Average run time":

- `tia-core/.../report/StatusReportGenerator.java`
- `tia-core/.../report/plaintext/TextSummaryReport.java`
- `tia-core/.../report/html/HtmlSummaryReport.java`

Per-suite reports (`HtmlTestSuiteReport`, `HtmlSourceMethodReport`) are unchanged - the
all-tests stat is Tia-level only and is always 0 per suite.

## Testing (Stages 2-4)

`given` / `when` / `then` style, javadoc on new/changed methods. Stage 1's own tests are listed
in its section above:

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

1. Developer-disabled suite flag: `TestSuiteTracker` field, detection in `TestRunnerService`,
   `tia_test_suite` column + migration, `TestSelector.getTestsToIgnore` exclusion + unit tests.
   Lands first so the trigger in later stages is trustworthy.
2. Model (`TestStats` + callers) + unit tests.
3. Persistence (`H2DataStore` `tia_core` columns + migration) + tests.
4. Reports + tests.
