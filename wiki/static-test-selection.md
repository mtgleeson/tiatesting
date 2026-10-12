# Static test selection — user-declared change-to-suite rules

Tia's bread-and-butter selection mechanism reads source code coverage off each test run and learns which suites exercise which source methods. The next run's selection criteria are derived from that learned mapping. There's an entire class of change drivers it can't see: SQL migration scripts, properties files, schema files, code-generator templates, anything that isn't compiled to bytecode the JaCoCo agent walks. Static test selection rules fill that gap with user-declared "if file X changes, run these suites" mappings.

### Why we call one mapping "dynamic" and the other "static"

The names are about *who owns the mapping and whether it changes from test runs*, not about regex vs. exact match or anything in the rule format.

- The **dynamic mapping** is the coverage-driven one. Tia builds it from JaCoCo data during runs that have `updateDBMapping = true`, stores it in the H2 DB, and consults it on every subsequent run. The mapping evolves on its own as runs land; the selection criteria for tomorrow's run depend on what got recorded today. The dynamic mapping is owned by Tia.

- The **static mapping** is the user-declared one. The rules live in your `tia { ... }` block (Gradle) or `<configuration>` (Maven). Tia compiles them at build start, applies them on each Tia-enabled run, and doesn't change them. The mapping is fixed in config and only moves when you edit the config. The static mapping is owned by you.

Both feed into the same `TestSelector.selectTestsToIgnore` call. From the test runner's perspective, a suite to run is a suite to run regardless of which mapping selected it; the suites picked by the static rules are additively unioned with those picked by the dynamic mapping.

### The two modes today

A rule's `mode` tells Tia what to do when the file-path regex matches a changed file:

- **`RUN_ALL`**: force-run every tracked test suite for this build. Useful when a change's blast radius is genuinely "everything" — for example a build script edit, a top-level dependency bump, or an infrastructure-as-code change you can't reason about narrowly.
- **`SUITE_NAMES`**: union in the set of suites whose simple class name *or* fully-qualified name matches any of the rule's `suiteNamePatterns` regexes. Useful when you know a specific subset of tests is sensitive to the change driver — for instance, only the `*MigrationIT` suites need to run when a SQL migration script changes.

A third mode, `ANNOTATIONS_TAGS`, is reserved in the enum but rejected at config parse time. The intent is to select suites by JUnit 5 `@Tag` / Spock annotation rather than by name regex; the implementation is deferred to a future stage because it needs a test-runner-side lookup the current code doesn't have.

### Why the rules are evaluated where they are

The two build tools take different paths from "user wrote a rule" to "the test selector applies it," and both follow directly from the underlying lifecycle differences explained in the "How Tia exchanges data with the test runner" chapter.

**Maven.** The rules are evaluated in the Maven JVM. `AgentMojo.execute()` and `SelectTestsMojo.execute()` both call `buildStaticTestSelectionConfig()`, then hand the resulting `StaticTestSelectionConfig` to `TestSelector` *in-process* — no Surefire fork required. The result of selection (the ignored / selected test lists) is then written to disk, where Surefire reads it when it launches the test JVM. The static rules never need to cross a process boundary because they're applied entirely on the Maven side.

**Gradle.** The same: the rules are evaluated in the Gradle daemon. The test task's action (`TiaTestTaskConfigurer`) builds the `StaticTestSelectionConfig` from the test task's merged `tia` extension with `TiaPlugin.buildStaticTestSelectionConfig(rawRules)` and hands it to `TestSelector` in the daemon, once per test task; the selection result reaches the test JVM through the same hand-off files Maven uses. Invalid regex / unknown mode / missing field therefore surfaces in the daemon before any test JVM starts, and the in-plugin `tia-select-tests` task uses the same builder, so both paths see identical validation.

(The Gradle selection used to run in the forked test JVM, which meant encoding the rules into a `tiaStaticTestSelectionRules` system property; that bridge is gone.)

### How the static and dynamic mappings compose

The composition is union, not intersection. `TestSelector` collects:

1. The suites selected by the dynamic mapping (because their coverage touched a changed source method).
2. The suites selected by every static rule whose `filePathPattern` matched at least one changed file.

The union becomes the "must run" set; everything else is the "ignore" set. There's no priority and no override — a suite picked by either mechanism runs; a suite picked by neither doesn't. This is deliberate: the two mappings cover different kinds of change driver, and combining them with anything other than union would mean either mechanism could silently veto the other.

`RUN_ALL` is the degenerate case at the static end: a `RUN_ALL` rule that fires effectively short-circuits the union to "every tracked suite," because adding "every tracked suite" to anything is "every tracked suite." That's the intended behaviour — `RUN_ALL` is for change drivers whose blast radius is "we don't know, run it all."

### POM-aware rules

A plain rule that matches `pom.xml` fires on every POM edit. A release commit that bumps the project version in every module, a tracked-library version bump that the library publish stamp and drain already handle precisely, and an edit to `<scm>` or the javadoc plugin all force the same full run. A POM-aware rule fires only when the POM change can affect what the code or tests do.

A rule becomes POM-aware when it sets `pomChanges` to a comma-separated list of categories. Its `filePathPattern` then defaults to any `pom.xml` (`(^|/)pom\.xml$`), and `mode` works as for any other rule:

```xml
<tiaStaticTestSelectionRule>
  <name>Functional POM changes</name>
  <pomChanges>FUNCTIONAL</pomChanges>
  <mode>RUN_ALL</mode>
</tiaStaticTestSelectionRule>
<tiaStaticTestSelectionRule>
  <name>Test runner config</name>
  <pomChanges>TEST_RUNNER</pomChanges>
  <mode>SUITE_NAMES</mode>
  <suiteNamePatterns>
    <suiteNamePattern>IT$</suiteNamePattern>
  </suiteNamePatterns>
</tiaStaticTestSelectionRule>
```

`FUNCTIONAL` is the recommended setting: it fires for any change that is not known to be inert. The finer categories are for targeting a subset of suites with `SUITE_NAMES`:

| Category | Fires on |
|---|---|
| `DEPENDENCY_VERSIONS` | The resolved version of an untracked, non-reactor dependency or managed dependency changed; a managed entry was added or removed; a BOM import or an external parent version changed. |
| `DEPENDENCY_SET` | A dependency was added or removed, or its scope, classifier, type, optional flag, system path or exclusions changed (tracked libraries included). |
| `COMPILER` | The compiler plugin (including annotation processors) or a `maven.compiler.*` / `project.build.sourceEncoding` property changed. |
| `TEST_RUNNER` | The surefire or failsafe plugin, or `argLine`, `skipTests` and the `surefire.*` / `failsafe.*` properties changed. |
| `CODE_GENERATION` | A known code generation plugin changed (openapi-generator, jaxb, cxf, protobuf, avro, antlr, jooq, build-helper and similar). Its output is not in version control, so coverage can't see the change. |
| `RESOURCES` | Resources, resource filtering, filters or the source directory layout changed. |
| `BUILD_PLUGINS` | Any other plugin or plugin management entry that is not inert changed. |
| `FUNCTIONAL` | All of the above, plus anything unrecognised: modules, packaging, repositories, extensions, profile activation, a changed property nothing in the POM references, and an added, deleted or unparseable POM. |

These changes never fire a POM-aware rule:

- project metadata (`name`, `description`, `url`, `scm`, `developers`, `licenses`, `distributionManagement`, `reporting`, ...);
- the project's own version, including through a CI-friendly `${revision}`, a same-reactor parent version, and the versions of dependencies on other reactor modules;
- version changes of tracked libraries (`tiaSourceLibs`), which the library publish stamp and drain own, including when the drain holds back (unknown build, downgrade);
- publishing, reporting and quality plugins (deploy, install, javadoc, source, gpg, release, site, checkstyle, spotbugs, pmd, enforcer, ...);
- Tia's own plugin configuration and `tia*` properties;
- whitespace, comments, and reordering of dependencies, plugins or executions.

A filtered resource that embeds `${project.version}` is a real behaviour change that the classifier treats as inert. If a test asserts on such a value, add a plain path or `SUITE_NAMES` rule for it.

**How the classification works.** When a POM-aware rule's path pattern matches a changed file, Tia reads that file's content on both sides of the diff with `VCSReader.loadFileContents`. That is one batched read for every matched POM, shared by all POM-aware rules. `PomChangeClassifier` then compares the two versions. Values are compared after resolving `${...}` against the POM's own properties, so a property change is judged by what it changes: a bumped `lib.version` shows up as the dependency version change it causes. Content is never read when no POM-aware rule matches, and plain rules never read content.

The classifier reads one POM at a time, which has two consequences for now:

- A changed property in a POM that may be a parent (`pom` packaging or `<modules>`) counts as `FUNCTIONAL`, because a child module could reference it. This includes a tracked library version kept as a property in a parent POM.
- Reactor modules are recognised by heuristics: a dependency at `${project.version}`, or one that shares the project's groupId and moved in lockstep with the project version.

**Logging and history.** When a POM-aware rule matches a POM, an INFO line says why it fired or why it did not, listing the first few changes, e.g. `Static test selection rule 'Functional POM changes' matched [pom.xml] but none of the changes is in [FUNCTIONAL]: [project version 1.4.0 -> 1.5.0 (inert)]`. The full change list is logged at DEBUG. The run-history trigger records the categories that fired, e.g. `Functional POM changes [DEPENDENCY_VERSIONS]`.

**Library rules.** A library's own static rules, evaluated at publish time against the files changed since its previous publish, use the same evaluation. A library's POM-aware rule therefore records a forced selection for a consumer only for a functional POM change.

POM-aware rules are Maven-only. Gradle build scripts are code, not data, and the Gradle extension does not expose `pomChanges`.

### When this design might shift

Two evolutions are already plausible:

- **`ANNOTATIONS_TAGS` mode.** The current `SUITE_NAMES` mode requires you to know the naming convention for the suites you want to force-run. Tag-based selection (JUnit 5 `@Tag`, Spock annotations) is more robust to renames. Adding it needs the test-runner-side listener to know each suite's tag set when it asks "does this rule apply to me," which is straightforward in JUnit 5 but needs design work for Spock.
- **Eager-only evaluation on the Maven side.** Today the same `StaticTestSelectionConfig` builder is called by both the agent mojo (runs every test build) and the preview select-tests mojo (runs on demand). If a future change makes the rule list dramatically more expensive to build, it'd be worth hoisting the build to once per project per Maven execution.

Both shifts are additive — no part of the current design needs to change to enable them.

---


---

Prev: [Pluggable datastore (H2, Postgres, and the seam for more)](pluggable-datastore.md) | [Back to the Wiki index](../WIKI.md) | Next: [Setting up a machine to run the release tasks (GPG signing)](release-signing-setup.md)
