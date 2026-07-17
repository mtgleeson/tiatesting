# Design: publish-time library stamping

Status: proposed, pre-implementation. Supersedes the run-time version-inference model
(LibraryVersionPolicy / high water mark / unknownNextVersion) documented in WIKI.md under
"Library versioning and the stamp/drain model". When implemented, that WIKI chapter is rewritten
around this design and this file is folded into it.

## 1. Problem

Today the stamp version for a library change is read from the library's build file at the moment
the source project's Tia run happens to execute (`readDeclaredVersionForLibrary`). That value is a
guess about which version the change will ship in, and the guess is wrong whenever a release and
post-release bump land between the change commit and Tia's run:

- Source project consumes libA `0.1.0`. Library declares `0.1.1-SNAPSHOT`.
- Developer commits a change to the library.
- The library is built and released as `0.1.1`; the build file is bumped to `0.1.2-SNAPSHOT`.
- Tia runs on the source project, sees the library change, and stamps it `0.1.2-SNAPSHOT`.
- The impacted tests will not run until the source project upgrades to `0.1.2` - one release too
  late. Underselection at `0.1.1`.

The root cause is structural: the version is inferred at observation time instead of recorded at
the one moment it is an unambiguous fact - publication. All of the existing compensation machinery
(LibraryVersionPolicy with its two bump conventions, the last-released high water mark, the
per-stamp unknownNextVersion hold flag, and the four-branch SNAPSHOT/release drain matrix) exists
only to make that inference less wrong. It cannot make it right.

Prerequisite that makes the fix cheap: tracked libraries live in the same repository as the source
project (already a documented requirement), so producer and consumer share one Tia datastore and
one commit history.

## 2. Decision

Stamp library changes at publish time, from the library's own build, and order all published
builds with a per-library monotonically increasing sequence number (`publishSeq`). The consumer
never reads the library's build file again; it drains by looking up the artifact it actually
resolved in the publish ledger.

### 2.1 The publish ledger

A new table records one row per published build of each tracked library:

```
tia_library_publish
  group_artifact   FK -> tia_library (cascade delete)
  seq              per-library monotonic integer, assigned max(seq)+1 in the publish transaction
  version          the exact version published (0.1.1-SNAPSHOT or 0.1.1)
  jar_hash         SHA-256 of the published artifact
  commit_value     repo HEAD at publish (provenance / debug)
  published_at     UTC epoch ms
```

Pending impacted-method stamps reference the seq of the publish they shipped in:

```
tia_pending_library_impacted_method (reworked)
  group_artifact, publish_seq (FK to the ledger row), method_id
  (stamp_version retained as a display column only; unknown_next_version dropped)
```

### 2.2 The drain rule (replaces the entire version-policy model)

1. Look up the resolved artifact in the ledger: by `jar_hash` first (works for snapshots and
   releases), else by exact `version` for a resolved release version.
2. Found at seq R: drain every PENDING stamp with `seq <= R`. Builds are cumulative snapshots of
   the source, so the jar at seq R contains every change stamped at or before R - draining
   "everything up to R" can never test a change against a jar that predates it. No lower bound is
   needed: drained stamps are deleted at cleanup, so the pending set is exactly the
   not-yet-applied set. Correctness rests on two mechanisms only - seq ordering and
   delete-on-drain.
3. Not found: hold all stamps and warn ("resolved build unknown to the publish ledger").
   Self-heals on the next publish/resolve cycle; holding cannot produce a false green, draining
   blindly can.
4. Post-run cleanup (existing drain-result flow): delete drained stamps, set
   `last_applied_seq = R`. A crash before cleanup leaves stamps pending; the next run re-drains.
   Overselection only - consistent with the persist-flow crash model.

`last_applied_seq` (one integer per library on `tia_library`, replacing both
`last_source_project_version` and `last_source_project_jar_hash`) is deliberately NOT part of the
drain predicate. It is a monotonic high-water mark used only for:

- the downgrade / stale-resolve warning: if the resolved build's seq R < `last_applied_seq`, the
  drain already selects nothing (all pending stamps are newer than R), but the field lets Tia say
  precisely "resolved an older build (seq R) than previously tested (seq N)" instead of staying
  silent;
- reporting: the libraries report derives "last applied version" by joining the ledger on it.

At worst a corrupted `last_applied_seq` produces a wrong warning - never a wrong drain.

Worked ledger (five publishes; seq 1 is the baseline seed, seq 4 the release commit):

```
 seq | version         | jarHash | stamped methods
-----+-----------------+---------+-----------------
  1  | 0.1.1-SNAPSHOT  | H1      | (baseline seed - none)
  2  | 0.1.1-SNAPSHOT  | H2      | {m1, m2}
  3  | 0.1.1-SNAPSHOT  | H3      | {m3}
  4  | 0.1.1           | H4      | (release version bump - none)
  5  | 0.1.2-SNAPSHOT  | H5      | {m4}
```

- Snapshot consumer at last_applied_seq=1 resolves H3: drain seq 2..3 -> {m1,m2,m3}. A skipped
  build (H2 never resolved) still drains, because H3 contains its changes.
- Release consumer upgrades 0.1.0 -> 0.1.1: lookup version 0.1.1 -> seq 4 -> drain <= 4. The
  original bug cannot occur: the build file's current 0.1.2-SNAPSHOT is never consulted.
- Stale resolve: app at seq 2, CI has published seq 3 {m3}, app resolves H2 again -> lookup gives
  seq 2 -> {m3} is held. Under a hash-difference rule it would have drained and produced a false
  green (m3's tests passing against a jar without the m3 change).

### 2.3 End-to-end sequence (the original bug scenario)

Three points the sequence makes concrete: the ledger is written on publish (a commit alone writes
nothing); the publish task writes both the ledger row and the pending stamp; and the app never
reads the library's build file - it identifies the dependency it actually resolved on its own
classpath and looks that up in the ledger.

```
      Developer              Library publish task           Tia DB                        App Tia run
      (monorepo commits)     (fires in the LIB's build)     (ledger/stamps/tia_library)   (drains only)
time
 |    starting state:  app pom declares libA 0.1.0
 |                     ledger:      seq1 = (0.1.0, jar H0)
 |                     tia_library: last_applied_seq=1, mapping_baseline_commit=C0
 |
 |--- C1: dev commits lib change (method m1) ---
 |            +-- nothing happens: a commit writes nothing to Tia.
 |
 |--- CI releases the lib at 0.1.1 (mvn deploy on lib module) ---
 |            +--> publish task fires:
 |                   1. version being published = 0.1.1     (its OWN build, at publish moment)
 |                   2. hash the built jar          -> H1
 |                   3. VCS diff  C0 -> HEAD(C1)    -> TireService.java changed
 |                   4. intersect w/ mapping ranges -> {m1}
 |                   5. ONE TRANSACTION:
 |                        INSERT ledger row    (seq2, version=0.1.1, hash=H1, commit=C1)
 |                        INSERT pending stamp (publish_seq=2, methods={m1})
 |
 |--- C2: dev bumps lib build file to 0.1.2-SNAPSHOT ---
 |            +-- nothing happens; no one reads this build file on the app side,
 |                so the bump cannot poison a stamp.
 |
 |--- app's Tia run (app still depends on 0.1.0) ---
 |                                                            resolve libA on APP classpath -> 0.1.0 / H0
 |                                                            ledger lookup H0        -> seq1
 |                                                            drain pending: seq <= 1 -> nothing
 |                                                            (correct: the jar under test
 |                                                             contains no new changes)
 |
 |--- C3: dev bumps the APP's dependency 0.1.0 -> 0.1.1 ---
 |
 |--- app's next Tia run ---
 |                                                            resolve libA on APP classpath -> 0.1.1 / H1
 |                                                            ledger lookup H1 (or version) -> seq2
 |                                                            drain pending: seq <= 2 -> stamp {m1}
 |                                                            getTestSuitesForMethods({m1}) -> tests
 |                                                            run them (+ normal app selection)
 |                                                            post-run cleanup:
 |                                                               DELETE stamp seq2
 |                                                               last_applied_seq = 2
 |                                                               mapping_baseline_commit = sealed commit
 v
```

The SNAPSHOT-consumer flow is identical except the ledger lookup matches by jar hash (the version
string is shared by every snapshot build and identifies nothing).

Who touches what:

| Actor                                | Writes                                          | Reads                                    |
|--------------------------------------|-------------------------------------------------|------------------------------------------|
| A commit, by itself                  | nothing                                         | -                                        |
| Lib publish task (CI lib build)      | ledger row + pending stamps                     | VCS diff, mapping line ranges            |
| App Tia run                          | deletes drained stamps; last_applied_seq, mapping_baseline_commit | its own resolved dependency, ledger, stamps |

## 3. Producer side: the publish stamp task

A new Tia goal/task in the library module, bound so it fires on every publication:

- Maven: bound to the `install` phase. The default lifecycle runs `... install -> deploy`, so one
  binding covers local `mvn install` (which publishes to `~/.m2`, where a local app build resolves
  from) and CI `mvn deploy`.
- Gradle: hooked on `publishToMavenLocal` and `publish`.

What it does (only when this build owns mapping writes - see section 5):

1. Read the exact version being published and SHA-256 the built artifact.
2. Diff the library's source dirs from the library's `mapping_baseline_commit` (section 4) to
   HEAD, via the existing VCS reader (same repo).
3. Intersect the diff hunks with the tracked method line ranges from the shared mapping
   (`getMethodsTrackedForFiles`), exactly as the current app-side stamping does.
4. In one transaction: insert the ledger row at `max(seq)+1`; insert the pending stamp rows if the
   impacted set is non-empty (an empty diff still writes the ledger row - the publish happened and
   must be resolvable).

First publish for a library (no baseline yet): write the ledger row, stamp nothing, seed
`mapping_baseline_commit` to HEAD. Mirrors today's reconciler baseline seeding and prevents
stamping the library's entire history. Library removed from `tiaSourceLibs` and re-added:
reconciler deletes the row (cascade removes ledger + stamps), next publish re-seeds. Tracked
libraries themselves stay managed by the reconciler exactly as today.

## 4. Line-number correctness: mapping_baseline_commit

The intersection in step 3 is only exact if the diff's original side uses the same line
coordinates as the mapping. Method line numbers in the mapping correspond to the commit at which
the library's covering suites last ran with coverage - not to the previous publish. Diffing
publish-to-publish would compound drift across publishes and can underselect (a moved method's
change misses its stale range).

Fix: add `mapping_baseline_commit` to `tia_library` and always diff from it.

- Advance rule: set to the run's sealed commit whenever the library's line numbers actually
  refresh - an all-tests (seed) run, or a primary app run that drains stamps for the library
  (the drain runs the covering suites with coverage, which rewrites exactly those ranges).
  Advanced in the mapping-write phase, before the seal, per the existing persist ordering.
- Consequence: stamps are cumulative since the baseline (publish k's set is a superset of
  publish k-1's for the same files). Harmless: the drain unions method ids across stamps before
  resolving tests, so a method touched in five publishes runs its tests once. In steady state the
  drain run advances the baseline, so cumulative degenerates to incremental.
- `last_published_commit` (from earlier drafts) is not needed: the ledger's per-row
  `commit_value` carries publish provenance, and the diff baseline is the mapping baseline.

Known parity limitation: a brand-new method added after the baseline has no coverage mapping, so
no stamp can select tests for it until the app re-covers. Identical to today; the drain run that
picks up the changed methods re-covers and registers the new ones.

## 5. Local development flow (updateDBMapping=false)

Only primary builds (CI, `updateDBMapping=true`) write the ledger and stamps. On a developer
machine the publish task is a no-op (debug log only) - required for shared-DB (server mode)
safety, and locally redundant because the flow below covers it.

A developer edits the library (uncommitted or committed-but-unpublished), runs `mvn install`
locally, then runs Tia on the app and expects the impacted tests to run before review/commit.
Because it is a monorepo, the app's own Tia run sees those library changes directly:

1. The app run's diff (checkLocalChanges for uncommitted, stored-commit -> local HEAD for
   committed) includes the library-owned files; they are partitioned out of source selection as
   today.
2. A synthetic stamp is built in memory from those diffs - impacted method ids only, no version,
   nothing persisted.
3. The synthetic stamp drains unconditionally within that run (the developer explicitly asked to
   test their local change); covering tests are added to the run set.
4. Nothing is written. CI re-verifies the same change authoritatively through the publish-time
   stamp and the seq drain.

Two-tier drain rule, summarised:

|                  | persisted stamps (CI)            | synthetic stamps (local, in-memory) |
|------------------|----------------------------------|-------------------------------------|
| identity         | publishSeq + version + jar hash  | none                                |
| drain condition  | seq <= resolved seq; unknown resolved build -> hold + warn | unconditional, within-run |
| writes           | ledger + stamp rows              | zero                                |

Implementation note (stage 5): the two halves land as follows. Local unpublished edits need no
new machinery - checkLocalChanges mode already bypasses the library-diff partition, so those
diffs feed direct source selection, which IS the in-memory stamp+drain (no version identity
needed). The behaviour change is on the persisted side: the drain no longer skips
checkLocalChanges mode - it is read-only and can only select changes the resolved build provably
contains, so a local run now also picks up published-but-not-yet-applied library changes on the
dev's classpath. Cleanup remains gated on the primary run's updateDBMapping.

Staleness footgun and its mitigation: if the developer forgets to `mvn install` the library, the
app resolves a stale jar and the synthetic drain tests old code. Recommended local invocation is a
reactor build from the repo root (`mvn test`), where the app resolves the library from the reactor
- the jar always matches the working tree. Document, do not engineer around.

## 6. What gets deleted

All of the following exist only to compensate for run-time version inference and are removed:

- `LibraryVersionPolicy` (enum, config surface, both conventions).
- `lastReleasedLibraryVersion` high water mark on `tia_library` and its advance logic.
- `unknownNextVersion` on the stamp row and its hold rule in the drainer.
- The four-branch SNAPSHOT/release drain matrix (`shouldDrainSnapshotStampAgainstReleaseSource`
  etc.) - collapses to the single ledger-lookup + seq comparison.
- `readDeclaredVersionForLibrary` on the consumer path (the app never reads the library build
  file again).
- `last_source_project_version` / `last_source_project_jar_hash` as drain state, replaced by
  `last_applied_seq`. (The libraries report derives a human-readable "last applied version" by
  joining the ledger on that seq.)
- The corresponding WIKI sections (policy model, HWM, worked policy examples).

Impact on the in-flight `feature/select-tests-library-preview` branch: the in-memory synthetic
batch construction and preview drain machinery is the foundation of section 5 and survives; its
version plumbing (buildPendingBatch reading the declared version, version-form drain branches in
the preview) is superseded. Hold the branch; rework it as part of Stage 5 rather than merging
first. The select-tests preview itself gets simpler under this design: published changes always
have persisted stamps (no synthetic needed for them), and unpublished changes are covered by the
synthetic local path with "what would run now" semantics unchanged.

## 7. Edge cases

- Non-reproducible rebuilds: a rebuild with no source change publishes a new hash at a new seq
  with an empty stamp. An app resolving it drains everything <= that seq - all genuinely contained
  in the jar. Benign.
- Duplicate ledger matches (identical hash republished, or a re-published release version): take
  the highest matching seq. Contents are identical or cumulative; the higher seq drains a
  superset, which is the safe direction.
- Consumer pinned for a long time: stamps accumulate (by design) and drain in one catch-up when
  the dependency finally moves. Union semantics dedupe repeated methods. No pruning initially; a
  lossless collapse rule ("merge stamps older than the last N publishes") is possible later if a
  never-updating consumer ever matters.
- Ledger retention: ledger rows are never deleted automatically (the only deletion is the cascade
  when a library is untracked). Rows at or below `last_applied_seq` are still read: the steady
  state looks up the currently-applied build on every run, and stale-resolve/downgrade detection
  needs the older rows to distinguish "known older build" from "build Tia has never seen". Rows
  above `last_applied_seq` must never be pruned - they identify builds not yet consumed. Cost is
  one small row per publish per library; if size ever matters, the safe rule is "prune below
  last_applied_seq, keeping the row at it".
- App run sees library commits that are not yet published: partitioned out, nothing selected
  (correct - the resolved jar does not contain them), nothing stamped (the app no longer stamps).
  The publish task's baseline diff still includes them later. The two cursors (app stored commit,
  mapping baseline) are independent; no coordination or timing window exists.
- Seq assignment concurrency: embedded mode has a single writer; server mode assigns max(seq)+1
  inside the publish transaction.

## 8. Staged implementation plan

Branch: new branch off main (independent of fix/h2-embedded-flush-on-close;
feature/select-tests-library-preview stays unmerged and is reworked in Stage 5). Per project
conventions: one stage at a time, stop for review after each, unit tests with given/when/then,
javadocs on every new/modified method, ASCII hyphens.

Stage 1 - schema and model. `tia_library_publish` table + model class + DataStore CRUD
(read ledger, insert publish, lookup by hash/version, max seq). `mapping_baseline_commit` and
`last_applied_seq` columns on `tia_library` (additive; old columns untouched this stage).
Stamp rows gain `publish_seq`. No DB migrations: Tia is pre-release with no existing databases
to upgrade - new columns/tables ship in the create-table DDL only, and a stale local dev DB is
deleted and reseeded. No behaviour change.
Tests: DataStore round-trips, lookup precedence (hash first, then version), cascade delete.

Stage 2 - producer service in tia-core. `LibraryPublishStamper`: baseline diff, mapping
intersection (reusing the existing method-impact analysis), transactional ledger+stamp write,
first-publish baseline seeding. Pure core service, not yet wired to any build tool.
Tests: stamp correctness against seeded mappings, empty-diff publish, first-publish seed,
cumulative-since-baseline supersets.

Stage 3 - consumer drain rewrite. Replace the drainer's version/policy rules with the ledger
lookup + seq drain (rules 1-5 in section 2.2). TestRunnerService cleanup advances
last_applied_seq and mapping_baseline_commit. Delete LibraryVersionPolicy, HWM,
unknownNextVersion, the version-form drain branches, and the consumer-side declared-version read.
Rewrite the library end-to-end tests around publish -> resolve -> drain -> cleanup.
Tests: scenarios A-E (leapfrog, release upgrade, stale resolve hold, unknown hash hold+warn,
downgrade guard), crash-before-cleanup re-drain.

Stage 4 - build-tool wiring. Maven mojo bound to the install phase; Gradle hooks on
publishToMavenLocal/publish; updateDBMapping gating (no-op on non-primary); artifact hash of the
actually-built jar; verify install-phase firing under both mvn install and mvn deploy against the
test workspace projects.

Stage 5 - local synthetic flow + preview rework. Enable the library path under checkLocalChanges;
synthetic in-memory stamp + unconditional within-run drain; rework the select-tests preview to
read persisted stamps plus local synthetic ones, removing the preview's version plumbing.
Reconcile or retire feature/select-tests-library-preview here.
Tests: local uncommitted change selects covering tests with zero DB writes; preview parity with
a primary run over the same range.

Stage 6 - reporting. The existing libraries task keeps its scope (tracked libraries, each with
its pending changes - now seq-based). Two new tasks/mojos, each taking a {@code group:artifact}
input parameter and rendering table-formatted output: one lists the library's pending impacted
methods, the other lists the library's publish ledger (seq, version, jar hash, commit,
published-at). The HTML report gains a per-library publish-ledger table alongside the tracked
libraries and their pending changes.

Stage 7 - docs and cleanup. Rewrite the WIKI stamp/drain chapter around this design (fold this
file in), update README library-tracking sections, remove policy configuration from docs and
sample configs, drop the now-dead tia_library columns from the schema DDL. Re-point every
javadoc/comment reference to this design doc (stages 1-4 used "See DESIGN-publish-time-stamping.md
section N") at the new WIKI chapter sections, then delete this file. If WIKI.md has grown too
large as a single document, split it into per-chapter subpages (e.g. a wiki/ directory, one md
file per chapter) with WIKI.md remaining as the index: title + one-line summary + relative link
per chapter, and prev/next links in each subpage.

## 9. Out of scope

- Libraries in a separate repository (the same-repo requirement stands; a separate-repo producer
  would need its own Tia integration and a shared server-mode DB - possible later on top of the
  same ledger model, since nothing in the ledger assumes one repo except the diff step).
- Per-method bytecode diffing of published jars (would remove the source-diff dependency
  entirely; disproportionate to current needs).
- Stamp retention/pruning policies (lossless collapse is available later if needed).
