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
`SelectionHandoff` files into the test task's temporary directory. It does not need an agent to
find them: `task.systemProperty(...)` names each file directly (`tiaIgnoredTestsFile`,
`tiaSelectedTestsFile`, `tiaRunSelectionDetailsFile`, and `tiaDrainResultFile` when something was
drained), and the framework's Tia module in the fork - `TiaSpockGlobalExtension` for Spock - reads
them. That per-framework step is the plugin's `TestFrameworkAdapter`; everything else in the task
action is framework-agnostic.

The selection used to run inside the Spock test JVM instead. Moving it to the daemon matters for
three reasons:

1. **It runs once per test task.** With `maxParallelForks > 1` or a test-retry round, every forked
   JVM used to repeat the diff and the library-impact drain.
2. **The fork needs no version control system.** Only the build JVM reads the VCS, so no VCS
   library is ever on the test classpath.
3. **One model for both build tools.** Library metadata and static rules are built where the build
   model is, rather than encoded into system properties for the fork to rebuild.

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
