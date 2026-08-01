# Postgres database auto-creation - design

Date: 2026-08-01
Status: design approved, pending spec review
Branch: `feature/postgres-db-auto-create` (stacked on `feature/pluggable-datastore` @ 48ece3d)

## Problem

The schema-per-branch work isolates each VCS branch into a `tia_<sanitized-branch>` schema within a
single Postgres database, but it deliberately left one gap (a documented non-goal in
`2026-07-31-schema-per-branch-design.md`): Tia creates the per-branch *schema*, not the *database*.
If the configured Postgres database does not exist, the run fails at the first connection.

H2 has no such gap - it auto-creates its (single, fixed) database file on connect. This effort brings
Postgres to parity: auto-create the database when it is missing and the role is allowed to, then
proceed to the existing schema create/select.

## Goals

- On connect, if the configured Postgres database does not exist and the role holds `CREATEDB`, Tia
  creates it automatically, then continues to create/select the per-branch schema (mirroring how H2
  auto-creates its database).
- If the role lacks `CREATEDB`, fail with a clear, actionable message.
- Preserve the schema-per-branch reduced-privilege model: a user who pre-creates the database still
  needs only `CREATE`-on-database (no `CREATEDB`). Auto-create is an *additive convenience*, not a new
  requirement.
- No change to the perf-critical schema create/select path in `JdbcDataStore` (the auto-create lives
  entirely in the connection provider).

## Non-goals

- MySQL database auto-creation (still a seam; MySQL keeps the generic `JdbcConnectionProvider`).
- A transient-socket connection retry for Postgres (that is an H2-server-mode concern; not added here).
- Any new user-facing config flag - auto-create is always-on, like H2 (see "Enablement" below).
- Auto-creating the maintenance database (`postgres`) itself, or granting/altering roles.

## Decisions (from brainstorming)

1. **Placement: a dedicated `PostgresConnectionProvider`**, mirroring the existing `H2ConnectionProvider`
   pattern - the provider owns the whole flow. `JdbcConnectionProvider` stays a generic fallback and
   `SqlDialect` gains no methods. (Rejected: spreading the flow across a generic provider plus
   Postgres-only dialect hooks that H2/MySQL would no-op.)
2. **Enablement: always-on (H2 parity).** No config knob, consistent with Tia's
   automation-over-configuration principle. If the database is missing and the role lacks `CREATEDB`,
   fail clearly.
3. **CREATEDB gate: attempt-and-translate.** Try `CREATE DATABASE`; if it fails with SQLState `42501`
   (insufficient_privilege), catch and rethrow a clear Tia message. No pre-check query on the
   happy path.

## Design

### Placement and routing

`PostgresConnectionProvider extends JdbcConnectionProvider`, in
`org.tiatesting.core.persistence.connection`. `DataStoreFactory.fromConfig` routes the non-H2 branch
by dialect id: `dialect.id().equals("postgres")` -> `PostgresConnectionProvider`; every other resolved
dialect (the MySQL seam) keeps the plain `JdbcConnectionProvider`. Nothing else in the factory changes;
`JdbcDataStore` and `SqlDialect` are untouched.

### Connection flow: `PostgresConnectionProvider.get()`

```
try  super.get()                       // DriverManager.getConnection(targetUrl, user, password)
catch SQLException e:
  if !"3D000".equals(e.getSQLState())  // 3D000 = invalid_catalog_name (database does not exist)
      -> rethrow e                     // all other connect failures propagate unchanged
  createDatabaseViaMaintenance()
  return super.get()                   // retry once against the now-existing database
```

Only `3D000` triggers the create path; every other connection failure propagates exactly as today, so
existing error behaviour is unchanged. The retry is a single re-attempt after a successful (or
already-existing) create; it is not a loop.

### `createDatabaseViaMaintenance()`

1. Derive the **maintenance URL** from the target JDBC URL by swapping the database segment to
   `postgres` (you cannot `CREATE DATABASE` while connected to the target database). Authority
   (host(s)/port) and query parameters are preserved; only the database segment changes.
2. Open a connection to the maintenance URL with the same credentials.
3. Execute `CREATE DATABASE "<db>"` - the database name is quoted for exact-identifier behaviour, and
   the statement runs with the connection's default autoCommit (Postgres forbids `CREATE DATABASE`
   inside a transaction block; `DriverManager` connections default to autoCommit=true, so no explicit
   transaction is opened).
4. **Race handling:** if `CREATE DATABASE` fails with SQLState `42P04` (duplicate_database) - another
   test JVM created it first - treat it as success and proceed. (Postgres has no
   `CREATE DATABASE IF NOT EXISTS`.)
5. **CREATEDB gate:** if it fails with SQLState `42501` (insufficient_privilege), throw an unchecked
   `TiaPersistenceException` (which propagates past `JdbcDataStore.getConnection`'s `SQLException`
   catch unchanged, so the exact wording is preserved rather than re-wrapped), chaining the original
   `SQLException` as the cause **and embedding the driver's own message inline** in the thrown
   message. The original driver text is embedded inline (not only chained) so the user always sees it
   even if the caller's logging does not print the cause chain. Message:
   `Tia datastore database "<db>" does not exist and the configured role lacks CREATEDB to create it. Create the database first, or grant CREATEDB to the role. Original driver error: <sqlException.getMessage()>`
6. Close the maintenance connection (try-with-resources).

### Pure, unit-testable helpers

Two package-private static methods carry the fiddly URL logic and are unit-tested without a database:

- `static String databaseName(String jdbcUrl)` - the target database segment (used for the
  `CREATE DATABASE` identifier and the error message).
- `static String maintenanceUrl(String jdbcUrl)` - the same URL with the database segment replaced by
  `postgres`, authority and query parameters preserved.

Parsing model: `jdbc:postgresql://<authority>/<database>?<params>`. The authority runs from after
`jdbc:postgresql://` to the first `/`; the database is the segment from that `/` up to `?` (or end);
`<params>` (if any) is everything from `?` onward. Only the database segment is rewritten.

Edge cases the helpers must handle (each a unit test):
- No explicit port: `jdbc:postgresql://localhost/tia_junit5`.
- Explicit port: `jdbc:postgresql://localhost:5432/tia_junit5`.
- Query parameters: `jdbc:postgresql://localhost:5432/tia_junit5?ssl=true&foo=bar` (params preserved).
- Multi-host authority: `jdbc:postgresql://h1:5432,h2:5432/tia_junit5` (authority preserved verbatim).
- Trailing slash / empty database segment: surfaced as an explanatory failure rather than a silent
  wrong URL.
- Target database already `postgres`: `maintenanceUrl` returns an equivalent URL; this case does not
  arise in practice (connecting to `postgres` would not raise `3D000`), but the helper stays total.

### Privilege story (framing)

Auto-create is best-effort convenience; the schema-per-branch reduced-privilege model is preserved:

- **Database exists** -> no `CREATEDB` needed; the role needs only `CREATE`-on-database for the schema
  (unchanged from schema-per-branch v1).
- **Database missing + role has `CREATEDB`** -> Tia auto-creates it, then creates the schema.
- **Database missing + role lacks `CREATEDB`** -> clear failure with the message above.

A user unwilling to grant `CREATEDB` pre-creates the database once and nothing else changes for them.

## Error handling

- `3D000` (database missing) is the only trigger for the create path.
- `42P04` (database already exists, race) -> success.
- `42501` (insufficient privilege) -> translated, actionable message that both embeds the driver's
  original message inline and chains the original `SQLException` as the cause, so the underlying client
  error is always visible to the user.
- Every other `SQLException` from the initial connect or the maintenance connect propagates unchanged
  (wrapped by the caller in `JdbcDataStore.getConnection` as `TiaPersistenceException`, as today).

## Testing

- **Unit (no database):** `databaseName` and `maintenanceUrl` across all URL shapes above, in
  given/when/then style.
- **Integration (guarded; skipped when Postgres is unreachable, matching the existing cross-branch
  isolation tests):**
  - Connect via `PostgresConnectionProvider` against a uniquely-named, non-existent database; assert
    the database is created and a usable connection is returned.
  - Run the connect a second time to exercise the "database already exists" path: `3D000` is not
    raised, so no create is attempted and the connection succeeds directly. (This confirms the
    provider is a no-op once the database exists; it does not exercise the `42P04` branch.)
  - The `42P04` race branch (two connects both see `3D000` and both attempt `CREATE DATABASE`) is
    hard to trigger deterministically. It is covered by the explicit handling in
    `createDatabaseViaMaintenance`; a best-effort concurrency test may be added but the branch is not
    gated on one.
- **CREATEDB-lacking path:** verified via an integration test using a purpose-made role without
  `CREATEDB` if the Postgres fixture supports creating one; otherwise recorded as a documented manual
  check (the local `tia` role is a superuser, so it cannot exercise the gate directly). The `42501`
  translation itself is small and deterministic; where the restricted role is available, the test
  asserts both the Tia guidance wording and that the driver's original message is embedded inline in
  the thrown message.

## Delivery stages

Each stage stops for review (staged delivery).

1. **URL helpers.** `PostgresConnectionProvider.databaseName` and `maintenanceUrl` static methods with
   full unit tests. No wiring, no provider flow yet.
2. **Provider + wiring.** `PostgresConnectionProvider.get()` (3D000 -> create -> retry, 42P04
   idempotency, 42501 translation) and its `createDatabaseViaMaintenance`; `DataStoreFactory` routes
   the postgres dialect to it. Guarded integration tests (create + idempotent).
3. **Docs.** Update the WIKI (the pluggable-datastore / schema-per-branch chapter) and README: the
   auto-create behaviour, the three-way privilege story, and that pre-creating the database is still
   fully supported and needs only `CREATE`-on-database. Reference the WIKI (not this design doc) from
   any code comments.

## Risks and notes

- The maintenance-URL derivation is the fiddliest piece and is isolated into pure, exhaustively
  unit-tested helpers precisely so it can be reviewed and trusted independently of a live database.
- `CREATEDB` is a broader privilege than `CREATE`-on-database. This is a real implication for the
  Cloud SQL / DevOps setup, but it is opt-in by consequence: it only matters when the database is
  missing, and pre-creating the database avoids needing it entirely. Documented in stage 3.
- The change touches only the connection-acquisition path for the postgres dialect; H2 and the generic
  JDBC path are untouched, so there is no read-path performance risk.
