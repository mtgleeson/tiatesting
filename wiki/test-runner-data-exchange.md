# How Tia exchanges data with the test runner (Gradle vs Maven)

Both build tools run Tia's test selection in the build JVM - the Maven plugin's `prepare-agent` goal, the Gradle daemon's test-task action - once per test task, and hand the result to the forked test JVM(s) through the same files (`ignored-tests.txt`, `selected-tests.txt`, `drain-result.ser`, `run-selection-details.txt`), written by `tia-core`'s `SelectionHandoff`. What differs is only how each tells the fork where those files are. This chapter explains why the boundary looks the way it does.

### Both build tools fork a separate JVM for tests

Maven Surefire and Failsafe fork a JVM to run tests. Gradle's `Test` task does the same (configurable via `forkEvery` and `maxParallelForks`, but a fork happens by default). In both cases the build-tool process is one JVM and the test runner is another.

So Spock, JUnit5, and any other test framework run in the **forked test JVM** — not in the build-tool's JVM. There is no shared heap, no shared classloader, no in-process method call between the plugin code and the test framework code. Whatever data needs to flow has to cross a process boundary.

### Both build tools share startup state the same way: `-D` system properties

When a parent process forks a child JVM, the standard mechanism for passing key/value config is JVM arguments — specifically `-Dkey=value`. The child reads them via `System.getProperty(...)`. There's no magic.

- **Maven Surefire** has `<systemPropertyVariables>` in its plugin config; Surefire turns those into `-D` args when launching the test JVM.
- **Gradle's `Test` task** exposes `task.systemProperty(key, value)` / `task.systemProperties(map)`; Gradle turns those into `-D` args when launching the test worker.

Both paths are forked-process system-property propagation. The mechanism is identical.

### The real difference is *when* the plugin can compute the values

What separates the two is the lifecycle hook each build tool gives plugin authors:

- **Gradle's `Test` task** lets plugins register a `task.doFirst { ... }` action that runs in the Gradle daemon **immediately before** the fork. Inside that action, plugin code has full access to the project model, can run arbitrary computation (e.g. resolve library metadata via the Tooling API), and can call `task.systemProperty(...)` with the result. Gradle then includes those properties when launching the fork. Dynamic values flow naturally from plugin computation to forked test JVM.

- **Maven Surefire** reads its plugin configuration (including `<systemPropertyVariables>`) from the project's static XML config. By the time a Tia mojo realises it needs to inject specific data, the configuration phase has passed. The closest workaround Tia's Maven mojo uses is mutating the project's `argLine` property at runtime (`AgentMojo.execute` updates `projectProperties.setProperty(name, newValue)`); Surefire then includes that string in the fork's JVM args. This works for the agent JAR path and a handful of options, but it's awkward for arbitrary structured key/value data and offers no clean per-property API.

So the reason Tia leans on files isn't that it *can't* use system properties - it's that the *ergonomics* of dynamically setting many or large-valued properties through Surefire's static config model are bad enough that files end up cleaner.

### Why the selection travels in files

Two practical limits push both build tools toward files for the selection:

1. **Size.** The ignored-tests list can be thousands of entries (test classes × parametrizations × fully-qualified paths). Operating-system command-line argument limits (`ARG_MAX` on Unix, much smaller on Windows) make stuffing all of it into a single `-D` arg fragile. A file is unbounded.
2. **Structure.** `LibraryImpactDrainResult` is a serialized Java object, not a string. To force it into a `-D` arg you'd base64-encode the bytes — uglier than just writing the bytes to a file.

The Tia agent in the forked JVM reads file paths from `AgentOptions` (which *is* passed via JVM args, since that's a small fixed string) and loads the contents at startup.

The same file mechanism also carries the **forked-JVM system properties** the test listener needs - the connection settings (`tiaDBUrl` / `tiaDBUser` / `tiaDBPasswordFile` / `tiaDBFilePath`), `tiaProjectDir`, `tiaClassFilesDirs`, `testClassesDir`, and the `tiaUpdateDB*` / `tiaEnabled` flags. The agent mojo writes them to a `fork.properties` file, passes its path as the `forkPropertiesFile` agent option, and the agent's `premain` replays them into `System` properties (only when not already set, so an explicit `-D` still wins) via `ForkSystemProperties`. This removes the old requirement that the user mirror every value into Surefire `<systemPropertyVariables>` - the source of a common server-mode footgun where a missing `tiaDBUrl` in the fork silently fell back to embedded mode. A file (rather than appending more `-D` args to `argLine`) is the right carrier for the same two reasons as above: `tiaClassFilesDirs` is a comma-separated list that would collide with the comma-delimited `AgentOptions` parser, and it plus `testClassesDir` are long enough to risk the command-line limit (Windows especially). Gradle forwards the equivalent values with `task.systemProperty(...)`; Maven now reaches parity via this file. The database password is the one value that travels differently on each: it must never become a system property in the fork, because Surefire publishes those in the report XML and Gradle puts them on the worker command line. Gradle therefore forwards it with `task.environment(...)` and Maven forwards a file path, and `ForkSystemProperties.write` refuses any credential-shaped key outright.

### How Tia-Gradle hands the selection over

Gradle runs the selection in the test task's `doFirst` action, in the daemon, and writes the same
`SelectionHandoff` files into the test task's temporary directory. How the fork finds them is the
one per-framework step, the plugin's `TestFrameworkAdapter`; everything else in the task action is
framework-agnostic.

- **Spock** needs no agent: `task.systemProperty(...)` names each file directly
  (`tiaIgnoredTestsFile`, `tiaSelectedTestsFile`, `tiaRunSelectionDetailsFile`, and
  `tiaDrainResultFile` when something was drained), and `TiaSpockGlobalExtension` reads them and
  skips specs itself.
- **JUnit 5** uses the agent, as on Maven: JUnit has no Spock-style global extension Tia can rely
  on (an `ExecutionCondition` would need `junit.jupiter.extensions.autodetection.enabled`, which
  also switches on every other extension on the classpath), so skipping is done by marking ignored
  suites `@Disabled` at class-load time. `Junit5FrameworkAdapter` appends a
  `TiaAgentArgumentProvider` to the task's `jvmArgumentProviders` with
  `-javaagent:<tia-junit5-agent runtime jar>=<AgentOptions>` naming the files. The provider is
  added in the task action, after the jacoco plugin registered its own provider at configuration
  time, so the Tia agent follows the JaCoCo agent on the command line (jacoco/jacoco#551, the
  ordering Maven's `addVMArguments` keeps too). The jar is resolved from the project's repositories
  in a detached configuration, so it reaches none of the project's classpaths. The other settings
  already travel as task system properties, so the agent's `forkPropertiesFile` and
  `libraryJarsFile` options stay unset.

**The agent jar is searched before the project's classes on Gradle.** A `-javaagent` jar joins the
system class path when the JVM starts. Surefire's booter jar lists the project's test classpath
first, so on Maven the agent jar comes last. Gradle's worker places the project's classes on the
system class path after the agent jar (they are reachable when `premain` runs - measured on Gradle
8.4, Java 8 - just later in the search order), so on Gradle every class in the agent jar is found
before the project's own copy.

**The agent uses only the JDK and its own jar.** It cannot rely on the project: Surefire with
`useSystemClassLoader=false` keeps the project's classes off the system class loader entirely, and
a project need not have SLF4J at all. So the agent logs through `java.util.logging`, and the jar
bundles every Tia class `premain` touches.

**The agent jar carries nothing a project could also have.** The jar used to bundle all of
its dependencies - JUnit Platform and Jupiter, ByteBuddy, ASM, JaCoCo, H2, j2html - and a project on
a different JUnit version then ran with a mix of Tia's JUnit classes and its own, failing with
`NoSuchMethodError` on every run (JUnit 5.13 against the bundled 5.11). Maven never noticed because
Surefire puts the project's classpath first. The jar (built by the Shadow plugin in
`tia-junit5-agent`) now holds only:

- the agent itself (`org.tiatesting.agent`), including `IgnoreTestInstrumentor`;
- the `tia-core` classes `premain` uses, `AgentOptions`, `CommandLineSupport` and
  `ForkSystemProperties` - the same classes, from the same Tia version, also reach the test classpath
  through `tia-junit5`, so which copy loads first does not matter;
- ByteBuddy, relocated to `org.tiatesting.shaded.bytebuddy` so it is a different library, by name,
  from any ByteBuddy the project has (Mockito's, for example). The agent switches on its
  experimental mode, so test classes compiled for a newer Java than it knows are still annotated;
  the property is relocated too, so the switch for Tia's copy is
  `-Dorg.tiatesting.shaded.bytebuddy.experimental`, and `-Dnet.bytebuddy.experimental` only affects
  the project's own ByteBuddy.

No JUnit class is bundled. `@Disabled` is described, when each ignored test class loads, from the
class file that test class's own loader finds - the project's JUnit - and is added from that
description, so it resolves against the project's JUnit too. The `verifyAgentJar` task, part of
`check`, fails the build if anything else ever lands in the jar, or if a bundled Tia class refers to
a Tia class the jar does not contain.

A distributed runner hands its share over the same way. The daemon claims the group with
`DistributedRunnerAssignment.claim`, which also derives the suites the runner runs and ignores, and
writes those as the same files (no drain result, an empty selection breakdown). The fork only
reads files, whether or not the build is distributed.

The selection used to run inside the Spock test JVM instead. Moving it to the daemon matters for
three reasons:

1. **It runs once per test task.** With `maxParallelForks > 1` or a test-retry round, every forked
   JVM used to repeat the diff and the library-impact drain.
2. **The fork needs no version control system.** Only the build JVM reads the VCS, so no VCS
   library is ever on the test classpath.
3. **One model for both build tools.** Library metadata and static rules are built where the build
   model is, rather than encoded into system properties for the fork to rebuild.

### Nested test classes (JUnit 5 `@Nested`)

A `@Nested` class is tracked as its own suite (`Outer$Inner`), but it only ever runs inside its
enclosing class, and Tia skips a suite by marking its class `@Disabled` - which skips every class
nested in it too. A top-level class and everything nested in it form a **family**, read from the
binary name (`NestedTestSuites`), and four rules follow:

1. **Families are selected whole.** Once every other source of selection has run, `TestSelector`
   adds every tracked member of each selected suite's family to the run set
   (`NestedTestSuites.addFamilies`). Selecting anything less could skip affected tests: a selected
   nested class only runs inside its enclosing class; an edited test file names only its top-level
   class, though the nested classes declared in it are separate suites; and an enclosing class's
   `@BeforeAll` and static set-up run once for the whole family but are credited to one suite. The
   cost is some over-selection inside a family, never a missed test. The added suites count as
   selected in the estimate, the history row and a distributed plan. The history breakdown does not
   yet record why a family member was added (it has no trigger of its own), so the selected count
   can exceed the listed reasons - a known gap.
2. **A distributed plan keeps a family in one group.** `TestGroupBalancer` balances families
   rather than suites, a seed split counts top-level classes only (the disk scan's nested,
   anonymous and helper class names weigh nothing), and a forced plan's untracked disk-scan names
   join the group already holding their top-level suite.
3. **Coverage stays with the class whose tests produced it.** Jupiter runs `Outer`'s own tests and
   then `Outer$Inner` inside `Outer`'s container. The JUnit 5 listener collects coverage when a
   class container finishes, so without care `Outer$Inner`'s dump would also carry `Outer`'s tests.
   The listener therefore also collects when a nested class starts, if a test has run since the last
   dump, and credits that dump to the enclosing class.
4. **Each suite records its own run time.** An enclosing class's container wall clock includes the
   nested classes that ran inside it. The listener subtracts each nested class's container time
   from its enclosing class when that finishes, so stored averages are each suite's own and every
   consumer - the estimate, the overhead model, the balancer, the seal - can simply add them up.

**Known limitation: `@Nested` classes declared in a superclass.** The rules read a family from the
binary name. A `@Nested` class declared in a base class (`AbstractContractTest$WhenEmpty`) runs
inside each concrete subclass (`ConcreteTest`), whose name the binary name does not mention.
Selecting the nested suite then brings in `AbstractContractTest` - which never runs - while
`ConcreteTest` can stay ignored, and the `@Disabled` on it skips the selected nested tests; a
distributed plan can also separate them. Until this is fixed, a project relying on inherited
`@Nested` classes should not depend on Tia skipping their subclasses.

**Known limitation: static nested test classes.** A `static` nested class with its own tests (not
`@Nested`) also has a `$` in its name but runs on its own, not inside its outer class. The family
rules still apply to it: selecting either selects both, and a plan keeps them in one group. That
costs some selectivity and balancing freedom but never skips a test.

Both limitations have the same fix: the listener records which class each nested class actually ran
inside (it can see the running containers), and selection and planning use that recorded
relationship instead of the name. That needs a schema change.

Before these rules, a change covered only by `Outer`'s own tests was credited to `Outer$Inner`; the
selection then ran `Outer$Inner`, ignored `Outer`, and the `@Disabled` on `Outer` skipped both - so
nothing ran. A distributed plan could also put `Outer$Inner` in a different group from `Outer`, where
it ran on no runner.

### VCS libraries never cross the boundary

The VCS is read only in the build JVM, and neither build plugin depends on a VCS module. Each
detects the VCS (explicit `tiaVcs` / `vcs`, then a server URI for Perforce, then a `.git` entry)
and resolves only that provider module, `tia-vcs-git` or `tia-vcs-perforce`, into an isolated
class loader: Maven through its resolver at run time, Gradle through the `tiaVcs` configuration.
`ServiceLoader` finds the provider (`VCSReaderProvider`) in that loader. A provider already on the
plugin's own class path - a Maven plugin dependency, the Gradle buildscript class path - is used
directly instead.

### The branch and the commit always cross this boundary

Two values are forwarded on every Tia build, not only a distributed one: `tiaBranch` and
`tiaCommitValue`, as resolved by the build JVM. The test JVM used to work both out for itself by
opening a repository - which meant a JGit open (or a Perforce server connection) in every forked test
JVM, and meant the two ends could disagree about which branch's schema the run belonged to.

They travel the same way everything else does: in `fork.properties` on Maven, as test task system
properties on Gradle. `ForkSystemProperties.branchFromSystemProperties()` and
`commitValueFromSystemProperties()` read them back, and both fail naming the property when it is
absent rather than defaulting - a missing branch would silently resolve to a different schema than
the build JVM used, and a missing commit would leave a single-host run stamping a null and the next
build with no diff baseline. Neither is read when Tia is not enabled for the run.

No test JVM constructs a VCS reader on either build tool. See the
["What the post-plan steps need from the version control system"](distributed-test-runs.md) section
for why that matters for distributed runners.

### Why the paths reach the fork differently

Maven Surefire builds the fork's command line from static plugin configuration, so the only thing
a mojo can change at run time is the `argLine` property - which is why Maven passes the file paths
as Tia javaagent options and the agent republishes them in `premain`. Gradle's `doFirst` can set
system properties on the fork directly, so it needs no agent for this. The files themselves are the
same, written and read by the same `tia-core` code.

---


---

Prev: [Library publish-time stamping](library-publish-time-stamping.md) | [Back to the Wiki index](../WIKI.md) | Next: [Logging conventions (TRACE vs DEBUG)](logging-conventions.md)
