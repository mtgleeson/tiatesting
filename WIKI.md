# Tia Wiki

Design notes and operational documentation for Tia contributors. Each chapter lives in its own
page under [`wiki/`](wiki/); this page is the index. Chapters link to their neighbours, so the
wiki can also be read front to back.

## Chapters

- [Library publish-time stamping](wiki/library-publish-time-stamping.md) - how Tia tracks in-repo
  libraries: the publish ledger and sequence, the stamp/drain lifecycle, the mapping baseline,
  the local dev flow and the library reporting tasks.
- [Directory-based library-jar resolution](wiki/library-jars-directory-resolution.md) - the
  offline-safe `tiaLibraryJarsDirs` mode that resolves `tiaSourceLibs` coverage jars by filename
  inside a deployment `lib/` directory instead of through the source project's dependency graph.
- [How Tia exchanges data with the test runner (Gradle vs Maven)](wiki/test-runner-data-exchange.md) -
  both build tools select in the build JVM and hand the result to the forked test JVM through
  files; why only the way the fork finds them differs (system properties for Gradle/Spock, the
  JUnit 5 agent for Maven and Gradle/JUnit 5), how JUnit 5 `@Nested` classes are kept runnable,
  and why VCS libraries never reach the fork.
- [Logging conventions (TRACE vs DEBUG)](wiki/logging-conventions.md) - why daemon-side code must
  log at DEBUG and only test-JVM code may use TRACE.
- [Why Tia requires Maven 3.8.1+](wiki/maven-version-requirement.md) - the CVE-driven floor and
  how the `<prerequisites>` check surfaces it to users.
- [Profiling select-tests against a synthetic large DB](wiki/profiling-select-tests.md) - the
  `generateLargeTiaDb` / `profileSelectTests` harness for measuring the hot read path.
- [Test-run history log](wiki/test-run-history.md) - the `tia_test_run_history` audit table, the
  `history` task and the HTML History tab.
- [History timeline chart](wiki/history-timeline-chart.md) - the inline-SVG bar chart of recent run
  wall clocks above the History table: pass/fail colour, click-through to a run's detail page, the
  last-20 / show-10-more window, and why it is hand-rolled rather than a bundled charting library.
- [Run history details](wiki/run-history-details.md) - the per-run selection breakdown (source-method
  and static-rule triggers, five scalar counters), its storage, transport and distributed staging,
  and the HTML detail page / `history-details` CLI command that surface it.
- [The select-tests run-time estimate and its overhead model](wiki/select-tests-run-time-estimate.md) -
  how the estimate is built and why coverage-collecting runs get an amortised overhead figure.
- [Database schema (tables and relationships)](wiki/database-schema.md) - every table, its purpose
  and the relationships between the mapping, library-impact and audit clusters.
- [Persist flow and crash safety](wiki/persist-flow-and-crash-safety.md) - the seal-last invariant
  and the failure-mode taxonomy that keeps crashes self-correcting.
- [Failed-suite tracking](wiki/failed-suite-tracking.md) - what counts as a suite failure per test
  framework, how a suite's latest outcome carries across Surefire retries, the incremental failed-set
  write that keeps concurrent distributed runners from discarding each other's entries, and how a
  Surefire rerun or a Gradle test-retry round is detected and persisted as a retry.
- [Forced runs and re-seed](wiki/forced-runs-and-reseed.md) - the `tiaSelectAllTests` and
  `tiaReseed` runtime flags: the selection mode, why pending library stamps survive, the re-seed
  clear-out inside the seal transaction, retries, distributed runs, and the measured cost.
- [Distributed test runs (group assignment and the run lifecycle)](wiki/distributed-test-runs.md) -
  how one logical build is split across CI runners: the plan, the claim protocol, the completion
  barrier and the sealer election, plus what a pipeline has to run.
- [Embedded vs server-mode H2 connections](wiki/h2-connection-modes.md) - connection resolution,
  the embedded engine options and the shared-server considerations.
- [Database credentials](wiki/database-credentials.md) - the four channels a build can supply the
  password through, why the forked test JVM is handed a reference rather than the value, and why
  Maven and Gradle transport it differently.
- [Pluggable datastore (H2, Postgres, and the seam for more)](wiki/pluggable-datastore.md) - the
  `SqlDialect` / `ConnectionProvider` / `DataStoreFactory` architecture, URL-scheme dialect
  inference, and the two-classpath driver model.
- [Isolating the datastore per test task](wiki/datastore-schema-suffix.md) - the `schemaSuffix`
  dimension on the schema name, the collision guards, per-schema reporting, and the library stamp
  fan-out.
- [Static test selection](wiki/static-test-selection.md) - user-declared change-to-suite rules
  layered on top of dynamic selection.
- [Constructor and static initializer line ranges](wiki/initializer-line-ranges.md) - why a field
  declared after other methods stretches a constructor's line range over them, and the exact line
  ranges Tia records and matches instead.
- [Method run stats](wiki/method-run-stats.md) - the per-method executed-run and triggered-run
  counts: which runs count, how the seal accumulates them, how the triggering method ids reach it,
  what resets them, and the measured disk and run-time cost.
- [Setting up a machine to run the release tasks (GPG signing)](wiki/release-signing-setup.md) -
  GPG key setup for Gradle and Maven release signing.
