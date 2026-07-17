# Profiling `select-tests` against a synthetic large DB

### When you need this

`tia-select-tests` (Maven goal / Gradle task) reads the entire Tia H2 database into memory before deciding which tests to run. For a small project the read takes milliseconds; for a project with thousands of test suites and millions of `(test_suite, source_class, method)` coverage edges, the read dominates the task's wall time. Diagnosing those slow paths against a real customer database is awkward — the data is private, regenerating it after every code change is expensive, and reproducing race conditions or sort-spill behaviour requires controlled inputs.

The two test-scope tools under `tia-core/src/test/java/org/tiatesting/core/perf/` solve this by giving you a deterministic, large-but-fake DB and a focused harness that times each phase of the read path. They live in `src/test` so they don't ship with the published jar; they're driven by Gradle tasks under `:tia-core` so you don't need to set up a classpath manually.

### Step 1 — generate a synthetic DB

```bash
./gradlew :tia-core:generateLargeTiaDb \
    -PoutDb=/tmp/tia-perf \
    -PtestSuites=1000 \
    -PsourceMethods=50000 \
    -PavgClassesPerSuite=936 \
    -PavgMethodsPerClass=6 \
    -Pseed=42 \
    -Pbranch=main
```

What the parameters mean:

- `outDb` — directory the H2 file is written into. The actual file lands at `<outDb>/tiadb-<branch>.mv.db`, matching the layout `H2DataStore.buildJdbcUrl` expects, so a Tia plugin pointed at this directory will load it directly.
- `branch` — the branch name embedded in the DB filename. Defaults to `main`. Use this if you want multiple synthetic DBs side by side.
- `testSuites` — rows in `tia_test_suite`.
- `sourceMethods` — rows in `tia_source_method`.
- `avgClassesPerSuite` — average rows in `tia_source_class` per test suite. With ±50% jitter so the profile sees a mix of light and heavy suites.
- `avgMethodsPerClass` — average method-edges per class row in `tia_source_class_method`.
- `seed` — RNG seed; a fixed seed gives a byte-for-byte identical DB for repeatable measurements.

The synthetic identifiers (test suite names, source filenames, method signatures) are deep-package-style so the on-disk size also matches a real codebase — at the defaults above, the resulting `.mv.db` is around 480 MB, similar to the user's reference project at 1k suites / 50k methods / 5.6M edges. To match a different real DB exactly, query its row counts:

```sql
SELECT COUNT(*) FROM tia_test_suite;
SELECT COUNT(*) FROM tia_source_method;
SELECT COUNT(*) FROM tia_source_class;
SELECT COUNT(*) FROM tia_source_class_method;
```

…and pass `-PavgClassesPerSuite=<class_count / suite_count>` and `-PavgMethodsPerClass=<edge_count / class_count>`.

The generator uses raw JDBC + batched `PreparedStatement` inserts (10K rows per commit, autocommit off). At the reference scale it finishes in around 13 seconds. Schema creation goes through `H2DataStore.getTiaData(true)` so the layout always matches what Tia produces in normal operation, including the `tia_source_class.tia_test_suite_id` index that the bulk-load query depends on.

### Step 2 — time the select-tests read path

```bash
./gradlew :tia-core:profileSelectTests \
    -PoutDb=/tmp/tia-perf \
    -Pbranch=main \
    -Piterations=3
```

The harness opens the DB you just generated and runs each iteration through three timed phases:

1. **`H2DataStore` construction** — should always be near-zero. If this is non-trivial something is wrong with the connection setup.
2. **`getTiaData(true)` full load** — the path being investigated. This is what `select-tests` calls into, and what the bulk-join + index work in `select-tests-perf-fix` targets.
3. **`selectTestsToIgnore` with empty diffs** — exercises the rest of the selector logic on top of the just-loaded data, using a stub `VCSReader` that reports no source-file changes. With an empty diff the selector should be near-instant; if it isn't, the cost is somewhere in the post-load logic. Note that `TestSelector.selectTestsToIgnore` re-loads the DB internally (see `TestSelector.java`), so this phase's wall time is roughly the sum of "another full load" plus the actual selection logic.

Each phase prints its own elapsed-ms line plus an iteration TOTAL. Three iterations is enough to distinguish steady-state from first-run JIT-warmup costs while keeping a single run under five minutes at the reference DB size.

### Step 3 — attach a profiler

The Gradle task accepts a `-PjvmArgs="…"` property that's passed verbatim to the forked JVM. Two profilers worth pairing this with:

**async-profiler (preferred, produces flame graphs):**

```bash
./gradlew :tia-core:profileSelectTests \
    -PoutDb=/tmp/tia-perf -Piterations=1 \
    -PjvmArgs="-Xmx2g -agentpath:/opt/homebrew/Cellar/async-profiler/4.4/lib/libasyncProfiler.dylib=start,event=cpu,file=/tmp/tia-cpu.html"
```

The agent dumps `/tmp/tia-cpu.html` on JVM exit. Open it in a browser. Use one iteration for a clean profile (the second iteration's "redundant load inside selectTestsToIgnore" creates a duplicate flame tower that's noisy).

**JFR (no install required):**

```bash
./gradlew :tia-core:profileSelectTests \
    -PoutDb=/tmp/tia-perf -Piterations=1 \
    -PjvmArgs="-Xmx2g -XX:StartFlightRecording=duration=180s,filename=/tmp/tia.jfr,settings=profile"
```

Open `/tmp/tia.jfr` in JDK Mission Control.

**Heap sizing matters.** `-Xmx2g` is important: at the Gradle daemon's default 512 MB the JVM enters GC-thrash territory on a multi-million-edge DB and the flame graph fills with `PSPromotionManager::copy_to_survivor_space` and friends, hiding the real Tia and H2 hotspots. Bump the heap above the working-set size before drawing conclusions about hotspots.

### Reading the flame graph

The dominant wedges to look for, in priority order:

- **`H2DataStore.getTestSuitesData` / `lambda$getTestSuitesData$0`** — the bulk-load reducer. If a large fraction of CPU lives here, the read path itself is the bottleneck.
- **`H2DataStore.getSourceClasses`** (historical, no longer present in current code) — used to be 87% of CPU when the loader did N+1 per-suite queries via parallelStream. If it reappears in a future profile, the bulk-join rewrite has regressed.
- **`MVSortedTempResult.next` / `Page$NonLeaf.writeUnsavedRecursive`** — H2 spilling a sorted result set to a temp file. Indicates an `ORDER BY` whose sort can't be folded into an index. Fix: drop the `ORDER BY` and let the reducer cope with arbitrary row order.
- **`MVSecondaryIndex.find`** at high percentage with low `SingleFileStore.readFully` underneath — H2 doing index probes that aren't paying off (e.g. nested-loop join on a column that has no index). Fix: add the index.
- **`pread` / `MVStore.readPage` / `Cursor.hasNext`** as the bottom wedges — genuine "reading the data from disk" cost. After algorithmic fixes these should be the floor, not because Tia is doing anything wrong but because the DB is genuinely large.
- **`PSPromotionManager::copy_to_survivor_space` etc. on background GC threads** — young-gen GC overhead. If the heap is sized right (`-Xmx2g+`) and this still dominates, look for unnecessary allocation in the read path: `Integer` boxing, intermediate Strings, oversized HashSets.

The flame graph files are self-contained HTML; share them as artifacts on issues / PRs to make perf claims reproducible.

### Reproducibility notes

- The generator's RNG is seeded, so two runs with the same `-Pseed=` produce identical DBs.
- The generator regenerates from scratch — it truncates `tia_*` tables before populating. To start completely clean you can also `rm -rf <outDb>` first.
- The H2 `tiadb-<branch>.mv.db` file is portable; copying it between machines reproduces the same profile shape modulo CPU/disk differences.
- `ProfileSelectTests` doesn't write to the DB — repeat runs against the same fixture are safe.


---

Prev: [Why Tia requires Maven 3.8.1+](maven-version-requirement.md) | [Back to the Wiki index](../WIKI.md) | Next: [Test-run history log](test-run-history.md)
