# Test-run history log (`tia_test_run_history`)

### What it captures

Every Tia-enabled test run logs one row to a `tia_test_run_history` table in the same H2 file Tia already keeps per-branch. Each row captures:

- A deterministic id, derived from `branch | commit | runStartTimestampMs` so two persists of the same logical run produce the same row (idempotent MERGE on primary key).
- `run_timestamp` — UTC epoch milliseconds when the run started.
- `branch`, `commit_value` — VCS branch and commit / changelist the run targeted.
- `num_suites_ran`, `num_suites_ignored`, `num_suites_failed` — derived from the listener data already produced for stats / mapping (`testSuiteTrackers.size()`, `runnerTestSuites.size() - testSuiteTrackers.size()`, `testSuitesFailed.size()`).
- `duration_ms` — wall-clock duration of the run.
- `updated_db_mapping` — whether this run also persisted updates to the suite-to-method mapping.
- `run_source`, `host_name` — where the run came from and which machine executed it. See "Run origin" below.

The table is append-mostly; an index on `run_timestamp` backs the report's default "most-recent first" sort. There's currently no retention policy — the rows are tiny and the table grows slowly enough not to need pruning in practice.

### Run origin (`run_source`, `host_name`)

These two columns exist to answer "how much is Tia actually saving, and for whom" without guessing. Before them the only available discriminator was `updated_db_mapping`, which is a proxy rather than a fact about the run: a CI job configured with mapping updates off is indistinguishable from a developer's laptop. There was also nothing to group runs by machine, so a per-machine average silently mixed a maxed-out laptop with a workstation.

**`run_source` is detected, not configured.** `RunEnvironment.runSource()` returns `CI` when any of a set of marker environment variables is present (`CI`, `BUILD_NUMBER`, `JENKINS_URL`, `GITHUB_ACTIONS`, `GITLAB_CI`, `TEAMCITY_VERSION`, `BUILDKITE`, `CIRCLECI`, `TF_BUILD`, `bamboo_buildKey`), and `LOCAL` otherwise. Presence is the signal, not the value — a CI system is still a CI system whatever it sets its marker to — but an exported-but-empty variable does not count, since some shells export empty values wholesale.

Detection rather than configuration is deliberate. A forked test JVM inherits its parent's environment, so this works inside the fork with nothing plumbed through the build plugins and nothing for a developer to set up. A scheme that had to be configured per job would produce mislabelled rows from whichever job forgot, and a mislabelled row is worse than no column because it looks authoritative.

**The escape hatch** is `tiaRunSource`, which overrides detection with an arbitrary label (`NIGHTLY`, `PERF-RIG`, ...). It is available three ways, in this precedence order:

1. The `tiaRunSource` **system property** in the test JVM.
2. The `TIA_RUN_SOURCE` **environment variable**.
3. Detection.

As plugin config it is the Maven `tiaRunSource` parameter and the Gradle `runSource` extension property, both of which forward it into the forked test JVM as the system property — and both of which forward *nothing at all* when unset, so the fork sees the property absent and falls back to detection rather than receiving the literal string `"null"` and storing that as the run's source. On Gradle it merges from the project extension to each test task's like the distributed settings do, since it describes the build rather than any one test task.

Note that a bare `-DtiaRunSource=...` on the Maven command line sets the property on the *build* JVM, not the fork, so it has no effect on its own — use the plugin parameter (which the command-line property does feed, via `@Parameter(property = ...)`) or the environment variable.

**Nulls mean "not known"**, throughout. A row written before these columns existed reads back null on both (the migration adds them with no `DEFAULT`, so old rows are not retro-labelled with an origin nobody recorded). A run whose hostname will not resolve stores a null host rather than a placeholder — several unrelated runs would otherwise appear to share a machine called "unknown". And a distributed build stores its source but a **null host**: the row describes work several machines did between them, so naming the one that happened to seal last would read as "this build ran here", which is exactly what it did not do.

**What the columns do not fix.** The stored `time_savings` on a local row is still computed against `all_tests_run_time`, the baseline CI maintains — so it is (CI's full-suite time) minus (a laptop's partial run time), two different machines. To get a defensible local-machine ROI figure, compute the savings yourself from the local rows: average `duration_ms` where `num_suites_ignored = 0` is that population's full-suite baseline, and the difference from the average partial run is the real saving.

### Why timestamps are stored as UTC epoch ms

Tia runs on developer laptops, CI runners, and shared workspaces in potentially different timezones. Storing a timezone-agnostic numeric value avoids any "what does this string mean in this DB" ambiguity. The HTML History page renders each row's timestamp in the viewer's **local** timezone via a small inline script that calls `new Date(ms).toLocaleString(...)` — no millisecond precision and no timezone marker in the displayed text.

### The HTML report "History" tab

`HtmlHistoryReport` reads `tiaData.getTestRunHistory()` and renders `history/tia-history.html`, linked from the top navigation as "History". The table uses `simple-datatables` for sort / filter / paginate, defaulting to date descending. Long values (entry id, commit hash) are truncated to 8 characters in the cell; the full value is on a hover `title` so it stays accessible without widening the column.

`Source` and `Host` render there too, on the same "only when some row has one" rule the console table uses, and dashed rather than blank on a row that has none — an empty cell reads as a rendering slip, and a dash also sorts the unknown rows together.

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

When any run in view was a distributed build, two further columns appear after `Duration` -
`Wall clock` and `Groups`:

```
Date/time            Branch  Commit    Ran  Ignored  Failed  Duration  Wall clock  Groups  Savings  Savings %  Mapping  Id
-------------------  ------  --------  ---  -------  ------  --------  ----------  ------  -------  ---------  -------  --------
2026-08-17 22:35:46  main    6097d683    2        1       0  615ms     506ms            2  49ms            7%  yes      3fd70a70
2026-08-17 20:50:23  main    51e8970a    3        0       0  664ms     664ms            1  -                -  yes      17972bd5
```

`Duration` keeps its meaning in both modes - it is the **serial equivalent**, what the run's
selection would have cost on one host - which is why it stays the column `Savings` is computed
from and why a project's history stays comparable across the build where distributed mode was
switched on. `Wall clock` is what the distributed build actually waited for: its slowest group.
The two are equal when a run had a single group, as the seed run above did. A single-host row in a
mixed history dashes both extra columns rather than showing zeros, which would read as a build that
took no time and used no groups; a history with no distributed run in view renders neither column,
so the table is exactly as it was for a project that does not distribute. See
["Reporting: two durations, one history row"](distributed-test-runs.md#reporting-two-durations-one-history-row)
for how the sealer computes the pair, and why the wall clock is deliberately not the primary figure.

Likewise, `Source` and `Host` appear after `Savings %` when any run in view recorded where it came from, and a history recorded entirely before those columns existed renders neither. The host is deliberately **not** truncated the way commit and id are: it is read to tell machines apart, and a fixed-width prefix of several agents in one naming scheme would collapse them into one. A distributed build dashes the host - no single machine ran it.

Both optional groups are assembled by filtering one list of column descriptors (header, alignment, cell accessor) rather than by selecting between hardcoded parallel arrays. With two independent toggles there are four layouts; held as three parallel arrays each, a header, an alignment flag and a cell that drifted out of step would produce a table that is quietly *wrong* rather than one that fails.

Column widths are computed dynamically from the data so the table stays compact regardless of branch-name length. Numeric columns right-align; commit and id are truncated to the first 8 characters (matching the HTML report's compact rendering). Date/time is rendered in the JVM's local timezone using `yyyy-MM-dd HH:mm:ss`. The mapping flag renders as `yes` / `no` — the compact table form, not the HTML's "updated / not updated" wording. The `Savings` / `Savings %` columns show the time that run saved versus running the full suite, frozen at run time against the all-tests baseline then current; an all-tests run (and any run recorded before a baseline existed) shows `-`. When the history table is empty, the task prints `No Tia test run history recorded yet.` and exits cleanly.


---

Prev: [Profiling select-tests against a synthetic large DB](profiling-select-tests.md) | [Back to the Wiki index](../WIKI.md) | Next: [The select-tests run-time estimate and its overhead model](select-tests-run-time-estimate.md)
