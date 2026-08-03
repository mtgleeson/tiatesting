# Library-declared static forced selection via publish stamping

Date: 2026-08-03
Status: Design approved, pending spec review

## Problem

Tia's static test selection rules (`tiaStaticTestSelectionRules`) and the tracked-library
publish/stamp/drain lifecycle are two independent subsystems that do not interact.

- The library publish stamp records only impacted tracked *methods*
  (`LibraryPublishStamper` -> `Set<Integer> sourceMethodIds`). Non-code files (`.sql`,
  `.properties`, templates, migration scripts) have no tracked methods, so they are dropped
  by `filterToTrackedFiles` and never stamped.
- Static rules run only against the consumer source project's own repo diff
  (`getChangedFilePaths`), so they cannot see a change that lives in a release-versioned
  library's own module.

Consequence: when a library ships a non-code change (for example a SQL migration) under a
release version, nothing forces the consumer's tests to run when the consumer bumps to that
version. The method-stamp net excludes non-method files, and static rules cannot see the
library.

A static rule on the consumer's dependency-declaration file (`pom.xml` / `build.gradle`) is a
partial workaround, but it fires on every unrelated bump and cannot be version-gated to the
build that actually contains the change.

## Goal

Let a tracked library declare that certain of its own file changes should force downstream
tests, and route that decision through the existing publish/stamp/drain mechanism so it stays
version-gated: the forced tests fire only once the consumer resolves a build that actually
contains the change.

## Chosen approach

The library owns the policy. At publish/stamp time the library evaluates its own
`tiaStaticTestSelectionRules` against its own changed files. For each matching rule it records
the rule intent - the mode plus suite-name patterns - as a forced-selection batch keyed to the
publish sequence. The consumer's drain resolves that intent against the consumer's current
tracked suites and unions the result into the run set.

Rejected alternatives:

- Encoding RUN_ALL as a sentinel (`*` / magic id) in the existing method-id column. The pending
  table is flat (one row per `(groupArtifact, publishSeq, methodId)`); a sentinel row can encode
  RUN_ALL but has nowhere to carry suite-name patterns, so SUITE_NAMES ("run certain tests")
  cannot be represented. Rejected.
- Stamping the library's changed file paths and evaluating consumer-side static rules at drain.
  A large library change produces an unbounded path list to carry in the stamp; impractical.
  Rejected. Policy therefore lives with the library, not the consumer.

## Design

### 1. Data model

New domain type `PendingLibraryForcedSelection`, keyed `(groupArtifact, publishSeq)` exactly like
`PendingLibraryImpactedMethod`:

- `groupArtifact` - `groupId:artifactId` of the tracked library.
- `stampVersion` - the version the batch's publish shipped under (display only; the drain keys on
  `publishSeq`).
- `publishSeq` - the publish-ledger sequence of the build this forced selection shipped in.
- `ruleName` - the matching rule's display name (logging / reporting).
- `mode` - `RUN_ALL` or `SUITE_NAMES`.
- `suiteNamePatterns` - the rule's suite-name patterns; empty for `RUN_ALL`.

New sibling table `tia_pending_library_forced_selection`, flat, one row per suite-name pattern
(RUN_ALL records a single row with a null/empty pattern). Columns mirror the existing pending
table plus `rule_name`, `mode`, `suite_name_pattern`. The existing
`tia_pending_library_impacted_method` table and `PendingLibraryImpactedMethod` type are
unchanged. A single publish can produce both a method batch and a forced-selection batch at the
same `publishSeq`; they drain together under the same gate.

`JdbcDataStore` and `SerializedDataStore` each gain create / read-all / read-by-library /
persist / delete methods for the new entity, mirroring the pending-method methods
(`readAllPendingLibraryImpactedMethods`, `persistPendingLibraryImpactedMethods`,
`deletePendingLibraryImpactedMethods`, and the batch-grouping in
`buildPendingBatchesFromResultSet`).

### 2. Stamp time (library build)

In `LibraryPublishStamper.stampPublish`, after computing `impactedMethods`, add a parallel step:

1. Read the library's own `tiaStaticTestSelectionRules`, threaded in via the publish-stamp task
   config (see section 4).
2. Compute the since-previous-publish, library-scoped changed paths. The stamper already resolves
   `previousPublishCommit` and the library `sourceDirs`. Call
   `vcsReader.getChangedFilePaths(previousPublishCommit, false)` (unfiltered, all file types -
   `getDiffFiles` extension-filters `.sql` out and must not be used here) and restrict to paths
   under the library's source dirs.
3. For each rule whose `filePathPattern` matches at least one of those paths, record a
   `PendingLibraryForcedSelection(mode, patterns)`.
4. Persist the forced-selection batch in the same publish transaction as the method stamp.

Semantics, consistent with the method stamp:

- The `SEEDED` outcome (first publish, no baseline yet) forces nothing.
- Since-previous-publish scoping deduplicates: a version-only or no-matching-change re-publish
  records no forced batch.
- A SQL-only release produces an empty method stamp (unchanged) plus one forced-selection batch.

Path-form reconciliation is the one implementation wrinkle: `getChangedFilePaths` returns
repo-relative, forward-slash paths, while the recorded library source dirs may be absolute. The
prefix filter must normalize both to the same form. The rule regex itself matches via
`Matcher.find()` (substring), so users continue to write patterns such as `\.sql$`.

### 3. Drain time (consumer build)

The consumer drain reads forced-selection batches and applies the identical hold rules and
`publishSeq <= resolvedSeq` gate used for method batches (unresolvable library, unknown build,
downgrade -> hold). This can live in `PendingLibraryImpactedMethodsDrainer` as a peer loop or a
sibling drainer sharing the gate; either way it runs in the same drain pass so the expensive
source-project library resolution happens once.

For each drained forced batch, resolve against the consumer's current `testSuitesTracked` by
reusing `StaticTestSelectionResolver`'s resolution logic:

- `RUN_ALL` -> all consumer tracked-suite keys.
- `SUITE_NAMES` -> consumer suites matching the patterns, via `SuiteNameIndex`.

Forced batches and method batches at the same seq both apply (union). RUN_ALL does not
short-circuit method resolution - unioning is already correct and avoids a special case for no
real saving.

Post-run cleanup deletes the drained forced-selection rows and advances the same
`lastAppliedSeq` / `mapping_baseline_commit` the method drain already advances, under the existing
`updateDBMapping` gate in `TestRunnerService`, so there is no double-drain. `LibraryImpactDrainResult`
is extended to carry drained forced-selection batch keys alongside the method batch keys.

Drain-time application is log-only for v1: an INFO line per drained forced batch naming the
library, seq, rule, mode, and resolved suite count. No new drain report.

### 4. Config surface and wiring

The library module already configures `tiaStaticTestSelectionRules` for its own selection. The
same config is threaded into the publish-stamp task (`TiaLibraryPublishesTask` and the Maven
publish-stamp mojo) and passed to `stampPublish`. No new user-facing config concept is
introduced: a library's existing static rules gain a second effect - forcing downstream tests on
publish. This is documented in the library publish-time stamping chapter of `WIKI.md`.

### 5. Reporting

Pending forced-selection batches (queued, not yet drained) are surfaced alongside the existing
pending-method batches in:

- the HTML library pending report (`LibraryPendingMethodsReportGenerator` /
  `HtmlLibraryReport`), and
- the `library-pending-methods` task output (Gradle `TiaLibraryPendingMethodsTask` and the Maven
  pending-methods mojo).

Each surfaced forced batch shows the library, publish seq, stamp version, rule name, mode, and
patterns. Drain-time application remains log-only (section 3).

## Testing

Given / when / then unit tests (matching the `tia-core/src/test` style):

- `LibraryPublishStamper`: records a forced batch when a non-code change matches a rule;
  suppresses it on a version-only re-publish (dedup); forces nothing on `SEEDED`; records both a
  method batch and a forced batch when a publish contains both a code and a matching non-code
  change.
- Drainer: RUN_ALL resolves to all consumer tracked suites; SUITE_NAMES resolves to the matching
  subset; the seq gate holds a forced batch whose seq is above the resolved build; cleanup deletes
  the drained forced rows; forced and method batches at the same seq union.
- Datastore round-trip for `tia_pending_library_forced_selection` in the
  `DatastoreEquivalenceTest` family (H2 and Postgres parity).

Every new or modified method gets a javadoc (purpose plus `@param` / `@return`).

## Out of scope for v1

- `ANNOTATIONS_TAGS` mode (already rejected at rule construction; unchanged here).
- A dedicated drain-time forced-selection report (log-only for v1).
- Consumer-declared forcing against stamped library paths (rejected approach).
