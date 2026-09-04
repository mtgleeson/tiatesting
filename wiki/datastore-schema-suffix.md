# Isolating the datastore per test task (`schemaSuffix`)

Tia's schema name is derived from the VCS branch: `tia_` plus the sanitised branch, clamped to
Postgres's 63-character identifier limit. That is one dimension, and for a project with one test task
it is the right one. A project with **two** Tia-enabled test tasks writing to one schema corrupts
itself in two ways, and neither fails a build.

### 1. They delete each other's tracked suites

`TestRunnerService.removeDeletedTestSuites` reads "tracked in the DB but not known to this runner" as
"deleted from the repo". That inference holds only when the runner set covers the whole project. Each
test task's JVM sees only its own source set, so `test` deletes every `integrationTest` suite and
vice versa.

The steady state is worse than it sounds. `TestSelector.getTestsToIgnore` iterates the *tracked*
suites, so an untracked suite can never be ignored - it always runs. Each task therefore runs its
entire suite set every build, forever, re-tracking its own suites and deleting the other's. Tia is
enabled, writing to the database, and delivering nothing. The only symptom is that the build never
got faster.

### 2. They share one stored commit value

`tia_core` holds one row per schema, so both tasks stamp the same commit. Harmless while both run on
every commit; unsafe the moment their cadences differ, which is the usual reason to have separate
test tasks:

1. Commit X - both run, both seal X.
2. Commit Y - only `test` runs (a PR build). It seals **Y**.
3. Commit Z - `integrationTest` runs, reads the stored commit as Y, and diffs Y->Z.

The X->Y changes are invisible to `integrationTest`, which never ran against them. That is silent
**under-selection** - a green build that should have been red.

**The two mask each other.** Deletion keeps each task's suites untracked, everything runs, and
something that runs everything cannot under-select. Fixing deletion alone would move a project from
"Tia is not saving us much" to "Tia is silently skipping integration tests", so both are addressed
together.

## The suffix

```groovy
tia { enabled = true; updateDBMapping = true }

test            { tia { schemaSuffix = "unit" } }        // -> tia_main_unit
integrationTest { tia { schemaSuffix = "integration" } } // -> tia_main_integration
```

```xml
<!-- Maven: per execution -->
<tiaDBSchemaSuffix>integration</tiaDBSchemaSuffix>
```

Each task gets its own schema, its own suite table, and its own `tia_core` row - so its own commit
value and its own all-tests baseline, which is what closes the second defect.

**Unset changes nothing.** The schema is the `tia_<branch>` Tia has always used, byte for byte. A
single-test-task project needs no configuration and its existing mapping is untouched on upgrade.

### Why it is declared rather than derived

A derived name is a function of the build configuration, so it moves when the configuration moves.
Auto-suffixing only when several test tasks exist would silently relocate `test` from `tia_main` to
`tia_main_test` the day someone adds `integrationTest` - orphaning its mapping, forcing a re-seed,
and leaving the old schema as garbage, all triggered by editing an unrelated part of the build file
with nothing failing. Declared names move only when someone edits them.

### Two properties the naming has to hold

**With no suffix the output is byte-identical to the pre-suffix implementation.** The room reserved
for a suffix is reserved *only when there is one*; reserving it unconditionally would shorten the
name for any branch at the identifier limit and silently relocate that project's schema. Verified
against a copy of the old algorithm across 2,249 branch names.

**The suffix is never what gets clamped away.** Clamping the concatenation from the right would drop
it on a branch already at the limit, so two test tasks would land back in one schema - the exact bug
the suffix prevents, reappearing only for projects with long branch names. The branch is truncated to
make room instead, and a suffix leaving room for no branch at all falls back to a CRC of the pair.

Two collisions are left alone deliberately: two long branches sharing a prefix (pre-existing,
unchanged), and the sanitisation case where branch `main_integration` + suffix `test` meets branch
`main` + suffix `integration_test`. Every non-alphanumeric sanitises to `_`, so no separator can be
unambiguous - the guard below catches those loudly rather than the naming contorting around them.

## The guard

Declaring the suffix is optional, and both failures it prevents are silent, so the setting alone
would be a footgun. Both build systems refuse a colliding configuration.

**Gradle** fails when two *mapping-owning* test tasks resolve to one datastore and schema, naming
both and the setting that fixes it. Every **defined** test task counts, not only those in the current
build: the corruption spans invocations, because the datastore outlives either build. Only
mapping-owning tasks count - a task with `updateDBMapping` off writes no mapping and deletes nothing,
so counting it would refuse safe configurations.

**Maven** fails when two Tia executions in one module claim the same schema. Each execution records
the schema it *resolved* to, and the collision is detected between recordings - reading the
configured `<tiaDBSchemaSuffix>` text instead would miss the very case it exists for, since an
undefined property reference evaluates to null and two executions that both meant to declare a suffix
would silently share the unsuffixed schema while their configuration looked different. It sees one
Maven invocation, which is where surefire and failsafe both run.

## Reporting

The Gradle reporting tasks iterate the configured suffixes, printing a heading per schema only when
there is more than one - so single-schema output is unchanged. The text and HTML reports write one
tree per schema, scoped by the same folder mechanism that already scopes them per branch:
`html/main_unit/`, `html/main_integration/`, and plain `html/main/` when no suffix is declared.

Reporting counts **every enabled** test task, unlike the guard's mapping-owners. A task with
`updateDBMapping` off still writes history rows to its own schema - the local developer runs the
history report exists to surface - so excluding it would make that population invisible.

Maven cannot iterate: a reporting goal is a standalone invocation with no view of which executions
exist. It selects one schema per invocation instead, which the mojo parameter already supports:

```bash
mvn tia-junit5-git:history -DtiaDBSchemaSuffix=integration
```

The distributed goals need no selector at all. A build configuring a second distributed test task is
refused at configuration time, so there is exactly one schema a distributed run can belong to, and
`dist-plan`, `dist-status` and `dist-complete` derive it. They must address the schema the runner's
fork persists to, or the plan is written where no runner looks for it.

## Library publish stamping

A library published to consumers that isolate their tests into suffixed schemas must declare those
schemas:

```groovy
tia { libraryStampSchemas = "unit, integration" }
```

Declared, never derived: the consuming app is a **separate build**, so the library's own project has
no visibility of its schemas and cannot detect that it missed one. A stamp written where no consumer
reads it is never drained, and the suites the library change affects are never re-run.

Comma-separated rather than a list, matching every other multi-valued Tia setting and, more
importantly, the centralised parent-pom pattern: Maven cannot drive a `List` element from a single
property, so a `List` parameter would force projects to hard-code entries and lose their `-D`
overrides.

**Stamping several schemas is not atomic** - each is its own connection and transaction, and the
publish has already happened by the time the stamp runs. Every schema is therefore attempted rather
than failing at the first, leaving the smallest possible gap, and the build then fails naming exactly
which schemas hold the stamp and which do not. A warning would not do: what it describes is silent
under-selection in the schemas that missed it.

## What this does not cover

- **The fork variant.** One test task split across JVMs by `maxParallelForks > 1` or `forkEvery > 0`
  hits the same deletion bug, and suffixes do not help - those JVMs share a schema by definition. The
  fix there is a project-wide test class list, the way Maven's `testClassesDir` already works.
- **Maven multi-module reactors.** Each module's `testClassesDir` is its own, so a reactor with Tia
  enabled in several modules against a shared datastore has the same mutual deletion, with no guard
  outside the distributed path. Supporting that properly is separate work.
