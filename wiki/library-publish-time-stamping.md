# Library publish-time stamping

Tia can track in-repo libraries (same VCS repository, published as artifacts the source project
consumes) and run the tests impacted by a library change once - and only once - the consuming
project actually picks up a build that contains that change. This chapter describes the model end
to end: who records what, when, and how the consumer decides which pending changes to test.

### The problem this solves

A library change and the consumer's adoption of it are two separate events, possibly far apart.
Running the covering tests at change time would exercise the OLD jar still on the consumer's
classpath and produce a false green; never running them would silently skip real risk. Tia
therefore splits the work into a **stamp** (record what changed, at the moment the change becomes
consumable) and a **drain** (run the covering tests, at the moment the consumer actually holds a
build containing the change).

The critical design decision is *when* the stamp happens. An earlier model stamped on the
consumer's Tia run by reading the library's build file, which made the recorded version a guess -
wrong whenever a release and post-release version bump landed between the change commit and Tia's
run. Publish-time stamping removes the guess: the library's own build stamps at publish, when the
version and jar content are unambiguous facts.

### The publish ledger and publishSeq

Every publish of a tracked library appends a row to that library's **publish ledger**
(`tia_library_publish`): the exact published version, the jar's SHA-256 content hash, the repo
HEAD commit and the publish time, plus a per-library monotonically increasing **publish
sequence** (`publish_seq`, assigned `max(seq)+1` at insert).

The sequence is the point of the ledger. Version strings cannot order snapshot builds (three
consecutive deploys are all `1.0-SNAPSHOT`); jar hashes are opaque (given two hashes you cannot
tell which build is newer). The sequence records the publication order as a fact, and because
builds are cumulative snapshots of the source, *the jar published at seq R physically contains
every change stamped at or before R*.

Alongside the ledger row, the publish writes the **pending stamp**
(`tia_pending_library_impacted_method`): the tracked source methods impacted since the library's
mapping baseline, keyed by the publish sequence they shipped in. Ledger row and stamp are written
in one transaction - a publish row without its stamp would let a consumer drain past untested
changes.

### The drain rule

The consumer's Tia run keeps no version state of its own. It asks one question: *which published
build am I actually holding?*

1. Resolve the library on the consumer's classpath and look the artifact up in the ledger - by
   jar hash first (identifies snapshots and releases alike), falling back to an exact version
   match for a resolved release version (a snapshot version string identifies nothing).
2. Found at seq R: drain every PENDING stamp with `publish_seq <= R` - resolve the stamped method
   ids to covering suites via the current mapping and add them to the run set. No lower bound is
   needed: drained stamps are deleted after the run, so the pending set is exactly the
   not-yet-applied set.
3. Not found: hold everything and warn ("resolved build unknown to the publish ledger"). Holding
   cannot produce a false green; draining blindly could. Self-heals on the next publish/resolve
   cycle.
4. Downgrade guard: if R is below the library's `last_applied_seq`, hold and warn - those tests
   already ran against newer code; check the dependency resolution.

After the test run, the primary build's persist deletes the drained stamps, advances
`last_applied_seq` to R and moves the library's `mapping_baseline_commit` to the run's sealed
commit. `last_applied_seq` is deliberately NOT part of the drain predicate - it is a monotonic
high-water mark for the downgrade warning and reporting only; at worst a corrupted value produces
a wrong warning, never a wrong drain.

A crash before the cleanup leaves the stamps pending and the next run re-drains them -
overselection only, never underselection, consistent with the
[persist flow and crash safety](persist-flow-and-crash-safety.md) model.

### End-to-end sequence

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
 |
 |--- C3: dev bumps the APP's dependency 0.1.0 -> 0.1.1 ---
 |
 |--- app's next Tia run ---
 |                                                            resolve libA on APP classpath -> 0.1.1 / H1
 |                                                            ledger lookup H1 (or version) -> seq2
 |                                                            drain pending: seq <= 2 -> stamp {m1}
 |                                                            run the covering tests
 |                                                            post-run cleanup:
 |                                                               DELETE stamp seq2
 |                                                               last_applied_seq = 2
 |                                                               mapping_baseline_commit = sealed commit
 v
```

The SNAPSHOT-consumer flow is identical except the ledger lookup matches by jar hash. Who touches
what:

| Actor                           | Writes                                                            | Reads                                       |
|---------------------------------|-------------------------------------------------------------------|---------------------------------------------|
| A commit, by itself             | nothing                                                           | -                                           |
| Lib publish task (CI lib build) | ledger row + pending stamps                                       | VCS diff, mapping line ranges               |
| App Tia run                     | deletes drained stamps; last_applied_seq, mapping_baseline_commit | its own resolved dependency, ledger, stamps |

### The publish stamp task

The producer side is a goal/task on the **library** module's build, firing on every publication:

- **Maven**: the `publish-lib-stamp` goal, bound by default to the `install` phase - one binding
  covers a local `mvn install` (publishes to `~/.m2`, where a local consumer resolves from) and a
  CI `mvn deploy` (whose lifecycle passes through install). Library-module configuration:

```xml
<plugin>
  <groupId>org.tiatesting</groupId>
  <artifactId>tia-junit5-git-maven-plugin</artifactId>
  <executions>
    <execution>
      <goals><goal>publish-lib-stamp</goal></goals>
    </execution>
  </executions>
  <configuration>
    <tiaEnabled>true</tiaEnabled>
    <tiaUpdateDBMapping>true</tiaUpdateDBMapping>
    <tiaProjectDir>${project.basedir}</tiaProjectDir>
    <!-- must point at the same DB location the consuming project uses -->
    <tiaDBFilePath>${project.basedir}/../my-app</tiaDBFilePath>
  </configuration>
</plugin>
```

- **Gradle**: the Tia plugin hooks a stamp action onto the `publish` and `publishToMavenLocal`
  tasks by name, so it attaches regardless of `maven-publish` application order and is inert on
  projects that never publish.

The task reads the module's own coordinate and version from its build, hashes the built artifact
(a publish without a resolvable jar is still recorded, with a null hash - the drain then
identifies the build by exact version for releases), diffs the library's source dirs from the
mapping baseline to HEAD, intersects the hunks with the tracked method line ranges, and persists
ledger + stamp atomically.

Behaviours worth knowing:

- **First publish seeds.** A library with no mapping baseline gets a ledger row and its baseline
  set to HEAD, with nothing stamped - stamping the library's entire history would be wrong.
- **Untracked coordinate skips.** The module must already be a tracked library (declared in the
  consumer's `tiaSourceLibs` and reconciled by a primary consumer run); otherwise the task logs a
  warning and writes nothing.
- **Ownership gating.** Only a build that owns mapping-DB writes stamps (`tiaUpdateDBMapping` /
  `updateDBMapping` true). On a developer machine against a shared DB the task is a logged no-op -
  the local flow below covers development without persisted stamps.
- **Empty diffs still publish.** A rebuild with no source change writes a ledger row with an empty
  stamp; a consumer resolving it drains everything at or below it, all genuinely contained in the
  jar. Non-reproducible rebuild hashes are therefore benign.

### Line-number correctness: the mapping baseline

The stamp's method intersection is only exact when the diff's original side uses the same line
coordinates as the mapping. Method line numbers in the mapping correspond to the commit at which
the library's covering suites last ran with coverage - so each `tia_library` row carries that
commit as its `mapping_baseline_commit`, and the publish task always diffs from it (not from the
previous publish, which would compound line drift and risk underselection).

The baseline advances only when line numbers actually refresh: on an all-tests run (every suite
re-covered), and on a primary run that drained the library's stamps (the drain ran its covering
suites with coverage). Stamps are therefore cumulative since the baseline; successive publishes
overlap, and the drain's union-by-method semantics make the overlap harmless - a method touched in
five publishes runs its tests once.

Known parity limitation: a brand-new library method has no coverage mapping until the consumer
re-covers it, so no stamp can select tests for it - the drain run that picks up the changed
methods re-covers and registers the new ones.

### Local development flow

A developer edits the library, runs `mvn install` locally, then runs Tia on the app and expects
the impacted tests to run before review/commit. Because library and consumer share one repository,
the app's own Tia run sees the changes directly:

- **Unpublished local edits** (uncommitted, or committed but unpublished): in `checkLocalChanges`
  mode the library-diff partition is bypassed, so library-owned diffs feed direct source
  selection - the in-memory equivalent of a stamp+drain, with no version identity and zero DB
  writes.
- **Published changes**: the persisted-stamp drain runs in every mode, including local runs - it
  is read-only and can only select changes the resolved build provably contains, so a published
  library change on the dev's classpath is tested locally too. Cleanup stays with the primary run.

Staleness footgun: if the developer forgets to rebuild/install the library, the app resolves a
stale jar and local tests exercise old code. The recommended local invocation is a reactor build
from the repo root, where the app resolves the library from the reactor and the jar always matches
the working tree.

### Reporting

- The `libraries` task/mojo lists every tracked library with its ledger state
  (`last applied publish seq`, mapping baseline) and its pending batches (seq, version, method
  count).
- `library-publishes` (`-DtiaLibrary=group:artifact` on Maven, `--library=group:artifact` on
  Gradle's `tia-library-publishes`) prints the library's ledger as a table: seq, version, jar
  hash, commit, publish time and how many of the build's stamped methods still await drain.
- `library-pending-methods` / `tia-library-pending-methods` prints one row per pending method:
  the publish it shipped in (seq + version) and the method's tracked name and line range.
- The HTML `tia-libraries.html` page renders all three: tracked libraries, the publish ledger and
  the pending changes.

### Edge cases and operational notes

- **Ledger retention.** Ledger rows are never deleted automatically (only the cascade when a
  library is removed from `tiaSourceLibs`). Rows at or below `last_applied_seq` are still read -
  the steady state looks up the currently-applied build on every run, and stale-resolve/downgrade
  detection needs older rows to distinguish "known older build" from "build Tia has never seen".
  Rows above `last_applied_seq` must never be pruned - they identify builds not yet consumed.
- **Duplicate ledger matches** (identical artifact republished): the lookup takes the highest
  matching seq - contents are identical or cumulative, so the higher seq drains a superset, the
  safe direction.
- **A pinned consumer** accumulates stamps by design and drains them in one catch-up when the
  dependency finally moves; union semantics dedupe repeated methods.
- **Library commits not yet published** are partitioned out of the app run's source selection and
  select nothing - correct, the resolved jar does not contain them - and the publish task's
  baseline diff stamps them later. The app's commit cursor and the mapping baseline are
  independent; no coordination or timing window exists.
- **Reconcile owns identity only.** The reconciler inserts/updates/deletes `tia_library` rows from
  the `tiaSourceLibs` config (project dir, source dirs); the ledger state (`mapping_baseline_commit`,
  `last_applied_seq`) is owned by the publish task and the post-run cleanup and survives config
  changes. Removing a library cascades its ledger and stamps; re-adding starts fresh.

### Why this design over alternatives considered

- **Stamp on the consumer's run by reading the library's build file** (the previous model): the
  recorded version is a guess about the future, requiring a version-policy config, a high-water
  mark and hold heuristics to patch over - and still mis-stamping when a release intervened
  between commit and run. Recording at publish makes the version an observation, and all of that
  machinery was deleted.
- **Infer the release from VCS tags or the artifact repository**: fragile per-convention parsing,
  and requires access Tia does not otherwise need.
- **Run Tia in the window between commit and version bump**: a timing constraint on someone else's
  schedule; not a correctness mechanism.
- **Match by jar hash alone, without a sequence**: cannot order snapshot builds, so a stale
  resolve (an older cached jar) would drain newer stamps against a jar that does not contain
  them - a silent false green. The sequence makes that impossible.

---

[Back to the Wiki index](../WIKI.md) | Next: [How Tia exchanges data with the test runner](test-runner-data-exchange.md)
