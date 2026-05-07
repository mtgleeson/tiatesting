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
