# Unified schema-per-branch datastore isolation - design

Date: 2026-07-31
Status: design approved, pending spec review
Branch: `feature/datastore-schema-per-branch` (stacked on `feature/pluggable-datastore`)

## Problem

Tia isolates each VCS branch's mapping into its own place so that concurrent branches do not clobber
each other's data. Today the two SQL backends do this differently and inconsistently:

- **H2** uses a **database per branch**, driven by a `{branch}` token the user writes into the JDBC
  URL (`jdbc:h2:tcp://host/{branch}-project` -> `.../tiadb-<branch>-project`). H2 auto-creates the
  database on connect, so this "just works".
- **Postgres** (added by the pluggable-datastore work) has **no per-branch isolation at all**: the
  URL is used verbatim, the mapping tables are not branch-scoped, so multiple branches sharing one
  Postgres database would overwrite each other's mapping.

This is inconsistent, and it forces the user to encode the branch into per-project config even though
Tia already resolves the current branch from Git. We want one model across both backends, with no
branch token in config.

## Goals

- **One isolation model for both H2 and Postgres: schema per branch**, within a single database.
- **No `{branch}` token.** Tia derives the branch it already resolves from the VCS reader, computes a
  schema name, and creates/selects that schema automatically. `tiaDBUrl` is a plain database URL that
  does not change between branches.
- **Only schema-create privilege required** in the normal case (no `CREATEDB`).
- **No H2 read-path performance regression** (the perf-critical select-tests path).

## Non-goals (this effort)

- **Postgres database auto-creation** - deferred to a follow-up stage. For v1 the configured Postgres
  database must already exist; Tia creates the per-branch *schema* within it, not the database.
- MySQL (still a seam), connection pooling, and making the mapping tables carry a branch column
  (isolation stays namespace-based, as it is today for H2).
- A user-facing schema-name override (`tiaDBSchema`) - the branch-derived name is the point; can be
  added later if needed.

## Design

### Schema naming
The per-branch schema name is `tia_` + a sanitized branch name: lowercased, every character outside
`[a-z0-9_]` replaced with `_`, clamped to the 63-character identifier limit (Postgres; H2 is more
lenient but the same clamp keeps the two identical). The `tia_` prefix guarantees a valid identifier
(never starts with a digit), avoids colliding with `public`/existing schemas, and namespaces Tia's
objects clearly. Example: branch `feature/Foo-Bar` -> schema `tia_feature_foo_bar`. A shared
`BranchSchema.schemaName(branch)` helper owns this, with unit tests for the edge cases (slashes,
uppercase, leading digit, over-length, empty).

### Selecting and creating the schema
Two operations on connection acquisition, kept cheap on the hot read path:

1. **Creation happens once, memoised.** On the first connection Tia runs
   `CREATE SCHEMA IF NOT EXISTS <schema>` (not schema-qualified, so it works regardless of the
   current schema), guarded by a memo flag like the existing `schemaEnsured`. Subsequent connections
   skip it.
2. **Selection happens per connection, via `SET`** after acquiring the connection - Postgres
   `SET search_path TO <schema>`, H2 `SET SCHEMA <schema>`. (A URL parameter such as
   `?currentSchema=` / `;SCHEMA=` was considered but rejected: H2 errors at connect time if the named
   schema does not yet exist, so it cannot be used on the first run; `SET` after a memoised
   `CREATE SCHEMA` works uniformly.) The `SET` is a single lightweight statement, and the read path
   opens only a handful of connections, so the added cost is within noise (confirmed by the perf
   stage).

Both statements are vendor-varying and come from `SqlDialect`: `createSchemaIfNotExistsSql(schema)`
and `selectSchemaSql(schema)`, keeping schema SQL in the dialect abstraction alongside
`upsert`/`identityColumnDefinition`/`tableExists`. Identifiers are quoted (`"<schema>"`) for
deterministic exact-case behaviour, and the schema name is already lowercase-sanitised. Both live in
`JdbcDataStore.getConnection()`: memoised create, then select, on every acquired connection.

### Threading the branch through
`DataStoreFactory` already receives the `branch`. It derives the schema name via `BranchSchema` and
injects it into `JdbcDataStore`, which creates the schema once (memoised) and SET-selects it on every
acquired connection. H2 and Postgres share this path; only the dialect SQL differs.

### H2 changes
- H2 stops using a database per branch. The embedded database is a single fixed file
  (`jdbc:h2:<tiaDBFilePath>/tiadb;...`, no branch in the name); server mode uses the configured
  database verbatim. H2 still auto-creates the (now single) database file/database on connect, as it
  does today.
- The `{branch}` token substitution and the per-branch `tiadb-<branch>` file/database naming in
  `H2ConnectionProvider` are removed. H2's load-bearing embedded lifecycle
  (`DB_CLOSE_DELAY=-1`/`DB_CLOSE_ON_EXIT=FALSE`, the static shared connection across forked test JVMs,
  flush-on-close) is preserved unchanged.

### Postgres changes
- The configured database is used verbatim (must pre-exist for v1). Tia creates and selects the
  per-branch schema within it. The role needs `CREATE` on the database (schema creation), not
  `CREATEDB`.

### Config surface
- The `{branch}` token is removed from all URL handling (pre-release, no back-compat shim).
- `tiaDBUrl`/`tiaDBUser`/`tiaDBPassword`/`tiaDBFilePath`/`tiaDBDialect` keep their meaning; the URL is
  now branch-agnostic.
- Tia's own test projects that use `{branch}` (`junit5-git-maven`, `junit5-git-maven-postgres`) are
  updated to drop the token.

## Error handling
- Postgres database missing (v1, no auto-create): the connection fails; Tia surfaces a clear message
  that the configured database must exist (and that per-branch *databases* are not created in v1).
- Schema creation lacking privilege: surface the underlying permission error with the schema name.

## Testing
- **H2 non-regression**: the existing H2 suite stays green (updated where it asserts the old
  per-branch *database* file naming, which becomes per-branch *schema*); the select-tests perf
  harness shows no read-path regression from the schema indirection.
- **Cross-branch isolation** (new, both backends): seed a mapping under branch A's schema and a
  different mapping under branch B's schema against the same database, and assert each branch reads
  back only its own data (no clobbering) - the property the whole change exists to provide.
- **Schema naming**: unit tests for `BranchSchema.schemaName` edge cases.
- **Dialect**: unit tests for each dialect's `createSchemaIfNotExistsSql` and `selectSchemaSql`.
- The H2/Postgres selection-equivalence test continues to pass under the new model.

## Delivery stages

1. `BranchSchema` schema-name derivation + `SqlDialect.createSchemaIfNotExistsSql` + `selectSchemaSql` (H2 + Postgres), with unit tests. No wiring yet.
2. Wire the schema end to end: `JdbcDataStore.getConnection` creates the schema once (memoised) then SET-selects it per connection; `DataStoreFactory` derives the schema from the branch and injects it into the datastore. Both backends now isolate by schema. Existing suites green.
3. Remove the `{branch}` token substitution and per-branch database naming from `H2ConnectionProvider`
   (single fixed H2 database), and drop the token from config handling. Update H2 tests that assert
   the old naming.
4. Cross-branch isolation integration tests (H2 embedded + Postgres, the latter guarded to skip when
   Postgres is unreachable).
5. Update the test-project poms (remove `{branch}`) and the docs (README + the pluggable-datastore
   WIKI chapter): the schema-per-branch model, no `{branch}` token, Postgres database must pre-exist
   (auto-create deferred), and the reduced privilege requirement.
6. H2 perf non-regression check (perf harness before/after), recorded in the WIKI.

## Risks and notes

- The change reopens H2's stable connection/branch path (the pluggable-datastore work deliberately
  left it unchanged). The H2 suite + perf harness are the safety nets; stage 3 is the point of
  highest H2 risk and is isolated so it can be reviewed as such.
- Per-connection overhead is kept near zero: `CREATE SCHEMA` is memoised (runs once) and only a
  single lightweight `SET` runs per acquired connection; the read path opens only a handful of
  connections. The perf stage confirms no read-path regression.
- Postgres database auto-creation (with `CREATEDB` + a maintenance connection) is a deliberate
  follow-up; this stage delivers the schema model and leaves the database as a pre-existing prereq.
