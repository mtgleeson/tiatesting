# Design: Speed up the seed / large mapping persist

Date: 2026-06-26
Status: Approved

## Problem

On a large project, a full seed run took ~4 hours to execute the tests and then **~30 minutes to
persist the mapping to the DB**. The persist time is almost entirely the inserts into
`tia_source_class` (~979K rows) and `tia_source_class_method` (~5.86M rows).

Root causes, in order of impact:

1. **`tia_source_class` is inserted one row at a time** via `Statement.executeUpdate(sql,
   RETURN_GENERATED_KEYS)` (`H2DataStore.persistTestSuiteClasses`). That is ~979K separate
   parse-plan-execute-fetchKeys round trips. It is forced to be row-at-a-time because each class's
   *auto-generated* id is needed immediately to insert that class's edge rows.
2. **A fresh `PreparedStatement` is created per class** for the edge inserts
   (`persistTestSuiteClassMethods` calls `connection.prepareStatement(...)` every call - ~979K
   prepares) instead of reusing one across the load.
3. **Secondary-index maintenance during the bulk load.** `tia_source_class` carries its PK plus
   indexes on `tia_test_suite_id` and `source_filename`; `tia_source_class_method` carries its PK
   plus an index on `tia_source_method_id`. Maintaining those B-trees per row across millions of
   inserts is expensive (the project history already notes "full-mapping persist 6x slower from the
   two targeted-query indexes; drop/recreate is the flagged follow-up").
4. A `commit` per suite (many MVStore flushes).

(Independent confound: the example logs were at DEBUG, with a `log.debug` per class and per edge.
Re-measuring at INFO is worth doing to isolate the real DB cost, but the structural issues above
stand regardless.)

## Goal

Cut the large/seed mapping persist from ~30 minutes to single-digit minutes, without regressing the
fast incremental persist (a few suites re-run) or its crash semantics, and without breaking concurrent
`select-tests` readers in server mode.

## Approach: two-tier (Approach A)

Two distinct regimes, sharing one insert engine:

- **Tier 1 - always (seed + incremental):** replace the row-by-row `Statement` inserts with
  **chunked multi-row `INSERT ... VALUES (?,?),(?,?),...`** (1000 rows per statement) using
  application-assigned ids. This collapses the per-row network round trips that dominate a remote
  (server-mode) persist. No index or concurrency impact, so it is safe for the concurrent-reader
  incremental case.

  **Key finding (measured):** H2's `PreparedStatement.executeBatch()` does **not** pipeline over the
  wire - it sends one round trip per row, same as individual `executeUpdate`s. A first attempt using
  `executeBatch` cut only the per-class `prepareStatement`/`getGeneratedKeys` round trips and the
  per-row logging (30 min -> 17.5 min in server mode), not the per-row `INSERT` round trips. Local
  H2-server benchmark, 200K rows: `executeBatch` 6847ms vs multi-row 429ms (~16x). So multi-row
  `INSERT`, not `executeBatch`, is the mechanism.
- **Tier 2 - seed only:** additionally **drop the secondary read-indexes**, bulk-load, then
  **recreate** them. This removes the per-row index maintenance, confined to the run where it is
  safe (a seed runs effectively alone; incremental builds have concurrent readers, and dropping a
  global index there would both block readers and be a net loss since recreate rebuilds the whole
  table's index for a partial change).

Rejected alternatives:
- **Batch only, never touch indexes** (Tier 1 everywhere, no Tier 2): simpler and fully
  concurrency-safe, but the seed still pays index maintenance during the load - faster than today,
  slower than the two-tier design on the seed.
- **Seed-only bulk mode** (Tier 2 only, leave incremental untouched): narrowest blast radius, but
  large incremental persists stay slow.

## The seed gate

Seed detection is internal to `persistTestSuites` (no new parameter, no signature threading): a
single `SELECT MAX(id) FROM tia_source_class` returning `NULL` means the table is empty, which means
this is a **seed** (full rebuild). The same query does double duty - it also yields `nextId`
(`MAX(id)+1`, or `1` when null) for the Tier 1 application-side id allocation.

`MAX(id) IS NULL` -> Tier 2 bulk path; otherwise -> incremental path. Both use the Tier 1 engine.

This avoids rippling a `seed` boolean through the `DataStore` interface, `TestRunnerService`, and the
test call sites. The trade-off is that a crashed-seed *retry* (which leaves `tia_source_class`
non-empty) takes the incremental path instead of the fast bulk path - correct but slower, and rare.

## Tier 1: chunked multi-row inserts with application-assigned ids

Replaces the insert bodies of `persistTestSuiteClasses` / `persistTestSuiteClassMethods`.

- Read `MAX(id)` from `tia_source_class` once at the start: `NULL` -> seed and `nextId = 1`;
  otherwise `nextId = MAX(id) + 1`. (This is the same read that gates seed vs incremental.)
- Build two **full-chunk** multi-row insert statements (1000 value tuples each), prepared once and
  reused for the whole persist:
  - `INSERT INTO tia_source_class (id, tia_test_suite_id, source_filename) VALUES (?,?,?),(?,?,?),...`
  - `INSERT INTO tia_source_class_method (tia_source_class_id, tia_source_method_id) VALUES (?,?),(?,?),...`
- Per suite (the existing MERGE on `tia_test_suite` still yields the suite id - suites number in the
  thousands, not the bottleneck): assign each of the suite's classes an explicit id app-side
  (`nextId++`), then insert the suite's class rows and edge rows via the chunked helper - full
  1000-row chunks go through the reused statement (one round trip each), and the suite's remainder
  (< 1000 rows) through a single one-off statement sized to it. No per-row `RETURN_GENERATED_KEYS`.
  A suite of ~200 classes / ~1200 edges becomes ~a handful of round trips instead of ~thousands.
- Keep the per-suite transaction (commit per suite) for atomicity; suites number in the thousands,
  so the commit count is fine.
- After all inserts: `ALTER TABLE tia_source_class ALTER COLUMN id RESTART WITH <next>` so any future
  auto-increment insert cannot collide. Safe because exactly one mapping writer ever inserts these
  rows (MAX+1 id allocation is therefore race-free across clients).

Inserting an explicit value into the `BIGINT AUTO_INCREMENT` id column is permitted by H2; the
`RESTART` keeps the identity sequence consistent afterward.

## Tier 2: seed bulk load (index drop / recreate, self-healing)

Reached only when `tia_source_class` is empty (`MAX(id) IS NULL`). Steps:

1. **Drop the secondary read-indexes** (`DROP INDEX IF EXISTS`):
   - `tia_test_suite_id_idx` and `idx_source_class_filename` on `tia_source_class`
   - `idx_source_class_method_method_id` on `tia_source_class_method`
   - **PKs are kept.** Dropping/rebuilding the 5.86M-row composite PK is riskier for little gain, and
     the in-memory data is already deduped (`MethodIdSet`, fresh class ids), so there is no
     uniqueness risk during the load.
2. **Bulk-load** via the Tier 1 engine.
3. **Recreate the indexes** using the existing `buildCreate*IndexSql` DDL (`CREATE INDEX IF NOT
   EXISTS`), so the targeted select-tests query indexes are restored.

No `TRUNCATE` is needed: the seed path is entered only when the table is already empty.

### Crash safety

DDL is auto-commit in H2, so a crash mid-seed can leave indexes missing and rows partial. This is
tolerated rather than prevented. The run never seals its commit value (seal-last), so the next run is
still a seed run by selection - but `tia_source_class` is now non-empty, so the persist takes the
**incremental** path: each suite's `DELETE`-then-reinsert cleans up the leftover rows (a re-seed runs
every suite), and `ensureSchema` (`CREATE INDEX IF NOT EXISTS` on every contact) restores any missing
index. The retry is therefore correct, just slower (it does not get the index-drop speedup).

**Atomicity:** the per-suite transaction is **kept** (each suite's DELETE + multi-row inserts commit
together), so per-suite crash semantics are unchanged from today on both paths. The only non-atomic
seed action is the index drop/recreate (DDL is auto-commit in H2), handled by the self-healing retry
above.

## Incremental path (unchanged semantics)

Keeps today's per-suite `DELETE` of the suite's classes + edges, then re-inserts via the Tier 1
multi-row engine, inside the same per-suite transaction. **No index DDL** - safe with concurrent
`select-tests` readers.

## Concurrency

- The seed runs effectively alone (no concurrent readers), so its index drop/recreate is safe.
- Incremental builds commonly run with concurrent readers, so they perform no index DDL - only the
  multi-row inserts, which are read-safe.
- Id allocation (`MAX(id)+1`) is race-free because exactly one build is ever the mapping writer.

## Testing

`given`/`when`/`then`, javadocs on new/changed methods, embedded-H2 temp-dir DB per test (matching
the existing `H2DataStore*Test` style):

- **Seed round-trip:** persist a small mapping on an empty DB -> read back via `getTiaData(true)` and
  assert suites/classes/methods match exactly; assert the three secondary indexes exist afterward
  (query `INFORMATION_SCHEMA.INDEXES`); assert the identity is reseated (a later insert gets a fresh,
  non-colliding id).
- **Crashed-seed retry:** pre-populate partial `tia_source_class` rows (non-empty table), run a
  full-mapping persist, assert it takes the incremental path and produces a clean correct result.
- **Incremental:** with a non-empty `tia_source_class`, re-persist one suite -> only that suite's
  classes/edges change, others intact, indexes untouched, ids do not collide.
- **Seed detection:** empty `tia_source_class` -> bulk path (indexes dropped then recreated);
  non-empty -> incremental (indexes untouched). Asserted via the index-presence behaviour.
- **App-side ids:** ids unique and contiguous from `MAX+1`; every edge references its class's assigned
  id; ids continue correctly across a second persist.
- The full existing suite must stay green (the persist path is widely exercised).

## Measurement

Add a `profileSeedPersist` harness under `tia-core/src/test/java/org/tiatesting/core/perf/` (sibling
to `profileSelectTests`) that builds a large synthetic in-memory mapping (~980K classes / ~5.86M
edges, with a bounded pool of distinct methods) and times a seed `persistTestSuites`. The harness
takes an optional server JDBC URL so it can measure **server mode** - which is essential, because the
round-trip cost only appears there (embedded has none).

Measured (local H2 TCP server, 940K edges): original row-by-row **38.3s** -> multi-row **3.8s**
(~10x on localhost; larger on a real-RTT network). Embedded full size (5.88M edges) is ~26s either
way - confirming round trips, not embedded insert cost, were the bottleneck. Target: well under a
minute for the seed on the real server.

## Delivery

New branch off `main`. Staged delivery with a review pause and a commit-message summary after each
stage:

1. **Tier 1 + measurement harness.** The `profileSeedPersist` harness, then the multi-row insert
   engine (app-side ids, reused full-chunk statements, per-suite transaction, identity restart)
   applied to both the seed and incremental paths, with indexes left in place. Tests + before/after
   numbers. This stage removes the per-row round trips and is the high-value fix.
   - Note: the first committed attempt (`9272d9f`) used `executeBatch`, which does not pipeline in H2
     server mode (one round trip per row); it was superseded by chunked multi-row `INSERT`.
2. **Tier 2 seed bulk.** Internal seed detection (`MAX(id) IS NULL`) and drop/recreate of the
   secondary indexes on the seed path. Tests + before/after numbers. **Deferred** - Stage 1 (multi-row)
   already addresses the dominant server-mode cost; Stage 2 saves only server-side index-build time
   and stays optional pending a real server-mode re-measure.
