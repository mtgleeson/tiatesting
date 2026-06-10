# Tia Wiki

This document is a deeper companion to `README.md`. The README tells users *how* to install, configure, and run Tia; this wiki explains *why* Tia is designed the way it is — the underlying model, the constraints that shape it, and the reasoning behind specific design decisions.

Chapters will be added retrospectively as design choices come up. Each chapter is self-contained: it restates the problem, describes the model, and walks through concrete examples so a reader can pick up the topic without reading the rest of the wiki.

## Table of contents

- [Library versioning and the stamp/drain model](#chapter-library-versioning-and-the-stampdrain-model)
- [How Tia exchanges data with the test runner (Gradle vs Maven)](#chapter-how-tia-exchanges-data-with-the-test-runner-gradle-vs-maven)
- [Logging conventions (TRACE vs DEBUG)](#chapter-logging-conventions-trace-vs-debug)
- [Why Tia requires Maven 3.8.1+](#chapter-why-tia-requires-maven-381)
- [Profiling `select-tests` against a synthetic large DB](#chapter-profiling-select-tests-against-a-synthetic-large-db)
- [Test-run history log (`tia_test_run_history`)](#chapter-test-run-history-log-tia_test_run_history)
- [Persist flow and crash safety](#chapter-persist-flow-and-crash-safety)
- [Embedded vs server-mode H2 connections](#chapter-embedded-vs-server-mode-h2-connections)

---

## Chapter: Library versioning and the stamp/drain model

### The problem Tia is solving here

Tia tracks tests that exercise a particular piece of production code. When that production code changes, Tia re-runs the affected tests on the next build — this is test-impact analysis.

When the production code lives in a **separate library** that the project under test depends on as a binary artifact (a jar on the classpath), the problem is harder. The library's source changes aren't visible on the source project's VCS timeline; they land in the library's own repo and only reach the source project when someone upgrades the dependency version. A test that exercised library code v1.0 needs to be rerun when the source project adopts v1.1 — not at the moment the library code changed.

Tia's answer is the **stamp/drain model**:

- **Stamp**: When a tracked library's source changes, Tia records a "pending batch" of impacted methods tagged with the library's currently-declared version (from the library's own `pom.xml` / `build.gradle`).
- **Drain**: When the source project's resolved library version reaches that stamped version, Tia "drains" the pending batch — i.e. emits those methods' tests as impacted on the next source-project run.

Conceptually the stamp is a promise: *when you finally upgrade to version X, these are the tests you owe.*

### The subtle part: what "currently-declared version" actually means

The entire model hinges on one number: the version string Tia reads out of the library's build file at stamp time. The drainer compares that number against whatever the source project resolves. For the model to work, the stamped number must mean *"this is the version that will ship with these changes."*

That depends on how the library project's team manages their version number. There are two common conventions, and they produce opposite build-file semantics during development.

#### Convention A: bump-after-release

The declared version is the *next* version to be released. After each release, the build file is bumped to the next planned version (often a SNAPSHOT). Example Maven-release-plugin convention:

```
dev        →  1.6.0-SNAPSHOT     (working toward 1.6.0)
release    →  1.6.0              (cut the tag)
post-bump  →  1.7.0-SNAPSHOT     (now working toward 1.7.0)
```

Under this convention, any commit Tia sees in dev carries the version that change *will be released under*. The stamp is always "for this declared version," and the drain "resolved ≥ stamped" rule is correct.

#### Convention B: bump-at-release

The declared version stays at the *last released* version during dev; the bump happens atomically with the release itself.

```
release 1.0      →  build file at 1.0
dev after 1.0    →  build file still at 1.0   (changes destined for "next release, version unknown")
release 1.1      →  build file bumped to 1.1 as part of the release
dev after 1.1    →  build file still at 1.1   (changes destined for "next release after 1.1")
```

Under this convention, stamps taken during dev describe changes that are destined for an *unknown* future version — specifically, *not* the version currently declared in the build file.

### Why this matters: the drain bug under bump-at-release

Concrete walk-through:

1. LibA releases 1.0. Source project upgrades to 1.0. No stamps yet.
2. Developers commit changes to LibA. The library's build file still reads 1.0. Tia stamps a batch at **stampVersion=1.0**.
3. LibA is released as 1.1. The build file bumps to 1.1 as part of that release.
4. More changes committed to LibA on top of 1.1. Tia stamps a second batch at **stampVersion=1.1**.
5. The source project upgrades to 1.1.

At step 5, the drainer sees `resolved=1.1 ≥ stampVersion=1.0` and drains batch 1 — correct. It also sees `resolved=1.1 ≥ stampVersion=1.1` and drains batch 2 — **wrong**. Batch 2 represents changes that haven't been released yet; they'll ship in a future 1.2, and the source project hasn't seen those changes.

The user paying the price doesn't see a crash — they just see stale test selection: the tests for batch 2 don't get rerun when the source project eventually upgrades to 1.2, because the batch was already drained at 1.1.

### Why this isn't fixable by observation alone

Tia runs during builds. It sees file contents and VCS diffs. It does *not* see a "release event" — releases are a separate process (Maven release plugin, a Jenkins job, someone running `gradle publish`, tagging a commit, etc.). There is no signal in the build file itself that tells Tia which convention the project uses; "build file reads 1.0" is a valid state under either convention.

So Tia can't infer the policy. The user has to tell it.

### The design: policy + HWM + per-stamp flag

Three pieces cooperate:

**1. A project-level policy setting.**

```
libraryVersionPolicy = BUMP_AFTER_RELEASE   (default, matches Maven release plugin convention)
libraryVersionPolicy = BUMP_AT_RELEASE      (opt in if your team bumps at release time)
```

Global across all tracked libraries. Per-library overrides are out of scope for now — we can add them if someone asks.

**2. A high-water mark (HWM) per tracked library.**

`TrackedLibrary.lastReleasedLibraryVersion` records the highest build-file version Tia has ever observed for that library. It's seeded from the library's current build-file version when the library is first onboarded, and advances strictly forward — never regresses, never resets.

The HWM is maintained the same way under both policies:

- If the build-file version Tia reads at stamp time is higher than the stored HWM, the HWM advances.
- Otherwise the HWM stays put.

Under `BUMP_AFTER_RELEASE` the HWM is informational only — it doesn't change drain decisions. Maintaining it symmetrically keeps the stamping code one-pathed: the HWM update happens regardless of policy, and only the interpretation branches.

**3. A per-stamp flag.**

`PendingLibraryImpactedMethod.unknownNextVersion` is a boolean on each pending batch:

- `false` — the batch is tagged with the version it will actually ship under. Drain when `resolved ≥ stampVersion`, the normal rule.
- `true` — the batch is tagged with the *current* HWM, but the actual release version is unknown. Hold the batch until the library's HWM moves past the stamp — i.e. a new release has occurred.

The flag is set at stamp time based on the policy:

| Policy              | Condition at stamp time                        | `unknownNextVersion` |
|---------------------|------------------------------------------------|----------------------|
| `BUMP_AFTER_RELEASE`| any                                            | `false`              |
| `BUMP_AT_RELEASE`   | `loadedVersion > HWM` (HWM just advanced)      | `false`              |
| `BUMP_AT_RELEASE`   | `loadedVersion == HWM`                         | `true`               |
| `BUMP_AT_RELEASE`   | `loadedVersion < HWM` (regression — shouldn't happen) | `true` (conservative) + warn |

### The drain rule

The drainer stays policy-agnostic. It just honours the flag:

```
if batch.unknownNextVersion && resolvedVersion == batch.stampVersion:
    hold   (the stamped version is the last released; the changes are for the next one)
```

All the existing drain conditions remain (`resolved ≥ stamp`, `resolved ≠ lastSourceProjectVersion`, etc.). Under `BUMP_AFTER_RELEASE` the new check is inert — the flag is always false, so the condition never fires, and behaviour is identical to today.

SNAPSHOT drains are unchanged. SNAPSHOTs already use a hash-based flow (the drainer compares the jar's content hash, not its version string), so the version-policy question doesn't apply. `unknownNextVersion` is always left `false` for SNAPSHOT stamps regardless of policy.

### Worked example: bump-at-release

LibA is configured with `libraryVersionPolicy = BUMP_AT_RELEASE`. Build-file version starts at 1.0.

| Step | Event                                           | Stamp row                                  | `lastReleasedLibraryVersion` |
|------|-------------------------------------------------|--------------------------------------------|------------------------------|
| 1    | Onboard LibA                                    | —                                          | 1.0 (seeded)                 |
| 2    | Commit lib change, build file still 1.0         | `stampVersion=1.0, unknownNextVersion=true`| 1.0                          |
| 3    | Release cuts 1.1, build file bumps to 1.1 + commit | `stampVersion=1.1, unknownNextVersion=false` | 1.1                      |
| 4    | Commit lib change, build file still 1.1         | `stampVersion=1.1, unknownNextVersion=true`| 1.1                          |
| 5    | Source project upgrades to 1.1                  | (no new stamp; drain runs)                 | 1.1                          |

At step 5 the drainer sees three pending rows:

- Row from step 2: `stamp=1.0, flag=true`. `resolved=1.1 ≠ 1.0` → drain.
- Row from step 3: `stamp=1.1, flag=false`. `resolved=1.1 ≥ 1.1` → drain.
- Row from step 4: `stamp=1.1, flag=true`. `resolved=1.1 == 1.1` → **hold**.

When LibA later releases 1.2 and the source project upgrades:

- Row from step 4: `stamp=1.1, flag=true`. `resolved=1.2 ≠ 1.1` → drain.

### Worked example: bump-after-release

Same LibA with `libraryVersionPolicy = BUMP_AFTER_RELEASE` (the default). Build file reads `1.1.0-SNAPSHOT` after releasing 1.0.

| Step | Event                                              | Stamp row                                    | `lastReleasedLibraryVersion` |
|------|----------------------------------------------------|----------------------------------------------|------------------------------|
| 1    | Onboard LibA                                       | —                                            | 1.1.0-SNAPSHOT (seeded)      |
| 2    | Commit lib change, build file still `1.1.0-SNAPSHOT` | SNAPSHOT path (hash-based; flag irrelevant) | 1.1.0-SNAPSHOT               |
| 3    | Release cuts 1.1, build file at 1.1                | `stampVersion=1.1, unknownNextVersion=false` | 1.1                          |
| 4    | Post-release bump to `1.2.0-SNAPSHOT`              | SNAPSHOT path                                | 1.2.0-SNAPSHOT (HWM advances)|

The drain rule `unknownNextVersion && resolved == stamp` never fires under this policy — every release-path stamp carries `flag=false`. The HWM moves but doesn't influence decisions. Behaviour is exactly as it was before the fix.

### Operational constraints this exposes

The stamp/drain model assumes Tia sees each release once. Two implications worth calling out:

- **Tia must run on every release of a tracked library.** If a release is skipped, the stamp/drain pairing breaks — under `BUMP_AT_RELEASE` the HWM won't advance, and a batch stamped at the skipped version will hold forever.
- **The authoritative Tia run must operate on a clean working tree.** Tia reads the library version from the build file currently on disk; uncommitted version bumps look identical to committed ones and can cause premature drains. This is documented in the README's "Primary build requirement" section and applies to both policies.

### Where library resolution actually happens

The library metadata Tia reasons about — the declared version, the resolved version on the source project's classpath, the JAR path — has to be obtained from the build system. That resolution is plugin-side (it requires Maven's `ProjectBuilder` or Gradle's `Project` / Tooling API), but selection itself happens in different places on the two build systems:

- **Maven**: the test runner forks a separate JVM (Surefire/Failsafe), and selection has to run before the fork so the agent can apply it. The plugin mojo runs `TestSelector.selectTestsToIgnore` directly, including library reconcile / stamp / drain, then writes `ignored-tests.txt`, `selected-tests.txt`, and `drain-result.ser` for the forked test JVM to consume.
- **Gradle/Spock**: the Spock global extension runs *inside* the forked test JVM and drives selection there. The Gradle plugin resolves library metadata at task-action time (where it has access to the `Project`) and forwards the results as flat system properties; the test JVM rebuilds a `LibraryImpactAnalysisConfig` from those properties using a pre-resolved metadata reader. No filesystem handoff.

A subtlety in the Gradle path: when a tracked library lives in an **external Gradle build** (a separate Gradle project on disk, reached via the Tooling API), Tia reads the library's declared version from its `ProjectPublications` model — not from the Eclipse classpath model used elsewhere. A project's own classpath never contains itself, so a `groupId:artifactId` lookup against the library's classpath always misses. The publications model is the supported route to a project's own coordinates. Practical consequence: external-build libraries must apply `maven-publish` or `ivy-publish` and declare a publication, otherwise Tia logs "Could not determine declared version" and skips the pending stamp. Sibling subprojects don't have this constraint because the in-process `Project` exposes `getVersion()` directly.

This split exists because of *when* each build tool's plugin code can compute and pass dynamic values to the forked test JVM — not because of any difference in how the JVMs themselves share data. Both fork separate test JVMs and both share startup state via `-D` system properties at fork time; the difference is the lifecycle hook the plugin gets. See the chapter [How Tia exchanges data with the test runner](#chapter-how-tia-exchanges-data-with-the-test-runner-gradle-vs-maven) for the full picture. The stamp/drain semantics are identical across both build systems — only the plumbing differs.

A consequence specific to Gradle/Spock: each forked test JVM independently constructs the global extension and, on `updateDBMapping=true` runs, reaches the persist phase. The persist contract today scopes deletion to the suites the *current* fork saw, so multi-fork runs (`maxParallelForks > 1` or `forkEvery > 0`) corrupt the mapping by wiping suites owned by sibling forks. Documented as a single-fork requirement until the persist phase is reworked to be fork-aware. Library writes (reconcile + stamp) are idempotent / merge-safe under multi-fork, so library tracking specifically isn't the failure mode — the test-suite mapping is.

### Why this design over alternatives considered

- **Auto-detect the policy from git history.** Fragile: requires parsing tags, understanding release plugin conventions per build system, and handling projects that don't tag releases at all. A user-declared policy is one config line and unambiguous.
- **Always hold release-version stamps until the version advances.** Correct under `BUMP_AT_RELEASE`, but introduces a one-release drain lag under `BUMP_AFTER_RELEASE` — tests that should run when the user upgrades to the stamped version wouldn't run until the *next* upgrade. Unacceptable regression for the common case.
- **Track a separate "last observed release tag" via VCS.** Same detection-fragility problem, plus requires Tia to read VCS tags, which it doesn't otherwise need.

The per-stamp flag is the minimum persisted state that correctly encodes "drain-on-equal" vs "hold-for-next" without forcing the drainer to understand policy. That separation keeps the drainer simple and policy-extensible.

---

## Chapter: How Tia exchanges data with the test runner (Gradle vs Maven)

A recurring source of confusion when reading Tia's code is why the Maven and Gradle paths look architecturally different — Maven writes files (`ignored-tests.txt`, `selected-tests.txt`, `drain-result.ser`), Gradle uses system properties. The instinct is to assume one runtime can share state in-process and the other can't. That's not what's going on. This chapter unpacks what's actually different and why each path made the choice it did.

### Both build tools fork a separate JVM for tests

Maven Surefire and Failsafe fork a JVM to run tests. Gradle's `Test` task does the same (configurable via `forkEvery` and `maxParallelForks`, but a fork happens by default). In both cases the build-tool process is one JVM and the test runner is another.

So Spock, JUnit5, and any other test framework run in the **forked test JVM** — not in the build-tool's JVM. There is no shared heap, no shared classloader, no in-process method call between the plugin code and the test framework code. Whatever data needs to flow has to cross a process boundary.

### Both build tools share startup state the same way: `-D` system properties

When a parent process forks a child JVM, the standard mechanism for passing key/value config is JVM arguments — specifically `-Dkey=value`. The child reads them via `System.getProperty(...)`. There's no magic.

- **Maven Surefire** has `<systemPropertyVariables>` in its plugin config; Surefire turns those into `-D` args when launching the test JVM.
- **Gradle's `Test` task** exposes `task.systemProperty(key, value)` / `task.systemProperties(map)`; Gradle turns those into `-D` args when launching the test worker.

Both paths are forked-process system-property propagation. The mechanism is identical.

### The real difference is *when* the plugin can compute the values

What separates the two is the lifecycle hook each build tool gives plugin authors:

- **Gradle's `Test` task** lets plugins register a `task.doFirst { ... }` action that runs in the Gradle daemon **immediately before** the fork. Inside that action, plugin code has full access to the project model, can run arbitrary computation (e.g. resolve library metadata via the Tooling API), and can call `task.systemProperty(...)` with the result. Gradle then includes those properties when launching the fork. Dynamic values flow naturally from plugin computation to forked test JVM.

- **Maven Surefire** reads its plugin configuration (including `<systemPropertyVariables>`) from the project's static XML config. By the time a Tia mojo realises it needs to inject specific data, the configuration phase has passed. The closest workaround Tia's Maven mojo uses is mutating the project's `argLine` property at runtime (`AbstractTiaAgentMojo.execute` updates `projectProperties.setProperty(name, newValue)`); Surefire then includes that string in the fork's JVM args. This works for the agent JAR path and a handful of options, but it's awkward for arbitrary structured key/value data and offers no clean per-property API.

So the reason Tia-Maven leans on files isn't that it *can't* use system properties — it's that the *ergonomics* of dynamically setting many or large-valued properties through Surefire's static config model are bad enough that files end up cleaner.

### Why Tia-Maven uses files

Two practical limits push Maven specifically toward files for its biggest payloads:

1. **Size.** The ignored-tests list can be thousands of entries (test classes × parametrizations × fully-qualified paths). Operating-system command-line argument limits (`ARG_MAX` on Unix, much smaller on Windows) make stuffing all of it into a single `-D` arg fragile. A file is unbounded.
2. **Structure.** `LibraryImpactDrainResult` is a serialized Java object, not a string. To force it into a `-D` arg you'd base64-encode the bytes — uglier than just writing the bytes to a file.

The Tia agent in the forked JVM reads file paths from `AgentOptions` (which *is* passed via JVM args, since that's a small fixed string) and loads the contents at startup.

### Why Tia-Gradle/Spock uses system properties

The size/structure limits don't bite on Gradle for two reasons:

1. **Selection runs *inside* the test JVM.** `TiaSpockGlobalExtension` is loaded by Spock at test-JVM startup, opens the Tia DB directly, and computes the ignored-tests list there. The huge list of ignored tests never crosses a process boundary — it's computed where it's used. The only data the plugin needs to forward is the inputs that aren't reachable from inside the test JVM (anything that requires Gradle's `Project` or Tooling API).
2. **Library metadata is small and bounded.** A handful of coordinates × a few string fields per coordinate fits comfortably in one system property. The `tiaLibrariesMetadata` flat-string format documented in the previous chapter is well within `ARG_MAX` for any realistic project.

So Gradle gets to use system properties for everything that needs to cross the boundary, with no file artefacts and no parallel "data file" lifecycle to manage.

### When this design might shift

The current asymmetry is pragmatic, not principled. If Maven Surefire ever exposed a hook that lets a mojo inject system properties at fork time (the way Gradle's `doFirst` does), Tia-Maven could move some payloads off files. Conversely, if a Gradle test task someday accumulates a large enough payload to push past `ARG_MAX`, it'd need to fall back to a file like Maven. Neither pressure exists today.

The takeaway for anyone reading the code: **the plumbing difference is about lifecycle ergonomics and payload size, not about JVM-to-JVM communication capability**. Both build tools use the same underlying mechanism (`-D` args at fork time); each Tia integration picks the carrier (file vs system property) that fits its lifecycle and data shape.

---

## Chapter: Logging conventions (TRACE vs DEBUG)

Tia uses SLF4J throughout. Whether `log.trace(...)` is actually emitted depends entirely on which JVM the code is running in — the same line in `tia-core` can produce output in one runtime and silently disappear in another. The rule below is what keeps logging usable across both.

### Rule

- **Code reachable from the Gradle daemon must use `DEBUG` or higher.** That includes everything in `tia-gradle` and `tia-spock-git-gradle`, *plus* anything in `tia-core` that is called from a daemon-side path (TestSelector, the drainer/recorder, `H2DataStore`, diff and method-impact analysis, JaCoCo coverage parsing, etc.).
- **`TRACE` is only safe in code that exclusively runs inside the forked test JVM.** In practice today, that's a small subset of `tia-spock`. There are no TRACE call sites in `tia-core` — they were all converted to DEBUG because `tia-core` is shared between the two runtimes.
- **Maven runs in the Maven build process**, which uses Plexus logging via SLF4J. It does honour TRACE, but `tia-core` follows the daemon rule for consistency — so don't add TRACE to shared code on Maven's behalf either.

### Why daemon-side code can't use TRACE

Gradle's logging system has only six levels: `ERROR`, `QUIET`, `WARN`, `LIFECYCLE`, `INFO`, `DEBUG`. There is no `TRACE`. Anything an SLF4J `trace(...)` call emits while the Gradle daemon's logger is in effect is dropped — not down-mapped to DEBUG, not buffered, not flagged. `--debug` is the most verbose flag the CLI exposes, and that maps to Gradle's DEBUG level only.

The daemon ignores user-supplied `logback.xml` files, too. Gradle bundles its own SLF4J binding into the daemon's bootstrap classloader, and that binding wins over anything on the build classpath. Putting `<logger name="org.tiatesting" level="trace"/>` in a project's `src/main/resources` configures the **test JVM's** logback (because that's the classpath logback ends up on at test time) but has no effect on the daemon. Even if logback *were* loaded, Gradle's level enum would still cap output at DEBUG.

So a `log.trace(...)` line in `H2DataStore` fires perfectly during `:test` (where the test JVM loads logback and respects the user's level config) but disappears during `tia-select-tests` (which runs entirely in the daemon). That asymmetry is hostile to whoever is debugging — they see different logs depending on which task they ran, with no obvious explanation. Standardising on DEBUG removes the surprise.

### Why test-JVM code can use TRACE

The test JVM is a fresh process with the test runtime classpath. SLF4J binds to whatever logging implementation is on that classpath (typically Logback in projects that ship one), and Logback honours every level including TRACE. A `<logger name="org.tiatesting" level="trace"/>` entry in the user's `logback.xml` works exactly as intended there: TRACE statements emit, and DEBUG/INFO can be filtered down independently.

This means *test-JVM-only* code can legitimately use TRACE for very fine-grained tracing that would be too noisy at DEBUG. The bar is just that the code must be unreachable from the Gradle daemon — i.e. not in `tia-core`, not in `tia-gradle`, not in any shared module that the plugin pulls in at task-action time.

### Practical guidance for contributors

- Default to `log.debug(...)` for fine-grained logging in any shared module. If the line would have been TRACE in a single-runtime codebase, write it as DEBUG here.
- Reserve `log.trace(...)` for tia-spock-only code paths (the Spock global extension internals, the Tia agent inside the test JVM). If you find yourself wanting TRACE in `tia-core`, that's a sign the code is shared and DEBUG is the right level.
- For users diagnosing a problem in `tia-select-tests` or any other plugin task, the workflow is `gradle <task> --debug 2>&1 | grep -E "org\.tiatesting"`. There is no equivalent of Logback level filtering for the daemon — Gradle's DEBUG level is the most fine-grained signal available.

This rule isn't about elegance; it's about making logs predictable across the two runtimes that a single `tia-core` class might be called from.

---

## Chapter: Why Tia requires Maven 3.8.1+

### The problem

A Maven plugin runs *inside* the user's `mvn` process. The user's installed Maven supplies the actual `maven-core`, `maven-plugin-api`, and resolver classes at runtime — what the plugin's POM declares for those dependencies is only a compile-time API contract. This is true for every Maven plugin, including each of Tia's: `tia-junit4-git-maven-plugin`, `tia-junit4-perforce-maven-plugin`, `tia-junit5-git-maven-plugin`, `tia-junit5-perforce-maven-plugin`.

That separation matters when one of those Maven runtime classes turns out to have a vulnerability. The plugin's compile-time version of `maven-core` is irrelevant to the user's exposure; only the user's Maven installation is. Bumping the plugin's `<dependency>` version on `maven-core` would silence vulnerability scanners but leave users on old Maven runtimes just as exposed as before. The fix has to live somewhere that actually changes what the user runs against.

The driving CVE here is [CVE-2021-26291](https://nvd.nist.gov/vuln/detail/CVE-2021-26291). Maven before 3.8.1 will follow `<repository>` entries declared in transitively-resolved POMs, including ones served over plain `http://` URLs. A malicious POM published anywhere in a project's transitive dependency graph could redirect artifact resolution to an attacker-controlled HTTP repository and substitute a poisoned JAR. Maven 3.8.1 introduced the "external HTTP blocker" mitigation: by default, non-HTTPS external repositories declared in transitive POMs are rejected at resolution time. The vulnerability is fixed by upgrading the Maven runtime, not by anything the plugin can do at compile time.

### The design

Tia's Maven plugins set `<prerequisites><maven>3.8.1</maven></prerequisites>` in each plugin POM. Maven enforces this floor at *plugin discovery time*: when the user runs `mvn tia:select-tests` (or any other goal) under Maven < 3.8.1, the plugin loader refuses to load the plugin and emits a clear error along the lines of "requires Maven version 3.8.1". The check happens before any of the plugin's code runs, so users on an older Maven get an actionable message immediately rather than a confusing failure deep in plugin execution.

A few non-obvious design decisions sit behind this:

**Why `<prerequisites>` and not `maven-enforcer-plugin`'s `requireMavenVersion` rule.** The two mechanisms look interchangeable but serve different purposes. `requireMavenVersion` runs as part of the *consuming project's* build — it enforces a Maven floor on whoever is *building* the project that uses the plugin. `<prerequisites>` is a property of the *plugin's* POM and is checked by Maven's plugin-loading machinery at the moment the plugin is invoked. For a tool whose only purpose is to run as a plugin, `<prerequisites>` is the more direct fit: it lets the plugin itself say "I need this Maven runtime" without requiring the consuming project to add and configure an enforcer plugin. It also runs even when the consuming project's build doesn't bind enforcer to any phase.

**Why `<prerequisites>` only on the four wrapper plugins, not on `tia-maven-plugin`.** `tia-maven-plugin` is `packaging=jar`, not `packaging=maven-plugin`. It's a shared library — never invoked directly as a plugin — that the four wrappers extend. Maven only honours `<prerequisites>` for `maven-plugin` packaging; on a jar, it's a no-op and the build emits a deprecation warning. End users never depend on `tia-maven-plugin` directly: they pick a wrapper based on their test runner (Junit4 / Junit5) and VCS (Git / Perforce), and `tia-maven-plugin` comes in transitively. Putting the floor on the wrappers is both sufficient (every entry point covered) and the only place it actually does work.

**Why 3.8.1 specifically, and not a more recent LTS like 3.9.x.** 3.8.1 is the *lowest* Maven version that fixes CVE-2021-26291. Picking it as the floor maximises the user base we accept while still actually closing the vulnerability. Bumping to 3.9.x would lock out users running otherwise-fine 3.8.x installations for no security gain — they're already protected from the CVE. The floor can move forward later if a future Maven version fixes a CVE that 3.8.x doesn't, but raising it without that justification would just be churn.

**Why we still pin transitive dep versions in `<dependencyManagement>`.** This is a separate concern from the runtime CVE. Transitive deps like `commons-lang3`, `plexus-utils`, `commons-io`, `guava`, and `maven-shared-utils` are flagged by SCA scanners (Mend, Snyk, Dependabot) against their default versions shipped with `maven-core:3.6.3`. Pinning them to non-vulnerable versions keeps the *plugin's declared dependency tree* clean for scanners and avoids shipping stale transitive artifacts when a future build resolution puts one of them on a non-provided scope. The pins don't change runtime exposure (the user's Maven still wins for runtime classes) — they're hygiene for static analysis, not a security fix.

### Why this approach over alternatives considered

**"Just bump the plugin's compile-time `maven-core` version."** Doesn't help users. The user's installed Maven supplies the runtime classes regardless of what the plugin declares. A user still on Maven 3.6.x is exposed to CVE-2021-26291 even if the plugin's POM says it depends on `maven-core:3.9.9`. This is a real misconception worth flagging because it's the path most automated remediation tools push you toward.

**"Don't enforce a floor; let users decide."** Acceptable for an internal tool, but Tia's Maven plugins are publicly distributed and this CVE is a critical-severity (CVSS 9.1) RCE-style supply-chain vector. A clear floor with an actionable error message is the right user experience: it tells them *why* the upgrade matters and gives them a concrete version to upgrade to.

**"Use `maven-enforcer-plugin` configured by the user."** Putting the responsibility on the consuming project's build is unreliable — most users won't add it, and the failure mode for those that don't (running on old Maven, getting silently exposed to the CVE) is worse than a clear plugin-loader error.

### Practical guidance

For users: run a recent Maven. 3.9.x is the current LTS; 3.8.x is also fine. The exact floor (3.8.1) is the lowest version that fixes the CVE, not a recommendation — most active users will be well above it.

For contributors: when upgrading Tia's compile-time Maven dependencies (e.g. for a future API surface change), the `<prerequisites>` floor is independent of those bumps. Move the floor only when there's a security or API reason that genuinely requires it; raising it gratuitously costs users for no gain. The transitive pins in `<dependencyManagement>` should track the latest non-vulnerable version on the Java-8-compatible line until the project's own Java baseline moves up.

---

## Chapter: Profiling `select-tests` against a synthetic large DB

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

## Chapter: Test-run history log (`tia_test_run_history`)

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

Date/time            Branch        Commit    Ran  Ignored  Failed  Duration  Mapping  Id
-------------------  ------------  --------  ---  -------  ------  --------  -------  --------
2026-05-15 09:30:42  main          abc123de   42        3       1  1m 23s    yes      550e8400
2026-05-14 14:22:01  feature/foo   9f8a1b2c   30        0       0  45s       no       7c3e1a09
```

The number of rows is configurable: `mvn <plugin>:history -DtiaHistoryLast=N` for Maven, `./gradlew tia-history --last=N` for Gradle. The default is **20**, chosen so the output fits in a terminal screen without scrolling. Values `<= 0` (or non-numeric for `--last`) fail fast with a clear error.

Column widths are computed dynamically from the data so the table stays compact regardless of branch-name length. Numeric columns right-align; commit and id are truncated to the first 8 characters (matching the HTML report's compact rendering). Date/time is rendered in the JVM's local timezone using `yyyy-MM-dd HH:mm:ss`. The mapping flag renders as `yes` / `no` — the compact table form, not the HTML's "updated / not updated" wording. When the history table is empty, the task prints `No Tia test run history recorded yet.` and exits cleanly.

## Chapter: Persist flow and crash safety

### The problem this chapter explains

The Tia DB stores two things that must stay consistent with each other:

1. The **commit value** (or Perforce changelist): a single string on the `tia_core` row that says "the suite-to-method mapping is up to date as of commit X."
2. The **mapping**: rows in `tia_test_suite`, `tia_source_class`, `tia_source_class_method`, and `tia_source_method` that say "test suite A exercises methods M1, M2, M3."

If those two get out of sync — the stored commit claims X but the mapping reflects an earlier state — the next `select-tests` run computes a diff against X, sees no changes between "what's mapped" and "what's stored as the head," and **under-selects**. Tests that should run, don't. Silent correctness bug.

This chapter explains how Tia avoids that state, what failure modes remain, and how Tia self-recovers from them on the next run.

### One persist per run

A Tia-instrumented test run accumulates state in-process (suite trackers, method-id trackers, failed-suite set, drain results) and persists once at the end via `TestRunnerService.persistTestRunData`. The listener invokes it from:

- **JUnit 5**: `TiaTestExecutionListener.testPlanExecutionFinished`.
- **JUnit 4**: `TiaJunit4Listener.testRunFinished`.
- **Spock**: `TiaSpockRunListener.finishAllTests`, called from the global extension's `stop()` once per JVM.

On Surefire retries, `persistTestRunData` is called per attempt for JUnit 5 / JUnit 4 (each retry's listener writes its own row). Spock collapses retries naturally — one call per JVM. See the "Test-run history log" chapter for the per-attempt counters that decouple retry semantics from the mapping accumulator.

### Write sequence: the seal-last invariant

`persistTestRunData` sequences the DB writes so that the **commit value is the last mapping-related write**:

```
1. updateTestSuiteMapping      — tia_test_suite + tia_source_class + tia_source_class_method
2. updateMethodsTracked        — tia_source_method (reads tia_source_class_method, so depends on #1)
3. updateTestSuitesFailed      — tia_test_suites_failed
4. applyLibraryImpactDrainResult — drain pending rows + update tracked-library version stamps
5. updateTiaCoreData (SEAL)    — tia_core (commit value, last-updated, optional stats)
6. persistTestRunHistory       — tia_test_run_history (audit row)
```

The invariant: **if commit X is the stored value, every mapping write for X has completed.**

That's it. There's no enclosing transaction across the six steps — each `dataStore.persistX(...)` call manages its own connection lifecycle, and inside H2 each step has its own per-call atomicity guarantees (see below). Consistency between the commit value and the mapping comes from the **ordering**, not from a single atomic commit.

### Per-call atomicity inside H2DataStore

Each individual persist call is internally atomic in H2's MVStore:

- **`persistSourceMethods` (tia_source_method)**: `TRUNCATE` + `INSERT` are wrapped in one transaction. H2's `TRUNCATE` is transactional (unlike MySQL/InnoDB), so an exception during the `INSERT` rolls the truncate back too — the previous source-method rows survive. See `H2DataStore.persistSourceMethods` (the TRUNCATE-INSERT atomicity block).
- **`persistTestSuiteClasses` (tia_source_class + tia_source_class_method)**: per-suite `DELETE` + `INSERT` is wrapped in one transaction. A failure mid-rewrite of one suite's class/method edges leaves that suite's previous mappings intact. Wrapping the entire outer `persistTestSuites` loop in one transaction would put potentially millions of edges in one transaction and risk MVStore undo-log blow-up; per-suite is the right balance — each suite is internally consistent, and at worst a partial outer-loop failure leaves some suites updated and some not (the same outcome that would happen anyway).
- **`persistTestSuites` (tia_test_suite)**: `MERGE` per suite. Each MERGE is an atomic UPSERT.
- **`persistTestSuitesFailed` (tia_test_suites_failed)**: bulk delete + insert; idempotent on subsequent runs.
- **`persistCoreData` (tia_core)**: single `INSERT` or `UPDATE` of the one core row. Atomic.
- **`persistTestRunHistoryEntry` (tia_test_run_history)**: single `MERGE` keyed by a deterministic id derived from `branch|commit|runStartTimestampMs`. Idempotent — re-persisting the same logical run is a no-op.

What's NOT atomic is the *cross-call* sequence in `persistTestRunData`. That's where the seal-last ordering does its work.

### Failure-mode taxonomy

#### A. Crash *before* the seal (anywhere in steps 1-4)

The stored commit value remains the **prior** value. The mapping tables may be:

- Partially updated. Some suites have new edges (one of the suites finished its per-suite transaction), others still have their prior edges (their per-suite transaction never ran).
- Ahead of what the stored commit claims. The mapping reflects newer state than the commit-value stamp suggests.
- Carrying orphan `tia_source_class_method` rows whose corresponding `tia_source_method` tracker entries weren't written (the per-suite write at step 1 succeeded, but step 2 — which rewrites the whole `tia_source_method` table — never ran).

The next `select-tests` reads the prior commit value and diffs `workspace HEAD ↔ prior commit`. The mapping is "newer than claimed," so it covers strictly more code than the stored commit value suggests. **Net result:** the next run computes a (possibly slightly oversized) diff and re-runs the impacted tests. Self-correcting. No under-selection.

#### B. Crash *after* the seal (in or after step 6)

The commit value advanced to the new value. The only thing that didn't complete is the history-row write. **Not a correctness concern** — the mapping is fully consistent; the audit log just misses one entry. The next run writes a new history row whenever it next persists.

### Self-recovery and orphan handling

The "partially updated, orphan rows" state described in category A is real. The orphan-skip in `TestRunnerService.updateMethodTracker` exists specifically to recover from it:

> The id is referenced from `tia_source_class_method` but neither this run's JaCoCo results nor the `tia_source_method` table on disk knows about it. Most likely an orphan left behind by an earlier run that aborted between updating the join table and the truncate+insert of `tia_source_method`. Skip the orphan rather than NPE downstream.

So even when category-A partial state exists, the next persist sees the orphan, logs it once at `ERROR`, and drops the dangling reference. The orphan disappears from the DB on the next clean persist (because `tia_source_class_method` is rewritten per-suite, replacing any rows that reference the orphan id).

Failed-tests recovery: if step 3 (`updateTestSuitesFailed`) ran but step 5 (the seal) didn't, the new failed-tests set is on disk but the commit value is still old. The next run reads the old commit + the new failed-tests, and the failed tests get force-re-run on the next attempt. If they now pass, they're cleared; if they still fail, no change. Self-correcting.

Library drain recovery: if step 4 ran but step 5 didn't, the drained pending rows are gone but the commit value didn't advance. The next run computes its diff against the prior commit and proceeds without those pending rows — the library impact for those drained batches has already been reflected in the (persisted) suite mapping, so dropping the pendings is consistent. If step 4 didn't run, the pendings stay in the DB; the next run re-drains them on its own successful persist. Either way the system converges.

### What is intentionally NOT mitigated

- **Cross-call atomicity (one transaction wrapping all of `persistTestRunData`)**: not implemented. The seal-last ordering makes correctness-relevant failure modes safe-by-default (always over-select on recovery, never under-select). A wrapping transaction would convert orphan-row warnings into "never happens" but would also risk MVStore undo-log blow-up on large mapping updates. The cost/value isn't currently justified. See the residual-risk note in the `TestRunnerService.persistTestRunData` javadoc.
- **Multi-fork persist (Gradle `maxParallelForks > 1`, `forkEvery > 0`)**: not supported. Tia relies on tests running sequentially in one JVM so JaCoCo coverage can be attributed per-suite. Running multiple forks concurrently will produce mappings polluted with cross-suite coverage and races between fork persists — not a partial-write issue but a fundamental architectural one. Use a single fork when Tia is updating the mapping DB.
- **Exactly-once semantics for history rows**: a crash between the seal and `persistTestRunHistoryEntry` means the run was sealed but no audit row exists for it. The mapping is consistent; only the audit log is short. Tia uses an idempotent MERGE on the deterministic id, so this could be addressed by retrying the history write — but the current code accepts the gap because the audit log isn't load-bearing for select-tests.

### Why a wrapping transaction isn't needed today

Three triggers would justify wrapping all of `persistTestRunData` in a single H2 transaction:

1. The orphan-skip log messages (currently `ERROR` level) become noisy enough in production to mask real errors.
2. Audit-grade guarantees on history rows become a hard requirement.
3. The storage layer changes (e.g. moving to a shared multi-host DB) so per-call atomicity guarantees shift and need a different approach.

Until one of those triggers fires, the seal-last ordering plus per-call H2 atomicity plus the orphan-skip fallback provides correct-by-construction behaviour for the case that actually matters: keeping the stored commit value and the mapping in agreement.

The renderer is `TestRunHistoryConsoleFormatter` in `tia-core`; both the Maven `AbstractHistoryMojo` and the Gradle `TiaHistoryTask` are thin shells over `DataStore.readTestRunHistory()` and the formatter, so the output is identical from either build tool.

---

## Chapter: Embedded vs server-mode H2 connections

### The problem

Tia's data store is H2. Historically it was always an *embedded* database: each build opened a `tiadb-<branch>.mv.db` file on the local disk via a `jdbc:h2:<path>/...` URL. That's the right default - zero setup, no server to run - but it means every machine has its own copy of the mapping and statistics. Teams that want several builds to share one Tia database (a primary CI writer plus developer/local readers, say) need Tia to connect to an H2 running in [server (TCP) mode](https://www.h2database.com/html/tutorial.html#using_server) over `jdbc:h2:tcp://host:port/db`.

The two modes look similar (both are H2, both go through `H2DataStore`) but differ in ways that matter for correctness, not just connection strings.

### The one decision point: `H2ConnectionSettings`

Rather than teach every caller (six Maven mojos, four daemon-side Gradle tasks, three test-runner listeners) how to choose a mode, the choice is resolved once in `H2ConnectionSettings`. It exposes `embedded(path, suffix)`, `server(url, user, password)`, `fromConfig(...)` (picks server iff a URL is supplied), and `fromSystemProperties(branch)` (the listener entry point, reading `tiaDBUrl` / `tiaDBUser` / `tiaDBPassword` / `tiaDBFilePath`). `H2DataStore` takes a settings object and stops caring how the mode was chosen.

The build tools each build the settings from their own config surface and converge on the same object:
- **Maven**: `AbstractTiaMojo.buildH2ConnectionSettings(branch)` from the `tiaDBUrl` / `tiaDBFilePath` parameters. The forked test JVM reads the same values from the user's Surefire `systemPropertyVariables`, exactly as it already did for `tiaDBFilePath`.
- **Gradle**: `TiaBasePlugin.buildH2ConnectionSettings(branch)` for the daemon-side tasks; the forked test JVM gets the values forwarded as system properties by `TiaSpockGitGradlePluginTestExtension` (only when set, so the embedded case never sends the literal string `"null"`).

### What actually differs between the modes

Three behaviours in `H2DataStore` are embedded-only and would be wrong against a shared server, so they are gated on `settings.isServerMode()`:

1. **Engine-option URL params.** Embedded mode appends `PAGE_SIZE`, `CACHE_SIZE`, `DB_CLOSE_DELAY=-1`, and `DB_CLOSE_ON_EXIT=FALSE`. These configure the *database engine instance*, which in server mode lives in the remote server process and is configured when that server starts - not by a connecting client. Server mode therefore uses the supplied URL verbatim with none of these appended.
2. **The `tiadb-<branch>` suffix.** Embedded mode derives the file name from the branch so each branch gets its own file. Server mode does not rewrite the URL automatically, with one opt-in exception: if the configured `dbUrl` contains the `{dbname}` token, `H2DataStore.buildJdbcUrl` replaces it with `tiadb-<branch>` (path separators in the branch sanitized to `-`), giving the same per-branch isolation without Tia having to guess where the database name lives. A URL without the token is used verbatim, so a fully-specified URL still wins. This keeps the connection contract explicit - Tia only rewrites the part of the URL the user has explicitly delegated.
3. **`SHUTDOWN IMMEDIATELY` on `close()`.** In embedded mode `close()` issues `SHUTDOWN IMMEDIATELY` to release the `.mv.db` file lock before Surefire/Gradle forks the test JVM (with `DB_CLOSE_DELAY=-1` the lock would otherwise persist for the life of the daemon JVM). Against a server that command shuts down the whole database **for every connected client**, so server-mode `close()` is a no-op - the per-operation connections are already closed by their own `finally` blocks.

### Credential resolution and keeping secrets out of config

Server mode needs a username and password, and the obvious place to put them - the `tia { dbPassword = '...' }` block or the POM `<tiaDBPassword>` - is checked into source control. To avoid committing a secret, `H2ConnectionSettings.server(...)` resolves each credential by precedence: the explicitly configured value, then a `TIA_DB_USER` / `TIA_DB_PASSWORD` environment variable, then a default (`sa` / empty). So a build can leave `dbPassword` unset and have CI inject `TIA_DB_PASSWORD` into the environment, keeping the repo credential-free.

The fallback lives in the single `server(...)` factory, so it applies uniformly to every entry point (`fromConfig`, `fromSystemProperties`, and the Maven/Gradle builders that delegate to them). The environment lookup is passed in via a package-private overload (`server(url, user, password, env)`) so the precedence logic is unit-tested without mutating the real process environment. This is intentionally a *fallback*, not a replacement for the build tools' own indirection (Maven `${env.X}` / encrypted settings.xml, Gradle `~/.gradle/gradle.properties`): those still work and compose, since they resolve before Tia ever sees the value. Tia never logs the password - only the JDBC URL - so the one remaining footgun is embedding credentials inside `dbUrl` itself.

The password resolver deliberately distinguishes *not configured* from *configured as empty*, which the username resolver does not. H2 accepts an empty password, so `resolvePassword` treats only `null` as "fall back to the environment"; any non-null configured value - including `""` - is used verbatim and is never trimmed (whitespace can be significant in a password). That lets a build pin an empty password explicitly (`dbPassword = ''` / `<tiaDBPassword></tiaDBPassword>`) and bypass `TIA_DB_PASSWORD`. The null-vs-empty distinction survives both plugin bridges: Maven's `@Parameter` is `null` when omitted but `""` when present-and-empty, and the Gradle forwarder only emits `-DtiaDBPassword` when the value is non-null, so an explicit empty string reaches the test JVM as a set-but-empty system property rather than an absent one. An empty *username* is meaningless to H2, so the username keeps the simpler blank-is-unset rule.

### Server-mode prerequisite: `-ifNotExists`

`H2DataStore` auto-creates the schema (and, in embedded mode, the database file) on first use via `createTiaDB()`. An H2 TCP server refuses to create a database for a remote client unless it was started with the `-ifNotExists` flag. So running a server-mode Tia against a server without that flag fails on the very first run. This is a deployment precondition Tia can't paper over from the client side, so it's documented rather than worked around.

### Concurrency: one mapping writer, best-effort statistics

The operational model is unchanged from embedded mode and is what makes shared server mode safe in practice: **exactly one build is the mapping writer** (`tiaUpdateDBMapping=true`); every other client runs in local mode (`tiaUpdateDBMapping=false`) and only updates statistics. The mapping - the load-bearing data for test selection - has a single owner, so the delete-then-reinsert and truncate-then-insert rewrites in the persist path never contend across clients for mapping rows.

That leaves **statistics** as the only data multiple clients write concurrently. Statistics counters (`num_runs`, `avg_run_time`, success/fail counts on both `tia_core` and `tia_test_suite`) are read-modify-write: each client reads the current value, increments in memory (`TestRunnerService` / `incrementStats` / `mergeTestMappingStats`), and writes it back. With several clients doing this against one server database there is a classic lost-update race - two clients read `num_runs=10`, both write `11`, and one increment is lost.

This is a deliberate non-goal. Statistics in Tia are advisory: they drive reports and run-time estimates, not test selection. Adding locking (atomic SQL `num_runs = num_runs + 1` increments, or `SELECT ... FOR UPDATE` row locks) would buy exactness on data that doesn't need it, at a cost on the write path. So Tia accepts statistic drift under concurrent writers; if exact shared statistics ever become a requirement, the atomic-increment rewrite is the place to start. This is the same class of concern as the multi-fork persist limitation in the "Persist flow and crash safety" chapter - and it's the storage-layer-change trigger (#3) that chapter anticipated for revisiting `persistTestRunData`'s transaction strategy.

### Running an H2 server locally to test server mode

You don't need a separate H2 install to exercise server mode on a dev machine: Tia already depends on H2 (`com.h2database:h2:2.2.224` in `tia-core/build.gradle`), so the runnable jar is sitting in your Gradle cache. The same jar that backs embedded mode also ships H2's `org.h2.tools.Server` entry point.

**1. Start the TCP server.** The one non-negotiable flag is `-ifNotExists`: `H2DataStore.createTiaDB()` creates the database on the first run, and an H2 TCP server refuses to create a database for a remote client unless it was started with that flag (see the prerequisite subsection above).

```bash
mkdir -p ~/h2-tia
H2_JAR=$(find ~/.gradle/caches/modules-2 -name 'h2-2.2.224.jar' | head -1)

java -cp "$H2_JAR" org.h2.tools.Server \
  -tcp -ifNotExists -baseDir ~/h2-tia
```

The server listens on port `9092` by default and prints `TCP server running at tcp://...:9092`. Leave it running. `-baseDir` is where the `tiadb.mv.db` file is created; add `-tcpAllowOthers` only if a build on another machine needs to reach it. The Gradle-cache path changes when the cache is cleaned, so for a long-lived local server copy the jar somewhere stable (`cp "$H2_JAR" ~/h2-tia/h2.jar`) and run from there.

**2. Point Tia at the server.** Name the database in the URL yourself, or use the `{dbname}` token to get a per-branch database (`tiadb-<branch>`, mirroring embedded mode). A URL without the token is used verbatim:

```groovy
// Gradle - one database per branch via the {dbname} token
tia {
    dbUrl = 'jdbc:h2:tcp://localhost:9092/{dbname}'
}
```

```xml
<!-- Maven - or a fixed database name, used verbatim -->
<tiaDBUrl>jdbc:h2:tcp://localhost:9092/tiadb</tiaDBUrl>
```

With no credentials configured, Tia falls back through `TIA_DB_USER` / `TIA_DB_PASSWORD` to `sa` / empty (see the credential-resolution subsection above), which matches the `sa`/empty account H2 creates for a brand-new database. To rehearse the env-var fallback, `export TIA_DB_PASSWORD=...` before the build and leave `dbPassword` unset; note that H2 fixes the account on first creation, so whatever password first connects becomes the database's password.

**3. Inspect the data while testing.** Run H2's web console against the same server to watch Tia's tables populate:

```bash
java -cp "$H2_JAR" org.h2.tools.Server -web -webPort 8082
```

Open `http://localhost:8082`, connect with JDBC URL `jdbc:h2:tcp://localhost:9092/tiadb` and user `sa`, and Tia's schema appears after the first run.

**4. Run a build.** From the project under test, run the normal Tia-enabled test task (`./gradlew test` / `mvn test`). The first run creates the schema; subsequent runs do selective testing against the shared server database. Remember the single-writer model from the concurrency subsection: exactly one build should run with `tiaUpdateDBMapping=true`, the rest as statistics-only readers.
