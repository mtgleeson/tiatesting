# Postgres database auto-creation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On connect, auto-create the configured Postgres database when it is missing and the role holds `CREATEDB` (H2 parity), then proceed to the existing per-branch schema create/select.

**Architecture:** A new `PostgresConnectionProvider extends JdbcConnectionProvider` owns the whole flow: its `get()` catches SQLState `3D000` (database missing), creates the database over a maintenance connection to the `postgres` administrative database, and retries once. `DataStoreFactory` routes the postgres dialect to it; `JdbcDataStore` and `SqlDialect` are untouched, so the perf-critical schema path does not change.

**Tech Stack:** Java 8, JUnit 5, Gradle, PostgreSQL JDBC driver (`org.postgresql:postgresql:42.7.4`, already on tia-core's test classpath).

## Global Constraints

- Every new/modified test method uses `// given` / `// when` / `// then` marker comments.
- Every new/modified method gets a javadoc explaining its purpose, with `@param` for each parameter and `@return` when it returns a value.
- ASCII hyphen `-` only, never the em-dash, in all code, comments, javadocs, and commit messages.
- No backwards-compatibility shims: change signatures directly and update all callers in the same change.
- Reference `wiki/pluggable-datastore.md` (not this plan or the design doc) from any code comment or javadoc that needs a doc pointer.
- Staged delivery: after each task, stop and wait for review before starting the next. Print a short commit-message-ready summary (files touched, what/why, any deferral).
- Compile the affected module after changes: `./gradlew :tia-core:compileJava :tia-core:compileTestJava`.
- End every commit message with:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- Branch: all work lands on `feature/postgres-db-auto-create` (already created, stacked on `feature/pluggable-datastore` @ 48ece3d).

---

## File Structure

- `tia-core/src/main/java/org/tiatesting/core/persistence/connection/PostgresConnectionProvider.java` (create) - the provider: pure URL/message helpers (Task 1), then the auto-create `get()` flow (Task 2).
- `tia-core/src/main/java/org/tiatesting/core/persistence/TiaPersistenceException.java` (modify, Task 2) - add a `(String, Throwable)` constructor so the CREATEDB-gate message can carry the driver cause.
- `tia-core/src/main/java/org/tiatesting/core/persistence/DataStoreFactory.java` (modify, Task 2) - route the postgres dialect to `PostgresConnectionProvider`.
- `tia-core/src/main/java/org/tiatesting/core/persistence/JdbcDataStore.java` (modify, Task 2) - add a package-private `getConnectionProvider()` accessor so a no-DB routing test can assert the wired provider type.
- `tia-core/src/test/java/org/tiatesting/core/persistence/connection/PostgresConnectionProviderHelpersTest.java` (create, Task 1) - unit tests for the pure helpers (no database).
- `tia-core/src/test/java/org/tiatesting/core/persistence/DataStoreFactoryPostgresRoutingTest.java` (create, Task 2) - no-DB routing test.
- `tia-core/src/test/java/org/tiatesting/core/persistence/connection/PostgresDbAutoCreateTest.java` (create, Task 2) - guarded integration tests (create + idempotent), skipped when Postgres is unreachable.
- `wiki/pluggable-datastore.md` (modify, Task 3) and `README.md` (modify, Task 3) - document the auto-create behaviour and the three-way privilege story.

---

### Task 1: Pure URL and message helpers

Creates `PostgresConnectionProvider` with only its constructor and three package-private static helpers - `databaseName`, `maintenanceUrl`, `createDbPrivilegeErrorMessage` - plus a private `parseSegments` splitter. No `get()` override yet: the class inherits `JdbcConnectionProvider.get()` and behaves like the generic provider until Task 2. This isolates the fiddly URL parsing and the exact error wording into pure, exhaustively unit-tested functions.

**Files:**
- Create: `tia-core/src/main/java/org/tiatesting/core/persistence/connection/PostgresConnectionProvider.java`
- Test: `tia-core/src/test/java/org/tiatesting/core/persistence/connection/PostgresConnectionProviderHelpersTest.java`

**Interfaces:**
- Consumes: `JdbcConnectionProvider(String jdbcUrl, String user, String password)` (existing superclass constructor).
- Produces (used by Task 2):
  - `static String databaseName(String jdbcUrl)` - the target database segment.
  - `static String maintenanceUrl(String jdbcUrl)` - the URL with the database segment replaced by `postgres`, authority and query params preserved.
  - `static String createDbPrivilegeErrorMessage(String databaseName, String driverMessage)` - the CREATEDB-gate message, with the driver's own message embedded inline.
  - `public PostgresConnectionProvider(String jdbcUrl, String user, String password)`.

- [ ] **Step 1: Write the failing tests**

Create `tia-core/src/test/java/org/tiatesting/core/persistence/connection/PostgresConnectionProviderHelpersTest.java`:

```java
package org.tiatesting.core.persistence.connection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PostgresConnectionProvider}'s pure URL and message helpers, which carry the
 * fiddly maintenance-URL derivation and the CREATEDB-gate wording. No database is required. See the
 * pluggable-datastore WIKI chapter.
 */
class PostgresConnectionProviderHelpersTest {

    @Test
    void databaseNameNoPort() {
        // given
        String url = "jdbc:postgresql://localhost/tia_junit5";
        // when
        String db = PostgresConnectionProvider.databaseName(url);
        // then
        assertEquals("tia_junit5", db);
    }

    @Test
    void databaseNameWithPort() {
        // given
        String url = "jdbc:postgresql://localhost:5432/tia_junit5";
        // when
        String db = PostgresConnectionProvider.databaseName(url);
        // then
        assertEquals("tia_junit5", db);
    }

    @Test
    void databaseNameWithParams() {
        // given
        String url = "jdbc:postgresql://localhost:5432/tia_junit5?ssl=true&foo=bar";
        // when
        String db = PostgresConnectionProvider.databaseName(url);
        // then
        assertEquals("tia_junit5", db);
    }

    @Test
    void maintenanceUrlNoPort() {
        // given
        String url = "jdbc:postgresql://localhost/tia_junit5";
        // when
        String maintenance = PostgresConnectionProvider.maintenanceUrl(url);
        // then
        assertEquals("jdbc:postgresql://localhost/postgres", maintenance);
    }

    @Test
    void maintenanceUrlWithPort() {
        // given
        String url = "jdbc:postgresql://localhost:5432/tia_junit5";
        // when
        String maintenance = PostgresConnectionProvider.maintenanceUrl(url);
        // then
        assertEquals("jdbc:postgresql://localhost:5432/postgres", maintenance);
    }

    @Test
    void maintenanceUrlPreservesParams() {
        // given
        String url = "jdbc:postgresql://localhost:5432/tia_junit5?ssl=true&foo=bar";
        // when
        String maintenance = PostgresConnectionProvider.maintenanceUrl(url);
        // then
        assertEquals("jdbc:postgresql://localhost:5432/postgres?ssl=true&foo=bar", maintenance);
    }

    @Test
    void maintenanceUrlPreservesMultiHostAuthority() {
        // given
        String url = "jdbc:postgresql://h1:5432,h2:5432/tia_junit5";
        // when
        String maintenance = PostgresConnectionProvider.maintenanceUrl(url);
        // then
        assertEquals("jdbc:postgresql://h1:5432,h2:5432/postgres", maintenance);
    }

    @Test
    void maintenanceUrlWhenTargetAlreadyPostgres() {
        // given
        String url = "jdbc:postgresql://localhost:5432/postgres";
        // when
        String maintenance = PostgresConnectionProvider.maintenanceUrl(url);
        // then
        assertEquals("jdbc:postgresql://localhost:5432/postgres", maintenance);
    }

    @Test
    void emptyDatabaseSegmentThrows() {
        // given
        String url = "jdbc:postgresql://localhost:5432/";
        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> PostgresConnectionProvider.databaseName(url));
    }

    @Test
    void nonPostgresUrlThrows() {
        // given
        String url = "jdbc:h2:tcp://localhost:9092/tiadb";
        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> PostgresConnectionProvider.maintenanceUrl(url));
    }

    @Test
    void privilegeErrorMessageEmbedsDbNameAndDriverMessage() {
        // given
        String driverMessage = "ERROR: permission denied to create database";
        // when
        String message = PostgresConnectionProvider.createDbPrivilegeErrorMessage("tia_junit5", driverMessage);
        // then
        assertTrue(message.contains("tia_junit5"));
        assertTrue(message.contains("CREATEDB"));
        assertTrue(message.contains(driverMessage));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :tia-core:compileTestJava --tests "org.tiatesting.core.persistence.connection.PostgresConnectionProviderHelpersTest"`
Expected: FAIL - compilation error, `PostgresConnectionProvider` does not exist / symbol not found.

- [ ] **Step 3: Write the class with the helpers**

Create `tia-core/src/main/java/org/tiatesting/core/persistence/connection/PostgresConnectionProvider.java`:

```java
package org.tiatesting.core.persistence.connection;

/**
 * Postgres-specific {@link ConnectionProvider}. Beyond the generic {@link JdbcConnectionProvider}
 * behaviour it inherits, it auto-creates the configured database when it does not yet exist and the
 * connecting role is allowed to (see {@code get()} in a later stage), bringing Postgres to parity
 * with H2's auto-create. This class also owns the pure helpers that derive the maintenance-connection
 * URL and build the CREATEDB-gate error message. See the pluggable-datastore WIKI chapter.
 */
public class PostgresConnectionProvider extends JdbcConnectionProvider {

    /** JDBC URL prefix every Postgres URL this provider handles starts with. */
    private static final String POSTGRES_URL_PREFIX = "jdbc:postgresql://";

    /** The always-present administrative database used as the maintenance-connection target. */
    static final String MAINTENANCE_DB = "postgres";

    /**
     * Construct a Postgres connection provider for a fixed JDBC URL and credentials.
     *
     * @param jdbcUrl  the target Postgres JDBC URL to connect to
     * @param user     the database username
     * @param password the database password
     */
    public PostgresConnectionProvider(final String jdbcUrl, final String user, final String password) {
        super(jdbcUrl, user, password);
    }

    /**
     * Extract the target database name from a Postgres JDBC URL, for use as the {@code CREATE
     * DATABASE} identifier and in error messages.
     *
     * @param jdbcUrl the Postgres JDBC URL
     * @return the database segment of the URL
     * @throws IllegalArgumentException if the URL is not a Postgres URL or has no database segment
     */
    static String databaseName(final String jdbcUrl) {
        return parseSegments(jdbcUrl)[1];
    }

    /**
     * Derive the maintenance-connection URL by replacing the target URL's database segment with the
     * {@code postgres} administrative database, preserving the authority (host(s)/port) and any query
     * parameters. {@code CREATE DATABASE} cannot run while connected to the target database, so Tia
     * connects to this maintenance database to create it.
     *
     * @param jdbcUrl the target Postgres JDBC URL
     * @return the same URL with the database segment swapped to {@code postgres}
     * @throws IllegalArgumentException if the URL is not a Postgres URL or has no database segment
     */
    static String maintenanceUrl(final String jdbcUrl) {
        String[] segments = parseSegments(jdbcUrl);
        String authority = segments[0];
        String params = segments[2];
        return POSTGRES_URL_PREFIX + authority + "/" + MAINTENANCE_DB + params;
    }

    /**
     * Build the actionable message shown when the target database does not exist and the role lacks
     * {@code CREATEDB} to create it. The driver's own message is embedded inline (in addition to being
     * chained as the exception cause) so the underlying client error is always visible to the user,
     * even if the caller's logging does not print the cause chain.
     *
     * @param databaseName  the missing database name
     * @param driverMessage the JDBC driver's original error message
     * @return the actionable, user-facing error message
     */
    static String createDbPrivilegeErrorMessage(final String databaseName, final String driverMessage) {
        return "Tia datastore database \"" + databaseName + "\" does not exist and the configured role "
                + "lacks CREATEDB to create it. Create the database first, or grant CREATEDB to the role. "
                + "Original driver error: " + driverMessage;
    }

    /**
     * Split a Postgres JDBC URL {@code jdbc:postgresql://<authority>/<database>[?<params>]} into its
     * three parts. The authority runs from after the scheme prefix to the first {@code /}; the
     * database is from that {@code /} up to {@code ?} (or the end); the params (if any) are everything
     * from {@code ?} onward, including the leading {@code ?}.
     *
     * @param jdbcUrl the Postgres JDBC URL to split
     * @return a three-element array of {authority, database, params}
     * @throws IllegalArgumentException if the URL is not a Postgres URL, has no database segment, or
     *         has an empty database segment
     */
    private static String[] parseSegments(final String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith(POSTGRES_URL_PREFIX)) {
            throw new IllegalArgumentException("Not a Postgres JDBC URL: " + jdbcUrl);
        }
        String rest = jdbcUrl.substring(POSTGRES_URL_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("Postgres JDBC URL has no database segment: " + jdbcUrl);
        }
        String authority = rest.substring(0, slash);
        String afterAuthority = rest.substring(slash + 1);
        int question = afterAuthority.indexOf('?');
        String database = question < 0 ? afterAuthority : afterAuthority.substring(0, question);
        String params = question < 0 ? "" : afterAuthority.substring(question);
        if (database.isEmpty()) {
            throw new IllegalArgumentException("Postgres JDBC URL has an empty database segment: " + jdbcUrl);
        }
        return new String[]{authority, database, params};
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :tia-core:test --tests "org.tiatesting.core.persistence.connection.PostgresConnectionProviderHelpersTest"`
Expected: PASS - all 11 tests green.

- [ ] **Step 5: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/persistence/connection/PostgresConnectionProvider.java \
        tia-core/src/test/java/org/tiatesting/core/persistence/connection/PostgresConnectionProviderHelpersTest.java
git commit -m "feat(datastore): Postgres maintenance-URL and CREATEDB-message helpers

Pure, database-free helpers for the upcoming Postgres DB auto-create: derive
the maintenance-connection URL (database segment swapped to postgres, authority
and params preserved), extract the target database name, and build the
CREATEDB-gate message with the driver's original message embedded inline.
No wiring yet - PostgresConnectionProvider still inherits the generic get().

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

Then print the stage summary and STOP for review.

---

### Task 2: Auto-create flow and factory wiring

Adds the behavioural core: `PostgresConnectionProvider.get()` catches `3D000`, creates the database over a maintenance connection (handling the `42P04` race and the `42501` CREATEDB gate), and retries once. Wires the factory to route the postgres dialect to this provider, adds the `TiaPersistenceException(String, Throwable)` constructor the gate message needs, and a package-private accessor on `JdbcDataStore` so a no-DB routing test can assert the wiring. Guarded integration tests prove the create and idempotent paths against a real Postgres.

**Files:**
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/connection/PostgresConnectionProvider.java`
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/TiaPersistenceException.java`
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/DataStoreFactory.java:56-58`
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/JdbcDataStore.java` (add accessor near `getConnection()`, ~line 2183)
- Test: `tia-core/src/test/java/org/tiatesting/core/persistence/DataStoreFactoryPostgresRoutingTest.java` (create)
- Test: `tia-core/src/test/java/org/tiatesting/core/persistence/connection/PostgresDbAutoCreateTest.java` (create)

**Interfaces:**
- Consumes (from Task 1): `PostgresConnectionProvider.databaseName`, `.maintenanceUrl`, `.createDbPrivilegeErrorMessage`, `.MAINTENANCE_DB`, and the constructor.
- Produces:
  - `@Override public Connection get() throws SQLException` on `PostgresConnectionProvider`.
  - `public TiaPersistenceException(String message, Throwable cause)`.
  - `ConnectionProvider JdbcDataStore.getConnectionProvider()` (package-private).
  - `DataStoreFactory.fromConfig` routing postgres -> `PostgresConnectionProvider`.

- [ ] **Step 1: Add the `TiaPersistenceException(String, Throwable)` constructor**

Modify `tia-core/src/main/java/org/tiatesting/core/persistence/TiaPersistenceException.java` - add this constructor after the existing `TiaPersistenceException(Exception exception)`:

```java
    /**
     * Wrap a lower-level failure while preserving both an explanatory Tia message and the original
     * cause, so callers see the Tia guidance and can still inspect the underlying exception.
     *
     * @param message the explanatory Tia message
     * @param cause   the original underlying exception
     */
    public TiaPersistenceException(String message, Throwable cause){
        super(message, cause);
    }
```

- [ ] **Step 2: Write the failing routing test (no database)**

Create `tia-core/src/test/java/org/tiatesting/core/persistence/DataStoreFactoryPostgresRoutingTest.java`:

```java
package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.persistence.connection.PostgresConnectionProvider;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link DataStoreFactory#fromConfig} routes a {@code jdbc:postgresql} URL to a
 * {@link PostgresConnectionProvider} (the auto-creating provider), not the generic
 * {@link org.tiatesting.core.persistence.connection.JdbcConnectionProvider}. Builds the store only -
 * no connection is opened - so this runs in the normal build without a Postgres instance. See the
 * pluggable-datastore WIKI chapter.
 */
class DataStoreFactoryPostgresRoutingTest {

    @Test
    void routesPostgresUrlToPostgresConnectionProvider() {
        // given
        String url = "jdbc:postgresql://localhost:5432/tiaperf";
        // when
        DataStore store = DataStoreFactory.fromConfig(null, url, "tia", "tia", null, "main");
        // then
        assertTrue(store instanceof JdbcDataStore);
        assertTrue(((JdbcDataStore) store).getConnectionProvider() instanceof PostgresConnectionProvider);
    }
}
```

- [ ] **Step 3: Run the routing test to verify it fails**

Run: `./gradlew :tia-core:compileTestJava --tests "org.tiatesting.core.persistence.DataStoreFactoryPostgresRoutingTest"`
Expected: FAIL - compilation error: `getConnectionProvider()` does not exist on `JdbcDataStore`.

- [ ] **Step 4: Add the `getConnectionProvider()` accessor**

Modify `tia-core/src/main/java/org/tiatesting/core/persistence/JdbcDataStore.java` - add this method immediately after `getConnection()` (the last method in the class, ~line 2210, before the closing brace):

```java
    /**
     * Expose the injected {@link ConnectionProvider} for tests that need to assert which provider the
     * {@link DataStoreFactory} wired in (for example that a postgres URL routes to the auto-creating
     * {@code PostgresConnectionProvider}). Package-private: for test use only, like {@link #getConnection()}.
     *
     * @return the connection provider this datastore was constructed with
     */
    ConnectionProvider getConnectionProvider() {
        return connectionProvider;
    }
```

- [ ] **Step 5: Route the postgres dialect to `PostgresConnectionProvider`**

Modify `tia-core/src/main/java/org/tiatesting/core/persistence/DataStoreFactory.java`. Add the import near the other connection imports (after line 5):

```java
import org.tiatesting.core.persistence.connection.PostgresConnectionProvider;
```

Replace the current non-H2 tail of `fromConfig` (lines 56-58):

```java
        requireDriverPresent(dialect.id());
        ConnectionProvider connectionProvider = new JdbcConnectionProvider(dbUrl, user, password);
        return new JdbcDataStore(dialect, connectionProvider, schema);
```

with:

```java
        requireDriverPresent(dialect.id());
        ConnectionProvider connectionProvider = "postgres".equals(dialect.id())
                ? new PostgresConnectionProvider(dbUrl, user, password)
                : new JdbcConnectionProvider(dbUrl, user, password);
        return new JdbcDataStore(dialect, connectionProvider, schema);
```

- [ ] **Step 6: Run the routing test to verify it passes**

Run: `./gradlew :tia-core:test --tests "org.tiatesting.core.persistence.DataStoreFactoryPostgresRoutingTest"`
Expected: PASS.

- [ ] **Step 7: Write the failing integration tests (guarded)**

Create `tia-core/src/test/java/org/tiatesting/core/persistence/connection/PostgresDbAutoCreateTest.java`:

```java
package org.tiatesting.core.persistence.connection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guarded integration tests for {@link PostgresConnectionProvider}'s database auto-create. Skipped
 * (not failed) when no Postgres is reachable on {@code localhost:5432}, mirroring
 * {@link org.tiatesting.core.persistence.PostgresPersistTest}'s guard so the normal build stays green
 * without the {@code spike/postgres/} harness running. See the pluggable-datastore WIKI chapter.
 */
class PostgresDbAutoCreateTest {

    private static final String HOST_URL = "jdbc:postgresql://localhost:5432/";
    private static final String MAINTENANCE_URL = HOST_URL + PostgresConnectionProvider.MAINTENANCE_DB;
    private static final String USER = "tia";
    private static final String PASSWORD = "tia";

    // Unique per run so a failed teardown never collides with the next run.
    private final String dbName = "tia_autocreate_" + System.currentTimeMillis();
    private final String targetUrl = HOST_URL + dbName;

    /**
     * Skip the test (rather than fail it) when the local Postgres instance is not reachable, via a
     * quick raw TCP connect with a short timeout.
     */
    private static boolean pgReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Drop the per-test database via a maintenance connection to the {@code postgres} database, so the
     * test starts (and ends) with the database absent.
     *
     * @throws SQLException if the maintenance connection or the drop statement fails
     */
    private void dropTestDatabase() throws SQLException {
        try (Connection connection = DriverManager.getConnection(MAINTENANCE_URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS \"" + dbName + "\"");
        }
    }

    /**
     * Remove the per-test database after each test, but only when Postgres is reachable, so the
     * cleanup itself never fails the build on a machine without the harness.
     *
     * @throws SQLException if the drop fails while Postgres is reachable
     */
    @AfterEach
    void tearDown() throws SQLException {
        if (pgReachable()) {
            dropTestDatabase();
        }
    }

    /**
     * Connecting to a non-existent database creates it and returns a usable connection pinned to it.
     *
     * @throws SQLException if the drop, connect, or catalog read fails
     */
    @Test
    void createsDatabaseWhenMissing() throws SQLException {
        // given a reachable Postgres and a guaranteed-absent target database
        assumeTrue(pgReachable(), "spike Postgres not running");
        dropTestDatabase();
        PostgresConnectionProvider provider = new PostgresConnectionProvider(targetUrl, USER, PASSWORD);

        // when a connection is requested for the missing database
        try (Connection connection = provider.get()) {
            // then the database was created and the connection is pinned to it
            assertNotNull(connection);
            assertEquals(dbName, connection.getCatalog());
        }
    }

    /**
     * A second connect once the database exists is a no-op that succeeds directly ({@code 3D000} is
     * not raised, so no create is attempted).
     *
     * @throws SQLException if the drop or either connect fails
     */
    @Test
    void idempotentWhenDatabaseAlreadyExists() throws SQLException {
        // given a reachable Postgres and the target database created by a first connect
        assumeTrue(pgReachable(), "spike Postgres not running");
        dropTestDatabase();
        PostgresConnectionProvider provider = new PostgresConnectionProvider(targetUrl, USER, PASSWORD);
        try (Connection first = provider.get()) {
            assertNotNull(first);
        }

        // when a second connection is requested, now that the database exists
        try (Connection second = provider.get()) {
            // then it succeeds directly without attempting another create
            assertNotNull(second);
            assertEquals(dbName, second.getCatalog());
        }
    }
}
```

- [ ] **Step 8: Run the integration tests to verify they fail**

Run: `./gradlew :tia-core:test --tests "org.tiatesting.core.persistence.connection.PostgresDbAutoCreateTest"`
Expected (with Postgres running): FAIL - `createsDatabaseWhenMissing` throws (the inherited generic `get()` does not create the database, so `DriverManager.getConnection` raises SQLState `3D000`).
Expected (without Postgres): both tests SKIPPED via the assumption - not a valid failing state, so run this step on a machine with the `spike/postgres/` harness running (see the Postgres test-fixture memory) to observe the real failure.

- [ ] **Step 9: Implement the auto-create `get()` override**

Modify `tia-core/src/main/java/org/tiatesting/core/persistence/connection/PostgresConnectionProvider.java`. Add these imports at the top (after the package statement):

```java
import org.tiatesting.core.persistence.TiaPersistenceException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
```

Add these SQLState constants and the target credentials/URL fields. Change the constructor to store what the create path needs, and add the `get()` override plus `createDatabaseViaMaintenance`. The full class body (helpers from Task 1 unchanged) becomes:

```java
    /** SQLState raised by the driver when the target database does not exist. */
    private static final String SQLSTATE_DB_MISSING = "3D000";
    /** SQLState raised by {@code CREATE DATABASE} when the database already exists (create race). */
    private static final String SQLSTATE_DUPLICATE_DB = "42P04";
    /** SQLState raised by {@code CREATE DATABASE} when the role lacks the CREATEDB privilege. */
    private static final String SQLSTATE_INSUFFICIENT_PRIVILEGE = "42501";

    private final String user;
    private final String password;
    private final String maintenanceUrl;
    private final String databaseName;
```

Replace the Task 1 constructor with:

```java
    /**
     * Construct a Postgres connection provider for a fixed JDBC URL and credentials, precomputing the
     * maintenance-connection URL and target database name used by the auto-create path.
     *
     * @param jdbcUrl  the target Postgres JDBC URL to connect to
     * @param user     the database username
     * @param password the database password
     */
    public PostgresConnectionProvider(final String jdbcUrl, final String user, final String password) {
        super(jdbcUrl, user, password);
        this.user = user;
        this.password = password;
        this.maintenanceUrl = maintenanceUrl(jdbcUrl);
        this.databaseName = databaseName(jdbcUrl);
    }
```

Add these two methods (for example after the constructor):

```java
    /**
     * Open a connection, auto-creating the target database first if it does not yet exist. The normal
     * path is a plain {@link JdbcConnectionProvider#get()}; only a {@code 3D000} (database does not
     * exist) failure triggers the create-then-retry. Every other connection failure propagates
     * unchanged, so existing error behaviour is untouched. This brings Postgres to parity with H2,
     * which already auto-creates its database on first use. See the pluggable-datastore WIKI chapter.
     *
     * @return an open connection to the (now-existing) target database
     * @throws SQLException if the connection or the database creation fails for a reason other than
     *         the missing database
     */
    @Override
    public Connection get() throws SQLException {
        try {
            return super.get();
        } catch (SQLException e) {
            if (!SQLSTATE_DB_MISSING.equals(e.getSQLState())) {
                throw e;
            }
            createDatabaseViaMaintenance();
            return super.get();
        }
    }

    /**
     * Create the target database over a maintenance connection to the {@code postgres} administrative
     * database ({@code CREATE DATABASE} cannot run while connected to the target). A concurrent
     * creator winning the race ({@code 42P04}) is treated as success. A role lacking {@code CREATEDB}
     * ({@code 42501}) is surfaced as a {@link TiaPersistenceException} whose message both explains the
     * fix and embeds the driver's original message inline, with the original exception chained as the
     * cause. Any other failure propagates unchanged.
     *
     * @throws SQLException if the maintenance connection fails, or {@code CREATE DATABASE} fails for a
     *         reason other than the database already existing or the CREATEDB gate
     */
    private void createDatabaseViaMaintenance() throws SQLException {
        try (Connection maintenance = DriverManager.getConnection(maintenanceUrl, user, password);
             Statement statement = maintenance.createStatement()) {
            statement.execute("CREATE DATABASE \"" + databaseName + "\"");
        } catch (SQLException e) {
            if (SQLSTATE_DUPLICATE_DB.equals(e.getSQLState())) {
                return;
            }
            if (SQLSTATE_INSUFFICIENT_PRIVILEGE.equals(e.getSQLState())) {
                throw new TiaPersistenceException(
                        createDbPrivilegeErrorMessage(databaseName, e.getMessage()), e);
            }
            throw e;
        }
    }
```

- [ ] **Step 10: Run the integration tests to verify they pass**

Run (with the `spike/postgres/` harness running): `./gradlew :tia-core:test --tests "org.tiatesting.core.persistence.connection.PostgresDbAutoCreateTest"`
Expected: PASS - both tests green (database created on first connect, no-op on the second).

- [ ] **Step 11: Run the full tia-core persistence suite for non-regression**

Run: `./gradlew :tia-core:test --tests "org.tiatesting.core.persistence.*"`
Expected: PASS - all persistence tests green (the H2 and generic-JDBC paths are untouched; guarded Postgres tests pass when the harness runs, skip otherwise).

- [ ] **Step 12: Manual verification of the CREATEDB gate (documented check)**

The local `tia` role is a superuser, so the `42501` path cannot be exercised by the automated suite. The message wording and inline driver message are already covered deterministically by Task 1's `privilegeErrorMessageEmbedsDbNameAndDriverMessage`. To verify the live gate manually against the harness Postgres:

```bash
psql -h localhost -U tia -d postgres -c "CREATE ROLE tia_nocreatedb LOGIN PASSWORD 'x' NOCREATEDB;"
```

Then, in a scratch main, build `new PostgresConnectionProvider("jdbc:postgresql://localhost:5432/tia_absent_db", "tia_nocreatedb", "x").get()` and confirm the thrown `TiaPersistenceException` message contains both the CREATEDB guidance and the driver's `permission denied to create database` text. Drop the role afterwards:

```bash
psql -h localhost -U tia -d postgres -c "DROP ROLE tia_nocreatedb;"
```

This is a documented manual check, not an automated test (superuser fixture cannot host it deterministically).

- [ ] **Step 13: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/persistence/connection/PostgresConnectionProvider.java \
        tia-core/src/main/java/org/tiatesting/core/persistence/TiaPersistenceException.java \
        tia-core/src/main/java/org/tiatesting/core/persistence/DataStoreFactory.java \
        tia-core/src/main/java/org/tiatesting/core/persistence/JdbcDataStore.java \
        tia-core/src/test/java/org/tiatesting/core/persistence/DataStoreFactoryPostgresRoutingTest.java \
        tia-core/src/test/java/org/tiatesting/core/persistence/connection/PostgresDbAutoCreateTest.java
git commit -m "feat(datastore): auto-create the Postgres database when missing

PostgresConnectionProvider.get() now catches SQLState 3D000 (database does
not exist), creates the database over a maintenance connection to the
postgres admin database, and retries once - H2 parity. A concurrent-create
race (42P04) is treated as success; a role lacking CREATEDB (42501) fails
with a clear message that embeds the driver's original error inline and
chains it as the cause. DataStoreFactory routes the postgres dialect to this
provider. Always-on, no config flag.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

Then print the stage summary and STOP for review.

---

### Task 3: Documentation

Update the WIKI and README to describe the new behaviour and the three-way privilege story, replacing the "database must already exist" statements. Pure docs - no code, no tests.

**Files:**
- Modify: `wiki/pluggable-datastore.md:80-84`
- Modify: `README.md:823` and `README.md:899`

**Interfaces:** none (documentation only).

- [ ] **Step 1: Update the WIKI chapter**

In `wiki/pluggable-datastore.md`, replace the paragraph at lines 80-84 (starting "Postgres does not create databases on Tia's behalf...") with:

```markdown
Postgres now auto-creates the database when it is missing, matching H2. On connect, if the database
named in `tiaDBUrl` does not exist, `PostgresConnectionProvider` opens a maintenance connection to the
`postgres` administrative database, runs `CREATE DATABASE`, and retries - then the branch schema is
created inside it as usual. This needs the connecting role to hold `CREATEDB`. Auto-create is a
best-effort convenience, so the reduced-privilege model still holds three ways: if the database already
exists, no `CREATEDB` is needed and the role needs only `CREATE` on the database to make schemas; if the
database is missing and the role has `CREATEDB`, Tia creates it; if the database is missing and the role
lacks `CREATEDB`, Tia fails with a clear message (embedding the driver's own error) telling you to create
the database first or grant `CREATEDB`. A user who will not grant `CREATEDB` simply pre-creates the
database once. H2 has always auto-created its embedded file (or, in server mode, relied on the server's
`-ifNotExists`); this brings Postgres to the same footing.
```

- [ ] **Step 2: Update the README config-reference bullet**

In `README.md`, replace the bullet at line 823 (starting "**Postgres** requires the database named...") with:

```markdown
- **Postgres** auto-creates the database named in `tiaDBUrl` / `dbUrl` when it does not yet exist, the same way H2 does, provided the connecting role holds the `CREATEDB` privilege. If you would rather not grant `CREATEDB`, pre-create the database yourself; then Tia needs only `CREATE` on that database to make the per-branch schema (e.g. `GRANT CREATE ON DATABASE tiadb TO tia;`). If the database is missing and the role lacks `CREATEDB`, Tia fails with a clear message (including the driver's own error) telling you to create the database first or grant `CREATEDB`. See [Using a different database](#using-a-different-database) below.
```

- [ ] **Step 3: Update the README Postgres-example note**

In `README.md`, replace the note at line 899 (starting "**Note:** the database named in `tiaDBUrl`...") with:

```markdown
**Note:** Tia auto-creates the database named in `tiaDBUrl` (`tiadb` above) when it is missing, if the connecting role holds `CREATEDB`. Otherwise pre-create it and grant the role `CREATE` on it (e.g. `GRANT CREATE ON DATABASE tiadb TO tia;`) - Tia then creates the per-branch schema inside it (see [Branch isolation](#branch-isolation-schema-per-branch)). If the database is missing and the role has neither the database nor `CREATEDB`, Tia fails with a message telling you to create the database or grant `CREATEDB`.
```

- [ ] **Step 4: Verify no em-dash slipped in and the anchors still resolve**

Run: `grep -n "—" wiki/pluggable-datastore.md README.md`
Expected: no output (no em-dash characters). If any line you edited is listed, replace the em-dash with an ASCII hyphen.

- [ ] **Step 5: Commit**

```bash
git add wiki/pluggable-datastore.md README.md
git commit -m "docs(datastore): document Postgres database auto-creation

Replace the 'Postgres database must already exist' statements in the WIKI and
README with the new behaviour: Tia auto-creates the database when missing and
the role has CREATEDB (H2 parity), and the three-way privilege story
(pre-existing DB needs only CREATE; missing + CREATEDB auto-creates; missing +
no CREATEDB fails clearly).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

Then print the stage summary and STOP for review.

---

## Notes for the implementer

- Run Gradle from the repo root `/Users/mgleeson/Documents/tia/tia`. The module is `tia-core`.
- The `spike/postgres/` harness (Homebrew `postgresql@16`, role `tia`/`tia`, databases `tiaperf` / `tia_junit5_pg`) must be running for the Task 2 integration tests to execute rather than skip. See the Postgres test-fixture memory / `spike/postgres/setup.sh`.
- The integration test creates and drops its own uniquely-named database (`tia_autocreate_<timestamp>`); it does not touch `tiaperf` or `tia_junit5_pg`.
- Do not add a config flag - auto-create is always-on by design (H2 parity, automation-over-configuration).
