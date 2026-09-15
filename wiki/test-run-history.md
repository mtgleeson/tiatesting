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

**A null host means "no machine to name"**. The source is never null - `RunEnvironment` falls back to `LOCAL` when nothing marks the run as CI - so every run Tia records carries one. A run whose hostname will not resolve stores a null host rather than a placeholder — several unrelated runs would otherwise appear to share a machine called "unknown". And a distributed build stores its source but a **null host**: the row describes work several machines did between them, so naming the one that happened to seal last would read as "this build ran here", which is exactly what it did not do.

**What the columns do not fix.** The stored `time_savings` on a local row is still computed against `all_tests_run_time`, the baseline CI maintains — so it is (CI's full-suite time) minus (a laptop's partial run time), two different machines. To get a defensible local-machine ROI figure, compute the savings yourself from the local rows: average `duration_ms` where `num_suites_ignored = 0` is that population's full-suite baseline, and the difference from the average partial run is the real saving.

### Runs that executed no test suite

A build whose test framework is not wired up correctly - a missing or mismatched JUnit dependency being the usual cause - finishes in milliseconds having executed nothing, and reports itself as a pass, because no suite ran so no suite failed. Left alone, Tia recorded that as a legitimate measurement: one run, one success, and on a run that ignored nothing its ~150ms duration became the new `all_tests_run_time`, the full-suite baseline every later savings figure is measured against. One misconfigured build could therefore collapse the baseline and silently deflate the reported savings of every run after it.

So a run that executed **none of the suites Tia expected it to** persists nothing but its history row. Nothing it could write is a claim it has earned: it observed neither the commit nor the suites.

| Write | Why an empty run is kept away from it |
| --- | --- |
| The Tia-level stats | It timed no test, so it is neither a run nor a success, and its duration belongs in neither average - least of all the full-suite baseline |
| The seal, and with it the stored commit value | Advancing the commit leaves the next run diffing past the suites this run never covered, so the tests that should have run do not. Skipping the seal leaves the stored commit at the prior value, which is exactly the state a crash before the seal leaves behind and which Tia already self-corrects |
| The tracked libraries' mapping baselines (part of the seal) | Same reason, one level down: the libraries were not re-covered, so their next change must stay visible to the diff |
| The `unsealed` flags (part of the seal) | The flag force-selects suites whose coverage is not yet trusted; an empty run recaptured none of it |
| The failed-suite set | It is maintained by removing the run's selection and adding back what failed. Nothing ran, so nothing failed - applying that would drop previously-failed suites from the force-run set without a passing run |
| The suite mapping metadata | It re-derives which suites were deleted from the repository and which are disabled in source. With no `tiaTestClassesDirs` configured the empty run's observed set is empty, which reads as "every tracked suite has been deleted" and would delete the project's whole mapping; with one configured, every selected-but-unexecuted suite reads as developer-disabled and would be flagged in a single build |

The history row is the exception, because it claims nothing about the code: a `num_suites_ran = 0` row is how the empty run stays visible rather than leaving an unexplained gap. It is credited **no savings** - the build finished early because it ran nothing, not because Tia deselected anything - and records `updated_db_mapping` as **false**, which is the truth for a run that sealed nothing whatever it was configured to do. A WARN naming what Tia expected to run is logged as well, since nothing else in the build output makes an empty run look wrong.

**"Expected" is what makes the guard safe.** A run where Tia ignored every suite because nothing was impacted also executes nothing, and that is Tia working exactly as intended - its savings are the largest Tia ever reports, and they must keep being recorded. `TestRunResult.ranNoExpectedSuites()` separates the two from the selector's own decision:

| Ignored | Selected | Ran | Reading |
| --- | --- | --- | --- |
| 0 | (any) | 0 | Every suite was expected to run and none did - **empty run**, nothing persisted but the row |
| > 0 | non-empty | 0 | The selected suites were expected to run and none did - **empty run**, nothing persisted but the row |
| > 0 | empty | 0 | Nothing was impacted, so there was nothing to run - normal: stats, savings and seal all recorded |
| (any) | (any) | > 0 | A run that executed suites - normal |

The executed-suite count is the per-attempt figure (`suitesRanThisAttempt`), so the question is asked of the attempt being persisted. A Surefire retry runs the suites holding the failed tests, so it does not read as an empty run; a retry contributes no run stats regardless.

**What the guard cannot tell apart, and what that costs.** Three different things produce a run that executed none of its selection: a broken test framework, a build-tool filter that excluded the whole selection (`-Dtest=`, `--tests`), and a selection whose every suite is disabled in source. Tia cannot distinguish them from inside the persist, so the WARN names all three rather than asserting the first, and all three are treated as empty runs. The consequence to know about is the third: because the empty run also skips the `developerDisabled` flag maintenance, a suite that is impacted and disabled in source is never flagged, so it keeps being selected and every such run keeps declining to seal - Tia re-diffs from the same older commit until something runnable is selected and actually runs. The alternative (letting an empty run maintain the flag) was rejected as worse: on a broken-framework build it flags *every* selected suite as developer-disabled in one go, and developer-disabled suites are excluded from the ignored-suite count, which can flip a later partial run into looking like an all-tests run and overwrite the baseline - the very damage the guard exists to prevent.

**A distributed build asks the same question at seal time.** No runner can answer it - "expected" is a property of the plan, and a runner only sees its own group - so `DistributedRunSealer` derives it from the two halves it already has: `DistributedRunTotals.getSuitesRan() == 0` (a counter that accumulates across retries, so reading zero from it is safe in a way reading non-zero would not be) and an expectation taken from the plan, which is the run row's seed-run flag or any group having been assigned a suite. An empty build seals nothing, records no stats and is credited no savings, exactly as the single-host empty run; the run is still retired and still writes its one row, because a build that left its barrier state behind would block the next one.

Note which distributed shape can reach the sealer at all: the completion guard reads each group's **observed** suites, not its executed ones, so a runner that saw every assigned suite get skipped completes its group and the build seals normally with `suites_ran = 0` - that is the shape the build-level guard catches. A runner that observed nothing at all never closes its group, so the barrier simply holds and the next build's plan write clears the open run.

**Which is why each runner is gated too, not only the seal.** A runner writes its own suites' mapping rows before any barrier, so the build-level guard is too late for them and, in the shape that matters most, never runs at all. `persistDistributedRunnerData` therefore applies the same `ranNoExpectedSuites()` test to the runner's own share: a runner that executed none of its assigned suites writes no mapping rows, no failed-set update and no staged method trackers. It **does** still report its group's progress - those counters and its duration are facts about the runner whatever it ran, and reporting them is what lets the barrier release so the build can reach the sealer at all. The two writes this gate prevents are the ones with no other line of defence: with no `tiaTestClassesDirs` configured the runner's observed set is empty, so `removeDeletedTestSuites` would delete the project's whole mapping; with one configured, every suite the runner was assigned would be flagged developer-disabled off a single build.

### Why timestamps are stored as UTC epoch ms

Tia runs on developer laptops, CI runners, and shared workspaces in potentially different timezones. Storing a timezone-agnostic numeric value avoids any "what does this string mean in this DB" ambiguity. The HTML History page renders each row's timestamp in the viewer's **local** timezone via a small inline script that calls `new Date(ms).toLocaleString(...)` — no millisecond precision and no timezone marker in the displayed text.

### The HTML report "History" tab

`HtmlHistoryReport` reads `tiaData.getTestRunHistory()` and renders `history/tia-history.html`, linked from the top navigation as "History". The table uses `simple-datatables` for sort / filter / paginate, defaulting to date descending. Long values (entry id, commit hash) are truncated to 8 characters in the cell; the full value is on a hover `title` so it stays accessible without widening the column.

`Source` and `Host` render there too, on the same "only when some row has one" rule the console table uses, and dashed rather than blank on a row that has none — an empty cell reads as a rendering slip, and a dash also sorts the unknown rows together.

A subtlety worth knowing: the local-time-rendering script must run **before** the `simple-datatables` init, not after. `simple-datatables` captures cell text into its internal model at init time; if the localization runs later via `DOMContentLoaded`, the `<time>` elements have already been replaced by `simple-datatables`' render output and the swap finds nothing.

### Config gate

The log is gated by `tiaUpdateDBTestRunHistory` (default **true**). Unlike `tiaUpdateDBMapping` — which defaults to `false` because it (and the run stats it carries) is a CI-only write — the history log is cheap (one INSERT per run, no mapping mutation) and is only useful when continuously populated, so on-by-default is the sane choice.

The flag participates in the listener's enablement predicate (`enabled && (updateDBMapping || updateDBTestRunHistory)`). That means a project with Tia enabled but no DB mapping / stats writes still benefits from the history log — handy for local-only setups that just want a record of what they ran.

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

Likewise, `Source` appears after `Savings %` on every history, since every run resolves one, while `Host` appears only when some run in view names a machine - a history made up entirely of distributed builds would otherwise carry a column of dashes. The host is deliberately **not** truncated the way commit and id are: it is read to tell machines apart, and a fixed-width prefix of several agents in one naming scheme would collapse them into one. A distributed build dashes the host - no single machine ran it.

Both optional groups are assembled by filtering one list of column descriptors (header, alignment, cell accessor) rather than by selecting between hardcoded parallel arrays. With two independent toggles there are four layouts; held as three parallel arrays each, a header, an alignment flag and a cell that drifted out of step would produce a table that is quietly *wrong* rather than one that fails.

Column widths are computed dynamically from the data so the table stays compact regardless of branch-name length. Numeric columns right-align; commit and id are truncated to the first 8 characters (matching the HTML report's compact rendering). Date/time is rendered in the JVM's local timezone using `yyyy-MM-dd HH:mm:ss`. The mapping flag renders as `yes` / `no` — the compact table form, not the HTML's "updated / not updated" wording. The `Savings` / `Savings %` columns show the time that run saved versus running the full suite, frozen at run time against the all-tests baseline then current; an all-tests run (and any run recorded before a baseline existed) shows `-`. When the history table is empty, the task prints `No Tia test run history recorded yet.` and exits cleanly.


---

Prev: [Profiling select-tests against a synthetic large DB](profiling-select-tests.md) | [Back to the Wiki index](../WIKI.md) | Next: [The select-tests run-time estimate and its overhead model](select-tests-run-time-estimate.md)
