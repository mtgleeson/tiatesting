# Test-run history log (`tia_test_run_history`)

### What it captures

Every Tia-enabled test run logs one row to a `tia_test_run_history` table in the same H2 file Tia already keeps per-branch. Each row captures:

- A deterministic id, derived from `branch | commit | runStartTimestampMs` so two persists of the same logical run produce the same row (idempotent MERGE on primary key).
- `run_timestamp` — UTC epoch milliseconds when the run started.
- `branch`, `commit_value` — VCS branch and commit / changelist the run targeted.
- `num_suites_ran`, `num_suites_ignored`, `num_suites_failed` — derived from the listener data already produced for stats / mapping (`testSuiteTrackers.size()`, `runnerTestSuites.size() - testSuiteTrackers.size()`, `testSuitesFailed.size()`).
- `duration_ms` — wall-clock duration of the run.
- `updated_db_mapping` — whether this run also persisted updates to the suite-to-method mapping.

The table is append-mostly; an index on `run_timestamp` backs the report's default "most-recent first" sort. There's currently no retention policy — the rows are tiny and the table grows slowly enough not to need pruning in practice.

### Why timestamps are stored as UTC epoch ms

Tia runs on developer laptops, CI runners, and shared workspaces in potentially different timezones. Storing a timezone-agnostic numeric value avoids any "what does this string mean in this DB" ambiguity. The HTML History page renders each row's timestamp in the viewer's **local** timezone via a small inline script that calls `new Date(ms).toLocaleString(...)` — no millisecond precision and no timezone marker in the displayed text.

### The HTML report "History" tab

`HtmlHistoryReport` reads `tiaData.getTestRunHistory()` and renders `history/tia-history.html`, linked from the top navigation as "History". The table uses `simple-datatables` for sort / filter / paginate, defaulting to date descending. Long values (entry id, commit hash) are truncated to 8 characters in the cell; the full value is on a hover `title` so it stays accessible without widening the column.

A subtlety worth knowing: the local-time-rendering script must run **before** the `simple-datatables` init, not after. `simple-datatables` captures cell text into its internal model at init time; if the localization runs later via `DOMContentLoaded`, the `<time>` elements have already been replaced by `simple-datatables`' render output and the swap finds nothing.

### Config gate

The log is gated by `tiaUpdateDBTestRunHistory` (default **true**). Unlike `tiaUpdateDBMapping` / `tiaUpdateDBStats` — which default to `false` because they're CI-only writes — the history log is cheap (one INSERT per run, no mapping mutation) and is only useful when continuously populated, so on-by-default is the sane choice.

The flag participates in the listener's enablement predicate (`enabled && (updateDBMapping || updateDBStats || updateDBTestRunHistory)`). That means a project with Tia enabled but no DB mapping / stats writes still benefits from the history log — handy for local-only setups that just want a record of what they ran.

### Inspecting from the CLI

The HTML report is the rich view, but it requires a full `tia-html-report` invocation and a browser. For a quick look from the terminal there's a dedicated task — Maven goal `history`, Gradle task `tia-history` — that prints the most recent rows from `tia_test_run_history` to stdout as a fixed-width table. Sample output:

```
Displaying the latest 20 test runs from a total of 47

Date/time            Branch        Commit    Ran  Ignored  Failed  Duration  Savings  Savings %  Mapping  Id
-------------------  ------------  --------  ---  -------  ------  --------  -------  ---------  -------  --------
2026-05-15 09:30:42  main          abc123de   42        3       1  1m 23s    5m 12s         79%  yes      550e8400
2026-05-14 14:22:01  feature/foo   9f8a1b2c   30        0       0  45s       -                -  no       7c3e1a09
```

The number of rows is configurable: `mvn <plugin>:history -DtiaHistoryLast=N` for Maven, `./gradlew tia-history --last=N` for Gradle. The default is **20**, chosen so the output fits in a terminal screen without scrolling. Values `<= 0` (or non-numeric for `--last`) fail fast with a clear error.

Column widths are computed dynamically from the data so the table stays compact regardless of branch-name length. Numeric columns right-align; commit and id are truncated to the first 8 characters (matching the HTML report's compact rendering). Date/time is rendered in the JVM's local timezone using `yyyy-MM-dd HH:mm:ss`. The mapping flag renders as `yes` / `no` — the compact table form, not the HTML's "updated / not updated" wording. The `Savings` / `Savings %` columns show the time that run saved versus running the full suite, frozen at run time against the all-tests baseline then current; an all-tests run (and any run recorded before a baseline existed) shows `-`. When the history table is empty, the task prints `No Tia test run history recorded yet.` and exits cleanly.


---

Prev: [Profiling select-tests against a synthetic large DB](profiling-select-tests.md) | [Back to the Wiki index](../WIKI.md) | Next: [The select-tests run-time estimate and its mapping overhead](select-tests-run-time-estimate.md)
