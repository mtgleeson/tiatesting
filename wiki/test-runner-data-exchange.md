# How Tia exchanges data with the test runner (Gradle vs Maven)

A recurring source of confusion when reading Tia's code is why the Maven and Gradle paths look architecturally different — Maven writes files (`ignored-tests.txt`, `selected-tests.txt`, `drain-result.ser`), Gradle uses system properties. The instinct is to assume one runtime can share state in-process and the other can't. That's not what's going on. This chapter unpacks what's actually different and why each path made the choice it did.

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

- **Maven Surefire** reads its plugin configuration (including `<systemPropertyVariables>`) from the project's static XML config. By the time a Tia mojo realises it needs to inject specific data, the configuration phase has passed. The closest workaround Tia's Maven mojo uses is mutating the project's `argLine` property at runtime (`AbstractTiaAgentMojo.execute` updates `projectProperties.setProperty(name, newValue)`); Surefire then includes that string in the fork's JVM args. This works for the agent JAR path and a handful of options, but it's awkward for arbitrary structured key/value data and offers no clean per-property API.

So the reason Tia-Maven leans on files isn't that it *can't* use system properties — it's that the *ergonomics* of dynamically setting many or large-valued properties through Surefire's static config model are bad enough that files end up cleaner.

### Why Tia-Maven uses files

Two practical limits push Maven specifically toward files for its biggest payloads:

1. **Size.** The ignored-tests list can be thousands of entries (test classes × parametrizations × fully-qualified paths). Operating-system command-line argument limits (`ARG_MAX` on Unix, much smaller on Windows) make stuffing all of it into a single `-D` arg fragile. A file is unbounded.
2. **Structure.** `LibraryImpactDrainResult` is a serialized Java object, not a string. To force it into a `-D` arg you'd base64-encode the bytes — uglier than just writing the bytes to a file.

The Tia agent in the forked JVM reads file paths from `AgentOptions` (which *is* passed via JVM args, since that's a small fixed string) and loads the contents at startup.

The same file mechanism also carries the **forked-JVM system properties** the test listener needs - the H2 connection (`tiaDBUrl` / `tiaDBUser` / `tiaDBPassword` / `tiaDBFilePath`), `tiaProjectDir`, `tiaClassFilesDirs`, `testClassesDir`, and the `tiaUpdateDB*` / `tiaEnabled` flags. The agent mojo writes them to a `fork.properties` file, passes its path as the `forkPropertiesFile` agent option, and the agent's `premain` replays them into `System` properties (only when not already set, so an explicit `-D` still wins) via `ForkSystemProperties`. This removes the old requirement that the user mirror every value into Surefire `<systemPropertyVariables>` - the source of a common server-mode footgun where a missing `tiaDBUrl` in the fork silently fell back to embedded mode. A file (rather than appending more `-D` args to `argLine`) is the right carrier for the same two reasons as above: `tiaClassFilesDirs` is a comma-separated list that would collide with the comma-delimited `AgentOptions` parser, and it plus `testClassesDir` are long enough to risk the command-line limit (Windows especially). Gradle forwards the equivalent values with `task.systemProperty(...)`; Maven now reaches parity via this file.

### Why Tia-Gradle/Spock uses system properties

The size/structure limits don't bite on Gradle for two reasons:

1. **Selection runs *inside* the test JVM.** `TiaSpockGlobalExtension` is loaded by Spock at test-JVM startup, opens the Tia DB directly, and computes the ignored-tests list there. The huge list of ignored tests never crosses a process boundary — it's computed where it's used. The only data the plugin needs to forward is the inputs that aren't reachable from inside the test JVM (anything that requires Gradle's `Project` or Tooling API).
2. **Library metadata is small and bounded.** A handful of coordinates × a few string fields per coordinate fits comfortably in one system property. The `tiaLibrariesMetadata` flat-string format documented in the previous chapter is well within `ARG_MAX` for any realistic project.

So Gradle gets to use system properties for everything that needs to cross the boundary, with no file artefacts and no parallel "data file" lifecycle to manage.

### When this design might shift

The current asymmetry is pragmatic, not principled. If Maven Surefire ever exposed a hook that lets a mojo inject system properties at fork time (the way Gradle's `doFirst` does), Tia-Maven could move some payloads off files. Conversely, if a Gradle test task someday accumulates a large enough payload to push past `ARG_MAX`, it'd need to fall back to a file like Maven. Neither pressure exists today.

The takeaway for anyone reading the code: **the plumbing difference is about lifecycle ergonomics and payload size, not about JVM-to-JVM communication capability**. Both build tools use the same underlying mechanism (`-D` args at fork time); each Tia integration picks the carrier (file vs system property) that fits its lifecycle and data shape.

---


---

Prev: [Library publish-time stamping](library-publish-time-stamping.md) | [Back to the Wiki index](../WIKI.md) | Next: [Logging conventions (TRACE vs DEBUG)](logging-conventions.md)
