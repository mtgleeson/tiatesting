# Unified Schema-Per-Branch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Isolate each VCS branch's Tia mapping into a `tia_<branch>` schema within one database, for both H2 and Postgres, with no `{branch}` token in config - Tia derives the branch from Git and creates/selects the schema automatically.

**Architecture:** Add a `BranchSchema` name-deriver and two `SqlDialect` methods (`createSchemaIfNotExistsSql`, `selectSchemaSql`). `JdbcDataStore.getConnection()` creates the branch schema once (memoised) and `SET`-selects it on every acquired connection. `DataStoreFactory` derives the schema from the `branch` it already receives and injects it into the store. H2 drops its per-branch-database `{branch}` model for a single fixed database + schemas.

**Tech Stack:** Java 8, JDBC, H2, PostgreSQL, JUnit 5, Gradle + Maven.

## Global Constraints

- Java source/target level is `1.8` - no Java 9+ APIs.
- Unit tests use `// given` / `// when` / `// then` marker comments.
- Every new/modified method gets a javadoc with `@param`/`@return`.
- ASCII hyphens only, never em-dashes.
- **No backwards-compatibility shims**: remove the `{branch}` token outright and update all callers/configs in the same change; no deprecated alias.
- The existing H2 test suite is the behavioural oracle and must stay green after every task (updated only where it asserts the old per-branch *database naming*, which becomes per-branch *schema*).
- Schema name format is exactly `tia_` + the branch lowercased with every char outside `[a-z0-9_]` replaced by `_`, clamped to 63 chars. Identifiers are quoted (`"<schema>"`) in the emitted SQL.
- No H2 read-path performance regression (verified in the final task with the perf harness).
- Postgres database auto-creation is OUT OF SCOPE (deferred); the Postgres database must pre-exist. Tia creates only the schema.
- A local Postgres is available at `jdbc:postgresql://localhost:5432/tia_junit5_pg` (user/pass `tia`/`tia`); `spike/postgres/setup.sh` starts it if down. Postgres integration tests are guarded to SKIP when it is unreachable.

---

## File Structure

New:
- `tia-core/src/main/java/org/tiatesting/core/persistence/BranchSchema.java` - `static String schemaName(String branch)`.
- `tia-core/src/test/java/org/tiatesting/core/persistence/BranchSchemaTest.java`
- `tia-core/src/test/java/org/tiatesting/core/persistence/SchemaPerBranchIsolationTest.java` (Task 4)

Modified:
- `tia-core/.../persistence/dialect/SqlDialect.java` - add `createSchemaIfNotExistsSql`, `selectSchemaSql`.
- `.../dialect/H2Dialect.java`, `.../dialect/PostgresDialect.java` - implement them (+ their tests).
- `.../persistence/JdbcDataStore.java` - constructor gains the schema; `getConnection()` creates-once + selects.
- `.../persistence/DataStoreFactory.java` - derive schema from `branch`, pass to `JdbcDataStore`.
- `.../persistence/connection/H2ConnectionProvider.java` - remove `{branch}` substitution + per-branch db naming (single fixed db).
- `.../persistence/h2/H2ConnectionSettings.java` - remove `{branch}` token/`branchSuffix` URL role (keep the class for embedded/server settings).
- H2 tests asserting the old `tiadb-<branch>` naming.
- `README.md`, `wiki/pluggable-datastore.md` (Task 5).
- `~/Documents/tia/test-projects/junit5-git-maven*/**/pom.xml` (Task 5) - drop `{branch}`.
- `wiki/profiling-select-tests.md` (Task 6).

---

## Task 1: BranchSchema + dialect schema SQL

**Files:**
- Create: `tia-core/src/main/java/org/tiatesting/core/persistence/BranchSchema.java`
- Create: `tia-core/src/test/java/org/tiatesting/core/persistence/BranchSchemaTest.java`
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/dialect/SqlDialect.java`, `H2Dialect.java`, `PostgresDialect.java`
- Test: `tia-core/src/test/java/org/tiatesting/core/persistence/dialect/H2DialectTest.java`, `PostgresDialectTest.java` (extend)

**Interfaces:**
- Produces: `BranchSchema.schemaName(String branch) -> String`; `SqlDialect.createSchemaIfNotExistsSql(String schema) -> String`; `SqlDialect.selectSchemaSql(String schema) -> String`; implemented by `H2Dialect` and `PostgresDialect`.

- [ ] **Step 1: Write the failing BranchSchema test**

```java
package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BranchSchemaTest {

    @Test
    void sanitizesAndPrefixes() {
        // given / when / then
        assertEquals("tia_main", BranchSchema.schemaName("main"));
        assertEquals("tia_feature_foo_bar", BranchSchema.schemaName("feature/Foo-Bar"));
        assertEquals("tia_123", BranchSchema.schemaName("123"));           // prefix makes it valid
        assertEquals("tia_", BranchSchema.schemaName(""));                  // empty branch
    }

    @Test
    void clampsToIdentifierLimit() {
        // given
        String longBranch = new String(new char[100]).replace('\0', 'a');
        // when
        String schema = BranchSchema.schemaName(longBranch);
        // then
        assertEquals(63, schema.length());
        assertEquals("tia_", schema.substring(0, 4));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :tia-core:test --tests '*BranchSchemaTest' --console=plain`
Expected: FAIL - `BranchSchema` does not exist.

- [ ] **Step 3: Write BranchSchema**

```java
package org.tiatesting.core.persistence;

/** Derives the per-branch schema name Tia isolates each VCS branch's mapping into. */
public final class BranchSchema {

    private static final int MAX_IDENTIFIER_LENGTH = 63; // Postgres identifier limit

    private BranchSchema() { }

    /**
     * Derive the schema name for a branch: {@code tia_} + the branch lowercased with every
     * character outside {@code [a-z0-9_]} replaced by {@code _}, clamped to 63 characters.
     * The {@code tia_} prefix guarantees a valid identifier and namespaces Tia's objects.
     *
     * @param branch the VCS branch name (may be null/empty)
     * @return the sanitised, prefixed, length-clamped schema name
     */
    public static String schemaName(String branch) {
        String safe = (branch == null ? "" : branch).toLowerCase().replaceAll("[^a-z0-9_]", "_");
        String name = "tia_" + safe;
        return name.length() > MAX_IDENTIFIER_LENGTH ? name.substring(0, MAX_IDENTIFIER_LENGTH) : name;
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :tia-core:test --tests '*BranchSchemaTest' --console=plain`
Expected: PASS.

- [ ] **Step 5: Write failing dialect tests, then implement**

Add to `H2DialectTest`:
```java
    @Test
    void schemaSql() {
        // given
        H2Dialect d = new H2Dialect();
        // when / then
        assertEquals("CREATE SCHEMA IF NOT EXISTS \"tia_main\"", d.createSchemaIfNotExistsSql("tia_main"));
        assertEquals("SET SCHEMA \"tia_main\"", d.selectSchemaSql("tia_main"));
    }
```
Add to `PostgresDialectTest`:
```java
    @Test
    void schemaSql() {
        // given
        PostgresDialect d = new PostgresDialect();
        // when / then
        assertEquals("CREATE SCHEMA IF NOT EXISTS \"tia_main\"", d.createSchemaIfNotExistsSql("tia_main"));
        assertEquals("SET search_path TO \"tia_main\"", d.selectSchemaSql("tia_main"));
    }
```
Add the two methods to `SqlDialect` (with javadocs):
```java
    /**
     * DDL that creates the given schema if it does not already exist.
     * @param schema the (already-sanitised) schema name
     * @return the vendor-specific CREATE SCHEMA IF NOT EXISTS statement
     */
    String createSchemaIfNotExistsSql(String schema);

    /**
     * Statement that makes the given schema the default for subsequent unqualified statements.
     * @param schema the (already-sanitised) schema name
     * @return the vendor-specific schema-selection statement
     */
    String selectSchemaSql(String schema);
```
Implement in `H2Dialect`:
```java
    @Override public String createSchemaIfNotExistsSql(String schema) {
        return "CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"";
    }
    @Override public String selectSchemaSql(String schema) {
        return "SET SCHEMA \"" + schema + "\"";
    }
```
Implement in `PostgresDialect`:
```java
    @Override public String createSchemaIfNotExistsSql(String schema) {
        return "CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"";
    }
    @Override public String selectSchemaSql(String schema) {
        return "SET search_path TO \"" + schema + "\"";
    }
```

- [ ] **Step 6: Run the dialect tests**

Run: `./gradlew :tia-core:test --tests '*H2DialectTest' --tests '*PostgresDialectTest' --console=plain`
Expected: PASS (module compiles - both dialects implement the new interface methods).

- [ ] **Step 7: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/persistence/BranchSchema.java tia-core/src/test/java/org/tiatesting/core/persistence/BranchSchemaTest.java tia-core/src/main/java/org/tiatesting/core/persistence/dialect tia-core/src/test/java/org/tiatesting/core/persistence/dialect
git commit -m "feat(datastore): BranchSchema deriver + dialect schema SQL"
```

---

## Task 2: Wire the schema into JdbcDataStore + DataStoreFactory

**Files:**
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/JdbcDataStore.java`
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/DataStoreFactory.java`
- Modify: every remaining `new JdbcDataStore(dialect, provider)` construction (tests + any direct callers) to pass the schema.

**Interfaces:**
- Consumes: `BranchSchema.schemaName`, `SqlDialect.createSchemaIfNotExistsSql`/`selectSchemaSql`.
- Produces: `JdbcDataStore(SqlDialect dialect, ConnectionProvider connectionProvider, String schema)`; `DataStoreFactory` derives `schema = BranchSchema.schemaName(branch)` and passes it.

- [ ] **Step 1: Write a failing test that data lands in the branch schema**

```java
// in tia-core/src/test/java/org/tiatesting/core/persistence/JdbcDataStoreSchemaTest.java
package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcDataStoreSchemaTest {

    @Test
    void createsAndUsesBranchSchemaOnH2(@TempDir Path dir) throws Exception {
        // given a factory-built H2 store on branch "featureX"
        DataStore store = DataStoreFactory.fromConfig(dir.toString(), null, "sa", "", null, "featureX");
        // when it persists anything (forces schema + table creation)
        store.persistTestSuitesFailed(new java.util.HashSet<>(java.util.Arrays.asList("x")));
        // then the tia tables exist under schema TIA_FEATUREX (H2 folds the quoted lower-case name)
        try (Connection c = new org.tiatesting.core.persistence.connection.H2ConnectionProvider(
                org.tiatesting.core.persistence.h2.H2ConnectionSettings.embedded(dir.toString(), "featureX")).get();
             ResultSet rs = c.getMetaData().getTables(null, "tia_featureX", "%", new String[]{"TABLE"})) {
            // then - at least one Tia table is present in the branch schema
            assertTrue(rs.next(), "expected Tia tables in schema tia_featureX");
        }
    }
}
```
(Note to implementer: adjust the schema-name casing in the assertion to match how H2 stores the quoted identifier - verify empirically; the point is the tables live in the branch schema, not the default one.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :tia-core:test --tests '*JdbcDataStoreSchemaTest' --console=plain`
Expected: FAIL - `DataStoreFactory.fromConfig` still builds a store with no schema, so tables are created in the default schema (or the constructor signature does not yet take a schema).

- [ ] **Step 3: Add the schema to JdbcDataStore**

Add a `private final String schema;` field and a memo flag `private boolean branchSchemaCreated;`. Change the constructor to `public JdbcDataStore(SqlDialect dialect, ConnectionProvider connectionProvider, String schema)`. In the private `getConnection()` (which currently wraps `connectionProvider.get()` in `TiaPersistenceException`), after acquiring the connection and before returning it: if `schema` is non-null/non-blank, run - once, guarded by `branchSchemaCreated` - `dialect.createSchemaIfNotExistsSql(schema)`, then always run `dialect.selectSchemaSql(schema)` via a `Statement`. Wrap any `SQLException` in `TiaPersistenceException` as the existing code does. (H2 requires the schema to exist before `SET SCHEMA`, so create-before-select ordering is mandatory.)

- [ ] **Step 4: Derive + inject the schema in the factory and all constructors**

In both `DataStoreFactory.fromConfig` branches, compute `String schema = BranchSchema.schemaName(branch);` and pass it as the third arg to `new JdbcDataStore(dialect, provider, schema)`. Update every other `new JdbcDataStore(...)` in the codebase (grep `new JdbcDataStore(`) to pass a schema - test-only constructions may pass `BranchSchema.schemaName("main")` or a fixed test schema.

- [ ] **Step 5: Run the new test + the full build**

Run: `./gradlew build --console=plain`
Expected: BUILD SUCCESSFUL; `JdbcDataStoreSchemaTest` passes; the whole existing suite stays green (data now lands in a branch schema, but each test uses one branch/schema so behaviour is unchanged within a test).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(datastore): create + select the per-branch schema on every connection"
```

---

## Task 3: Remove the {branch} token / per-branch database from H2

**Files:**
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/connection/H2ConnectionProvider.java`
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/h2/H2ConnectionSettings.java`
- Modify: H2 tests that assert the `tiadb-<branch>` file/database naming or the `{branch}` substitution.

**Interfaces:**
- Produces: H2 now connects to a single fixed database (embedded: `jdbc:h2:<dbFilePath>/tiadb;<engine opts>`; server: the configured URL verbatim), with no `{branch}` substitution. Branch isolation is now entirely via the schema (Task 2).

- [ ] **Step 1: Update the H2 URL building**

In `H2ConnectionProvider.buildJdbcUrl()`: embedded mode returns `"jdbc:h2:" + settings.getDbFilePath() + "/tiadb" + <the existing ;PAGE_SIZE/;CACHE_SIZE/;DB_CLOSE_DELAY=-1/;DB_CLOSE_ON_EXIT=FALSE options>` (drop the `-<branch>` suffix and the `sanitizeBranchForDbName` call). Server mode returns `settings.getDbUrl()` verbatim (drop the `applyServerDbNamePlaceholder` call). Delete the now-unused `applyServerDbNamePlaceholder` and `sanitizeBranchForDbName` methods. Preserve the retry, `DB_CLOSE_*`, static-shared-connection, and `close()`/SHUTDOWN logic unchanged.

- [ ] **Step 2: Drop the {branch} token from H2ConnectionSettings**

Remove `BRANCH_PLACEHOLDER` and the `branchSuffix`-as-URL-token role. If `branchSuffix` is now entirely unused (the factory derives the schema from `branch` directly), remove the field and its constructor/getters and update `H2ConnectionSettings.embedded/server/fromConfig/fromSystemProperties` signatures accordingly, updating all callers (`DataStoreFactory`, tests). If keeping the signatures is simpler, leave the parameter but stop using it for the URL - prefer removing it (no back-compat shims). Verify by grep that no `{branch}`/`BRANCH_PLACEHOLDER`/`applyServerDbNamePlaceholder` reference remains.

- [ ] **Step 3: Update H2 tests asserting the old naming**

Find tests asserting `tiadb-<branch>` file names or `{branch}` expansion (grep `tiadb-` and `{branch}` under `src/test`) and update them to the single fixed `tiadb` database (and, where they were checking per-branch isolation, to per-branch schema instead).

- [ ] **Step 4: Run the full build**

Run: `./gradlew build --console=plain`
Expected: BUILD SUCCESSFUL, whole suite green with the single-database + schema model.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(datastore): drop H2 per-branch database / {branch} token (schema-per-branch now)"
```

---

## Task 4: Cross-branch isolation integration test

**Files:**
- Create: `tia-core/src/test/java/org/tiatesting/core/persistence/SchemaPerBranchIsolationTest.java`

**Interfaces:**
- Consumes: `DataStoreFactory`.

- [ ] **Step 1: Write the isolation test (H2 + guarded Postgres)**

For each backend, build two stores via `DataStoreFactory.fromConfig` differing ONLY in the `branch` argument (`"branchA"` vs `"branchB"`) against the SAME database (H2: same `@TempDir` dbFilePath; Postgres: same `jdbc:postgresql://localhost:5432/tia_junit5_pg`). Persist a DISTINCT failed-suite set into each (e.g. `{"A_only"}` vs `{"B_only"}`), then read each back via `getTestSuitesFailed()` and assert branchA sees only `{"A_only"}` and branchB only `{"B_only"}` - proving the two branches are isolated in separate schemas within one database. Guard the Postgres half with the localhost:5432 TCP-probe `Assumptions.assumeTrue` skip (as in `DataStoreFactoryPostgresTest`). Drop/reset the two Postgres schemas at the start for a clean run.

- [ ] **Step 2: Run it (Postgres up)**

Run: `spike/postgres/setup.sh >/dev/null 2>&1; ./gradlew :tia-core:test --tests '*SchemaPerBranchIsolationTest' --console=plain`
Expected: PASS, both backends run (Postgres not skipped), each branch reads back only its own data. If branchA sees branchB's data, schema isolation is broken - fix before committing (do not weaken the assertion).

- [ ] **Step 3: Commit**

```bash
git add tia-core/src/test/java/org/tiatesting/core/persistence/SchemaPerBranchIsolationTest.java
git commit -m "test(datastore): cross-branch schema isolation on H2 and Postgres"
```

---

## Task 5: Update test-project configs + docs

**Files:**
- Modify: `~/Documents/tia/test-projects/junit5-git-maven-postgres/**/pom.xml` and `~/Documents/tia/test-projects/junit5-git-maven/**/pom.xml` (drop `{branch}`).
- Modify: `README.md`, `wiki/pluggable-datastore.md`

- [ ] **Step 1: Drop {branch} from the test-project poms**

In the Postgres test project, the URL is already `jdbc:postgresql://localhost:5432/tia_junit5_pg` (no token) - no change needed there beyond confirming. In `junit5-git-maven` (the H2 project), change `jdbc:h2:tcp://localhost:9092/{branch}-junit5-git-maven` to the fixed `jdbc:h2:tcp://localhost:9092/junit5-git-maven` in both the app and battery-lib poms. (These projects are outside the Tia git repo; edit in place, do not commit them into the Tia repo.)

- [ ] **Step 2: Update the docs**

In `wiki/pluggable-datastore.md` and the README datastore section: document the schema-per-branch model - Tia derives the branch from Git and isolates each branch into a `tia_<branch>` schema within one database; there is NO `{branch}` token; H2 uses one database with schemas (not a database per branch); Postgres requires the database to pre-exist (per-branch schemas are created, per-branch databases are not); the role needs CREATE-on-database (schema) privilege, not CREATEDB. Remove any `{branch}`-token references. Use ASCII hyphens.

- [ ] **Step 3: Verify + commit (Tia-repo docs only)**

Run: `grep -rniE "\{branch\}" README.md wiki/pluggable-datastore.md` -> expect no matches.
```bash
git add README.md wiki/pluggable-datastore.md
git commit -m "docs(datastore): schema-per-branch model, no {branch} token"
```

---

## Task 6: H2 perf non-regression

**Files:**
- Modify: `wiki/profiling-select-tests.md`

- [ ] **Step 1: Measure the H2 select-tests read path after the schema change**

```bash
./gradlew :tia-core:generateLargeTiaDb --console=plain -PoutDb=/tmp/tia-perf-schema \
  -PtestSuites=1000 -PsourceMethods=50000 -PavgClassesPerSuite=936 -PavgMethodsPerClass=6 -Pbranch=main
./gradlew :tia-core:profileSelectTests --console=plain -PoutDb=/tmp/tia-perf-schema \
  -Pbranch=main -PdiffFiles=20 -Piterations=5 -PfullLoad=false
```
Read the Phase-3 median (iterations 2-5, skipping warmup) and compare to the recorded baseline in `wiki/profiling-select-tests.md` (the post-dialect-extraction number, ~300 ms embedded on this shape). The per-connection `SET SCHEMA` is the only added cost.

- [ ] **Step 2: Judge + record**

If within ~1.5x of the baseline, record the new number in `wiki/profiling-select-tests.md`, noting it was taken after the schema-per-branch change and is unchanged within noise. If it regressed materially, the per-connection `SET` is heavier than expected on the read path - report it (do not bury it) for investigation.

- [ ] **Step 3: Commit**

```bash
git add wiki/profiling-select-tests.md
git commit -m "test(datastore): record H2 select-tests perf unchanged after schema-per-branch"
```

---

## Self-Review

- **Spec coverage:** schema naming -> Task 1 (`BranchSchema`); create+select mechanism -> Task 1 (dialect SQL) + Task 2 (`getConnection`); factory derives schema -> Task 2; remove `{branch}`/H2 per-branch db -> Task 3; cross-branch isolation -> Task 4; config + docs + test projects -> Task 5; H2 non-regression (suite green each task) + perf -> Tasks 2/3 (suite) + Task 6 (perf). Postgres-db-auto-create explicitly deferred (spec non-goal). All spec sections mapped.
- **Placeholder note:** Task 3 references the exact `H2ConnectionProvider` methods to change/delete rather than reprinting the class; Task 5's doc edits are described concretely with a verifying grep; the Task 2 H2-schema-casing assertion is flagged for the implementer to verify empirically (H2 quoted-identifier storage) rather than guessed. No `TODO`/`TBD`.
- **Type consistency:** `BranchSchema.schemaName(String)`, `SqlDialect.createSchemaIfNotExistsSql(String)`/`selectSchemaSql(String)`, and `JdbcDataStore(SqlDialect, ConnectionProvider, String)` are used consistently across Tasks 1-4.
