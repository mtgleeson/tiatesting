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
Two mechanisms, chosen to add near-zero per-connection overhead on the hot read path:

1. **Selection is via a connection/URL parameter**, so every connection lands in the branch schema
   with no extra `SET` statement per operation:
   - Postgres: `...?currentSchema=<schema>` on the JDBC URL.
   - H2: `;SCHEMA=<schema>` on the JDBC URL.
   The `ConnectionProvider` composes this from the derived schema name.
2. **Creation happens once, memoised**, folded into the existing schema-bootstrap path
   (`ensureSchema`, guarded by the existing `schemaEnsured` flag). On the first connection Tia runs
   `CREATE SCHEMA IF NOT EXISTS <schema>` (which is not schema-qualified, so it works regardless of
   search path) *before* the existing `CREATE TABLE IF NOT EXISTS` DDL; the tables then resolve into
   the branch schema via the connection's search path. Subsequent connections skip it.

The `CREATE SCHEMA` SQL is vendor-varying and comes from `SqlDialect` (a new
`createSchemaIfNotExistsSql(schema)` method), keeping schema SQL in the dialect abstraction alongside
`upsert`/`identityColumnDefinition`/`tableExists`. The URL-parameter format is owned by each
`ConnectionProvider`.

### Threading the branch through
`DataStoreFactory` already receives the `branch`. It derives the schema name via `BranchSchema` and
injects it into the `ConnectionProvider` (for the URL parameter) and makes it available to
`JdbcDataStore` (for the one-time `CREATE SCHEMA`). H2 and Postgres share this path; only the dialect
SQL and URL-parameter syntax differ.

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
- **Dialect**: unit tests for each dialect's `createSchemaIfNotExistsSql` and URL-parameter form.
- The H2/Postgres selection-equivalence test continues to pass under the new model.

## Delivery stages

1. `BranchSchema` schema-name derivation + `SqlDialect.createSchemaIfNotExistsSql` (H2 + Postgres) +
   the per-dialect URL-parameter form, with unit tests. No wiring yet.
2. Wire the schema end to end: `ConnectionProvider` adds the schema URL parameter; `JdbcDataStore`
   creates the schema once in the bootstrap before the table DDL; `DataStoreFactory` derives the
   schema from the branch and injects it. Both backends now isolate by schema. Existing suites green.
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
- Per-connection overhead is kept near zero by selecting the schema via the URL parameter rather than
  a per-operation `SET`; only the one-time `CREATE SCHEMA` is added to the existing bootstrap.
- Postgres database auto-creation (with `CREATEDB` + a maintenance connection) is a deliberate
  follow-up; this stage delivers the schema model and leaves the database as a pre-existing prereq.
