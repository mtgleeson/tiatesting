# Logging conventions (TRACE vs DEBUG)

Tia uses SLF4J throughout. Whether `log.trace(...)` is actually emitted depends entirely on which JVM the code is running in — the same line in `tia-core` can produce output in one runtime and silently disappear in another. The rule below is what keeps logging usable across both.

### Rule

- **Code reachable from the Gradle daemon must use `DEBUG` or higher.** That includes everything in `tia-gradle` and `tia-spock-git-gradle`, *plus* anything in `tia-core` that is called from a daemon-side path (TestSelector, the drainer/recorder, `H2DataStore`, diff and method-impact analysis, JaCoCo coverage parsing, etc.).
- **`TRACE` is only safe in code that exclusively runs inside the forked test JVM.** In practice today, that's a small subset of `tia-spock`. There are no TRACE call sites in `tia-core` — they were all converted to DEBUG because `tia-core` is shared between the two runtimes.
- **Maven runs in the Maven build process**, which uses Plexus logging via SLF4J. It does honour TRACE, but `tia-core` follows the daemon rule for consistency — so don't add TRACE to shared code on Maven's behalf either.

### Why daemon-side code can't use TRACE

Gradle's logging system has only six levels: `ERROR`, `QUIET`, `WARN`, `LIFECYCLE`, `INFO`, `DEBUG`. There is no `TRACE`. Anything an SLF4J `trace(...)` call emits while the Gradle daemon's logger is in effect is dropped — not down-mapped to DEBUG, not buffered, not flagged. `--debug` is the most verbose flag the CLI exposes, and that maps to Gradle's DEBUG level only.

The daemon ignores user-supplied `logback.xml` files, too. Gradle bundles its own SLF4J binding into the daemon's bootstrap classloader, and that binding wins over anything on the build classpath. Putting `<logger name="org.tiatesting" level="trace"/>` in a project's `src/main/resources` configures the **test JVM's** logback (because that's the classpath logback ends up on at test time) but has no effect on the daemon. Even if logback *were* loaded, Gradle's level enum would still cap output at DEBUG.

So a `log.trace(...)` line in `H2DataStore` fires perfectly during `:test` (where the test JVM loads logback and respects the user's level config) but disappears during `tia-select-tests` (which runs entirely in the daemon). That asymmetry is hostile to whoever is debugging — they see different logs depending on which task they ran, with no obvious explanation. Standardising on DEBUG removes the surprise.

### Why test-JVM code can use TRACE

The test JVM is a fresh process with the test runtime classpath. SLF4J binds to whatever logging implementation is on that classpath (typically Logback in projects that ship one), and Logback honours every level including TRACE. A `<logger name="org.tiatesting" level="trace"/>` entry in the user's `logback.xml` works exactly as intended there: TRACE statements emit, and DEBUG/INFO can be filtered down independently.

This means *test-JVM-only* code can legitimately use TRACE for very fine-grained tracing that would be too noisy at DEBUG. The bar is just that the code must be unreachable from the Gradle daemon — i.e. not in `tia-core`, not in `tia-gradle`, not in any shared module that the plugin pulls in at task-action time.

### Practical guidance for contributors

- Default to `log.debug(...)` for fine-grained logging in any shared module. If the line would have been TRACE in a single-runtime codebase, write it as DEBUG here.
- Reserve `log.trace(...)` for tia-spock-only code paths (the Spock global extension internals, the Tia agent inside the test JVM). If you find yourself wanting TRACE in `tia-core`, that's a sign the code is shared and DEBUG is the right level.
- For users diagnosing a problem in `tia-select-tests` or any other plugin task, the workflow is `gradle <task> --debug 2>&1 | grep -E "org\.tiatesting"`. There is no equivalent of Logback level filtering for the daemon — Gradle's DEBUG level is the most fine-grained signal available.

This rule isn't about elegance; it's about making logs predictable across the two runtimes that a single `tia-core` class might be called from.

---


---

Prev: [How Tia exchanges data with the test runner (Gradle vs Maven)](test-runner-data-exchange.md) | [Back to the Wiki index](../WIKI.md) | Next: [Why Tia requires Maven 3.8.1+](maven-version-requirement.md)
