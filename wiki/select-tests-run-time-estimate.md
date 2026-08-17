# The select-tests run-time estimate and its overhead model

The `select-tests` preview prints an `Estimated total run time` for the suites it would run. The base figure is the sum of each selected suite's stored `avgRunTime` (with a median substituted for suites Tia has never timed). That base is accurate for a normal selective run - but it systematically under-counts a build that updates the mapping, for a subtle reason.

### Why per-suite `avgRunTime` excludes coverage capture

The framework listeners measure a suite's `avgRunTime` as the wall clock between the suite-started and suite-finished callbacks, and that measurement is taken **before** `JacocoClient.collectCoverage()` runs for the suite (see `TiaTestExecutionListener.testSuiteFinished` and its JUnit4 / Spock equivalents - the runtime is frozen, then coverage is collected). So `avgRunTime` is pure test-execution time; the per-suite coverage capture (which can be seconds per suite on a large project) is never in it. The Tia-level `allTestsRunTime` is different - it's whole-run wall clock (`now - testRunStartTime` at the end of the plan), so it **does** include every coverage capture plus JVM/agent startup and the final persist. That asymmetry is why summing per-suite times can come out at half the real full-build wall clock.

### The overhead allowance

Overhead is modelled in two parts:

```
runTotal = Σ avgRunTime(selected suites)     per-suite test time
         + capture × (suites selected)       per-suite coverage collection
         + fixed                             per-JVM, paid once however few suites run
```

`capture` is JaCoCo's per-suite dump - genuinely proportional to the number of suites, wherever they run. `fixed` is what a test JVM costs to start: engine start-up, class loading, the final coverage dump. The distinction only matters once a build is split across runners, and then it matters a lot: `capture` is **divided** across groups, while `fixed` is **duplicated**, since every runner is its own JVM.

**The fall-back, when the split has never been measured.** One equation cannot separate two unknowns, so a project that has never run a distributed build has nothing to read and Tia uses the single-number model it always did - the whole overhead amortised per suite, with `fixed` left at zero:

```
capture = max(0, allTestsRunTime - Σ avgRunTime(all tracked suites)) / numTrackedSuites
fixed   = 0
```

The difference `allTestsRunTime - Σ avgRunTime` is exactly the whole-run cost that lives outside the per-suite windows, derived entirely from data already in memory at selection time - no new persistence. It is **exact for a "run all" estimate** (every suite selected); for a small partial mapping-update selection it slightly under-counts the fixed portion because that cost is amortised per suite rather than added once. On a single host, coverage dominates and this is accepted.

Two guards on the fall-back: with no baseline (`allTestsRunTime == 0`) or no tracked suites, no overhead is added; and if the baseline is below the per-suite sum - which happens when the build runs suites **in parallel** (wall clock less than the serial sum) - the overhead clamps to zero rather than going negative. The heuristic only models sequential builds.

**Where the measured split comes from.** A distributed build supplies the second equation the decomposition needs, because its groups run at a different suite count from the whole run:

```
wholeRunOverhead  = fixed + capture × trackedSuites      (from the all-tests baseline)
meanGroupOverhead = fixed + capture × meanGroupSuites    (from the build's group rows)
```

Every runner already reports `suites_duration_ms` alongside `actual_duration_ms`, so the per-group overhead is measured rather than guessed. `DistributedRunOverheadModel` solves the pair at seal time and folds the answer into rolling averages on `tia_core`; `TestSelector.overheadModel` reads them back and they win over the fall-back. See ["The two-part overhead model"](distributed-test-runs.md#the-two-part-overhead-model).

The mean per-group overhead is used here, where `DistributedRunTotals` takes the minimum from the same measurement. That is deliberate and the two must not be reconciled: the totals are subtracting from a duration the build actually took, so they need a floor no runner can fall below, while this is a forecast and a forecast wants the expected value.

### The overhead is data on the result; inclusion is a display-time decision

Both figures are **always computed** and carried on `TestSelectorResult.getCaptureOverheadMs()` and `getFixedOverheadMs()`, separate from the base `getEstimatedRunTimeMs()`. Whether to add them is decided where the estimate is *shown*: `SelectTestsOutputFormatter.formatEstimateBlock(result, lineSep, includeMappingOverhead, distributedWallClockMs)`. The two preview tasks pass `includeMappingOverhead` from the configured `updateDBMapping` (Maven `isTiaUpdateDBMapping()`, Gradle `getUpdateDBMapping()`). Both terms are gated on the same flag, because the measured `fixed` includes the final coverage dump that a run collecting no coverage never performs.

`fixed` is added **once** to the printed total, not once per suite: that total is the serial-equivalent time, and one host starts one test JVM however the work is later split. An empty selection reports `fixed` as zero - a build that runs no suites starts no test JVM, and charging it would put a non-zero estimate on a nothing-impacted build.

This keeps the inclusion decision off the `selectTestsToIgnore` write-flag, which it must not be gated on: the read-only `select-tests` preview passes `updateDBMapping=false` (no library/stamp writes) yet still estimates a run that may collect coverage. Gating the overhead on that write-flag was a real bug - it never showed in the preview, the one place the estimate is printed. Computing the overhead unconditionally and folding it in only at display time avoids that, and means the real-run selection path (Maven agent, Spock) carries no extra flag at all - it never displays the estimate, so the discarded overhead figure is simply ignored.


---

Prev: [Test-run history log](test-run-history.md) | [Back to the Wiki index](../WIKI.md) | Next: [Database schema (tables and relationships)](database-schema.md)
