# Database schema (tables and relationships)

Tia stores everything in a single H2 database (embedded file or server mode) - or the equivalent
Postgres schema, see the [pluggable datastore](pluggable-datastore.md) chapter. All DDL lives in
`JdbcDataStore` (`createTiaDB` plus the `buildCreate*TableSql` / `ensure*` helpers). The tables
fall into four clusters: the **mapping** cluster (what test covers what code - the bulk of the
data), the **library-impact** cluster (see the
[library publish-time stamping](library-publish-time-stamping.md) chapter), the **distributed-run**
cluster (see the [distributed test runs](distributed-test-runs.md) chapter), and a few
**standalone** header/audit tables, one of which - `tia_id_block` - exists purely to allocate ids,
not to store mapping or audit data.

The distributed-run cluster is the only one whose rows are transient: they describe one in-flight
build, and the next plan write for the branch clears them.

```mermaid
erDiagram
    tia_test_suite ||--o{ tia_source_class : "covers"
    tia_source_class ||--o{ tia_source_class_method : "edges"
    tia_source_method ||--o{ tia_source_class_method : "covered by"
    tia_library ||--o{ tia_library_publish : "FK (cascade)"
    tia_library ||--o{ tia_pending_library_impacted_method : "FK (cascade)"
    tia_library_publish ||--o{ tia_pending_library_impacted_method : "by publish seq"
    tia_source_method ||--o{ tia_pending_library_impacted_method : "by method id"
    tia_distributed_run ||--o{ tia_distributed_run_group : "by run id"
    tia_distributed_run_group ||--o{ tia_distributed_run_group_suite : "by run id + group"
    tia_distributed_run ||--o{ tia_distributed_run_method_stage : "by run id"

    tia_core {
        VARCHAR commit_value PK
        VARCHAR branch
        TIMESTAMP last_updated
        BIGINT num_runs
        BIGINT avg_run_time
        BIGINT num_success_runs
        BIGINT num_fail_runs
        BIGINT all_tests_run_time
        BIGINT num_all_tests_runs
        BIGINT fixed_overhead_ms
        BIGINT capture_overhead_per_suite_ms
        BIGINT num_overhead_measurements
    }

    tia_test_suite {
        BIGINT id PK
        VARCHAR name
        VARCHAR source_filename UK
        BIGINT num_runs
        BIGINT avg_run_time
        BIGINT num_success_runs
        BIGINT num_fail_runs
        BOOLEAN developer_disabled
        BOOLEAN unsealed
    }

    tia_source_class {
        BIGINT id PK
        BIGINT tia_test_suite_id FK
        VARCHAR source_filename
    }

    tia_source_method {
        INT id PK
        VARCHAR method_name
        INT line_number_start
        INT line_number_end
    }

    tia_source_class_method {
        BIGINT tia_source_class_id PK, FK
        INT tia_source_method_id PK, FK
    }

    tia_test_suites_failed {
        VARCHAR test_suite_name PK
    }

    tia_test_run_history {
        VARCHAR id PK
        BIGINT run_timestamp
        VARCHAR branch
        VARCHAR commit_value
        INT num_suites_ran
        INT num_suites_ignored
        INT num_suites_failed
        BIGINT duration_ms
        BOOLEAN updated_db_mapping
        BIGINT time_savings
        INT savings_percent
    }

    tia_library {
        VARCHAR group_artifact PK
        VARCHAR project_dir
        VARCHAR source_dirs_csv
        VARCHAR mapping_baseline_commit
        BIGINT last_applied_seq
    }

    tia_library_publish {
        VARCHAR group_artifact PK, FK
        BIGINT publish_seq PK
        VARCHAR published_version
        VARCHAR jar_hash
        VARCHAR commit_value
        BIGINT published_at
    }

    tia_pending_library_impacted_method {
        VARCHAR group_artifact PK, FK
        VARCHAR stamp_version
        BIGINT publish_seq PK
        INT tia_source_method_id PK, FK
    }

    tia_id_block {
        VARCHAR block_name PK
        BIGINT next_value
    }

    tia_distributed_run {
        VARCHAR run_id PK
        VARCHAR branch
        VARCHAR commit_value
        VARCHAR status
        INT group_count
        BIGINT target_run_time_ms
        BIGINT estimated_total_ms
        BIGINT created_at
        VARCHAR sealed_by
        BIGINT sealed_at
        BLOB drain_result
        BOOLEAN seed_run
    }

    tia_distributed_run_group {
        VARCHAR run_id PK
        INT group_number PK
        VARCHAR status
        VARCHAR runner_key
        BIGINT claimed_at
        BIGINT completed_at
        BIGINT estimated_ms
        BIGINT actual_duration_ms
        INT suites_ran
        INT suites_failed
        INT suites_observed
        BIGINT suites_duration_ms
    }

    tia_distributed_run_group_suite {
        VARCHAR run_id PK
        INT group_number PK
        VARCHAR test_suite_name PK
    }

    tia_distributed_run_method_stage {
        VARCHAR run_id PK
        INT id PK
        VARCHAR method_name
        INT line_number_start
        INT line_number_end
    }
```

(`tia_core`, `tia_test_suites_failed`, `tia_test_run_history` and `tia_id_block` carry no foreign
keys - they are linked only logically, by commit / branch / suite name, or - for `tia_id_block` -
not linked to other rows at all; it is consulted, not joined against. The four
`tia_distributed_run*` tables carry no declared foreign keys either - they are linked by `run_id`,
and the plan write clears all four as a set before inserting, rather than relying on cascades.)

### Table purposes

- **tia_core** - single-row header: the sealed `commit_value` the mapping is valid for, the branch,
  and the Tia-level aggregate run stats (selected-run average `avg_run_time`, full-suite baseline
  `all_tests_run_time`, run/success/fail counts). It also carries the two-part overhead model -
  `fixed_overhead_ms` (what a test JVM costs to start, charged once per runner) and
  `capture_overhead_per_suite_ms` (JaCoCo's per-suite dump), as rolling averages over the
  `num_overhead_measurements` distributed builds that measured them. All three are `0` until a
  project runs its first distributed build, which is the signal for the estimate to fall back to
  the older single-number overhead. See the "The two-part overhead model" section of the
  distributed test runs chapter.
- **tia_test_suite** - one row per tracked test suite: name, source file, per-suite run stats, the
  `developer_disabled` flag (suite disabled in source by the developer, not ignored by Tia), and the
  `unsealed` flag - set when this suite's mapping edges were written by a run whose seal has not
  yet completed, cleared by the next seal. See the "Persist flow and crash safety" chapter.
- **tia_source_class** - the source classes a given suite exercises; the first hop of the
  suite -> class -> method coverage mapping (`tia_test_suite_id` points back to the suite).
- **tia_source_method** - catalogue of every tracked source method with its line range; the unit of
  change-impact analysis.
- **tia_source_class_method** - the join table holding the coverage **edges** (which methods each
  tracked source-class row covers). This is the bulk of the database - millions of rows on a large
  project.
- **tia_test_suites_failed** - the set of suites with a pending failure, force-re-run on the next
  selection ("Running previously failed tests").
- **tia_test_run_history** - audit log: one row per run (timestamp, branch, commit, ran/ignored/
  failed counts, duration, frozen per-run savings). Drives the `history` task and HTML History tab.
- **tia_library** - tracked in-repo libraries for library-impact analysis: declared coordinates and
  source dirs (config-owned), the `mapping_baseline_commit` the publish stamper diffs from, and the
  `last_applied_seq` high-water mark used for downgrade warnings and reporting.
- **tia_library_publish** - the publish ledger: one row per published build of a tracked library,
  ordered per library by the `publish_seq` assigned at publish time. Gives builds the total order
  that version strings (shared across SNAPSHOT builds) and jar hashes (opaque) cannot provide.
- **tia_pending_library_impacted_method** - source methods impacted by a library publish, keyed by
  the publish sequence they shipped in (`stamp_version` is display-only) and awaiting "drain" once
  the consuming project resolves a build at or past that sequence (FK to `tia_library`,
  `ON DELETE CASCADE`).
- **tia_id_block** - one row per named id counter (currently just `tia_source_class`), holding the
  next id to hand out. `allocateSourceClassIdBlock` locks a counter row with `SELECT ... FOR UPDATE`
  and advances it by the size of the block a writer needs, so concurrent writers reserve disjoint
  id ranges instead of both computing the same `MAX(id) + 1` and colliding on the primary key.
- **tia_distributed_run** - one row per distributed run, keyed by the user-supplied `run_id`: the
  branch and commit the plan was built from (authoritative for the seal), the run's `status`
  (`OPEN` / `SEALED`), the plan's shape, and `sealed_by` / `sealed_at` - the election record whose
  `IS NULL` predicate is what makes exactly one runner the sealer. `drain_result` carries the
  library-impact drain the plan computed, for the sealer to apply once. `seed_run` records that the
  planner collapsed this run to a single group with no suite names because the branch had no stored
  mapping yet - the seal reads it to tell that build (which ran everything and ignored nothing) from
  a nothing-impacted one, whose groups carry empty suite lists too but which ignored every tracked
  suite. Nothing else in the row separates the two, which is why the planner's answer is stored
  rather than re-derived.
- **tia_distributed_run_group** - one row per group: its `status` (`PENDING` / `CLAIMED` /
  `COMPLETED`), the `runner_key` that claimed it, the planner's `estimated_ms`, and the progress
  figures each persist accumulates. `suites_observed` is the one the completeness guard reads -
  see the "Distributed test runs" chapter for why it is not `suites_ran`. `suites_duration_ms` is
  the share of `actual_duration_ms` that went on named suites; the remainder is the runner's fixed
  per-JVM overhead, which the sealer charges once for the build rather than once per group. Indexed
  on (`run_id`, `status`) for the claim's lowest-`PENDING` lookup.
- **tia_distributed_run_group_suite** - the plan's assignment: which suite names belong to which
  group. Also the denominator the completeness guard counts.
- **tia_distributed_run_method_stage** - staged method trackers from every runner, held until the
  sealer rebuilds `tia_source_method` from them. Staged rather than written directly because no
  single runner sees the whole build's methods.

The mapping read path runs this chain in reverse: a code change resolves changed files to
`tia_source_method` ids, those to the covering `tia_source_class_method` edges, and those up to the
`tia_test_suite`s that must run.

---

Prev: [The select-tests run-time estimate and its overhead model](select-tests-run-time-estimate.md) | [Back to the Wiki index](../WIKI.md) | Next: [Persist flow and crash safety](persist-flow-and-crash-safety.md)
