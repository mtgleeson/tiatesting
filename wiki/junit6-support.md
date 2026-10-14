# JUnit 6 support

### One Tia binary serves JUnit 5 and JUnit 6

JUnit 6 is still Jupiter on the JUnit Platform. The group ids (`org.junit.jupiter`, `org.junit.platform`), the `LauncherSessionListener` service hook, and `org.junit.jupiter.api.Disabled` are all unchanged. So Tia supports both majors with the same modules, `tia-junit5` (the listener) and `tia-junit5-agent` (the `@Disabled` agent), and with the same Gradle `testFramework = 'junit5'` adapter. `testFramework = 'junit6'` is an alias for it (`TestFrameworkAdapter.aliases()`), so JUnit 6 users need not wonder whether `junit5` covers them. Detection on Gradle needs no change because it matches the `org.junit.jupiter` group.

Both modules stay Java 8 bytecode and compile against the JUnit 5 Platform API. They only call APIs that JUnit 6 kept unchanged: `LauncherSessionListener`, `LauncherSession.getLauncher()`, `Launcher.registerTestExecutionListeners`, `TestPlan.getParent(TestIdentifier)`, `TestPlan.getDescendants`, `TestIdentifier.getUniqueId()` / `getSource()` / `isContainer()`, plus `ClassSource`, `MethodSource` and `TestExecutionResult`. JUnit comes from the user's project at run time, so:

- a JUnit 5 project on Java 8 or 11 gets the same Java 8 jars as before and never sees a JUnit 6 class;
- a JUnit 6 project is on Java 17+ anyway (JUnit 6's own baseline), and runs the Java 8 bytecode there.

Tia's own minimum Java version therefore does not move for JUnit 6. Using a JUnit 6-only API would change that. It would need a separate Java 17 module, reflection, or a raised floor for everyone.

### Tia never chooses the JUnit Platform launcher version

The launcher must match the project's JUnit Platform engine. `tia-junit5` and `tia-spock` compile against `junit-platform-launcher` but do not publish it as a dependency, and `verifyPublishedPom` (part of `check`) fails the build if any `org.junit*` dependency reaches either published POM.

This is what broke JUnit 6 on Maven before it was fixed. When the launcher was a normal dependency at 1.11.3, Maven's nearest-wins resolution put `junit-platform-engine:1.11.3` next to `junit-jupiter-engine:6.x` in a project that did not import the JUnit BOM. Discovery then failed with `TestEngine with ID 'junit-jupiter' failed to discover tests`, caused by `NoClassDefFoundError: org/junit/platform/engine/support/discovery/DiscoveryIssueReporter`. Gradle hid the problem, because its conflict resolution upgraded the pinned launcher to 6.x.

Who supplies the launcher instead:

- **Maven:** Surefire/Failsafe add the launcher matching the engine version on the test classpath when the project declares none. JUnit 6 needs Surefire/Failsafe 3.0.0 or newer.
- **Gradle:** the project declares `testRuntimeOnly 'org.junit.platform:junit-platform-launcher'`, with the version aligned by the JUnit BOM. JUnit recommends this and Gradle 9 requires it. The Tia plugin deliberately does not add a versionless launcher. That only resolves when the BOM reaches the graph through Gradle module metadata, and it would fail in repositories that strip `.module` files. Gradle 8 falls back to its own bundled launcher when none is declared, which is old and does not match a JUnit 6 engine.

### How the build keeps JUnit 6 working

`./gradlew check` (and `build`) runs two JUnit 6 suites on a Java 17+ JVM. `./gradlew test` does not run them.

- `:tia-junit5:junit6Test` is a separate `junit6Test` source set, compiled for Java 17 against JUnit 6. It runs fixture suites (passing with `@Nested`, failing, `@Disabled`) through the real JUnit 6 launcher, then checks that the Java 8 listener tracked, failed, observed and persisted them. The ordinary listener tests cannot be re-run on JUnit 6. They build plans with `TestPlan.from(Collection, ConfigurationParameters)`, which JUnit 6 removed, and its replacement takes an `OutputDirectoryCreator` that JUnit 5 does not have.
- `:tia-junit5-agent:junit6Test` re-runs the agent's existing tests, compiled for Java 8, with every JUnit artifact forced to 6.x. Those tests only use `@Test`, `@Disabled` and `Assertions`. Its classpath is a dedicated resolvable configuration with Java 17 runtime attributes, because JUnit 6 publishes Java 17 variants only and the Java 8 test runtime classpath rejects them.

The JUnit 6 version is `junit6Version` in `gradle.properties`.

### Contributor setup: a local JDK 17+

The JUnit 6 suites need a locally installed JDK of `junit6TestJavaVersion` (`gradle.properties`, default `17`). Gradle and the rest of the build keep running on JDK 8. Gradle finds JDKs under `/Library/Java/JavaVirtualMachines`, SDKMAN and the other standard locations by itself. A JDK it cannot find (Homebrew's `openjdk@21`, for one) is named with `org.gradle.java.installations.paths`, and a JDK other than 17 is selected with `junit6TestJavaVersion`:

```
./gradlew check \
    -Pjunit6TestJavaVersion=21 \
    -Porg.gradle.java.installations.paths="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
```

Both properties can go in `~/.gradle/gradle.properties` instead. Without a matching JDK, `check` fails at `compileJunit6TestJava` with Gradle's "Cannot find a Java installation on your machine matching this tasks requirements: {languageVersion=17 ...}".

The build does not download the toolchain automatically. The foojay toolchain resolver's API rejects the `Java/1.8.x` HTTP user agent with HTTP 403 (Cloudflare error 1010), and that is the user agent Gradle sends when it runs on JDK 8. No resolver version Gradle 8.7 can load gets past it. Running Gradle itself on 17+ would avoid that, but the existing tests would then lose their Java 8 runtime.
