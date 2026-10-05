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
- **Spock.** `finishAllTests` persists once per JVM, so there is only one attempt per JVM to
  track. A Gradle test-retry round is a fresh JVM that persists after the previous one, so the
  latest outcome still wins across rounds - see "Retries" below.

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

### Retries: Surefire reruns and Gradle test-retry rounds

A build tool can retry failed tests after the test task's first run, and each retry persists on its
own. `TestRunResult` carries which attempt a persist describes, as a `RunAttempt`:

- `FIRST` - the test task's real run.
- `RERUN_SAME_JVM` - a Surefire rerun (`rerunFailingTestsCount`). It runs in the first attempt's
  JVM, so the listener's trackers already carry every attempt's coverage and suites. JUnit 5 numbers
  test plans in `SharedTestRunData`.
- `RERUN_NEW_JVM` - a Gradle `org.gradle.test-retry` round. Each round is a **fresh test JVM**
  (a separate `delegate.execute`, which starts and stops its own worker processes) with the same
  system properties, knowing only what it ran itself. A round only happens when every failure of
  the previous round is retryable, so each round re-runs every earlier failure.

**Detecting a Gradle round.** Nothing a round's JVM is handed says which round it is, so the Tia
Gradle plugin numbers the JVMs itself (`TestJvmSequence`): the test task's `doFirst`, which runs
once per task execution before any round, resets a counter file in the task's temporary directory
and forwards its path as `tiaTestJvmSequenceFile`; the Spock extension increments it once per JVM,
under a file lock, and caches the answer. 1 is the real run, 2 or more a round. It relies on one
test JVM per round - the single-fork requirement - and on nothing internal to Gradle or the
test-retry plugin. With no counter (Maven, or a build without the plugin) a JVM is `FIRST`.
Rejected: telling a round by its narrowed test plan, which a tag or `groups` filter produces too,
and inspecting Gradle's filter object in the worker, which is a Gradle internal.

**What a retry does differently:**

| | `FIRST` | `RERUN_SAME_JVM` | `RERUN_NEW_JVM` |
|---|---|---|---|
| History row `rerun` | false | true | true |
| Savings (serial and wall clock) | as computed | 0 | 0 |
| Tia-level stats | contributed | none | none |
| Per-suite stats | counted | the rerun is counted: a flaky suite shows a fail then a pass | the same, with the suite's stored average run time, so the weighted mean does not move |
| Coverage edges | replaced by this run's capture | replaced, but the capture is already the union of every attempt | the stored edges of the retried suites are read (`DataStore.readTestSuiteCoverage`) and unioned with the round's capture first |
| Developer-disabled flag | maintained | maintained | left as stored |
| Failed set | as above | as above | as above |

Why each difference matters on a Gradle round, which knows nothing of the first attempt:

- **Coverage.** A round captures coverage for only the retried features, and the suite write
  replaces a suite's edges with the run's capture. Without the union a retried spec's mapping
  would shrink to those features, and later builds could skip it when code only its other features
  reach changes. The unsealed flag would not catch it, because the round seals too.
- **Run times.** A round times only the retried features. Its run is counted, with its own
  outcome, as a Surefire rerun's is, but its partial duration would pull the spec's average - and
  with it the run-time estimate and distributed group packing - down, so it carries the stored
  average instead. The seal takes no Tia-level stats from any retry: the first attempt already
  counted the run.
- **Developer-disabled flags - distributed builds.** A distributed runner re-derives its whole
  group in every round but runs only the retried specs, so every other spec of the group would read
  as "selected, discovered, did not execute" and be flagged. A single-host round does not have this
  problem: its selection runs in the test JVM, against the state the first round just sealed, so it
  selects only the previous failures.
- **Savings.** A single-host round selects only the previous failures and runs for seconds, so it
  would otherwise be credited nearly the whole baseline as time saved. A Surefire rerun has the same
  shape. Every rerun row is credited zero instead, and left out of the summary averages; see
  [Test-run history log](test-run-history.md).

The failed set needs nothing special: each round clears and re-adds only the suites it executed,
and the rounds run one after another, so the latest outcome wins across rounds. A distributed
group's `suites_failed` is right too, because the last round re-ran every suite still failing.

**The plugin used to fail at configuration with test-retry applied.** Gradle refuses
`Project#afterEvaluate` inside a `configureEach` action ("cannot be executed in the current
context"), which is where the Tia plugin wired its `tia-dist-complete` finalizer - and applying
`org.gradle.test-retry`, in either order, put Tia's action in that context. The hook is now
registered once from the plugin's `apply` (`TiaSpockGitGradlePluginTestExtension.wireDistCompleteFinalizers`)
and wires every Tia-applied test task after evaluation.

---

Prev: [Persist flow and crash safety](persist-flow-and-crash-safety.md) | [Back to the Wiki index](../WIKI.md) | Next: [Distributed test runs (group assignment and the run lifecycle)](distributed-test-runs.md)
