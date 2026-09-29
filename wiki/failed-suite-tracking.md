# Failed-suite tracking

Tia force-selects every suite that failed on its last execution, whatever the diff says, so a
failing suite keeps running until it passes ("Running previously failed tests" in the selection
log). The set of such suites lives in `tia_test_suites_failed`. This chapter covers what counts as a
failure, how a test JVM decides a suite's failed state across retries, and how that state is written
so that concurrent distributed runners cannot discard each other's entries.

**Scope: one test JVM per build, or per distributed runner.** Tia runs a build's suites one after
another in a single JVM so JaCoCo can attribute coverage per suite. Multi-fork configurations
(Maven `forkCount > 1` / `reuseForks=false`, Gradle `maxParallelForks > 1` / `forkEvery > 0`) are
unsupported - see "Multi-fork persist" in [Persist flow and crash safety](persist-flow-and-crash-safety.md).
Nothing here makes them work. The one supported form of concurrency is distributed runners: separate
JVMs on separate hosts, each running its own disjoint group sequentially, all writing to one database.

### What counts as a failure

A suite is failed for a run if and only if **a test or container within it finished `FAILED`**.
An assumption that was not met is not a failure: the build tool reports that test as skipped, and
recording its suite as failed would force-run a passing suite on every build and count a failed
run in its stats.

- **JUnit 5** (`TiaTestExecutionListener.executionFinished`). `ABORTED` is not a failure. `FAILED`
  is, whether it lands on a test, on the class container (a failing `@BeforeAll` / `@AfterAll`, which
  reports no child tests), or on a method-level container (a parameterized method whose argument
  source throws, or a `@TestFactory` that throws). The owning suite comes from the node's own
  source when it names a class (`ClassSource`, or `MethodSource`'s class). Otherwise - a dynamic
  test whose source is absent or a URI - the listener walks up the `TestPlan` to the nearest
  ancestor that names a class. For `@Nested`, that is the nested class, the same name its own
  container is tracked under. A failure with no class on the path to the root (the engine
  container) is ignored.
- **JUnit 4** (`TiaJunit4Listener`). `testFailure` records a failure, including one reported against
  the class itself (`@BeforeClass`), which resolves to the same suite. `testAssumptionFailure`
  records nothing.
- **Spock** (`TiaSpockRunListener.error`). Spock does not notify its listeners of an opentest4j
  `TestAbortedException` or `TestSkippedException` at all (`MasterRunSupervisor.error`), so every
  `error` call is a real failure. The spec is resolved as `error.getMethod().getParent().getBottomSpec()`,
  never through the method's feature. Only feature methods have a feature - `setup`, `cleanup`,
  `setupSpec`, `cleanupSpec`, field initializers and `where:` data providers do not - and a
  method's parent is the spec that *declares* it, so for anything inherited the bottom spec is the
  one actually running and the one `beforeSpec` tracked.

### Latest outcome wins, across retries

For one test JVM, a suite's failed state is the outcome of its **most recent execution** in that
JVM:

- executed, and its latest execution failed - failed;
- executed, and its latest execution passed (including a flaky pass on a retry) - not failed;
- not executed in this JVM - its stored state is left untouched.

How each listener gets there:

- **JUnit 5.** The failed set lives in `SharedTestRunData`, shared across every test plan in the
  JVM. A suite is removed from it when its class container starts and added back if it fails. Up
  to Surefire 3.5.3, each retry opens a new launcher session and so gets a new listener instance;
  the shared set carries the state across them. A later Surefire that retries on the same launcher
  session, and so reuses one listener, behaves identically, because the per-attempt sets are
  cleared in `testPlanExecutionStarted` rather than relying on a fresh instance. A retry of only a
  suite's failing methods still starts its class container, so the suite is judged on the retry.
- **JUnit 4.** Surefire reuses one listener instance for retries, so its failed set already spans
  attempts. `testSuiteStarted` removes a suite being run again, and `testFailure` adds it back.
- **Spock.** `finishAllTests` persists once per JVM, so there is only one attempt to track.

### Writing the failed set

The listener hands the service the JVM-wide failed set and the tracker map, whose keys are exactly
the suites that executed in this JVM - a tracker is created only when a suite starts, never for a
skipped one. `TestRunnerService.updateTestSuitesFailed` then calls
`DataStore.persistTestSuitesFailed(suitesToClear, suitesFailed)`:

- **`suitesToClear`** is the suites this JVM executed, plus any selected suite now flagged
  developer-disabled (selected, discovered, did not execute - see `updateDeveloperDisabledFlags`).
  Tia can show that such a suite will not run, so keeping it force-selected would gain nothing; if
  it is re-enabled it executes and is judged again.
- **`suitesFailed`** is the JVM's latest-outcome failures.

A selected suite that did not execute and is not flagged is **not** cleared: it told this run
nothing about whether it still fails. The write clears only suites that executed, never the whole
selection. The old behaviour - remove the whole selection, add back this attempt's failures -
dropped a suite that failed in one Surefire attempt but was not retried, and emptied the set on
every build a suite was selected but filtered out.

**The write is incremental, which is what makes it safe for concurrent runners.** On JDBC it is one
transaction: a chunked `DELETE ... WHERE test_suite_name IN (...)` for `suitesToClear`, then an
insert of `suitesFailed` through `SqlDialect.upsert` keyed on the name. No part of it reads the
stored set first. The previous write read the whole set, edited it in memory and then cleared the
table and re-inserted everything, so two runners persisting at about the same time - common, since
groups are sized to finish together - could each read the set before the other wrote, and the
second write discarded the first runner's failures. Now each runner touches only its own group's
suites, and groups are disjoint.

The insert has to be insert-if-absent, not a plain `INSERT`, because `test_suite_name` is the
primary key: a name another writer had just stored would otherwise fail the persist. On H2 the
upsert is `MERGE ... KEY(...)`. On Postgres, `upsert` of a row whose only column is the key emits
`ON CONFLICT (...) DO NOTHING`, since there is nothing to update and an empty `DO UPDATE SET` list
is a syntax error. `SerializedDataStore` applies the same remove-then-add to its in-memory set, for
parity; it is single-process, so there is no race to close.

**A deleted suite takes its failed row with it.** A suite only ever leaves the failed set by
executing, which a deleted suite never does. `deleteTestSuites` therefore deletes the suite's
`tia_test_suites_failed` row in the same transaction as its `tia_test_suite` row, so a crash
cannot leave the failed row behind without the suite row that the next run's deletion check would
find and retry.

### Two failed counts, for two readers

`TestRunResult` carries both:

- **`getTestSuitesFailed()`** - the JVM-wide latest-outcome set. It feeds the failed-set write and
  a distributed runner's group progress (`suites_failed`, written as current state with `= ?`; see
  "Suite retries" in [Distributed test runs](distributed-test-runs.md)). After a retry that ran
  only suite B, which passed, while suite A failed earlier and was not retried, the group still
  reports 1 failed.
- **`getSuitesFailedThisAttempt()`** - the attempt's own count. On a single host each attempt
  writes its own history row, whose ran count is also per-attempt (`suitesRanThisAttempt`), so the
  failed count is per-attempt too. The same retry row reads "ran 1, failed 0", not "ran 1, failed
  1" with a failure in a suite that attempt never ran.

### Gradle test-retry

The `org.gradle.test-retry` plugin re-runs failed tests in rounds, and each round is a **fresh test
JVM** with the same Tia agent arguments (each round is a separate `delegate.execute`, which starts
and stops its own worker processes). Tia therefore persists once per round, with no knowledge of
earlier rounds. A round only happens when every failure of the previous round is retryable, so
each round re-runs every earlier failure.

The failed set comes out right: each round clears and re-adds only the suites it executed, and the
rounds run one after another, so the latest outcome wins across rounds. A distributed group's
`suites_failed` is right too, because the last round re-ran every suite still failing.

The rest of the persist is not safe with `updateDBMapping` under test-retry, and it is not
supported for mapping builds:

- **Mapping under-selection.** A round captures coverage for only the retried features, and the
  suite write replaces a suite's edges with this run's capture. A retried spec's mapping shrinks to
  those features, so later builds can skip it when code only its other features cover changes.
  Maven retries avoid this because the JVM-shared tracker merges coverage across attempts.
- **Baseline pollution.** On a run that ignored nothing, `ignoredTestSuiteCount == 0` again in the
  retry round, so the round counts as an all-tests run and its short duration folds into
  `all_tests_run_time`.
- **Developer-disabled misflagging.** A round gets the full selection but executes only the retried
  specs, so every selected spec that passed in the first round and was not retried reads as
  "selected, discovered, did not execute" and is flagged developer-disabled until it next executes.

---

Prev: [Persist flow and crash safety](persist-flow-and-crash-safety.md) | [Back to the Wiki index](../WIKI.md) | Next: [Distributed test runs (group assignment and the run lifecycle)](distributed-test-runs.md)
