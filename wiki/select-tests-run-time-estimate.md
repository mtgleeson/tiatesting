# The select-tests run-time estimate and its mapping overhead

The `select-tests` preview prints an `Estimated total run time` for the suites it would run. The base figure is the sum of each selected suite's stored `avgRunTime` (with a median substituted for suites Tia has never timed). That base is accurate for a normal selective run - but it systematically under-counts a build that updates the mapping, for a subtle reason.

### Why per-suite `avgRunTime` excludes coverage capture

The framework listeners measure a suite's `avgRunTime` as the wall clock between the suite-started and suite-finished callbacks, and that measurement is taken **before** `JacocoClient.collectCoverage()` runs for the suite (see `TiaTestExecutionListener.testSuiteFinished` and its JUnit4 / Spock equivalents - the runtime is frozen, then coverage is collected). So `avgRunTime` is pure test-execution time; the per-suite coverage capture (which can be seconds per suite on a large project) is never in it. The Tia-level `allTestsRunTime` is different - it's whole-run wall clock (`now - testRunStartTime` at the end of the plan), so it **does** include every coverage capture plus JVM/agent startup and the final persist. That asymmetry is why summing per-suite times can come out at half the real full-build wall clock.

### The overhead allowance

When the previewed run will collect coverage, the estimate adds an amortised overhead per selected suite:

```
overheadPerSuite = max(0, allTestsRunTime - Σ avgRunTime(all tracked suites)) / numTrackedSuites
estimate += overheadPerSuite × (suites selected)
```

The difference `allTestsRunTime - Σ avgRunTime` is exactly the whole-run cost that lives outside the per-suite windows (coverage capture + the fixed per-run costs), derived entirely from data already in memory at selection time - no new persistence. It is **exact for a "run all" estimate** (every suite selected); for a small partial mapping-update selection it slightly under-counts the fixed portion (JVM/persist) because that fixed cost is amortised per suite rather than added once. Coverage dominates, so this is accepted.

Two guards: with no baseline (`allTestsRunTime == 0`) or no tracked suites, no overhead is added; and if the baseline is below the per-suite sum - which happens when the build runs suites **in parallel** (wall clock less than the serial sum) - the overhead clamps to zero rather than going negative. The heuristic only models sequential builds.

### The overhead is data on the result; inclusion is a display-time decision

The overhead is **always computed** and carried on `TestSelectorResult.getMappingOverheadMs()`, separate from the base `getEstimatedRunTimeMs()`. Whether to add it is decided where the estimate is *shown*: `SelectTestsOutputFormatter.formatEstimateBlock(result, lineSep, includeMappingOverhead)`. The two preview tasks pass `includeMappingOverhead` from the configured `updateDBMapping` (Maven `isTiaUpdateDBMapping()`, Gradle `getUpdateDBMapping()`).

This keeps the inclusion decision off the `selectTestsToIgnore` write-flag, which it must not be gated on: the read-only `select-tests` preview passes `updateDBMapping=false` (no library/stamp writes) yet still estimates a run that may collect coverage. Gating the overhead on that write-flag was a real bug - it never showed in the preview, the one place the estimate is printed. Computing the overhead unconditionally and folding it in only at display time avoids that, and means the real-run selection path (Maven agent, Spock) carries no extra flag at all - it never displays the estimate, so the discarded overhead figure is simply ignored.


---

Prev: [Test-run history log](test-run-history.md) | [Back to the Wiki index](../WIKI.md) | Next: [Database schema (tables and relationships)](database-schema.md)
