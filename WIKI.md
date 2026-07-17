# Tia Wiki

Design notes and operational documentation for Tia contributors. Each chapter lives in its own
page under [`wiki/`](wiki/); this page is the index. Chapters link to their neighbours, so the
wiki can also be read front to back.

## Chapters

- [Library publish-time stamping](wiki/library-publish-time-stamping.md) - how Tia tracks in-repo
  libraries: the publish ledger and sequence, the stamp/drain lifecycle, the mapping baseline,
  the local dev flow and the library reporting tasks.
- [How Tia exchanges data with the test runner (Gradle vs Maven)](wiki/test-runner-data-exchange.md) -
  why Maven hands state to the forked test JVM via files while Gradle/Spock uses system
  properties, and when each plugin can compute what.
- [Logging conventions (TRACE vs DEBUG)](wiki/logging-conventions.md) - why daemon-side code must
  log at DEBUG and only test-JVM code may use TRACE.
- [Why Tia requires Maven 3.8.1+](wiki/maven-version-requirement.md) - the CVE-driven floor and
  how the `<prerequisites>` check surfaces it to users.
- [Profiling select-tests against a synthetic large DB](wiki/profiling-select-tests.md) - the
  `generateLargeTiaDb` / `profileSelectTests` harness for measuring the hot read path.
- [Test-run history log](wiki/test-run-history.md) - the `tia_test_run_history` audit table, the
  `history` task and the HTML History tab.
- [The select-tests run-time estimate and its mapping overhead](wiki/select-tests-run-time-estimate.md) -
  how the estimate is built and why coverage-collecting runs get an amortised overhead figure.
- [Database schema (tables and relationships)](wiki/database-schema.md) - every table, its purpose
  and the relationships between the mapping, library-impact and audit clusters.
- [Persist flow and crash safety](wiki/persist-flow-and-crash-safety.md) - the seal-last invariant
  and the failure-mode taxonomy that keeps crashes self-correcting.
- [Embedded vs server-mode H2 connections](wiki/h2-connection-modes.md) - connection resolution,
  the embedded engine options and the shared-server considerations.
- [Static test selection](wiki/static-test-selection.md) - user-declared change-to-suite rules
  layered on top of dynamic selection.
- [Setting up a machine to run the release tasks (GPG signing)](wiki/release-signing-setup.md) -
  GPG key setup for Gradle and Maven release signing.
