# Pluggable Datastore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users run Tia against a user-selected JDBC SQL database (PostgreSQL first, MySQL as a seam) by configuration alone, while H2 stays the zero-config default with unchanged behaviour and select-tests performance.

**Architecture:** Extract two vendor-varying concerns out of `H2DataStore` - the SQL text (`SqlDialect`) and the connection lifecycle (`ConnectionProvider`) - and rename the class to `JdbcDataStore` parameterised by both. A `DataStoreFactory` + `SqlDialectRegistry` infers the dialect from the JDBC URL scheme (overridable via `tiaDBDialect`) and constructs the store, replacing the ~10 hard-coded `new H2DataStore(...)` sites.

**Tech Stack:** Java 8, JDBC, H2 (bundled default), PostgreSQL (user-supplied driver), JUnit 5, Gradle + Maven.

## Global Constraints

- Java source/target level is `1.8` (`buildSrc/shared.gradle`) - no Java 9+ APIs.
- Unit tests use `// given` / `// when` / `// then` marker comments.
- Every new or modified method gets a javadoc with `@param`/`@return`.
- ASCII hyphens only, never em-dashes, in code/comments/commits.
- **No backwards-compatibility shims**: rename `H2DataStore` -> `JdbcDataStore` directly and update ALL callers in the same change; no deprecated alias.
- H2 stays the zero-config default; `H2Dialect` must reproduce today's SQL exactly (the existing `tia-core` H2 test suite is the behavioural oracle and must stay green after every task).
- The select-tests read path is performance-critical: no measurable regression (verified with the perf harness in Stage 1's final task).
- New spike/prod code is production code under `tia-core/src/main/...` (this is NOT the throwaway spike; do not depend on the `spike/postgres-datastore-viability` branch - only reuse its verified SQL translations as reference).
- Postgres JDBC driver for tests: `org.postgresql:postgresql:42.7.4`, test scope only.

---

## File Structure

New (all under `tia-core/src/main/java/org/tiatesting/core/persistence/`):
- `dialect/SqlDialect.java` - interface: identity DDL, upsert SQL, table-exists lookup.
- `dialect/H2Dialect.java` - reproduces today's H2 SQL exactly.
- `dialect/PostgresDialect.java` - Postgres SQL (ON CONFLICT, IDENTITY, lower-case lookup).
- `dialect/SqlDialectRegistry.java` - URL-scheme / id -> dialect + connection-provider factory.
- `connection/ConnectionProvider.java` - interface: `Connection get()`, lifecycle hooks.
- `connection/H2ConnectionProvider.java` - H2 embedded/server lifecycle (moved out of H2DataStore).
- `connection/JdbcConnectionProvider.java` - plain DriverManager provider for networked vendors.
- `DataStoreFactory.java` - the single construction point.

Renamed / modified:
- `h2/H2DataStore.java` -> `JdbcDataStore.java` (moved to `persistence/`), parameterised by `(SqlDialect, ConnectionProvider)`.
- `h2/H2ConnectionSettings.java` -> generalised connection settings consumed by the providers.
- The ~10 `new H2DataStore(...)` call sites (junit4/5 listeners, Spock extension, Gradle tasks, Maven mojos).
- `core/agent/ForkSystemProperties.java` + Maven params + Gradle extension (Stage 4 config).
- `README.md`, `WIKI.md` / `wiki/*` (Stage 5 docs).

---

## Task 1: SqlDialect interface + H2Dialect

**Files:**
- Create: `tia-core/src/main/java/org/tiatesting/core/persistence/dialect/SqlDialect.java`
- Create: `tia-core/src/main/java/org/tiatesting/core/persistence/dialect/H2Dialect.java`
- Test: `tia-core/src/test/java/org/tiatesting/core/persistence/dialect/H2DialectTest.java`

**Interfaces:**
- Produces:
  - `SqlDialect` with: `String identityColumnDefinition()`; `String upsert(String table, java.util.List<String> columns, java.util.List<String> keyColumns)`; `boolean tableExists(java.sql.Connection connection, String tableName) throws java.sql.SQLException`; `String id()`.
  - `H2Dialect implements SqlDialect`.

**Reference:** the H2 SQL to reproduce lives in `H2DataStore` - identity DDL `BIGINT AUTO_INCREMENT PRIMARY KEY` (lines 1824, 1837); the `MERGE INTO <table> (<cols>) KEY(<keycols>) VALUES (?,...)` shape (e.g. line 1261); the table-exists lookup `getMetaData().getTables(null, null, tableName.toUpperCase(), {"TABLE"})` (line 1896-1898).

- [ ] **Step 1: Write the failing test**

```java
package org.tiatesting.core.persistence.dialect;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class H2DialectTest {

    @Test
    void identityColumnMatchesH2() {
        // given
        H2Dialect dialect = new H2Dialect();
        // when
        String ddl = dialect.identityColumnDefinition();
        // then
        assertEquals("BIGINT AUTO_INCREMENT PRIMARY KEY", ddl);
    }

    @Test
    void upsertUsesMergeIntoWithKey() {
        // given
        H2Dialect dialect = new H2Dialect();
        // when
        String sql = dialect.upsert("tia_test_suite",
                Arrays.asList("name", "num_runs"), Arrays.asList("name"));
        // then
        assertEquals("MERGE INTO tia_test_suite (name, num_runs) KEY(name) VALUES (?, ?)", sql);
    }

    @Test
    void idIsH2() {
        // given / when / then
        assertEquals("h2", new H2Dialect().id());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :tia-core:test --tests '*H2DialectTest' --console=plain`
Expected: FAIL - `SqlDialect` / `H2Dialect` do not exist (compile error).

- [ ] **Step 3: Write SqlDialect**

```java
package org.tiatesting.core.persistence.dialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Vendor-specific SQL for the Tia JDBC datastore. See the pluggable-datastore WIKI chapter. */
public interface SqlDialect {

    /**
     * The DDL fragment for an auto-incrementing BIGINT primary-key column.
     * @return the vendor-specific identity column definition
     */
    String identityColumnDefinition();

    /**
     * Build a parameterised upsert (insert-or-update) statement.
     * @param table the target table
     * @param columns all inserted columns, in bind order
     * @param keyColumns the conflict/merge key columns (subset of columns)
     * @return the vendor-specific upsert SQL with one {@code ?} per column
     */
    String upsert(String table, List<String> columns, List<String> keyColumns);

    /**
     * Whether a table exists, accounting for the vendor's identifier case folding.
     * @param connection an open connection
     * @param tableName the unquoted table name as written in the DDL
     * @return true if the table exists
     * @throws SQLException on metadata access failure
     */
    boolean tableExists(Connection connection, String tableName) throws SQLException;

    /**
     * The stable dialect id used for the {@code tiaDBDialect} override and error messages.
     * @return the dialect id (e.g. "h2", "postgres")
     */
    String id();
}
```

- [ ] **Step 4: Write H2Dialect**

```java
package org.tiatesting.core.persistence.dialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** H2 dialect - reproduces the SQL H2DataStore emitted before the dialect extraction. */
public final class H2Dialect implements SqlDialect {

    /** {@inheritDoc} H2 uses {@code AUTO_INCREMENT}. */
    @Override public String identityColumnDefinition() { return "BIGINT AUTO_INCREMENT PRIMARY KEY"; }

    /**
     * {@inheritDoc} H2 uses {@code MERGE INTO ... KEY(...) VALUES (...)}.
     */
    @Override
    public String upsert(String table, List<String> columns, List<String> keyColumns) {
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) { placeholders.append(i == 0 ? "?" : ", ?"); }
        return "MERGE INTO " + table + " (" + String.join(", ", columns) + ") KEY("
                + String.join(", ", keyColumns) + ") VALUES (" + placeholders + ")";
    }

    /** {@inheritDoc} H2 folds unquoted identifiers to upper case. */
    @Override
    public boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet rs = connection.getMetaData()
                .getTables(null, null, tableName.toUpperCase(), new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    /** {@inheritDoc} */
    @Override public String id() { return "h2"; }
}
```

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :tia-core:test --tests '*H2DialectTest' --console=plain`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/persistence/dialect tia-core/src/test/java/org/tiatesting/core/persistence/dialect
git commit -m "feat(datastore): add SqlDialect interface + H2Dialect"
```

---

## Task 2: ConnectionProvider + H2ConnectionProvider

**Files:**
- Create: `tia-core/src/main/java/org/tiatesting/core/persistence/connection/ConnectionProvider.java`
- Create: `tia-core/src/main/java/org/tiatesting/core/persistence/connection/H2ConnectionProvider.java`
- Test: `tia-core/src/test/java/org/tiatesting/core/persistence/connection/H2ConnectionProviderTest.java`

**Interfaces:**
- Consumes: the existing `org.tiatesting.core.persistence.h2.H2ConnectionSettings` (its `getDbFilePath`, `getDbUrl`, `getUsername`, `getPassword`, `getBranchSuffix`, `isServerMode`, and the `embedded`/`server` factories).
- Produces: `ConnectionProvider` with `java.sql.Connection get() throws java.sql.SQLException` and `String jdbcUrl()`; `H2ConnectionProvider implements ConnectionProvider` built from `H2ConnectionSettings`.

**Reference:** move the exact connection-URL construction and `getConnection()` body from `H2DataStore` (constructor lines 131-159 build `jdbcURL`/`username`/`password`; `getConnection()` opens them; the embedded URL appends `PAGE_SIZE`/`CACHE_SIZE`/`DB_CLOSE_DELAY=-1`/`DB_CLOSE_ON_EXIT=FALSE` - see the URL-building javadoc around lines 2308-2349). Preserve that logic verbatim; only its home changes.

- [ ] **Step 1: Write the failing test**

```java
package org.tiatesting.core.persistence.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.nio.file.Path;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

class H2ConnectionProviderTest {

    @Test
    void opensEmbeddedH2Connection(@TempDir Path dir) throws Exception {
        // given
        H2ConnectionProvider provider = new H2ConnectionProvider(
                H2ConnectionSettings.embedded(dir.toString(), "main"));
        // when
        try (Connection c = provider.get()) {
            // then
            assertTrue(c.isValid(2));
            assertTrue(provider.jdbcUrl().startsWith("jdbc:h2:"));
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :tia-core:test --tests '*H2ConnectionProviderTest' --console=plain`
Expected: FAIL - classes absent.

- [ ] **Step 3: Write ConnectionProvider + H2ConnectionProvider**

`ConnectionProvider`:
```java
package org.tiatesting.core.persistence.connection;

import java.sql.Connection;
import java.sql.SQLException;

/** Vendor-specific JDBC connection acquisition + lifecycle for the Tia datastore. */
public interface ConnectionProvider {
    /**
     * Open (or reuse) a connection to the configured database.
     * @return an open JDBC connection
     * @throws SQLException on connection failure
     */
    Connection get() throws SQLException;

    /**
     * The JDBC URL this provider connects to (password-free), for logging/errors.
     * @return the JDBC URL
     */
    String jdbcUrl();
}
```

`H2ConnectionProvider`: move the URL-building + `getConnection` retry logic from `H2DataStore` (constructor + `getConnection()` + the embedded/server URL helpers) into this class, keeping behaviour identical. Its constructor takes `H2ConnectionSettings`; `get()` returns the same connection `H2DataStore.getConnection()` returns today (including the server-mode retry described in the connection-retry memory); `jdbcUrl()` returns the built URL.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :tia-core:test --tests '*H2ConnectionProviderTest' --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/persistence/connection tia-core/src/test/java/org/tiatesting/core/persistence/connection
git commit -m "feat(datastore): add ConnectionProvider + H2ConnectionProvider"
```

---

## Task 3: Rename H2DataStore -> JdbcDataStore, parameterise by dialect + provider

**Files:**
- Rename: `tia-core/src/main/java/org/tiatesting/core/persistence/h2/H2DataStore.java` -> `tia-core/src/main/java/org/tiatesting/core/persistence/JdbcDataStore.java`
- Modify: every `new H2DataStore(...)` caller (temporarily construct `new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(settings))` - the factory replaces this in Task 4).

**Interfaces:**
- Consumes: `SqlDialect`, `ConnectionProvider`, `H2Dialect`, `H2ConnectionProvider`.
- Produces: `public final class JdbcDataStore implements DataStore` with constructor `JdbcDataStore(SqlDialect dialect, ConnectionProvider connectionProvider)`.

- [ ] **Step 1: Rename the class + move package**

`git mv tia-core/src/main/java/org/tiatesting/core/persistence/h2/H2DataStore.java tia-core/src/main/java/org/tiatesting/core/persistence/JdbcDataStore.java`, rename the class to `JdbcDataStore`, update the `package` line to `org.tiatesting.core.persistence`.

- [ ] **Step 2: Replace the constructor + connection with the injected provider**

Change the constructor to `public JdbcDataStore(SqlDialect dialect, ConnectionProvider connectionProvider)`, store both as fields, delete the `jdbcURL`/`username`/`password` fields and the private `getConnection()` URL-building (now in `H2ConnectionProvider`); make the private `getConnection()` delegate to `connectionProvider.get()`.

- [ ] **Step 3: Route the vendor-varying SQL through the dialect**

- The 5 `MERGE INTO ...` statements (lines ~621, 736, 1056, 1261, and the tia_source_class_method suite-mapping merge): replace each with `dialect.upsert(table, columns, keyColumns)`, binding parameters in the same order. Keep the surrounding transaction/batch logic identical.
- The 2 `"BIGINT AUTO_INCREMENT PRIMARY KEY"` DDL fragments (lines 1824, 1837): replace the literal with `dialect.identityColumnDefinition()`.
- The private `checkTableExists(Connection, String)` (line 1896): replace its body with `return dialect.tableExists(connection, tableName);` (removing the inline `toUpperCase()`).

- [ ] **Step 4: Update all callers**

Replace every `new H2DataStore(<settings>)` with `new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(<settings>))`, and fix imports. The sites (from `grep -rn "new H2DataStore" --include=*.java`):
`tia-spock/.../TiaSpockGlobalExtension.java`, `tia-junit4/.../TiaJunit4Listener.java`, `tia-junit5/.../TiaTestExecutionListener.java` (or equivalent), `tia-gradle/.../TiaLibraryPublishesTask.java`, `TiaHistoryTask.java`, `TiaLibraryPendingMethodsTask.java`, `TiaBasePlugin.java` (several), `tia-maven-plugin/.../AbstractTextReportMojo.java`, and the perf harness `GenerateLargeTiaDb.java` / `ProfileSelectTests.java` (test scope). Update each and its imports.

- [ ] **Step 5: Run the FULL existing H2 suite (the behavioural oracle) + compile all modules**

Run: `./gradlew build --console=plain`
Expected: BUILD SUCCESSFUL - the entire existing test suite passes unchanged (proves `H2Dialect` + `H2ConnectionProvider` reproduce behaviour). If any H2 test fails, the extraction diverged from the original SQL/lifecycle - fix before committing.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(datastore): rename H2DataStore -> JdbcDataStore, parameterise by dialect + provider"
```

---

## Task 4: DataStoreFactory + SqlDialectRegistry (H2-only), replace call sites

**Files:**
- Create: `tia-core/src/main/java/org/tiatesting/core/persistence/dialect/SqlDialectRegistry.java`
- Create: `tia-core/src/main/java/org/tiatesting/core/persistence/DataStoreFactory.java`
- Test: `tia-core/src/test/java/org/tiatesting/core/persistence/DataStoreFactoryTest.java`
- Modify: the call sites from Task 3 (swap the direct `new JdbcDataStore(...)` for the factory).

**Interfaces:**
- Produces:
  - `SqlDialectRegistry` with `static SqlDialect forUrl(String jdbcUrl, String dialectOverride)` (returns `H2Dialect` for `jdbc:h2` / null-url / `dialectOverride=="h2"`; throws `IllegalArgumentException` listing supported ids otherwise - Postgres added in Task 6).
  - `DataStoreFactory` with `static DataStore fromSystemProperties(String branch)` and `static DataStore fromConfig(String dbFilePath, String dbUrl, String user, String password, String dialectOverride, String branch)`, mirroring the existing `H2ConnectionSettings.fromSystemProperties`/`fromConfig` inputs.

- [ ] **Step 1: Write the failing test**

```java
package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DataStoreFactoryTest {

    @Test
    void buildsH2StoreForNullUrl(@TempDir Path dir) {
        // given / when
        DataStore store = DataStoreFactory.fromConfig(dir.toString(), null, "sa", "", null, "main");
        // then
        assertNotNull(store);
        assertTrue(store instanceof JdbcDataStore);
    }

    @Test
    void unknownUrlSchemeThrowsWithSupportedList() {
        // given / when / then
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DataStoreFactory.fromConfig(null, "jdbc:oracle:thin:@x", "u", "p", null, "main"));
        assertTrue(ex.getMessage().toLowerCase().contains("h2"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :tia-core:test --tests '*DataStoreFactoryTest' --console=plain`
Expected: FAIL - classes absent.

- [ ] **Step 3: Write SqlDialectRegistry + DataStoreFactory**

`SqlDialectRegistry.forUrl`: if `dialectOverride` is non-empty, look it up by id (`h2` -> `H2Dialect`; else throw listing ids). Otherwise if `jdbcUrl` is null/blank or starts with `jdbc:h2`, return `H2Dialect`; else throw `new IllegalArgumentException("Unsupported JDBC URL '" + jdbcUrl + "'. Supported dialects: [h2]")`.
`DataStoreFactory.fromConfig`: resolve the dialect via the registry; for H2 build `H2ConnectionSettings.fromConfig(...)` + `H2ConnectionProvider` and return `new JdbcDataStore(dialect, provider)`. `fromSystemProperties(branch)`: read the same system properties `H2ConnectionSettings.fromSystemProperties` reads (`tiaDBFilePath`/`tiaDBUrl`/`tiaDBUser`/`tiaDBPassword`) plus the new `tiaDBDialect`, and delegate to `fromConfig`.

- [ ] **Step 4: Replace the call sites with the factory**

Change every `new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.fromSystemProperties(branch)))` (from Task 3) to `DataStoreFactory.fromSystemProperties(branch)`, and the report/task sites that build settings from config to `DataStoreFactory.fromConfig(...)`. Same ~10 sites.

- [ ] **Step 5: Run full build (H2 suite still green)**

Run: `./gradlew build --console=plain`
Expected: BUILD SUCCESSFUL, `DataStoreFactoryTest` passes.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(datastore): DataStoreFactory + SqlDialectRegistry, route all callers through the factory"
```

---

## Task 5: PostgresDialect

**Files:**
- Create: `tia-core/src/main/java/org/tiatesting/core/persistence/dialect/PostgresDialect.java`
- Test: `tia-core/src/test/java/org/tiatesting/core/persistence/dialect/PostgresDialectTest.java`

**Interfaces:**
- Produces: `PostgresDialect implements SqlDialect` (`id()` returns `"postgres"`).

**Reference:** the verified translations from the viability spike (`spike/postgres-datastore-viability` branch, `PostgresDataStore`/`PostgresSchema`): identity `BIGINT GENERATED BY DEFAULT AS IDENTITY`; upsert `INSERT ... ON CONFLICT (keys) DO UPDATE SET col=EXCLUDED.col,...`; table-exists lookup with the name lower-cased (Postgres folds unquoted identifiers to lower case).

- [ ] **Step 1: Write the failing test**

```java
package org.tiatesting.core.persistence.dialect;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class PostgresDialectTest {

    @Test
    void identityUsesGeneratedAsIdentity() {
        // given / when / then
        assertEquals("BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY",
                new PostgresDialect().identityColumnDefinition());
    }

    @Test
    void upsertUsesOnConflictDoUpdate() {
        // given
        PostgresDialect dialect = new PostgresDialect();
        // when
        String sql = dialect.upsert("tia_test_suite",
                Arrays.asList("name", "num_runs"), Arrays.asList("name"));
        // then
        assertEquals("INSERT INTO tia_test_suite (name, num_runs) VALUES (?, ?) "
                + "ON CONFLICT (name) DO UPDATE SET num_runs = EXCLUDED.num_runs", sql);
    }

    @Test
    void idIsPostgres() {
        // given / when / then
        assertEquals("postgres", new PostgresDialect().id());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :tia-core:test --tests '*PostgresDialectTest' --console=plain`
Expected: FAIL - class absent.

- [ ] **Step 3: Write PostgresDialect**

```java
package org.tiatesting.core.persistence.dialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** PostgreSQL dialect. SQL translations verified in the datastore viability spike. */
public final class PostgresDialect implements SqlDialect {

    /** {@inheritDoc} */
    @Override public String identityColumnDefinition() {
        return "BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY";
    }

    /** {@inheritDoc} Postgres upsert via {@code INSERT ... ON CONFLICT (keys) DO UPDATE}. */
    @Override
    public String upsert(String table, List<String> columns, List<String> keyColumns) {
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) { ph.append(i == 0 ? "?" : ", ?"); }
        List<String> updates = new ArrayList<>();
        for (String c : columns) {
            if (!keyColumns.contains(c)) { updates.add(c + " = EXCLUDED." + c); }
        }
        return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES (" + ph + ") "
                + "ON CONFLICT (" + String.join(", ", keyColumns) + ") DO UPDATE SET "
                + String.join(", ", updates);
    }

    /** {@inheritDoc} Postgres folds unquoted identifiers to lower case. */
    @Override
    public boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet rs = connection.getMetaData()
                .getTables(null, null, tableName.toLowerCase(), new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    /** {@inheritDoc} */
    @Override public String id() { return "postgres"; }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :tia-core:test --tests '*PostgresDialectTest' --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/persistence/dialect/PostgresDialect.java tia-core/src/test/java/org/tiatesting/core/persistence/dialect/PostgresDialectTest.java
git commit -m "feat(datastore): add PostgresDialect"
```

---

## Task 6: JdbcConnectionProvider + register Postgres in the factory

**Files:**
- Create: `tia-core/src/main/java/org/tiatesting/core/persistence/connection/JdbcConnectionProvider.java`
- Modify: `SqlDialectRegistry.java` (register `jdbc:postgresql` -> PostgresDialect), `DataStoreFactory.java` (build a `JdbcConnectionProvider` for non-H2 dialects).
- Modify: `tia-core/build.gradle` (add `testImplementation 'org.postgresql:postgresql:42.7.4'`).
- Test: `tia-core/src/test/java/org/tiatesting/core/persistence/DataStoreFactoryPostgresTest.java`

**Interfaces:**
- Produces: `JdbcConnectionProvider implements ConnectionProvider` with constructor `(String jdbcUrl, String user, String password)`; `get()` returns `DriverManager.getConnection(jdbcUrl, user, password)`.

- [ ] **Step 1: Write the failing test (guarded to skip when no Postgres)**

```java
package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

class DataStoreFactoryPostgresTest {

    private void assumePg() {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress("localhost", 5432), 500);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "spike Postgres not running");
        }
    }

    @Test
    void buildsPostgresStoreFromUrl() {
        // given
        assumePg();
        // when
        DataStore store = DataStoreFactory.fromConfig(null,
                "jdbc:postgresql://localhost:5432/tiaperf", "tia", "tia", null, "main");
        // then
        assertTrue(store instanceof JdbcDataStore);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :tia-core:test --tests '*DataStoreFactoryPostgresTest' --console=plain`
Expected: FAIL - `jdbc:postgresql` unsupported in the registry (or class absent).

- [ ] **Step 3: Write JdbcConnectionProvider + register Postgres**

`JdbcConnectionProvider`: fields `jdbcUrl`/`user`/`password`; `get()` -> `DriverManager.getConnection(jdbcUrl, user, password)`; `jdbcUrl()` -> the url. In `SqlDialectRegistry.forUrl`, add `jdbc:postgresql` -> `new PostgresDialect()` and `postgres` to the id map + supported-ids list. In `DataStoreFactory.fromConfig`, when the dialect is not H2, build `new JdbcConnectionProvider(dbUrl, user, password)` instead of `H2ConnectionProvider`.

- [ ] **Step 4: Run to verify pass (Postgres up)**

Run: `spike/postgres/setup.sh` then `./gradlew :tia-core:test --tests '*DataStoreFactoryPostgresTest' --console=plain`
Expected: PASS (not skipped). Note: this reuses the spike harness at `spike/postgres/` from the viability branch; if not present, start any local Postgres on 5432 with db `tiaperf`, user/pass `tia`/`tia`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(datastore): JdbcConnectionProvider + Postgres registration in the factory"
```

---

## Task 7: H2/Postgres selection-equivalence integration test

**Files:**
- Test: `tia-core/src/test/java/org/tiatesting/core/persistence/DatastoreEquivalenceTest.java`

**Interfaces:**
- Consumes: `DataStoreFactory`, the perf harness `GenerateLargeTiaDb` (now targeting either backend via the factory) and `TestSelector`.

**Reference:** the spike's `PostgresH2EquivalenceTest` proved identical selection across backends. Reproduce it against the production `DataStoreFactory` path (not the spike's throwaway `PostgresDataStore`).

- [ ] **Step 1: Write the guarded equivalence test**

Seed the same small synthetic mapping (deterministic seed) into an embedded H2 and into Postgres via `DataStoreFactory`, run `TestSelector.selectTestsToIgnore` with the same synthetic diff against each, and assert the ignore sets are equal and non-empty. Guard with the same `assumePg()` reachability skip as Task 6. (Model the seeding + stub VCS reader on the spike's `SpikeSeedFixture`; keep it under `tia-core/src/test`.)

- [ ] **Step 2: Run to verify it fails, then passes**

Run: `./gradlew :tia-core:test --tests '*DatastoreEquivalenceTest' --console=plain` (Postgres up)
Expected: PASS, sets equal + non-empty. If unequal, a production dialect query diverged from H2 - fix before committing.

- [ ] **Step 3: Commit**

```bash
git add tia-core/src/test/java/org/tiatesting/core/persistence/DatastoreEquivalenceTest.java
git commit -m "test(datastore): H2/Postgres selection-equivalence via the factory"
```

---

## Task 8: Config plumbing - tiaDBDialect through Maven, Gradle, and the fork bridge

**Files:**
- Modify: `tia-core/src/main/java/org/tiatesting/core/agent/ForkSystemProperties.java` (add the `tiaDBDialect` key to the forwarded set).
- Modify: `tia-maven-plugin/.../AbstractTiaMojo.java` (add a `tiaDBDialect` `@Parameter` alongside `tiaDBUrl`).
- Modify: `tia-maven-plugin/.../AbstractTiaAgentMojo.java` (forward `tiaDBDialect` to the agent, next to `tiaDBUrl`).
- Modify: the Gradle extension (`tia-gradle/.../TiaBaseTaskExtension.java` or the plugin's config class) to expose `dbDialect`, and pass it through to the datastore construction / fork properties.
- Test: `tia-core/src/test/java/org/tiatesting/core/agent/ForkSystemPropertiesTest.java` (extend or add).

**Interfaces:**
- Consumes: `DataStoreFactory.fromSystemProperties` (already reads `tiaDBDialect` from Task 4).

- [ ] **Step 1: Write the failing test**

Assert `ForkSystemProperties` round-trips a `tiaDBDialect` value (written by the plugin, read at premain) - mirror the existing test for `tiaDBUrl`. If no such test exists, add one asserting the key is included in the forwarded properties when set and omitted when null.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :tia-core:test --tests '*ForkSystemPropertiesTest' --console=plain`
Expected: FAIL - key not forwarded.

- [ ] **Step 3: Add tiaDBDialect end to end**

Add the `tiaDBDialect` key to `ForkSystemProperties`'s forwarded map (guarded on non-null, like `tiaDBUrl`); add the Maven `@Parameter(property = "tiaDBDialect")` field + getter in `AbstractTiaMojo` and forward it in `AbstractTiaAgentMojo` (mirror `getTiaDBUrl()`); add `dbDialect` to the Gradle extension and thread it into the fork properties / factory call.

- [ ] **Step 4: Run to verify pass + compile all modules**

Run: `./gradlew build --console=plain`
Expected: BUILD SUCCESSFUL, the fork-properties test passes.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(datastore): plumb tiaDBDialect through Maven, Gradle, and the fork bridge"
```

---

## Task 9: Actionable driver + dialect errors

**Files:**
- Modify: `DataStoreFactory.java` (wrap driver loading / connection with clear errors).
- Test: `tia-core/src/test/java/org/tiatesting/core/persistence/DataStoreFactoryErrorTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataStoreFactoryErrorTest {

    @Test
    void unknownDialectOverrideListsSupported() {
        // given / when / then
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DataStoreFactory.fromConfig(null, "jdbc:postgresql://h/db", "u", "p", "oracle", "main"));
        assertTrue(ex.getMessage().contains("postgres"));
        assertTrue(ex.getMessage().contains("h2"));
    }

    @Test
    void missingDriverMessageNamesVendorAndClasspath() {
        // given a postgres URL but the driver class made unavailable is hard to force in-process;
        // instead assert the factory's driver-presence check produces the actionable message.
        // when / then
        String msg = DataStoreFactory.missingDriverMessage("postgres");
        assertTrue(msg.contains("postgres"));
        assertTrue(msg.toLowerCase().contains("driver"));
        assertTrue(msg.toLowerCase().contains("dependenc"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :tia-core:test --tests '*DataStoreFactoryErrorTest' --console=plain`
Expected: FAIL.

- [ ] **Step 3: Implement the error paths**

Add `static String missingDriverMessage(String dialectId)` returning e.g. `"Tia could not find the " + dialectId + " JDBC driver on the classpath. Add the driver as a test-scope dependency in your project AND as a dependency of the Tia plugin (see the pluggable-datastore docs)."`. In `fromConfig`, before opening a non-H2 connection, check the driver is registered (`DriverManager.getDrivers()` / attempt `Class.forName` of the known driver class per dialect) and throw `IllegalStateException(missingDriverMessage(id))` if absent. Ensure the unknown-dialect-override path lists supported ids (already partly in the registry; assert message content).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :tia-core:test --tests '*DataStoreFactoryErrorTest' --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(datastore): actionable missing-driver and unknown-dialect errors"
```

---

## Task 10: Documentation - two-classpath driver model + config

**Files:**
- Modify: `README.md`
- Create: `wiki/pluggable-datastore.md`; Modify: `WIKI.md` (index link)

- [ ] **Step 1: Write the docs**

README: add a "Using a different database" subsection under the datastore docs covering the `tiaDBUrl`/`tiaDBUser`/`tiaDBPassword`/`tiaDBDialect` config, a Postgres example, and the two-place driver declaration (test-scope dependency + Maven `<plugin>` dependency / Gradle plugin dependency) with a worked pom/build.gradle snippet. WIKI: a `pluggable-datastore.md` chapter describing the `SqlDialect` + `ConnectionProvider` + `DataStoreFactory` architecture, URL-scheme dialect inference, the two-classpath driver model and why it exists, H2 as the default, and the MySQL seam (what a `MySqlDialect` fills in). Add the chapter to the `WIKI.md` index. Use ASCII hyphens only.

- [ ] **Step 2: Verify no broken internal references / placeholders**

Run: `grep -niE '<fill>|TODO|TBD' README.md wiki/pluggable-datastore.md WIKI.md`
Expected: no output.

- [ ] **Step 3: Commit**

```bash
git add README.md WIKI.md wiki/pluggable-datastore.md
git commit -m "docs(datastore): pluggable datastore config + two-classpath driver model"
```

---

## Task 11: H2 perf non-regression check

**Files:**
- No product code. Records a before/after measurement.

- [ ] **Step 1: Measure the select-tests read path on H2 after the refactor**

Generate a large synthetic H2 DB and profile the read path:
```bash
./gradlew :tia-core:generateLargeTiaDb --console=plain -PoutDb=/tmp/tia-perf-h2 \
  -PtestSuites=1000 -PsourceMethods=50000 -PavgClassesPerSuite=936 -PavgMethodsPerClass=6 -Pbranch=main
./gradlew :tia-core:profileSelectTests --console=plain -PoutDb=/tmp/tia-perf-h2 \
  -Pbranch=main -PdiffFiles=20 -Piterations=5 -PfullLoad=false
```
Expected: Phase-3 read median in the same range as the pre-refactor baseline recorded in the perf memory/WIKI (sub-second on this shape). A regression (e.g. > ~1.5x the baseline) means the dialect indirection added cost on the hot path - investigate before finishing.

- [ ] **Step 2: Record the number in the WIKI perf chapter**

Append the post-refactor H2 read median to the profiling-select-tests WIKI chapter as the new baseline, noting it was taken after the dialect extraction and is unchanged within noise.

- [ ] **Step 3: Commit**

```bash
git add wiki/profiling-select-tests.md
git commit -m "test(datastore): record H2 select-tests perf unchanged after dialect extraction"
```

---

## Self-Review

- **Spec coverage:** SqlDialect + ConnectionProvider extraction -> Tasks 1-3; JdbcDataStore rename -> Task 3; DataStoreFactory + registry + URL-scheme inference -> Tasks 4, 6; PostgresDialect -> Task 5; Postgres connection + registration -> Task 6; config surface (tiaDBDialect + fork bridge + Maven/Gradle) -> Task 8; driver provisioning errors -> Task 9; docs (two-classpath model) -> Task 10; H2 non-regression (suite green every task + perf) -> Tasks 3/4 (suite) + Task 11 (perf); Postgres equivalence -> Task 7; MySQL seam (doc note only) -> Task 10. All spec sections mapped.
- **Placeholder note:** Task 3 (the 2000-line rename/parameterise) references exact `H2DataStore` line/method locations and the precise transformation for each vendor-varying site rather than reprinting the class; the new units (dialects, providers, factory) carry literal code. No `TODO`/`TBD` in any step.
- **Type consistency:** `SqlDialect` (`identityColumnDefinition`, `upsert(table, columns, keyColumns)`, `tableExists(Connection, String)`, `id()`), `ConnectionProvider` (`get()`, `jdbcUrl()`), `DataStoreFactory` (`fromSystemProperties(String)`, `fromConfig(dbFilePath, dbUrl, user, password, dialectOverride, branch)`, `missingDriverMessage(String)`), and `JdbcDataStore(SqlDialect, ConnectionProvider)` are used consistently across Tasks 1-9.
