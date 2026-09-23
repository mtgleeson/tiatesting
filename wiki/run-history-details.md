# Run history details

Every row in `tia_test_run_history` (see the [test-run history log](test-run-history.md) chapter)
records that a run happened and what it cost and saved. It does not record *why* the run selected
the tests it did. This chapter covers the breakdown that answers that question: what is captured,
how it is stored, how it survives a fork boundary or a distributed build, and the two places a
developer can see it.

### What is captured

`TestRunSelectionDetails` is the per-run breakdown computed at selection time. It carries two
named trigger lists plus five scalar counters - deliberately **counts only**, never per-trigger
test-name lists, so the breakdown stays small and safe to keep indefinitely:

- **`SOURCE_METHOD` triggers** - one entry per changed source method whose mapping pulled test
  suites into the run, each with the method's name and the number of suites it accounted for.
- **`STATIC_RULE` triggers** - one entry per fired [static selection rule](static-test-selection.md),
  each with the rule's name and the number of suites it forced.
- Five scalar counters, recorded as totals only because they do not name a single trigger the way
  a method or a rule does: `numModifiedTestFiles`, `numNewTestFiles`, `numPreviouslyFailed`,
  `numUnsealedMapping`, `numPendingLibrary`.

A trigger's count is a per-trigger contribution, not a partition of the run's suites: a suite
pulled in by two changed methods is counted under both, so the counts across triggers can sum to
more than the run's distinct selected-suite count. `TestRunTrigger.Type` is the two-value enum
(`SOURCE_METHOD`, `STATIC_RULE`) both trigger lists share.

### Storage: columns, a child table, and null-vs-zero

The five counters live as nullable `INT` columns directly on `tia_test_run_history`
(`num_modified_test_files`, `num_new_test_files`, `num_previously_failed`, `num_unsealed_mapping`,
`num_pending_library`) - see the [database schema](database-schema.md) chapter for the full table
definitions. Being scalar and one-per-run, they cost nothing extra to read on the hot history path.

The triggers do not: they are unbounded per run, so they live in a separate child table,
`tia_test_run_history_trigger` (`history_id` FK to `tia_test_run_history.id`, `ON DELETE CASCADE`;
`trigger_type`, `trigger_name`, `test_count`). **This table is loaded only on demand** - by the
per-run detail page and by the history-details CLI command below - and is never touched by the hot
`readTestRunHistory()` path that the summary History table and the console `history` command use.
That split exists because of the perf note in `CLAUDE.md`: read paths in `select-tests` and
anything `TestRunHistory` renders in bulk are sensitive to added work, and a per-run trigger join
is exactly the kind of cost that must not ride along on every history read - it is paid only by the
caller who actually wants one run's breakdown.

**Null means "not recorded", not "zero".** The only case where a persisted row's five counters are
null is a row written before this feature existed. Every run recorded since - single-host or
distributed - writes the counts, zero included, because selection always produces a
`TestRunSelectionDetails` (`TestRunSelectionDetails.empty()` for an all-tests or seed run, a
populated one otherwise) and `empty()` is a non-null object whose counters are all `0`. Only a
genuinely-absent breakdown (`null`) leaves the columns null; a recorded run with nothing to
attribute stores `0`. Every reader - the console formatter, the HTML detail page - renders the null
case as a dash (`-`) rather than `0`, so "nothing triggered this" and "we don't know" stay visually
distinct.

### Transport: how the breakdown crosses the fork boundary

Test selection runs in the build-tool JVM; the history row is written from the forked test JVM.
The breakdown has to cross that boundary the same way everything else selection produces does - see
the [test-runner data exchange](test-runner-data-exchange.md) chapter for why Maven and Gradle
solve this differently in general.

- **Maven / JUnit** writes the breakdown to a sidecar file, `run-selection-details.txt`, via
  `RunSelectionDetailsCodec` - a small tab-separated format, one counters line and one line per
  trigger. `AbstractTiaAgentMojo` writes the file and passes its path as the agent's
  `selectionDetailsFile` option, which becomes the `tiaRunSelectionDetailsFile` system property in
  the fork; the test listener reads and parses it there. This is the same pattern the ignored/
  selected test-name files already use, for the same reason: the payload (an unbounded trigger
  list) does not fit comfortably as a `-D` argument.
- **Spock (Gradle)** never crosses a process boundary for this: selection and the persist that
  writes the history row share the one forked test JVM, so the breakdown is threaded straight
  through in-process, with no codec and no sidecar file.

### Distributed runs: staged at plan time, copied at seal time

A distributed build selects once, at plan time, in the planner's JVM - not per runner, and not in
any forked test JVM (see the [distributed test runs](distributed-test-runs.md) chapter for the
run lifecycle this fits into). The breakdown that selection produces is staged rather than carried
forward directly, because no single runner - and no single persist - is the one that will end up
writing the build's one history row; that is decided later, by whichever runner wins the seal.

Two run-id-keyed tables hold the staged breakdown: `tia_distributed_run_selection` (one row per
run, the same five counters as the history table) and `tia_distributed_run_trigger` (one row per
staged trigger, same shape as `tia_test_run_history_trigger` but keyed by `run_id` instead of
`history_id`, with no foreign key). Both are populated by
`DataStore.persistDistributedRunSelectionDetails` and are cleared and replaced whenever the branch
is (re)planned, the same lifecycle the other distributed-run tables already follow.

At seal time, `DistributedRunSealer` reads the staged breakdown back
(`DataStore#readDistributedRunSelectionDetails`) and folds its five counters and its triggers onto
the build's single history row and its trigger table, via the same `TestRunHistoryEntry` factory
and `persistTestRunTriggers` call a single-host run uses. A run with no staged breakdown - a seed
run, for example - reads back `TestRunSelectionDetails.empty()` rather than null, so the sealer
never has to special-case a missing row; the history row it writes carries zero counters and no
trigger rows in that case, exactly as a single-host all-tests run does, not null.

### Surfaces

**The HTML detail page.** Each row's Id cell in the HTML report's History table
(`history/tia-history.html`) links to a per-run detail page at `history/<id>.html`, generated by
`HtmlHistoryDetailReport`. The page shows the run's summary (timestamp, branch, commit, suite
counts, wall clock and serial duration, groups used and groups available, wall-clock and serial
savings - see "Wall-clock savings" in the [test-run history log](test-run-history.md) chapter),
the five counters - dashed where not recorded - and two tables ranking
the source-method and static-rule triggers by suite count descending. A run with nothing recorded
at all (every counter null and no triggers) shows a "No selection breakdown was recorded for this
run" note in place of the trigger tables.

**The CLI command.** `TestRunHistoryDetailConsoleFormatter` renders the same breakdown as plain
text - a summary block, a "Selection sources" block for the five counters, and "Source method
changes" / "Static rules" blocks for the ranked triggers - shared by both build tools:

- Gradle: `./gradlew tia-history-details --id=<historyId>`
- Maven: `mvn <plugin>:history-details -DtiaHistoryId=<historyId>`

Both look the requested id up in `readTestRunHistory()` first and load its triggers via
`readTestRunTriggers(historyId)` only on a match - the on-demand load the storage section above
describes - then print `TestRunHistoryDetailConsoleFormatter.format(...)`, or a not-found message
when the id does not match any row. The id is typically copied from the Id column the plain
`history` / `tia-history` command already prints (see the test-run history log chapter).

---

Prev: [History timeline chart](history-timeline-chart.md) | [Back to the Wiki index](../WIKI.md) | Next: [The select-tests run-time estimate and its overhead model](select-tests-run-time-estimate.md)
