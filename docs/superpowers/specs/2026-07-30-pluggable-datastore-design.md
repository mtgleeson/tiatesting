# Pluggable datastore (user-selectable SQL backend) - design

Date: 2026-07-30
Status: design approved, pending spec review
Branch: `feature/pluggable-datastore`

## Problem

Tia stores its test-to-source mapping in an embedded/served H2 database, hard-coded as the only
backend. Users need to run Tia against their own SQL database - PostgreSQL first, with MySQL and
other JDBC databases to follow - configuring which database to use, its JDBC connection parameters,
and supplying the JDBC driver themselves.

A measurement spike (`spike/postgres-datastore-viability`, findings in
`docs/superpowers/results/2026-07-30-postgres-spike-findings.md` on that branch) confirmed this is
viable: against Postgres the select-tests read path stays sub-second in-region (~446ms median at
~2ms RTT), H2 and Postgres produce identical test selections, and the only vendor SQL differences
are small and well-understood. This design turns that throwaway proof into a production abstraction.

## Goals

- A user can point Tia at a PostgreSQL database instead of the built-in H2 by configuration alone.
- The design generalises to other JDBC databases (MySQL next) via a small, well-bounded per-vendor
  unit - no fork of Tia per database, no bundling of every driver.
- **H2 remains the zero-config default**, with its behaviour and select-tests performance unchanged.
- The user supplies the JDBC driver jar; Tia core stays driver-free.

Non-goals (this effort): implementing MySQL (seam only), a schema-migration framework, connection
pooling, and any non-JDBC store (e.g. Spanner). MySQL and those are separate future work.

## Approach (chosen: dialect-parameterised single datastore)

`H2DataStore` (~2000 lines) mixes two vendor-varying concerns - the **SQL text** and the
**connection lifecycle** - so both are extracted, and the datastore is parameterised by them. One
JDBC code path serves all relational vendors; each vendor is a small dialect + connection provider.

Rejected alternatives: a parallel `JdbcDataStore` alongside an untouched `H2DataStore` (duplicates
the SQL, lets the two drift); a full hand-written `DataStore` per vendor (31 methods x N vendors,
maximal duplication). Both lose the single source of truth that makes MySQL cheap.

## Components

- **`SqlDialect`** (interface) - the vendor-specific SQL. Responsibilities:
  - identity/auto-increment column DDL fragment (`BIGINT AUTO_INCREMENT` for H2, `BIGINT GENERATED
    BY DEFAULT AS IDENTITY` for Postgres);
  - upsert SQL for a given table + key columns + update columns (`MERGE INTO ...` for H2,
    `INSERT ... ON CONFLICT (...) DO UPDATE` for Postgres);
  - DDL type mapping for the column types Tia uses;
  - the table-exists metadata lookup, including identifier casing (H2 folds unquoted identifiers to
    upper case, Postgres to lower case - a real correctness difference the spike caught);
  - any per-vendor value-binding quirk the persist path needs (e.g. timestamp binding).
  Implementations: `H2Dialect` (reproduces today's SQL exactly), `PostgresDialect`.
- **`ConnectionProvider`** (interface) - the vendor-specific connection acquisition and lifecycle.
  `H2ConnectionProvider` keeps H2's load-bearing embedded specifics (the `DB_CLOSE_DELAY=-1` /
  `DB_CLOSE_ON_EXIT=FALSE` URL flags, the static shared connection reused across forked test JVMs,
  flush-on-close). `JdbcConnectionProvider` (networked vendors) is a plain `DriverManager`
  open/close against the configured URL/user/password. The existing `H2ConnectionSettings` is
  generalised into the settings this consumes.
- **`JdbcDataStore`** - `H2DataStore` renamed and parameterised by `(SqlDialect, ConnectionProvider)`.
  Holds all 31 `DataStore` methods; every SQL literal that varies by vendor is sourced from the
  dialect. Per the no-backwards-compatibility-shims convention, the rename is direct and all callers
  are updated in the same change (no deprecated `H2DataStore` alias).
- **`SqlDialectRegistry`** - maps a JDBC URL scheme (or an explicit dialect id) to a `SqlDialect` +
  `ConnectionProvider` factory. `jdbc:h2` -> H2, `jdbc:postgresql` -> Postgres. Unknown scheme with
  no override -> a clear error listing supported dialects.
- **`DataStoreFactory`** - the single construction point. Reads the Tia DB config (URL, user,
  password, optional dialect override, embedded H2 file path), resolves the dialect via the
  registry, and returns a ready `JdbcDataStore`. Replaces the ~10 hard-coded `new H2DataStore(...)`
  call sites across the runner and build-tool modules (junit4/5 listeners, Spock extension, Gradle
  tasks, Maven mojos).

## Configuration surface

Vendor selection is driven by the JDBC URL scheme, so the only new knob is an optional override.

- `tiaDBUrl` / `dbUrl` (existing): the JDBC URL. Unset -> embedded H2 at `tiaDBFilePath` (today's
  default). `jdbc:h2:...` -> H2. `jdbc:postgresql://host:port/db` -> Postgres. The vendor is inferred
  from the scheme.
- `tiaDBUser` / `tiaDBPassword` (existing).
- `tiaDBFilePath` / `dbFilePath` (existing): still the embedded-H2 default location; ignored when a
  non-embedded `tiaDBUrl` is set.
- `tiaDBDialect` / `dbDialect` (**new, optional**): explicit dialect id (`h2`, `postgres`) that
  overrides scheme inference for edge cases (custom URLs, proxies).

These are plumbed through the Maven plugin parameters, the Gradle extension, **and the forked-JVM
system-property bridge** (Tia already forwards DB config to the agent via `ForkSystemProperties`;
the new/renamed keys are added there so the forked test JVM's listener constructs the same store).

## Driver provisioning (the two-classpath model)

Tia core bundles only H2 and never bundles other drivers (avoids per-vendor Tia builds, jar bloat,
and version/licence conflicts - the standard pattern for Flyway/Liquibase/Hibernate). The user
supplies the JDBC driver in **two places**, because Tia constructs the datastore in two runtimes:

1. **Forked test JVM** - the JUnit/Spock listener persists coverage there. The user adds the driver
   as a **test-scope dependency** in their project (the same place they already declare
   `tia-junit5-git`), so it lands on the Surefire/test fork classpath.
2. **Build-tool process** - the Maven mojos (reports, reconcile) and Gradle tasks construct the
   store on the build side. The driver must be on the **plugin's** classpath:
   - Maven: a `<dependency>` inside the Tia `<plugin>` block.
   - Gradle: a dependency declared on the Tia plugin's resolution classpath.

If the driver class is absent on whichever classpath is constructing the store, `DataStoreFactory`
throws a clear, actionable error naming the vendor and the classpath to add it to - not a raw
`ClassNotFoundException`. This dual requirement is the one genuinely new thing users must learn and
is documented explicitly in the README and WIKI.

## Schema / DDL

The existing on-demand `CREATE TABLE IF NOT EXISTS` idiom is retained; each dialect supplies its
type fragments (only the identity column differs materially between H2 and Postgres today). No
migration framework - Tia is pre-release and creates its schema on demand.

## Preserving H2 (correctness and performance)

The read path is Tia's performance-critical surface, so H2 must be provably unchanged:

- `H2Dialect` reproduces the current SQL verbatim (the same `MERGE INTO`, `BIGINT AUTO_INCREMENT`,
  upper-cased table-exists lookup, embedded URL params), so the emitted SQL is byte-identical to
  today's.
- The existing `tia-core` H2 test suite must stay green throughout - it is the behavioural oracle
  for the refactor.
- The select-tests **perf harness** (`ProfileSelectTests` / `GenerateLargeTiaDb`) is run against a
  large synthetic H2 DB before and after the refactor to confirm the dialect indirection adds no
  measurable read-path regression.

## Error handling

- Unknown URL scheme and no `tiaDBDialect` override -> error listing supported dialect ids.
- Explicit `tiaDBDialect` that is not registered -> error listing supported ids.
- Missing driver on the constructing classpath -> actionable "add the `<vendor>` JDBC driver to
  `<test dependencies | the Tia plugin dependencies>`" message.
- Connection failure -> surfaced with the JDBC URL, password masked.

## Testing

- **H2 non-regression:** the full existing H2 suite stays green; perf harness before/after shows no
  read-path regression.
- **Postgres correctness:** promote the spike's H2-vs-Postgres selection-equivalence approach (same
  synthetic mapping + same diff -> identical ignore set on both backends) into a maintained
  integration test, guarded by a JUnit assumption that SKIPS when no Postgres is reachable (so the
  normal build stays green). Add focused unit tests for `PostgresDialect`'s SQL fragments (upsert,
  identity DDL, table-exists casing).
- **Factory/registry:** unit tests for URL-scheme inference, explicit override, unknown-scheme
  error, and the missing-driver error message.
- **MySQL:** no implementation and no tests; a design note in the code enumerates exactly what a
  `MySqlDialect` would fill in (upsert `INSERT ... ON DUPLICATE KEY UPDATE`, identity
  `AUTO_INCREMENT`, identifier casing) so the seam is obviously complete.

## Delivery stages

1. Extract `SqlDialect` + `ConnectionProvider`; rename `H2DataStore` -> `JdbcDataStore`; add
   `H2Dialect` + `H2ConnectionProvider` reproducing current behaviour. H2 suite green, perf
   unchanged. No new vendor, no factory yet - callers still construct the H2-configured store
   directly.
2. Add `SqlDialectRegistry` + `DataStoreFactory`; replace the ~10 `new H2DataStore(...)` sites with
   the factory. Still H2-only functionally.
3. Add `PostgresDialect` + Postgres connection provider, promoting the spike's verified SQL
   translations. Postgres reachable via a hand-set config in a test.
4. Config plumbing: `tiaDBDialect` + URL-scheme inference wired through Maven params, the Gradle
   extension, and the forked-JVM system-property bridge.
5. Driver provisioning wiring + the actionable missing-driver / unknown-dialect errors; README and
   WIKI documentation of the two-classpath model.
6. Tests: the maintained H2/Postgres equivalence integration test, perf non-regression, and the
   factory/registry/error-path unit tests.

## Risks and notes

- The Stage 1 rename touches a large, performance-critical, lifecycle-heavy class. The `H2Dialect`
  reproduction + green H2 suite + perf harness are the three-part safety net; Stage 1 lands nothing
  new functionally, which keeps it reviewable as a pure refactor.
- H2's embedded connection lifecycle (static shared connection across forked JVMs, close-delay
  flags, flush-on-close) is subtle and load-bearing; it stays entirely inside `H2ConnectionProvider`
  rather than leaking into `JdbcDataStore`.
- The two-classpath driver requirement is the main user-facing complexity; clear errors and docs are
  part of the deliverable, not an afterthought.
- Persist-vs-RTT for networked backends was not measured by the spike (only the 0ms seed); it is not
  a blocker for this design but should be measured on a real Postgres during Stage 3/6 so the docs
  can set expectations honestly.
