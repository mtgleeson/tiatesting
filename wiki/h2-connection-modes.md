# Embedded vs server-mode H2 connections

### The problem

Tia's default data store is H2. It can run in two modes: an *embedded* database, where each build opens a single fixed `tiadb.mv.db` file on local disk via a `jdbc:h2:<path>/tiadb...` URL, or a *server-mode* database, where Tia connects to an H2 instance running in [server (TCP) mode](https://www.h2database.com/html/tutorial.html#using_server) over `jdbc:h2:tcp://host:port/db`. Either way it is one fixed database, not one per branch - branch isolation is handled separately, by a schema Tia creates and selects per branch inside that one database (see the "Branch isolation: schema per branch" section of the [pluggable datastore](pluggable-datastore.md) chapter). Embedded mode is the right default - zero setup, no server to run - but it means every machine has its own copy of the mapping and statistics. Teams that want several builds to share one Tia database (a primary CI writer plus developer/local readers, say) need server mode instead.

The two modes look similar (both are H2, both go through `JdbcDataStore` via an `H2ConnectionProvider`) but differ in ways that matter for correctness, not just connection strings.

### The one decision point: `H2ConnectionSettings`

Rather than teach every caller (Maven mojos, Gradle tasks, test-runner listeners) how to choose a mode, the choice is resolved once in `H2ConnectionSettings`. It exposes `embedded(dbFilePath)`, `server(url, user, password)`, `fromConfig(dbFilePath, dbUrl, dbUser, dbPassword)` (picks server iff a URL is supplied), and `fromSystemProperties()` (the listener entry point, reading `tiaDBUrl` / `tiaDBUser` / `tiaDBPassword` / `tiaDBFilePath`). None of these take a branch - `H2ConnectionSettings` only resolves *how to connect* (embedded vs server, URL, credentials); it has no opinion on branch isolation. `H2ConnectionProvider` takes a settings object and builds the JDBC URL from it, and stops caring how the mode was chosen.

Branch isolation is a separate, later step. `DataStoreFactory.fromConfig(dbFilePath, dbUrl, user, password, dialectOverride, branch)` and `fromSystemProperties(branch)` are what callers actually use to build a `DataStore`: each resolves the `H2ConnectionSettings` (or, for Postgres, a `JdbcConnectionProvider`) exactly as above, then separately derives the per-branch schema via `BranchSchema.schemaName(branch)` and passes it into `JdbcDataStore`, which creates and selects that schema on every connection. See the [pluggable datastore](pluggable-datastore.md) chapter for how that half works.

The build tools each build their connection settings from their own config surface and converge on the same object:
- **Maven**: `AbstractTiaMojo.buildDataStore(branch)` resolves connection settings from the `tiaDBUrl` / `tiaDBFilePath` parameters via `DataStoreFactory.fromConfig`. The forked test JVM gets the same values from a `fork.properties` file the agent mojo writes and the Tia javaagent replays into system properties at `premain` (see the "How Tia exchanges data with the test runner" chapter) - the user no longer has to mirror them into Surefire `systemPropertyVariables`.
- **Gradle**: `TiaBasePlugin.buildDataStore(branch)` for the daemon-side tasks; the forked test JVM gets the values forwarded as system properties by `TiaSpockGitGradlePluginTestExtension` (only when set, so the embedded case never sends the literal string `"null"`).

### What actually differs between the modes

Two behaviours in `H2ConnectionProvider` are embedded-only and would be wrong against a shared server, so they are gated on `settings.isServerMode()`:

1. **Engine-option URL params.** Embedded mode appends `PAGE_SIZE`, `CACHE_SIZE`, `DB_CLOSE_DELAY=-1`, and `DB_CLOSE_ON_EXIT=FALSE`. These configure the *database engine instance*, which in server mode lives in the remote server process, so server mode uses the supplied URL verbatim with none of these appended. One of them matters enough in server mode that the **user** should put it in the URL themselves: `DB_CLOSE_DELAY=-1` is a *database-level* setting that a remote client's URL can apply when its connection opens the database, and without it the server closes and reopens the database around every Tia operation - see "Why server-mode URLs should include `DB_CLOSE_DELAY=-1`" below.
2. **Graceful `SHUTDOWN` on `close()`.** In embedded mode `close()` issues a plain `SHUTDOWN` (deliberately not `SHUTDOWN IMMEDIATELY`) to flush the MVStore's buffered write pages to disk and release the `.mv.db` file lock before Surefire/Gradle forks the test JVM (with `DB_CLOSE_DELAY=-1` the lock would otherwise persist for the life of the daemon JVM). `SHUTDOWN IMMEDIATELY` skips that flush - harmless for a read-only run, but it can silently drop a small write (e.g. a tracked-library reconcile) that the MVStore's delayed writer hasn't reached disk with yet, so `H2ConnectionProvider` deliberately pays the (near-zero, for a run with no dirty pages) cost of the graceful form instead. Against a server, any `SHUTDOWN` shuts down the whole database **for every connected client**, so server-mode `close()` is a no-op - the per-operation connections are already closed by their own `finally` blocks.

Both modes connect to a single fixed database - the file path or the server URL never varies by branch. This replaces an earlier scheme where embedded mode named the file `tiadb-<branch>.mv.db` and server mode supported an opt-in `{branch}` URL token; both are gone. Branch isolation now happens one layer up, in `JdbcDataStore`, via the per-branch schema described in the [pluggable datastore](pluggable-datastore.md) chapter's "Branch isolation: schema per branch" section - not by naming the database after the branch.

### Why server-mode URLs should include `DB_CLOSE_DELAY=-1`

`JdbcDataStore` opens a fresh JDBC connection for every operation and closes it in a `finally` block. That per-operation pattern is deliberate - it keeps the store stateless, makes every method safe to call in isolation, and in embedded mode it costs almost nothing because the engine option `DB_CLOSE_DELAY=-1` (hardcoded into the embedded URL) keeps the database instance open between connections.

Server mode inherits the same per-operation connections but **not** the same setting: the server-mode URL is used verbatim (see item 1 above), so unless the user's URL says otherwise, the database on the server runs with H2's default `DB_CLOSE_DELAY=0` - *close the database when its last connection closes*. Tia's connections never overlap, so every connection is "the last connection". Each datastore call then makes the server flush and close the whole database on `Connection.close()`, and re-open it from disk on the next `getConnection()`.

On a large mapping DB each close/reopen cycle costs roughly half a second to a second, and a single `select-tests` run performs a dozen or more datastore operations - more when library impact analysis is enabled, because the drain path reads pending batches per tracked library. On the reference project this was measured at ~23.6s of a 28s run spent blocked on the server's close/reopen churn; appending `DB_CLOSE_DELAY=-1` to the URL dropped the run to 3.5s.

The JFR signature of a missing `DB_CLOSE_DELAY` is distinctive, and worth recognizing because it looks superficially like "slow queries":
- the main thread is blocked in `SocketInputStream.socketRead` under `org.h2.value.Transfer.readInt` (waiting for the server, not transferring data);
- the dominant H2 client frame is `JdbcConnection.close()` - the time is in *closing connections*, not executing statements;
- CPU is idle (few `ExecutionSample` events) and per-read payloads are tiny.

Why `-1` (keep open until the server shuts down) rather than a timeout like `DB_CLOSE_DELAY=60`: the server in this topology exists solely to serve Tia clients, and the mapping DB is its working set - there is nothing to reclaim by letting it close between builds, and a timeout just reintroduces the reopen cost for whichever build arrives after a quiet period. The trade-off of `-1` is that the database stays open (holding its cache) until the server process stops; that is the desired steady state for a shared Tia server.

Why Tia doesn't append it automatically: the server-mode contract is that the URL is the user's, verbatim - Tia never rewrites it, on any branch (branch isolation is handled by the per-branch schema in `JdbcDataStore`, not the URL - see the single-fixed-database note at the end of "What actually differs between the modes" above). Silently injecting engine options would blur that contract and surprise a user who has deliberately configured a different close-delay policy on their server. Auto-appending it only-when-absent would be safe in practice and may become the default later; until then it is a documented one-liner in the README's server-mode checklist.

### Credential resolution and keeping secrets out of config

Server mode needs a username and password, and the obvious place to put them - the `tia { dbPassword = '...' }` block or the POM `<tiaDBPassword>` - is checked into source control. To avoid committing a secret, `H2ConnectionSettings.server(...)` resolves each credential by precedence: the explicitly configured value, then a `TIA_DB_USER` / `TIA_DB_PASSWORD` environment variable, then a default (`tia` / empty). So a build can leave `dbPassword` unset and have CI inject `TIA_DB_PASSWORD` into the environment, keeping the repo credential-free.

The fallback lives in the single `server(...)` factory, so it applies uniformly to every entry point (`fromConfig`, `fromSystemProperties`, and the Maven/Gradle builders that delegate to them). The environment lookup is passed in via a package-private overload (`server(url, user, password, env)`) so the precedence logic is unit-tested without mutating the real process environment. This is intentionally a *fallback*, not a replacement for the build tools' own indirection (Maven `${env.X}` / encrypted settings.xml, Gradle `~/.gradle/gradle.properties`): those still work and compose, since they resolve before Tia ever sees the value. Tia never logs the password - only the JDBC URL - so the one remaining footgun is embedding credentials inside `dbUrl` itself.

The password resolver deliberately distinguishes *not configured* from *configured as empty*, which the username resolver does not. H2 accepts an empty password, so `resolvePassword` treats only `null` as "fall back to the environment"; any non-null configured value - including `""` - is used verbatim and is never trimmed (whitespace can be significant in a password). That lets a build pin an empty password explicitly (`dbPassword = ''` / `<tiaDBPassword></tiaDBPassword>`) and bypass `TIA_DB_PASSWORD`. The null-vs-empty distinction survives both plugin bridges: Maven's `@Parameter` is `null` when omitted but `""` when present-and-empty, and the Gradle forwarder only emits `-DtiaDBPassword` when the value is non-null, so an explicit empty string reaches the test JVM as a set-but-empty system property rather than an absent one. An empty *username* is meaningless to H2, so the username keeps the simpler blank-is-unset rule.

### Branch recorded in `tia_core`

`tia_core` holds the single "current state" row for the currently-selected branch schema: the commit the stored mapping is valid for, the last-updated timestamp, and the aggregate run stats. It also carries a `branch` column. Even though each branch's data now lives in its own schema - so a row can no longer be confused with another branch's row the way it could when every branch shared one table - the column still travels with the row itself: a status / text / HTML report generated from `tia_core` states which branch it describes without the reader needing to know which schema produced it. The branch is already known at persist time (`TestRunnerService.persistTestRunData` receives it and already stamps it on every `tia_test_run_history` row) and is stamped on the core row too, under the same `updateDBMapping` guard that seals the commit value - the branch is the identity of the mapping being stamped, so the two move together.

The column is part of the `tia_core` DDL in `createTiaDB`. Tia is pre-release with no external databases to preserve, so there is no schema migration for it - a database is simply created with the column. A genuinely unset branch (e.g. a stats-only run that never seals a commit) is stored as SQL `NULL` rather than the literal text `null`; the three summary reports render it as `N/A`.

### Server-mode prerequisite: `-ifNotExists`

`JdbcDataStore` auto-creates Tia's tables (and, in embedded mode, the database file itself) on first use via `createTiaDB()`, and separately creates the current branch's schema the first time that branch is selected (see "What actually differs between the modes" above). An H2 TCP server refuses to create a database for a remote client unless it was started with the `-ifNotExists` flag. So running a server-mode Tia against a server without that flag fails on the very first run. This is a deployment precondition Tia can't paper over from the client side, so it's documented rather than worked around.

### Concurrency: one mapping writer, best-effort statistics

The operational model is unchanged from embedded mode and is what makes shared server mode safe in practice: **exactly one build is the mapping writer** (`tiaUpdateDBMapping=true`) per branch; every other client on that branch runs in local mode (`tiaUpdateDBMapping=false`) and only updates statistics. The mapping - the load-bearing data for test selection - has a single owner per branch, so the delete-then-reinsert and truncate-then-insert rewrites in the persist path never contend across clients for mapping rows. Different branches can't contend with each other at all now that each has its own schema; the concurrency concern below is scoped to clients sharing one branch's schema.

That leaves **statistics** as the only data multiple clients write concurrently. Statistics counters (`num_runs`, `avg_run_time`, success/fail counts on both `tia_core` and `tia_test_suite`, plus the all-tests-run baseline `all_tests_run_time` / `num_all_tests_runs` on `tia_core`) are read-modify-write: each client reads the current value, increments in memory (`TestRunnerService` / `incrementStats` / `mergeTestMappingStats`), and writes it back. With several clients doing this against one branch's schema there is a classic lost-update race - two clients read `num_runs=10`, both write `11`, and one increment is lost.

This is a deliberate non-goal. Statistics in Tia are advisory: they drive reports and run-time estimates, not test selection. Adding locking (atomic SQL `num_runs = num_runs + 1` increments, or `SELECT ... FOR UPDATE` row locks) would buy exactness on data that doesn't need it, at a cost on the write path. So Tia accepts statistic drift under concurrent writers; if exact shared statistics ever become a requirement, the atomic-increment rewrite is the place to start. This is the same class of concern as the multi-fork persist limitation in the "Persist flow and crash safety" chapter - and it's the storage-layer-change trigger (#3) that chapter anticipated for revisiting `persistTestRunData`'s transaction strategy.

### Running an H2 server locally to test server mode

You don't need a separate H2 install to exercise server mode on a dev machine: Tia already depends on H2 (`com.h2database:h2:2.2.224` in `tia-core/build.gradle`), so the runnable jar is sitting in your Gradle cache. The same jar that backs embedded mode also ships H2's `org.h2.tools.Server` entry point.

**1. Start the TCP server.** The one non-negotiable flag is `-ifNotExists`: `JdbcDataStore.createTiaDB()` creates the database on the first run, and an H2 TCP server refuses to create a database for a remote client unless it was started with that flag (see the prerequisite subsection above).

```bash
mkdir -p ~/h2-tia
H2_JAR=$(find ~/.gradle/caches/modules-2 -name 'h2-2.2.224.jar' | head -1)

java -cp "$H2_JAR" org.h2.tools.Server \
  -tcp -ifNotExists -baseDir ~/h2-tia
```

The server listens on port `9092` by default and prints `TCP server running at tcp://...:9092`. Leave it running. `-baseDir` is where the `tiadb.mv.db` file is created; add `-tcpAllowOthers` only if a build on another machine needs to reach it. The Gradle-cache path changes when the cache is cleaned, so for a long-lived local server copy the jar somewhere stable (`cp "$H2_JAR" ~/h2-tia/h2.jar`) and run from there.

**2. Point Tia at the server.** The URL names one fixed database - Tia never rewrites it per branch, so the same value works on every branch:

```groovy
// Gradle
tia {
    dbUrl = 'jdbc:h2:tcp://localhost:9092/tiadb;DB_CLOSE_DELAY=-1'
}
```

```xml
<!-- Maven -->
<tiaDBUrl>jdbc:h2:tcp://localhost:9092/tiadb;DB_CLOSE_DELAY=-1</tiaDBUrl>
```

(`DB_CLOSE_DELAY=-1` keeps the database open between Tia's per-operation connections - see the dedicated subsection above.)

With no credentials configured, Tia falls back through `TIA_DB_USER` / `TIA_DB_PASSWORD` to `tia` / empty (see the credential-resolution subsection above), which matches the `tia`/empty account H2 creates for a brand-new database. To rehearse the env-var fallback, `export TIA_DB_PASSWORD=...` before the build and leave `dbPassword` unset; note that H2 fixes the account on first creation, so whatever password first connects becomes the database's password.

**3. Inspect the data while testing.** Run H2's web console against the same server to watch Tia's tables populate:

```bash
java -cp "$H2_JAR" org.h2.tools.Server -web -webPort 8082
```

Open `http://localhost:8082`, connect with JDBC URL `jdbc:h2:tcp://localhost:9092/tiadb` and user `tia`, and Tia's tables appear under the current branch's schema (e.g. `tia_main`) after the first run.

**4. Run a build.** From the project under test, run the normal Tia-enabled test task (`./gradlew test` / `mvn test`). The first run creates Tia's tables and the current branch's schema; a different branch checked out later gets its own schema created automatically in the same database, with no config change. Remember the single-writer model from the concurrency subsection: exactly one build per branch should run with `tiaUpdateDBMapping=true`, the rest as statistics-only readers.

---

Prev: [Persist flow and crash safety](persist-flow-and-crash-safety.md) | [Back to the Wiki index](../WIKI.md) | Next: [Pluggable datastore (H2, Postgres, and the seam for more)](pluggable-datastore.md)
