# Supported Gradle versions

The Tia Gradle plugin (`org.tiatesting.tia`) supports:

| Gradle | Supported |
|---|---|
| before 6.1 | no - fails with the floor message |
| 6.1 - 6.5.x | yes |
| 6.6 - 7.6.3 | no - cannot load the plugin's H2 jar |
| 7.6.4 - 7.6.x | yes |
| 8.0 - 8.2.1 | no - cannot load the plugin's H2 jar |
| 8.3 - 9.x | yes |

Gradle 10 has not been tested yet; see [Gradle 10](#gradle-10). Every unsupported 6.6+ version has a
supported upgrade within or next to its major version: 7.6.4 for 6.6 - 7.6.3, 8.3 for 8.0 - 8.2.1.

### Why 6.1 is the floor

The plugin loads the VCS provider (`tia-vcs-git` or `tia-vcs-perforce`) in an isolated class loader,
and shares those class loaders between every project in the build through a shared build service,
`VcsProviderClassLoaders`. Gradle closes the service when the build finishes, which closes the class
loaders; a loader cached anywhere longer-lived would leak its jars for the life of the daemon. Shared
build services (`Gradle.getSharedServices()`, `BuildServiceSpec`) were added in Gradle 6.1, so 6.1 is
the lowest version the plugin can run on.

Nothing else in the plugin needs a newer Gradle. Where a newer API is optional, the plugin calls it
and tolerates its absence: `TiaPlugin.markNotConfigurationCacheCompatible` catches the
`NoSuchMethodError` thrown on Gradle before 7.4, which has no configuration cache to opt out of.

### How the floor is checked

`TiaPlugin.apply` first calls `checkMinimumGradleVersion(GradleVersion.current())`, which fails the
build with "Tia requires Gradle 6.1 or newer, but this build is running on Gradle X." It compares
base versions, so a release candidate or snapshot of 6.1 is accepted.

The check only works if Gradle can load the plugin class at all. Gradle decorates a plugin class
before calling `apply`, and that inspects every declared method's signature, including the synthetic
methods the compiler generates for lambdas. A lambda that takes a `BuildServiceSpec` inside
`TiaPlugin` was enough to fail Gradle 6.0.1 with "Could not generate a decorated class for class
TiaPlugin > org/gradle/api/services/BuildServiceSpec", before the version check could run. So:

- The build service registration lives in `VcsProviderClassLoaders.forBuild(Gradle)`, not in
  `TiaPlugin`.
- `TiaPluginGradleVersionTest.pluginMethodsNameNoBuildServiceType` fails if any `TiaPlugin` method
  signature names an `org.gradle.api.services` type again.

If the floor moves up for a new API, apply the same rule: keep the new type out of `TiaPlugin`'s
method signatures, or the older Gradle versions get an unreadable error instead of the floor message.

### Gradle versions that cannot load the H2 jar

On Gradle 6.6 - 7.6.3 and 8.0 - 8.2.1, applying the plugin fails before any Tia code runs:

```
Failed to create Jar file .../caches/jars-9/.../h2-2.2.224.jar.
  > Failed to process the entry 'META-INF/versions/21/org/h2/util/Utils21.class'
  > Unsupported class file major version 65
```

From 6.6, Gradle rewrites the classes of every jar on the build script classpath, to track what the
build reads (the configuration cache's inputs), and caches the result under `caches/jars-*`. It
rewrites them with the ASM library it bundles. H2 2.2.224, which `tia-core` depends on, is a
multi-release jar with one class compiled for Java 21 (`Utils21`, used only on a Java 21+ runtime).
A Java 8 JVM ignores that class, but Gradle's rewrite reads every entry whatever JVM runs the build,
and an ASM older than 9.5 cannot read a Java 21 (major 65) class file. Gradle before 6.6 does not
rewrite the jar, and 7.6.4 and 8.3 bundle an ASM that reads it.

The plugin cannot report this itself: Gradle fails while building the classpath, before it loads
`TiaPlugin`, so the floor check never runs. H2 2.1.214, the last release without the Java 21 class,
loads on every version, so H2 is the only jar involved. Moving H2 into an isolated class loader, as
the VCS providers are, or repackaging it without the Java 21 class, would remove the gaps; both were
judged not worth it while every affected version has a nearby supported upgrade.

### What has been tested

End to end, with selection after a committed change:

- **6.0.1** - fails with the floor message.
- **6.1, 6.5.1** - JUnit 5 on Java 8, with the launcher declared; without it, the launcher warning
  below and nothing recorded.
- **6.6 - 7.6.3, 8.0 - 8.2.1** (6.6, 6.8.3, 6.9.4, 7.0, 7.3.3, 7.4.2, 7.5.1, 7.6 - 7.6.3, 8.0, 8.2,
  8.2.1) - fail loading the H2 jar, as above.
- **7.6.4, 7.6.6** - JUnit 5 on Java 8 with the launcher declared; without it, the launcher warning
  and the test JVM failing with `NoClassDefFoundError`.
- **8.3** - JUnit 5 on Java 8, with and without the launcher declared.
- **8.4, 8.14** - the regular fixtures (JUnit 5, Spock, test-retry); Tia itself builds with 8.7. 8.14
  also without the launcher declared.
- **9.8.1** - Spock and JUnit 5 on a Java 8 toolchain, JUnit 6 on Java 21, and test-retry. Every Tia
  task, including a distributed plan, claim, complete and seal, runs clean under
  `--warning-mode fail`.

### Gradle 9 and the JUnit Platform launcher

Gradle 8 loads its own bundled `junit-platform-launcher` into the test JVM when the project declares
none (deprecated since 8.2). Gradle 9 removed that fallback, so a JUnit Platform test task fails with
"Failed to load JUnit Platform" unless the launcher is on the test runtime classpath. This is a Gradle
requirement for every JUnit project, not one Tia adds, and Tia deliberately does not add the launcher
itself; see [JUnit 6 support](junit6-support.md) for why. A Spock project may get the launcher
transitively (`groovy-all` brings it through `groovy-test-junit5`), but should not rely on it.

### Gradle's bundled launcher

Both Tia listeners, `TiaLauncherSessionListener` (JUnit 5 and 6) and `TiaSpockLauncherSessionListener`,
register as a JUnit Platform `LauncherSessionListener`, which the launcher added in 1.8. A project
that declares a launcher older than 1.8 gets tests that run and pass while Tia records no mapping and
no history, and nothing fails. A project that declares none runs on the launcher Gradle bundles, and
what that does to Tia depends on the Gradle version:

| Gradle | Bundled launcher | Tia without a declared launcher |
|---|---|---|
| 6.x | 1.3.1 (6.1) - 1.7.0 (6.9) | records nothing; the tests pass |
| 7.0 - 7.4 | 1.7.x | (cannot load the plugin) |
| 7.5 - 7.6.x | 1.8.2 | the test JVM fails with `NoClassDefFoundError: org/junit/platform/launcher/LauncherSessionListener` |
| 8.x | 1.8.2 | works |
| 9.x | none | Gradle fails the task: "Failed to load JUnit Platform" |

On 7.x Gradle loads the bundled launcher in a class loader that Tia's listener, on the test runtime
classpath, cannot see; Gradle 8 puts it where the listener can. A project that declares the launcher,
as JUnit recommends, is unaffected on any Gradle version.

So neither failure goes unexplained, the test task action calls
`TiaTestTaskConfigurer.warnIfLauncherUnusable`, which uses `JunitPlatformLauncherCheck` and logs a
warning naming the cause and the fix when the project declares a launcher older than 1.8, or
declares none on Gradle before 8. It reads only jar file names on the test task's classpath, which the
task resolves to run anyway, and runs at execution time, so configuration pays nothing for it. It
warns rather than fails: on Gradle 6 the tests themselves are fine, and a launcher whose version
cannot be parsed is assumed to be new enough.

### Gradle 10

Gradle 9 deprecates, and Gradle 10 turns into errors, a task reading its own `Task.project` or
`Task.extensions` while it executes. Tia's tasks no longer do either:

- The test task action works from the project and extensions `TiaTestTaskConfigurer.applyTo`
  captures while the task is configured, and passes the project to the framework adapters.
- `tia-dist-plan`, `tia-dist-complete` and `tia-select-tests` reach the project through the plugin
  (`TiaPlugin.resolveProjectDir`, `TiaPlugin.distributedClaimRegistry`), which holds it from `apply`.

The plugin also no longer calls `Configuration.setVisible`, which has no effect since Gradle 9 and is
removed in Gradle 11. With these gone, every Tia task runs on Gradle 9.8.1 under
`--warning-mode fail`. Gradle 10 itself has not been run yet. The tasks remain marked not compatible
with the configuration cache; making them compatible is separate work.
