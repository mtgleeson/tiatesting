# Static test selection — user-declared change-to-suite rules

Tia's bread-and-butter selection mechanism reads source code coverage off each test run and learns which suites exercise which source methods. The next run's selection criteria are derived from that learned mapping. There's an entire class of change drivers it can't see: SQL migration scripts, properties files, schema files, code-generator templates, anything that isn't compiled to bytecode the JaCoCo agent walks. Static test selection rules fill that gap with user-declared "if file X changes, run these suites" mappings.

### Why we call one mapping "dynamic" and the other "static"

The names are about *who owns the mapping and whether it changes from test runs*, not about regex vs. exact match or anything in the rule format.

- The **dynamic mapping** is the coverage-driven one. Tia builds it from JaCoCo data during runs that have `updateDBMapping = true`, stores it in the H2 DB, and consults it on every subsequent run. The mapping evolves on its own as runs land; the selection criteria for tomorrow's run depend on what got recorded today. The dynamic mapping is owned by Tia.

- The **static mapping** is the user-declared one. The rules live in your `tia { ... }` block (Gradle) or `<configuration>` (Maven). Tia compiles them at build start, applies them on each Tia-enabled run, and doesn't change them. The mapping is fixed in config and only moves when you edit the config. The static mapping is owned by you.

Both feed into the same `TestSelector.selectTestsToIgnore` call. From the test runner's perspective, a suite to run is a suite to run regardless of which mapping selected it; the suites picked by the static rules are additively unioned with those picked by the dynamic mapping.

### The two modes today

A rule's `mode` tells Tia what to do when the file-path regex matches a changed file:

- **`RUN_ALL`**: force-run every tracked test suite for this build. Useful when a change's blast radius is genuinely "everything" — for example a build script edit, a top-level dependency bump, or an infrastructure-as-code change you can't reason about narrowly.
- **`SUITE_NAMES`**: union in the set of suites whose simple class name *or* fully-qualified name matches any of the rule's `suiteNamePatterns` regexes. Useful when you know a specific subset of tests is sensitive to the change driver — for instance, only the `*MigrationIT` suites need to run when a SQL migration script changes.

A third mode, `ANNOTATIONS_TAGS`, is reserved in the enum but rejected at config parse time. The intent is to select suites by JUnit 5 `@Tag` / Spock annotation rather than by name regex; the implementation is deferred to a future stage because it needs a test-runner-side lookup the current code doesn't have.

### Why the rules are evaluated where they are

The two build tools take different paths from "user wrote a rule" to "the test selector applies it," and both follow directly from the underlying lifecycle differences explained in the "How Tia exchanges data with the test runner" chapter.

**Maven.** The rules are evaluated in the Maven JVM. `AgentMojo.execute()` and `SelectTestsMojo.execute()` both call `buildStaticTestSelectionConfig()`, then hand the resulting `StaticTestSelectionConfig` to `TestSelector` *in-process* — no Surefire fork required. The result of selection (the ignored / selected test lists) is then written to disk, where Surefire reads it when it launches the test JVM. The static rules never need to cross a process boundary because they're applied entirely on the Maven side.

**Gradle.** The same: the rules are evaluated in the Gradle daemon. The test task's action (`TiaTestTaskConfigurer`) builds the `StaticTestSelectionConfig` from the test task's merged `tia` extension with `TiaPlugin.buildStaticTestSelectionConfig(rawRules)` and hands it to `TestSelector` in the daemon, once per test task; the selection result reaches the test JVM through the same hand-off files Maven uses. Invalid regex / unknown mode / missing field therefore surfaces in the daemon before any test JVM starts, and the in-plugin `tia-select-tests` task uses the same builder, so both paths see identical validation.

(The Gradle selection used to run in the forked test JVM, which meant encoding the rules into a `tiaStaticTestSelectionRules` system property; that bridge is gone.)

### How the static and dynamic mappings compose

The composition is union, not intersection. `TestSelector` collects:

1. The suites selected by the dynamic mapping (because their coverage touched a changed source method).
2. The suites selected by every static rule whose `filePathPattern` matched at least one changed file.

The union becomes the "must run" set; everything else is the "ignore" set. There's no priority and no override — a suite picked by either mechanism runs; a suite picked by neither doesn't. This is deliberate: the two mappings cover different kinds of change driver, and combining them with anything other than union would mean either mechanism could silently veto the other.

`RUN_ALL` is the degenerate case at the static end: a `RUN_ALL` rule that fires effectively short-circuits the union to "every tracked suite," because adding "every tracked suite" to anything is "every tracked suite." That's the intended behaviour — `RUN_ALL` is for change drivers whose blast radius is "we don't know, run it all."

### When this design might shift

Two evolutions are already plausible:

- **`ANNOTATIONS_TAGS` mode.** The current `SUITE_NAMES` mode requires you to know the naming convention for the suites you want to force-run. Tag-based selection (JUnit 5 `@Tag`, Spock annotations) is more robust to renames. Adding it needs the test-runner-side listener to know each suite's tag set when it asks "does this rule apply to me," which is straightforward in JUnit 5 but needs design work for Spock.
- **Eager-only evaluation on the Maven side.** Today the same `StaticTestSelectionConfig` builder is called by both the agent mojo (runs every test build) and the preview select-tests mojo (runs on demand). If a future change makes the rule list dramatically more expensive to build, it'd be worth hoisting the build to once per project per Maven execution.

Both shifts are additive — no part of the current design needs to change to enable them.

---


---

Prev: [Pluggable datastore (H2, Postgres, and the seam for more)](pluggable-datastore.md) | [Back to the Wiki index](../WIKI.md) | Next: [Setting up a machine to run the release tasks (GPG signing)](release-signing-setup.md)
