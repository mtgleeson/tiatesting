# Embedded vs server-mode H2 connections

### The problem

Tia's data store is H2. Historically it was always an *embedded* database: each build opened a `tiadb-<branch>.mv.db` file on the local disk via a `jdbc:h2:<path>/...` URL. That's the right default - zero setup, no server to run - but it means every machine has its own copy of the mapping and statistics. Teams that want several builds to share one Tia database (a primary CI writer plus developer/local readers, say) need Tia to connect to an H2 running in [server (TCP) mode](https://www.h2database.com/html/tutorial.html#using_server) over `jdbc:h2:tcp://host:port/db`.

The two modes look similar (both are H2, both go through `H2DataStore`) but differ in ways that matter for correctness, not just connection strings.

### The one decision point: `H2ConnectionSettings`

Rather than teach every caller (six Maven mojos, four daemon-side Gradle tasks, three test-runner listeners) how to choose a mode, the choice is resolved once in `H2ConnectionSettings`. It exposes `embedded(path, suffix)`, `server(url, user, password)`, `fromConfig(...)` (picks server iff a URL is supplied), and `fromSystemProperties(branch)` (the listener entry point, reading `tiaDBUrl` / `tiaDBUser` / `tiaDBPassword` / `tiaDBFilePath`). `H2DataStore` takes a settings object and stops caring how the mode was chosen.

The build tools each build the settings from their own config surface and converge on the same object:
- **Maven**: `AbstractTiaMojo.buildH2ConnectionSettings(branch)` from the `tiaDBUrl` / `tiaDBFilePath` parameters. The forked test JVM gets the same values from a `fork.properties` file the agent mojo writes and the Tia javaagent replays into system properties at `premain` (see the "How Tia exchanges data with the test runner" chapter) - the user no longer has to mirror them into Surefire `systemPropertyVariables`.
- **Gradle**: `TiaBasePlugin.buildH2ConnectionSettings(branch)` for the daemon-side tasks; the forked test JVM gets the values forwarded as system properties by `TiaSpockGitGradlePluginTestExtension` (only when set, so the embedded case never sends the literal string `"null"`).

### What actually differs between the modes

Three behaviours in `H2DataStore` are embedded-only and would be wrong against a shared server, so they are gated on `settings.isServerMode()`:

1. **Engine-option URL params.** Embedded mode appends `PAGE_SIZE`, `CACHE_SIZE`, `DB_CLOSE_DELAY=-1`, and `DB_CLOSE_ON_EXIT=FALSE`. These configure the *database engine instance*, which in server mode lives in the remote server process, so server mode uses the supplied URL verbatim with none of these appended. One of them matters enough in server mode that the **user** should put it in the URL themselves: `DB_CLOSE_DELAY=-1` is a *database-level* setting that a remote client's URL can apply when its connection opens the database, and without it the server closes and reopens the database around every Tia operation - see "Why server-mode URLs should include `DB_CLOSE_DELAY=-1`" below.
2. **The `tiadb-<branch>` suffix.** Embedded mode derives the file name from the branch so each branch gets its own file. Server mode does not rewrite the URL automatically, with one opt-in exception: if the configured `dbUrl` contains the `{branch}` token, `H2DataStore.buildJdbcUrl` replaces *just that token* with `tiadb-<branch>` (path separators in the branch sanitized to `-`), giving the same per-branch isolation without Tia having to guess where the database name lives. Because only the token is swapped, any prefix or suffix the user writes around it survives (`.../{branch}-myproject` becomes `.../tiadb-main-myproject`). A URL without the token is used verbatim, so a fully-specified URL still wins. This keeps the connection contract explicit - Tia only rewrites the part of the URL the user has explicitly delegated.
3. **`SHUTDOWN IMMEDIATELY` on `close()`.** In embedded mode `close()` issues `SHUTDOWN IMMEDIATELY` to release the `.mv.db` file lock before Surefire/Gradle forks the test JVM (with `DB_CLOSE_DELAY=-1` the lock would otherwise persist for the life of the daemon JVM). Against a server that command shuts down the whole database **for every connected client**, so server-mode `close()` is a no-op - the per-operation connections are already closed by their own `finally` blocks.

### Why server-mode URLs should include `DB_CLOSE_DELAY=-1`

`H2DataStore` opens a fresh JDBC connection for every operation and closes it in a `finally` block. That per-operation pattern is deliberate - it keeps the store stateless, makes every method safe to call in isolation, and in embedded mode it costs almost nothing because the engine option `DB_CLOSE_DELAY=-1` (hardcoded into the embedded URL) keeps the database instance open between connections.

Server mode inherits the same per-operation connections but **not** the same setting: the server-mode URL is used verbatim (see item 1 above), so unless the user's URL says otherwise, the database on the server runs with H2's default `DB_CLOSE_DELAY=0` - *close the database when its last connection closes*. Tia's connections never overlap, so every connection is "the last connection". Each datastore call then makes the server flush and close the whole database on `Connection.close()`, and re-open it from disk on the next `getConnection()`.

On a large mapping DB each close/reopen cycle costs roughly half a second to a second, and a single `select-tests` run performs a dozen or more datastore operations - more when library impact analysis is enabled, because the drain path reads pending batches per tracked library. On the reference project this was measured at ~23.6s of a 28s run spent blocked on the server's close/reopen churn; appending `DB_CLOSE_DELAY=-1` to the URL dropped the run to 3.5s.

The JFR signature of a missing `DB_CLOSE_DELAY` is distinctive, and worth recognizing because it looks superficially like "slow queries":
- the main thread is blocked in `SocketInputStream.socketRead` under `org.h2.value.Transfer.readInt` (waiting for the server, not transferring data);
- the dominant H2 client frame is `JdbcConnection.close()` - the time is in *closing connections*, not executing statements;
- CPU is idle (few `ExecutionSample` events) and per-read payloads are tiny.

Why `-1` (keep open until the server shuts down) rather than a timeout like `DB_CLOSE_DELAY=60`: the server in this topology exists solely to serve Tia clients, and the mapping DB is its working set - there is nothing to reclaim by letting it close between builds, and a timeout just reintroduces the reopen cost for whichever build arrives after a quiet period. The trade-off of `-1` is that the database stays open (holding its cache) until the server process stops; that is the desired steady state for a shared Tia server.

Why Tia doesn't append it automatically: the server-mode contract is that the URL is the user's, verbatim - Tia only ever substitutes the explicitly-delegated `{branch}` token (item 2 above). Silently injecting engine options would blur that contract and surprise a user who has deliberately configured a different close-delay policy on their server. Auto-appending it only-when-absent would be safe in practice and may become the default later; until then it is a documented one-liner in the README's server-mode checklist.

### Credential resolution and keeping secrets out of config

Server mode needs a username and password, and the obvious place to put them - the `tia { dbPassword = '...' }` block or the POM `<tiaDBPassword>` - is checked into source control. To avoid committing a secret, `H2ConnectionSettings.server(...)` resolves each credential by precedence: the explicitly configured value, then a `TIA_DB_USER` / `TIA_DB_PASSWORD` environment variable, then a default (`sa` / empty). So a build can leave `dbPassword` unset and have CI inject `TIA_DB_PASSWORD` into the environment, keeping the repo credential-free.

The fallback lives in the single `server(...)` factory, so it applies uniformly to every entry point (`fromConfig`, `fromSystemProperties`, and the Maven/Gradle builders that delegate to them). The environment lookup is passed in via a package-private overload (`server(url, user, password, branchSuffix, env)`) so the precedence logic is unit-tested without mutating the real process environment. This is intentionally a *fallback*, not a replacement for the build tools' own indirection (Maven `${env.X}` / encrypted settings.xml, Gradle `~/.gradle/gradle.properties`): those still work and compose, since they resolve before Tia ever sees the value. Tia never logs the password - only the JDBC URL - so the one remaining footgun is embedding credentials inside `dbUrl` itself.

The password resolver deliberately distinguishes *not configured* from *configured as empty*, which the username resolver does not. H2 accepts an empty password, so `resolvePassword` treats only `null` as "fall back to the environment"; any non-null configured value - including `""` - is used verbatim and is never trimmed (whitespace can be significant in a password). That lets a build pin an empty password explicitly (`dbPassword = ''` / `<tiaDBPassword></tiaDBPassword>`) and bypass `TIA_DB_PASSWORD`. The null-vs-empty distinction survives both plugin bridges: Maven's `@Parameter` is `null` when omitted but `""` when present-and-empty, and the Gradle forwarder only emits `-DtiaDBPassword` when the value is non-null, so an explicit empty string reaches the test JVM as a set-but-empty system property rather than an absent one. An empty *username* is meaningless to H2, so the username keeps the simpler blank-is-unset rule.

### Branch recorded in `tia_core`

`tia_core` holds the single "current state" row for a database: the commit the stored mapping is valid for, the last-updated timestamp, and the aggregate run stats. It now also carries a `branch` column. This matters most for a shared server-mode database, where several branches may point at the same server: without the branch, a row of `tia_core` (or a status / text / HTML report generated from it) gives no indication of which branch the mapping belongs to. The branch is already known at persist time (`TestRunnerService.persistTestRunData` receives it and already stamps it on every `tia_test_run_history` row) and is now stamped on the core row too, under the same `updateDBMapping` guard that seals the commit value - the branch is the identity of the mapping being stamped, so the two move together.

The column is part of the `tia_core` DDL in `createTiaDB`. Tia is pre-release with no external databases to preserve, so there is no schema migration for it - a database is simply created with the column. A genuinely unset branch (e.g. a stats-only run that never seals a commit) is stored as SQL `NULL` rather than the literal text `null`; the three summary reports render it as `N/A`.

### Server-mode prerequisite: `-ifNotExists`

`H2DataStore` auto-creates the schema (and, in embedded mode, the database file) on first use via `createTiaDB()`. An H2 TCP server refuses to create a database for a remote client unless it was started with the `-ifNotExists` flag. So running a server-mode Tia against a server without that flag fails on the very first run. This is a deployment precondition Tia can't paper over from the client side, so it's documented rather than worked around.

### Concurrency: one mapping writer, best-effort statistics

The operational model is unchanged from embedded mode and is what makes shared server mode safe in practice: **exactly one build is the mapping writer** (`tiaUpdateDBMapping=true`); every other client runs in local mode (`tiaUpdateDBMapping=false`) and only updates statistics. The mapping - the load-bearing data for test selection - has a single owner, so the delete-then-reinsert and truncate-then-insert rewrites in the persist path never contend across clients for mapping rows.

That leaves **statistics** as the only data multiple clients write concurrently. Statistics counters (`num_runs`, `avg_run_time`, success/fail counts on both `tia_core` and `tia_test_suite`, plus the all-tests-run baseline `all_tests_run_time` / `num_all_tests_runs` on `tia_core`) are read-modify-write: each client reads the current value, increments in memory (`TestRunnerService` / `incrementStats` / `mergeTestMappingStats`), and writes it back. With several clients doing this against one server database there is a classic lost-update race - two clients read `num_runs=10`, both write `11`, and one increment is lost.

This is a deliberate non-goal. Statistics in Tia are advisory: they drive reports and run-time estimates, not test selection. Adding locking (atomic SQL `num_runs = num_runs + 1` increments, or `SELECT ... FOR UPDATE` row locks) would buy exactness on data that doesn't need it, at a cost on the write path. So Tia accepts statistic drift under concurrent writers; if exact shared statistics ever become a requirement, the atomic-increment rewrite is the place to start. This is the same class of concern as the multi-fork persist limitation in the "Persist flow and crash safety" chapter - and it's the storage-layer-change trigger (#3) that chapter anticipated for revisiting `persistTestRunData`'s transaction strategy.

### Running an H2 server locally to test server mode

You don't need a separate H2 install to exercise server mode on a dev machine: Tia already depends on H2 (`com.h2database:h2:2.2.224` in `tia-core/build.gradle`), so the runnable jar is sitting in your Gradle cache. The same jar that backs embedded mode also ships H2's `org.h2.tools.Server` entry point.

**1. Start the TCP server.** The one non-negotiable flag is `-ifNotExists`: `H2DataStore.createTiaDB()` creates the database on the first run, and an H2 TCP server refuses to create a database for a remote client unless it was started with that flag (see the prerequisite subsection above).

```bash
mkdir -p ~/h2-tia
H2_JAR=$(find ~/.gradle/caches/modules-2 -name 'h2-2.2.224.jar' | head -1)

java -cp "$H2_JAR" org.h2.tools.Server \
  -tcp -ifNotExists -baseDir ~/h2-tia
```

The server listens on port `9092` by default and prints `TCP server running at tcp://...:9092`. Leave it running. `-baseDir` is where the `tiadb.mv.db` file is created; add `-tcpAllowOthers` only if a build on another machine needs to reach it. The Gradle-cache path changes when the cache is cleaned, so for a long-lived local server copy the jar somewhere stable (`cp "$H2_JAR" ~/h2-tia/h2.jar`) and run from there.

**2. Point Tia at the server.** Name the database in the URL yourself, or use the `{branch}` token to get a per-branch database (the token expands to `tiadb-<branch>`, mirroring embedded mode; a prefix/suffix around the token is preserved). A URL without the token is used verbatim:

```groovy
// Gradle - one database per branch via the {branch} token
tia {
    dbUrl = 'jdbc:h2:tcp://localhost:9092/{branch};DB_CLOSE_DELAY=-1'
}
```

```xml
<!-- Maven - or a fixed database name, used verbatim -->
<tiaDBUrl>jdbc:h2:tcp://localhost:9092/tiadb;DB_CLOSE_DELAY=-1</tiaDBUrl>
```

(`DB_CLOSE_DELAY=-1` keeps the database open between Tia's per-operation connections - see the dedicated subsection above.)

With no credentials configured, Tia falls back through `TIA_DB_USER` / `TIA_DB_PASSWORD` to `sa` / empty (see the credential-resolution subsection above), which matches the `sa`/empty account H2 creates for a brand-new database. To rehearse the env-var fallback, `export TIA_DB_PASSWORD=...` before the build and leave `dbPassword` unset; note that H2 fixes the account on first creation, so whatever password first connects becomes the database's password.

**3. Inspect the data while testing.** Run H2's web console against the same server to watch Tia's tables populate:

```bash
java -cp "$H2_JAR" org.h2.tools.Server -web -webPort 8082
```

Open `http://localhost:8082`, connect with JDBC URL `jdbc:h2:tcp://localhost:9092/tiadb` and user `sa`, and Tia's schema appears after the first run.

**4. Run a build.** From the project under test, run the normal Tia-enabled test task (`./gradlew test` / `mvn test`). The first run creates the schema; subsequent runs do selective testing against the shared server database. Remember the single-writer model from the concurrency subsection: exactly one build should run with `tiaUpdateDBMapping=true`, the rest as statistics-only readers.

---


---

Prev: [Persist flow and crash safety](persist-flow-and-crash-safety.md) | [Back to the Wiki index](../WIKI.md) | Next: [Static test selection](static-test-selection.md)
