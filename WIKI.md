# Tia Wiki

This document is a deeper companion to `README.md`. The README tells users *how* to install, configure, and run Tia; this wiki explains *why* Tia is designed the way it is — the underlying model, the constraints that shape it, and the reasoning behind specific design decisions.

Chapters will be added retrospectively as design choices come up. Each chapter is self-contained: it restates the problem, describes the model, and walks through concrete examples so a reader can pick up the topic without reading the rest of the wiki.

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
