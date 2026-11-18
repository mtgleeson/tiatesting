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
   already ran against newer code; check the dependency resolution. It applies to builds on the
   followed line only - see [Source lines](#source-lines).

After the test run, the primary build's persist deletes the drained stamps, advances
`last_applied_seq` to R and moves the library's `mapping_baseline_commit` to the commit build R
was published at (the ledger row's commit, carried on the drain result). `last_applied_seq` is deliberately NOT part of the drain predicate - it is a monotonic
high-water mark for the downgrade warning and reporting only; at worst a corrupted value produces
a wrong warning, never a wrong drain.

A crash before the cleanup leaves the stamps pending and the next run re-drains them -
overselection only, never underselection, consistent with the
[persist flow and crash safety](persist-flow-and-crash-safety.md) model.

### Source lines

One library coordinate can be built from more than one place: a `1.x` hotfix line and a `2.x`
line, or two streams that each carry their own copy of the library. A consumer takes its builds
from one of them, and only that line's changes are in the jar it holds. Each publish therefore
records its **source line**, and each consumer schema records the line it **follows**.

**What a line is.** Where the library source that was built lives, independent of the workspace
it was built in:

- **Perforce:** the depot paths the publishing module's source roots resolve to through the
  client view (one `p4 where`), sorted and comma-joined. A release job working through a virtual
  or importing stream resolves to the real depot location, so the same source is the same line
  through any view. The stream or client name is deliberately not used. Source roots inside the
  module's build directory (generated sources) are left out, so the line does not change with
  what a build happened to generate.
- **Git:** the branch the publishing workspace identifies as - the configured `tiaBranch` when
  set, the same value that names the schema. Not the checked-out ref, which is a commit SHA on a
  detached HEAD and would make every CI publish a line of its own.

The line is stored on the ledger row (`tia_library_publish.source_line`). When it cannot be
identified - no source root maps through the client view - the row has no line, the publish logs a
warning, and the row matches any line, as do rows written before lines were recorded.

**How a consumer learns its line.** It runs no VCS command. The drain already hashes the library
jar on the classpath and finds it in the schema's ledger; the matching row names the line. The
first primary run that resolves a known build - one with pending stamps to evaluate, or a forced
all-tests run - reports that line, and the seal stores it as `tia_library.followed_line`.

**Every line stamps every consumer.** A publish writes its ledger row and its stamp into every
schema tracking the coordinate, whichever line that schema follows. The publisher cannot know
which line a consumer takes its builds from today; the consumer's drain can, because it identifies
the jar it holds. So the line is applied at the drain. At any run that resolves a build:

- pending batches on the resolved build's line at or below its sequence drain as usual;
- pending batches from other lines are **discarded**: deleted at the seal without selecting a
  test. The jar the consumer holds does not contain those changes.

The first such run also establishes the followed line. A consumer of a library with several active
lines therefore pays one library resolution on the run after each other-line publish, and selects
nothing for it.

**Per-line previous publish.** Diff 2's "since the previous publish" is measured from the
previous publish on the publishing line. Measuring from another line's publish would filter out
this line's own earlier edits.

**Ambiguous matches.** When several ledger rows match the resolved jar's hash or release version
(two lines can publish the same version), a row on the followed line is preferred, then the
highest sequence.

**Line switch.** A consumer that follows a line and resolves a build from another - an app moving
from the hotfix line to the next major - has switched line. The run **selects every tracked
suite**: the mapping does not record which methods belong to the library, and a diff across lines
cannot be matched to the mapping's line numbers, so no narrower selection exists. A warning names
both lines. At the seal:

- every pending batch for the library is deleted, whichever line it is from - the old line's no
  longer describe the held jar, and the new line's are covered by having run everything;
- the followed line becomes the new line and `last_applied_seq` the resolved build's sequence;
- the mapping baseline moves to the resolved build's publish commit, as after any drain.

The downgrade guard is skipped on a switch: sequences on different lines do not order each other,
so a lower sequence on the new line is not an older build. From the next publish on, the new
line's stamps drain precisely and the old line's are discarded.

A switch is noticed when the drain identifies the held build, which it does when the library has a
pending stamp or the run is a forced all-tests run (the no-pending fast path resolves nothing, to
keep selection cheap). Because every line stamps every consumer, a publish on the new line leaves
a stamp pending in the consumer's schema, so the old line need not publish again for the move to
be noticed. This is why a publish is not withheld from schemas following another line.

**A changed build file also triggers it.** A run on the old line discards the new line's pending
stamp, so if the consumer ran between the new line's publish and adopting that build - the usual
order for a release - nothing is pending when it moves. Moving normally means editing a build file
(a declared version, a version property or catalogue, a BOM import), so a selective run whose
changes since the stored commit include a `pom.xml`, a Gradle build or settings script,
`gradle.properties` or a `*.versions.toml` identifies every tracked library's held build, as a
forced run does, and a switch is noticed at that run. The changed paths are read once and shared
with the static selection rules; with no static rules configured, the trigger costs one
changed-paths query per run, plus a library resolution on the runs where a build file changed.

Only the source project's own build files count: those in the source project directory, below it
(its modules), or in a directory above it up to the workspace root (parent POMs, root settings
scripts and `gradle.properties`). A version catalogue in a `gradle/` directory counts for the
build one level up, where Gradle reads it. A build file of another project in the same repository
or client view cannot change what this project resolves, so it does not trigger a resolution.
The workspace root is the Git work tree or the Perforce client root, read locally with no server
call. When the source project lies outside the workspace, every changed build file counts.

The trigger does not cover a move with no build-file change, such as a snapshot dependency that
resolves to another line's build because a different line published the same snapshot version
last. Run `tiaSelectAllTests` once after such a move to switch the consumer at that run;
otherwise the switch is noticed at the next publish on either line.

Local (non-primary) runs read the followed line and apply the same rules but never write it.
Nothing here needs the VCS on the consumer side: the line is read at publish time and travels in
the ledger.

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
 |                                                               mapping_baseline_commit = C2 (seq2's publish commit)
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
  <artifactId>tia-maven-plugin</artifactId>
  <executions>
    <execution>
      <goals><goal>publish-lib-stamp</goal></goals>
    </execution>
  </executions>
  <configuration>
    <tiaEnabled>true</tiaEnabled>
    <tiaUpdateDBMapping>true</tiaUpdateDBMapping>
    <tiaProjectDir>${project.basedir}</tiaProjectDir>
    <!-- the database the consuming projects use; the stamp needs nothing else to find them -->
    <tiaDBFilePath>${project.basedir}/../my-app</tiaDBFilePath>
  </configuration>
</plugin>
```

- **Gradle**: the Tia plugin hooks a stamp action onto the `publish` and `publishToMavenLocal`
  tasks by name, so it attaches regardless of `maven-publish` application order and is inert on
  projects that never publish.

The task reads the module's own coordinate and version from its build, hashes the built artifact
(a publish without a resolvable jar is still recorded, with a null hash - the drain then
identifies the build by exact version for releases), computes the impacted methods with the
two-diff approach described in [Two diffs: identify, then filter](#two-diffs-identify-then-filter),
and persists ledger + stamp atomically.

Behaviours worth knowing:

- **Diffs run over the publishing module's own source roots.** The `tia_library` row records the
  library's source dirs as absolute paths from the *consumer's* workspace. A library released from
  its own CI job (its own workspace / P4 client) would find those paths outside its workspace and
  diff nothing, so the task diffs the module's own source roots instead (Maven: the compile source
  roots; Gradle: the `main` source set's Java dirs; in both cases only those that exist on disk).
  Mapping keys are package-relative, so they still match the consumer's tracked methods. The
  `tia_library` row is left unchanged - the consumer still needs its own paths. When the module has
  no source roots, the recorded dirs are used.
- **First publish seeds.** A library with no mapping baseline gets a ledger row and its baseline
  set to HEAD, with nothing stamped - stamping the library's entire history would be wrong.
- **Untracked coordinate skips.** The module must already be a tracked library (declared in a
  consumer's `tiaSourceLibs` and reconciled by a primary consumer run); when no schema in the
  database tracks it the task logs a warning, writes nothing and the publish succeeds.
- **Ownership gating.** Only a build that owns mapping-DB writes stamps (`tiaUpdateDBMapping` /
  `updateDBMapping` true). On a developer machine against a shared DB the task is a logged no-op -
  the local flow below covers development without persisted stamps.
- **Empty diffs still publish.** A rebuild with no source change writes a ledger row with an empty
  stamp; a consumer resolving it drains everything at or below it, all genuinely contained in the
  jar. Non-reproducible rebuild hashes are therefore benign.

### Consumer discovery

One library can be consumed from several branches or streams, each with its own schema, and from
several test tasks of one project, each with its own suffixed schema. The publish reaches all of
them without being told which exist. The only thing the publishing build shares with its consumers
is the database connection; its own branch and schema suffix select nothing, and no schema is
created for the publishing workspace.

For a publish on source line L:

1. **Discover.** List the schemas in the database named `tia_*` and keep those whose `tia_library`
   table holds a row for the published coordinate. That row is written by the consumer's own
   reconcile, so the set is exactly the consumers - nothing is declared, and a new consumer branch
   or test task is reached as soon as it has run once. Discovery is read-only: a schema that only
   shares the prefix is left untouched.
2. **Ledger row everywhere.** Every discovered schema records the publish (sequence, version, hash,
   commit, line L), so any build stays recognisable to any consumer.
3. **Stamp everywhere.** Every discovered schema also receives the method stamp and
   forced-selection batches, whichever line it follows. A schema following another line discards
   them at its next drain, or switches line if its consumer has moved to L. See
   [Source lines](#source-lines).
4. **Previous publish is per line, per schema.** Each schema runs its own baseline diff and its own
   "since the previous publish on L" filter, since baselines and sequences are per schema.

The two layouts this serves:

| Layout | Where the library source lives | What one publish does |
|---|---|---|
| Shared library stream | One stream; consumers on `mainline` and `future-development` both take its jars (the release job may build through a virtual stream) | One line. Both consumer schemas record the publish and receive the stamp; each drains it when its own build picks the jar up |
| Co-located library | Each consumer stream holds its own copy and builds its own jar | One line per stream. Every schema records the publish and receives the stamp; only the consumer holding a jar from the publishing stream's line runs tests for it, the others discard it at their next run |

Operational notes:

- **No tracking schema.** The publish logs a warning and succeeds - see "Untracked coordinate
  skips" above.
- **Not atomic.** Each schema is its own connection and transaction, and the artifact is already
  published. Every schema is attempted, then the build fails naming the schemas that hold the
  stamp and those that do not. Re-run the publish stamp once the cause is fixed.
- **Cost.** One metadata listing plus one indexed lookup per `tia_*` schema, then one stamp per
  consumer. It runs at publish time only; nothing is added to test selection. The stamps share
  their VCS work for the length of the publish: the source line is resolved once, and each diff,
  changed-paths query and content load is made once for all the schemas asking the same question.
  Schemas that hold the same build share one baseline and one previous publish, so the VCS work
  grows with the number of distinct baselines, not the number of schemas. Each schema still costs
  its own database reads and writes.

### Line-number correctness: the mapping baseline

The stamp's method intersection is only exact when the diff's original side uses the same line
coordinates as the mapping. Method line numbers in the mapping correspond to the commit at which
the library's covering suites last ran with coverage - so each `tia_library` row carries that
commit as its `mapping_baseline_commit`, and the publish task always diffs from it (not from the
previous publish, which would compound line drift and risk underselection).

The commit that matters is the library's, not the consumer's: the covering suites ran against
the jar the consumer holds, so the line numbers they captured are those of the source that jar
was built from. The baseline is therefore the commit the held build was **published** at, read
from its ledger row. The consumer's own sealed commit must not be used. It can sit past library
changes the held jar does not contain, and a baseline there hides those changes from every later
publish:

| Changelist | Event                                                        |
|------------|--------------------------------------------------------------|
| 480        | library released; the consumer picks this jar up             |
| 490        | library edited, not released                                 |
| 500        | consumer run drains the 480 release and seals                |
| 510        | library released, containing the 490 edit                    |

With the baseline at 480 the release at 510 diffs `480..510` and stamps the 490 edit. A baseline
of 500 would diff `500..510`, find nothing, and the consumer would upgrade to a jar containing an
untested change.

The baseline moves only when line numbers actually refresh, and only to a build the ledger knows:

- **A primary run that drained the library's stamps** (the drain ran its covering suites with
  coverage): to the resolved build's publish commit.
- **A forced all-tests run** (`tiaSelectAllTests`, `tiaReseed`): every suite is re-covered, so the
  drain identifies the held build of every tracked library, pending stamps or not, and each
  baseline moves to its build's publish commit. This is the one case where the drain resolves
  libraries with nothing pending; a normal selection still skips that work.
- **Held build not in the ledger** (unresolvable, never published with stamping, or a downgrade):
  the baseline stays where it is. The next publish diffs a wider range than strictly needed,
  never a narrower one. The exception is a library with no baseline at all, which takes the
  sealed commit on an all-tests run - the usual case being the first seed - so that its first
  publish stamps instead of seeding.

An all-tests run that was not forced (selection happened to ignore nothing) and has nothing
pending does not resolve held builds, so its baselines stay put - again the wider, safe direction.

The seal takes all of this from the drain result and the datastore. Identifying the held build
uses the build model, the jar on disk and the ledger, at selection time; nothing in the test run
or the seal reads the VCS, so runners without a repository are unaffected.

Between advances the baseline diff is cumulative - it re-reports every
method changed since the baseline on every publish - so a second diff filters that set down to what
is genuinely new for each publish, as the next section explains.

Known parity limitation: a brand-new library method has no coverage mapping until the consumer
re-covers it, so no stamp can select tests for it - the drain run that picks up the changed
methods re-covers and registers the new ones.

### Two diffs: identify, then filter

The cumulative baseline diff has a cost: a change stays in it until the consumer drains and the
baseline advances, so every publish in between re-reports the same methods. When those publishes
straddle the consumer's adoption - a common case, since a library is often released and
version-bumped several times before the app moves - the later re-stamps survive the drain and
re-run already-covered tests. The stamp is therefore computed from **two diffs with distinct
jobs**:

- **Diff 1 - identify (always `mapping_baseline_commit .. HEAD`).** Its original side sits on the
  mapping's commit, so its hunks line up with the mapping's stored method line ranges and the
  intersection yields *exact method ids*. It runs on every publish, is always method-precise, and
  is cumulative.
- **Diff 2 - filter (`previous publish commit .. HEAD`).** Used only to answer, per file, "did this
  file change at all since the last publish?" Its hunks are **never** mapped to methods: the
  previous-publish coordinates do not align with the baseline-based mapping ranges (an earlier edit
  may have shifted every line below it), so it can only be trusted at file granularity.

The recorded stamp is **the method ids from Diff 1, keeping only those whose file also appears in
Diff 2**. Diff 2 is purely a filter over the precise list Diff 1 produced - it never contributes a
method of its own, and the stored data is always method ids, never file names.

Worked example. `Calculator.java` has methods `add()` and `subtract()`; the consumer does not run
across this sequence, so the baseline stays put:

| Publish       | Source event                    | Diff 1 (from baseline) | Diff 2 (from prev publish)      | Stamp |
|---------------|---------------------------------|------------------------|---------------------------------|-------|
| 1.7-SNAPSHOT  | edit `add()`                    | `{add}`                | prev = baseline -> filter skipped | `{add}` |
| 1.7 (release) | version-only bump, same source  | `{add}`                | no source file changed          | `{}`  |
| 1.8-SNAPSHOT  | version-only bump, same source  | `{add}`                | no source file changed          | `{}`  |

The change is stamped once, on the publish that introduced it. A consumer that adopts 1.7 drains
that one stamp and runs `add`'s tests; moving to 1.8-SNAPSHOT later finds an empty stamp and runs
nothing. Under a cumulative-only model all three publishes would stamp `{add}`, and the
1.8-SNAPSHOT stamp would linger past the 1.7 drain to re-run those tests for nothing.

Two properties fall out of the file-granular filter:

- **It never misses.** A method is always stamped at the publish whose Diff 2 touches its file -
  that is the publish that actually edits it. The filter only ever *removes* a method already
  stamped by an earlier publish whose file has since been quiet.
- **It leaves a bounded redundancy.** If two different methods in the *same* file change across
  publishes that straddle a drain, the earlier method rides along when the later one's file
  re-appears in Diff 2 (the filter cannot sub-select methods within a file). The cost is one extra
  run of already-green tests, never a skipped test. Method-precise dedup would require computing
  method boundaries at HEAD (a mapping run at publish time), not worth it for that residual.

The first publish in each baseline window - the seed, and the first publish after a consumer on
the latest build drains and moves the baseline to that build's publish commit - has its previous
publish equal to the baseline, so Diff 2 is skipped and the publish stamps the full precise Diff 1.

### Library-declared forced selection

Method stamping (above) is coverage-driven: it can only select tests for library methods Tia
already tracks with line-level coverage. Some library changes need a coarser, user-declared
trigger instead - a database migration with no covering unit test, a config file that changes
behaviour Tia cannot see in bytecode. For that case a library can declare its own static test
selection rules, the same `tiaStaticTestSelectionRules` mechanism described in
[Static test selection](static-test-selection.md), and have them evaluated at publish time
rather than on every consumer run.

At publish time, alongside the method stamp, the publish task evaluates the library's own static
rules against the files it changed since its previous publish. A rule that matches records a
**forced-selection batch** - the rule's mode (`RUN_ALL` or `SUITE_NAMES`) and its suite-name
patterns - keyed to the same publish sequence as the method stamp, in the sibling
`tia_pending_library_forced_selection` table. The batch is written in the same transaction as the
ledger row and the method stamp, so it is subject to the identical version-gating: a SEEDED first
publish forces nothing, and a version-only or no-matching-change republish forces nothing either,
because the evaluation is scoped to files changed since the previous publish, mirroring the
method stamp's since-previous-publish dedup.

Two things about the evaluation are worth calling out:

- **Non-code files are visible.** The method stamp can only see files the coverage mapping
  tracks (source files with methods). The forced-selection evaluation instead runs against the
  library's unfiltered changed-paths diff - every file changed since the previous publish,
  regardless of type - so a rule can match a `.sql` migration, a YAML config, or any other file a
  method-level diff would never surface.
- **Matching is repo-wide; scoping is the rule's job.** The changed paths are not restricted to the
  library's own module directory. Every file changed since the previous publish - anywhere in the
  repository - is offered to each rule's `filePathPattern`. This is deliberate: a library may depend
  on a collaborating module in the same repo (for example a service it makes HTTP calls to) whose
  change should force the library's consumers to re-test even though it lives outside the library's
  own tree. A rule that should only fire on the library's own files must say so in its
  `filePathPattern` (e.g. anchor it to the module's path prefix). This mirrors how the source
  project's own static rules are evaluated repo-wide.

The consumer's drain resolves each pending forced batch exactly like it resolves method stamps:
gated on `publishSeq <= resolvedSeq` against the build the consumer actually holds, with the same
hold rules (unresolved build, downgrade). Once gated in, the batch is resolved against the
**consumer's current tracked suites**, not the library's:

- `RUN_ALL` resolves to every suite the consumer tracks.
- `SUITE_NAMES` resolves to the subset of the consumer's tracked suites whose simple class name
  or fully qualified name matches at least one of the rule's suite-name patterns.

The resolved suites are unioned into the run set alongside the tests selected from any method
stamps draining at the same sequence - a forced batch and a method stamp at the same seq both
apply; neither suppresses the other. Applying a forced batch is deliberately log-only (no separate
persisted record beyond the row's deletion), so a run's forced selections show up in its logs, not
as a second report artifact. Drained forced batches are deleted after the run, the same
crash-safe, overselection-only cleanup as method stamps.

Pending (not-yet-drained) forced batches are visible ahead of a drain in the same places as
pending method stamps: the `library-pending-methods` / `tia-library-pending-methods`
task/mojo lists each library's pending forced-selection batches (publish seq, version, rule name,
mode and patterns) alongside its pending methods, and the HTML `tia-libraries.html` page renders
them in a "Pending forced selections" section.

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
  (`last applied publish seq`, mapping baseline, followed source line) and its pending batches
  (seq, version, method count).
- `library-publishes` (`-DtiaLibrary=group:artifact` on Maven, `--library=group:artifact` on
  Gradle's `tia-library-publishes`) prints the library's ledger as a table: seq, version, jar
  hash, commit, publish time, how many of the build's stamped methods still await drain, and the
  source line the build was published from.
- `library-pending-methods` / `tia-library-pending-methods` prints one row per pending method:
  the publish it shipped in (seq + version) and the method's tracked name and line range.
- The HTML `tia-libraries.html` page renders all three: tracked libraries, the publish ledger and
  the pending changes.

### Edge cases and operational notes

- **Forced runs and re-seed.** A `tiaSelectAllTests` / `tiaReseed` run still drains, so it deletes
  exactly the stamps it applied. A re-seed never clears the pending tables: stamps above the
  resolved sequence are for builds the app has not picked up yet, and method ids are name hashes, so
  they stay valid after the mapping is rebuilt - see
  [Forced runs and re-seed](forced-runs-and-reseed.md).

- **Ledger retention.** Ledger rows are never deleted automatically (only the cascade when a
  library is removed from `tiaSourceLibs`). Rows at or below `last_applied_seq` are still read -
  the steady state looks up the currently-applied build on every run, and stale-resolve/downgrade
  detection needs older rows to distinguish "known older build" from "build Tia has never seen".
  Rows above `last_applied_seq` must never be pruned - they identify builds not yet consumed.
- **Duplicate ledger matches** (identical artifact republished): the lookup takes the highest
  matching seq - contents are identical or cumulative, so the higher seq drains a superset, the
  safe direction.
- **Several release lines of one library** (a `1.x` hotfix line alongside `2.x`, or a copy of the
  library per stream): each consumer schema follows the line it takes builds from and runs
  tests only for that line's stamps; the others' are discarded at its drain. Moving a consumer to
  another line runs all of its tests once and re-points it; see [Source lines](#source-lines).
- **A pinned consumer** accumulates stamps by design and drains them in one catch-up when the
  dependency finally moves; union semantics dedupe repeated methods.
- **Library commits not yet published** are kept out of the app run's source selection and
  select nothing - correct, the resolved jar does not contain them - and the publish task's
  baseline diff stamps them later. Outside `checkLocalChanges` mode the app run does not diff the
  tracked libraries' source dirs at all, so no listing or content fetch is spent on library files
  it would discard. The library-diff partition stays as a guard for configured source dirs that
  themselves cover a tracked library. The recorded `tia_library` source dirs are therefore read by
  the app run only in local-changes mode and by that partition. The app's commit cursor and the
  mapping baseline are independent - the baseline is a library publish commit, never the app's
  sealed commit - so no coordination or timing window exists.
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
- **Order the builds of different lines by version number**: would let one sequence span a `1.x`
  and a `2.x` line, but a version says nothing about which source a build contains - a `1.4.1`
  hotfix published after `2.0.0` is not contained in it - and snapshots of one version carry no
  order at all. Lines are kept apart instead and a move between them runs everything once.
- **Decide containment from VCS ancestry** (is the publish commit an ancestor of the held build's):
  Git-only - Perforce has no ancestry between streams' changelists - and it would put a VCS read on
  the consumer's drain, which today needs none.
- **Match by jar hash alone, without a sequence**: cannot order snapshot builds, so a stale
  resolve (an older cached jar) would drain newer stamps against a jar that does not contain
  them - a silent false green. The sequence makes that impossible.

---

[Back to the Wiki index](../WIKI.md) | Next: [How Tia exchanges data with the test runner](test-runner-data-exchange.md)
