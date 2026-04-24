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

### Why this design over alternatives considered

- **Auto-detect the policy from git history.** Fragile: requires parsing tags, understanding release plugin conventions per build system, and handling projects that don't tag releases at all. A user-declared policy is one config line and unambiguous.
- **Always hold release-version stamps until the version advances.** Correct under `BUMP_AT_RELEASE`, but introduces a one-release drain lag under `BUMP_AFTER_RELEASE` — tests that should run when the user upgrades to the stamped version wouldn't run until the *next* upgrade. Unacceptable regression for the common case.
- **Track a separate "last observed release tag" via VCS.** Same detection-fragility problem, plus requires Tia to read VCS tags, which it doesn't otherwise need.

The per-stamp flag is the minimum persisted state that correctly encodes "drain-on-equal" vs "hold-for-next" without forcing the drainer to understand policy. That separation keeps the drainer simple and policy-extensible.
