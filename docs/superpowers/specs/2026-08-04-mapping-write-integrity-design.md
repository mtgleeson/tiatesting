# Mapping write integrity: keeping mapping data consistent with the stored commit

Date: 2026-08-04
Status: Design approved, pending spec review

## Why this is its own spec

All three problems below were found while analysing the persist path for the
[distributed test runs](2026-08-04-distributed-test-runs-design.md) design. None is caused by
distribution:

- The id allocation bug bites any two concurrent writers, which already includes the known
  Gradle multi-fork case.
- The catalogue misalignment affects every single-host run that crashes before the seal.
- Unsealed suite mapping rows affect every single-host run that crashes before the seal.

They are prerequisites for distributed runs but stand on their own, and they should ship first
and independently. Fixing them closes real under-selection windows in current Tia.

## The shared theme: what can be ahead of the stored commit

`TestRunnerService.persistTestRunData` seals the commit value last, so that *if commit X is the
stored value, every mapping write for X has completed*. The converse is not controlled: after a
crash before the seal, several tables hold data captured at a **later** commit than the one
`tia_core` claims. The persist-flow chapter calls this Category A and describes it as
self-correcting. It is not, entirely.

| Written pre-seal | Ahead of the commit means | Verdict |
|---|---|---|
| `tia_test_suite`, `tia_source_class`, `tia_source_class_method` | a suite's edges describe commit B under a stored commit of A | **Problem C** |
| `tia_source_method` | line ranges are in B's coordinate space while the diff runs from A | **Problem A** |
| `tia_test_suites_failed` | the failed set reflects B | Safe. Over-inclusion just force-runs extra suites; the chapter's existing recovery argument holds. |
| `tia_library` (`mapping_baseline_commit`, `last_applied_seq`), pending library tables | the library baseline claims B while the catalogue is at A | Folded into Problem A's fix (see below) |

Problems A and C are both closed here. The failed set needs nothing.

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

Reorder the sequence so the catalogue write, the library drain cleanup and the seal are one
transaction:

```
1. updateTestSuiteMapping        (suite mapping rows)
3. updateTestSuitesFailed
2+4+5. [ method catalogue + drain cleanup + commit value ]  <-- one transaction
6. persistTestRunHistory
```

Then the catalogue can never be ahead of the commit. Either both land or neither does. A crash
leaves the catalogue and the stored commit aligned at the prior value, with only the suite
mapping rows ahead - which Problem C then handles.

The drain cleanup joins the same transaction because `updateAppliedLibraryState` and
`advanceAllMappingBaselines` both set `mapping_baseline_commit` to the commit being sealed. Left
outside, a crash would leave the library baseline claiming B while the catalogue it is supposed
to correspond to sits at A. It is a handful of rows, so the transaction stays small.

Why this is cheap: `persistSourceMethods` already wraps its `TRUNCATE` + `INSERT` in a single
transaction, and `persistCoreData` is a single-row `INSERT`/`UPDATE`. Merging them adds one row
to a transaction that already carries the whole catalogue, so the MVStore undo-log profile is
unchanged. This is explicitly *not* the "wrap all of `persistTestRunData` in one transaction"
option the persist-flow chapter rejects - the bulk suite mapping writes stay outside.

Dependency check for the reorder: `updateMethodsTracked` reads
`SELECT DISTINCT tia_source_method_id FROM tia_source_class_method`, so it must stay after step
1. It has no relationship to step 3, so moving it after that is safe. The drain cleanup keeps its
position relative to the seal, now by being in the same transaction rather than merely preceding
it.

### API impact

`persistSourceMethods`, the drain cleanup calls and `persistCoreData` each manage their own
connection today. Making them atomic requires one combined `DataStore` operation rather than
several independent calls. Per project convention there is no backwards-compatibility shim: the
existing methods are replaced by the combined one and all callers are updated in the same change.

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

## Problem C: suite mapping rows can be ahead of the stored commit

### The mechanism

Step 1 writes a suite's edges whenever that suite ran with coverage. Nothing records *which*
commit they were captured at - Tia infers it from `tia_core.commit_value`. After a crash before
the seal, that inference is wrong for exactly the suites that ran.

### The consequence

Stored commit A. A run at B rewrites suite S's edges; S's coverage no longer includes method M,
so the S→M edge is removed. The build fails before the seal, so the stored commit stays A.
Commit C restores the behaviour. The next build diffs A→C, sees M change, but the mapping says S
does not cover M, so S is not selected. **Under-selection.**

There is a structural argument that this usually self-heals: for S to stop covering M it must
have taken a different path, and the branch deciding that lives in code S still covers, so the
restoring change at C lands in a method S still has an edge to and selects S that way. It holds
in the common case but has real counterexamples - dispatch driven by untracked files
(`.properties`, DI wiring, reflection), or a caller deleted at B and restored at C. It is an
argument, not a guarantee.

### The fix: mark suite mapping rows unsealed until the seal clears them

Add `unsealed` (boolean) to `tia_test_suite`:

- Set `true` in the per-suite upsert whenever a run writes that suite's edges. It rides along on
  an upsert that already happens, so the write is free.
- Cleared inside the seal transaction with a single `UPDATE ... WHERE unsealed = true`, bounded
  by the suites this run wrote.
- `TestSelector` force-selects any suite with `unsealed = true`, alongside
  `addPreviouslyFailedTests`.

Behaviour: a crash before the seal leaves exactly the suites that ran flagged, and the next build
force-runs them, recaptures their coverage against its own commit, and seals. Suites Tia ignored
are never touched and stay `false`. If the next build also fails to seal they stay flagged, so
the mechanism is idempotent. A seed run flags every suite and the seal clears them all.

### Why not stamp the commit value and compare

The obvious design - store the capture commit and force-run when it differs from
`tia_core.commit_value` - is wrong, and wrong in a way that destroys selectivity rather than
failing loudly.

Ignored suites legitimately keep older stamps. Run 1 seals commit A and stamps all 1000 suites A.
Run 2 selects 10 suites at commit B, stamps those 10 B, and seals commit B. Now 990 suites are
stamped A under a stored commit of B, and an inequality test force-runs all of them - on that
run and every run after it.

The predicate that is actually wanted is "written but not yet sealed", which is not the same as
"different from the stored commit". Recovering the former from the latter needs commit *ancestry*
(is the stamp a descendant of the stored commit), which means `git merge-base` per suite and a
different mechanism again for Perforce changelists. A boolean expresses the intent directly and
costs nothing.

The capture commit is still stored, as `unsealed_commit`, but for diagnostics only - so
`tia-status` can report which commit the unsealed edges came from. It never drives selection.

### Interaction with distributed runs

Each runner sets the flag on its own suites; the sealer's `WHERE unsealed = true` clears every
runner's, since it runs after the barrier. If any runner dies and the run never seals, precisely
the suites that completed stay flagged and the next build force-runs those and nothing else.

This is what turns the distributed failure story from an argument into a mechanical guarantee,
which matters because unsealed builds get more likely as the runner count grows.

Caveat: a concurrent primary build on the same branch could have its flags cleared by another
build's seal. Schema-per-branch already isolates branches, and running concurrent primary builds
on one branch is already advised against; noted rather than defended against.

## Testing

Per project convention, `// given` / `// when` / `// then` throughout.

- Catalogue/seal atomicity: a failure injected during the catalogue insert leaves the catalogue,
  the library baseline and the stored commit at their prior values; a successful run advances all
  three.
- Reorder safety: the catalogue still sees step 1's edges.
- Block allocation: concurrent writers receive disjoint ranges; a dead writer's block is skipped
  without reuse; assigned ids never collide with the identity sequence.
- Unsealed flag: set by a mapping write; cleared by the seal; survives a crash before the seal;
  force-selects flagged suites; **ignored suites are not flagged after a partial run seals**
  (the regression the commit-comparison design would have caused); idempotent across two
  consecutive unsealed runs; a seed run flags then clears everything.
- Both dialects: H2 and Postgres.

## Delivery stages

1. Source-class id block allocation, replacing `MAX(id) + 1` and the `RESTART WITH` DDL.
2. Catalogue/seal atomicity: the combined `DataStore` operation and the persist reorder.
3. The `unsealed` flag: schema, the persist write, the seal clear, the selection rule, and the
   `tia-status` diagnostic.

Then the correction to the Category A section of `wiki/persist-flow-and-crash-safety.md`, which
depends on stages 2 and 3 both landing.

Stage 1 is self-contained. Stage 2 changes a `DataStore` signature. Stage 3 changes selection
behaviour. Each wants its own review.

## Relationship to distributed test runs

The distributed design depends on all three, and extends two of them:

- **Problem A**: with N runners there is no single process holding the run's method trackers, so
  the catalogue write moves to the sealer and runners stage their trackers in a per-run table.
  The atomicity established here is what makes that safe - the sealer performs the catalogue
  write, the drain cleanup and the seal as one transaction, exactly as a single-host run does.
- **Problem B**: concurrent runners cannot persist suite mapping rows at all without disjoint id
  blocks.
- **Problem C**: runners flag their own suites and the sealer clears all of them after the
  barrier, which is what makes an unsealed distributed build recover exactly the suites that ran.

## Non-goals

- Wrapping the whole of `persistTestRunData` in one transaction. Still rejected, for the reasons
  in the persist-flow chapter. The transaction added here covers the catalogue, the drain cleanup
  and the seal; the bulk suite mapping writes stay outside it.
- Spurious edge removal - a suite observing less coverage for non-code reasons. Judged out of
  Tia's control: the execution path from a test should be deterministic given unchanged source
  and test code, and tests should not be order-dependent. Recorded in
  `docs/notes/2026-08-04-edge-removal-under-selection.md` for the record, not scheduled.
- Commit-ancestry-aware selection. See "Why not stamp the commit value and compare".
