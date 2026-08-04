# Mapping write integrity: catalogue/seal atomicity and source-class id allocation

Date: 2026-08-04
Status: Design approved, pending spec review

## Why this is its own spec

Both problems below were found while analysing the persist path for the
[distributed test runs](2026-08-04-distributed-test-runs-design.md) design. Neither is caused by
distribution:

- The id allocation bug bites any two concurrent writers, which already includes the known
  Gradle multi-fork case.
- The catalogue misalignment affects every single-host run that crashes before the seal.

They are prerequisites for distributed runs but stand on their own, and they should ship first
and independently. Fixing them narrows a real under-selection window in current Tia.

## Problem A: `tia_source_method` can be ahead of the stored commit

### The mechanism

`MethodImpactAnalyzer.getMethodsForImpactedFile` diffs the original file content, fetched at the
**stored commit**, against the new content. It parses the unified diff and takes the hunk's
**original-side** line numbers (`HUNK_DIFF_ORIG_LINE_START_GROUP_INDEX`), then overlaps those
against the stored `line_number_start` / `line_number_end` in `tia_source_method`.

So the stored line ranges must be in the same coordinate space as the stored commit. The persist
sequence does not guarantee that:

```
1. updateTestSuiteMapping        (suite mapping rows)
2. updateMethodsTracked          (method catalogue)   <-- written here
3. updateTestSuitesFailed
4. applyLibraryImpactDrainResult
5. updateTiaCoreData             (the seal)           <-- commit advances here
6. persistTestRunHistory
```

A crash between step 2 and step 5 - the Category A failure mode in
`wiki/persist-flow-and-crash-safety.md` - leaves the catalogue at commit B while the stored
commit is still A.

### The consequence

Method M spans lines 10-20 at A. Lines are inserted above it, so it spans 30-40 at B. The
crashed run leaves 30-40 stored under commit A. The next build diffs A→C, produces a hunk inside
M at A-coordinates near 10-20, compares it against the stored 30-40, finds no overlap, and does
not select M's covering suites.

**Under-selection**, bounded by the line drift: methods shorter than the drift can be missed,
longer ones still overlap because the analyzer tests for range overlap rather than containment.

This makes the persist-flow chapter's claim that Category A yields "a (possibly slightly
oversized) diff ... Self-correcting. No under-selection" optimistic. The claim holds for
coverage edges, which carry no coordinates, but not for the catalogue.

### The fix: make the catalogue write atomic with the seal

Reorder the sequence so the catalogue write and the seal are one transaction:

```
1. updateTestSuiteMapping        (suite mapping rows)
3. updateTestSuitesFailed
4. applyLibraryImpactDrainResult
2+5. [ method catalogue + commit value ]  <-- one transaction
6. persistTestRunHistory
```

Then the catalogue can never be ahead of the commit. Either both land or neither does. A crash
leaves the catalogue and the stored commit aligned at the prior value, with only the suite
mapping rows ahead - which is the dimension that genuinely is safe (see the edge analysis in the
distributed spec).

Why this is cheap: `persistSourceMethods` already wraps its `TRUNCATE` + `INSERT` in a single
transaction, and `persistCoreData` is a single-row `INSERT`/`UPDATE`. Merging them adds one row
to a transaction that already carries the whole catalogue, so the MVStore undo-log profile is
unchanged. This is explicitly *not* the "wrap all of `persistTestRunData` in one transaction"
option the persist-flow chapter rejects - the bulk suite mapping writes stay outside.

Dependency check for the reorder: `updateMethodsTracked` reads
`SELECT DISTINCT tia_source_method_id FROM tia_source_class_method`, so it must stay after step
1. It has no relationship to steps 3 or 4, so moving it after them is safe. The drain still
precedes the seal, so `mapping_baseline_commit` still points at the commit being sealed. A crash
after the drain but before the catalogue+seal leaves drained pendings with an unadvanced commit,
which is the existing "Library drain recovery" case the chapter already covers as convergent.

### API impact

`DataStore.persistSourceMethods` and `persistCoreData` each manage their own connection today.
Making them atomic requires one combined operation on the interface rather than two independent
calls. Per project convention there is no backwards-compatibility shim: the two methods are
replaced by the combined one and all callers are updated in the same change.

## Problem B: colliding `tia_source_class` id allocation

### The mechanism

`JdbcDataStore.persistTestSuites` reads `readMaxSourceClassId() + 1` into a local holder, assigns
`tia_source_class.id` application-side per row from that holder, and finishes with
`ALTER TABLE ... ALTER COLUMN id RESTART WITH <next>`.

Two concurrent writers read the same `MAX(id)` and hand out the same ids.

### The consequence

`id` is `BIGINT AUTO_INCREMENT PRIMARY KEY` on H2 and
`BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` on Postgres, so a collision surfaces as a
primary key violation. **Loud failure, not silent corruption** - which is the good outcome, and
worth stating explicitly because the alternative would have been coverage edges cross-linked to
the wrong suite.

Separately, `ALTER TABLE ... RESTART WITH` is DDL on the persist path. Concurrent DDL from
several sessions is its own hazard.

### The fix: atomic block allocation

Replace `MAX(id) + 1` with a reservation: each writer claims a disjoint id block up front via a
single conditional `UPDATE` on a counter row, then assigns from its own block. A writer that
dies leaves an unused block, which is a harmless gap in the id space.

This also removes the `RESTART WITH` DDL entirely, since no writer relies on the identity
sequence to produce the next value.

The application-side id assignment itself stays. It exists because it lets rows be inserted
`INSERT_CHUNK` at a time rather than one per row, which the seed-persist bulk-load work made
load-bearing for persist performance. Block allocation preserves that.

## Testing

Per project convention, `// given` / `// when` / `// then` throughout.

- Catalogue/seal atomicity: a failure injected during the catalogue insert leaves both the
  catalogue and the stored commit at their prior values; a successful run advances both.
- Reorder safety: the drain still applies before the seal; the catalogue still sees step 1's
  edges.
- Block allocation: concurrent writers receive disjoint ranges; a dead writer's block is skipped
  without reuse; assigned ids never collide with the identity sequence.
- Both dialects: H2 and Postgres.

## Delivery stages

1. Source-class id block allocation, replacing `MAX(id) + 1` and the `RESTART WITH` DDL.
2. Catalogue/seal atomicity: the combined `DataStore` operation, the persist reorder, and the
   correction to the Category A section of `wiki/persist-flow-and-crash-safety.md`.

Stage 1 is self-contained. Stage 2 changes a `DataStore` signature, so it wants its own review.

## Relationship to distributed test runs

The distributed design depends on both, and extends Problem A's fix: with N runners there is no
single process holding the run's method trackers, so the catalogue write moves to the sealer and
runners stage their trackers in a per-run table. The atomicity established here is what makes
that safe - the sealer performs the catalogue write and the seal as one transaction, exactly as
a single-host run does.

## Non-goals

- Wrapping the whole of `persistTestRunData` in one transaction. Still rejected, for the reasons
  in the persist-flow chapter.
- The coverage-edge removal vector noted in
  `docs/notes/2026-08-04-edge-removal-under-selection.md`. Separate concern, separate
  investigation.
