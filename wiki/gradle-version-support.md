# Supported Gradle versions

The Tia Gradle plugin (`org.tiatesting.tia`) supports Gradle 6.1 and newer. Gradle 10 has not
been tested yet; see [Gradle 10](#gradle-10).

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

### What has been tested

End to end, with selection after a committed change:

- **6.0.1** - fails with the floor message.
- **6.1** - JUnit 5 on Java 8, with the launcher declared; without it, the launcher warning below.
- **8.4, 8.14** - the regular fixtures (JUnit 5, Spock, test-retry); Tia itself builds with 8.7.
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

### Launchers older than 1.8

Both Tia listeners, `TiaLauncherSessionListener` (JUnit 5 and 6) and `TiaSpockLauncherSessionListener`,
register as a JUnit Platform `LauncherSessionListener`, which the launcher added in 1.8. An older
launcher never calls them: the tests run and pass, but Tia records no mapping and no history, and
nothing fails. The launcher Gradle bundles depends on its version:

| Gradle | Bundled launcher |
|---|---|
| 6.0.1, 6.1 | 1.3.1 - too old for Tia |
| 8.4, 8.7, 8.14 | 1.8.2 |
| 9.x | none - the project must declare one |

Gradle 7.x has not been checked. A project that declares the launcher, as JUnit recommends, is
unaffected on any Gradle version.

So the failure is not silent, the test task action calls
`TiaTestTaskConfigurer.warnIfLauncherTooOld`, which uses `JunitPlatformLauncherCheck` to find the
launcher the task will run on and logs a warning naming its version and the fix when it is older
than 1.8. It reads only jar file names: a `junit-platform-launcher-<version>.jar` on the test task's
classpath (which the task resolves to run anyway) and, when the project declares none, the one in
the Gradle distribution's `lib/plugins` directory. It runs at execution time, so configuration pays
nothing for it. The check warns rather than fails: the tests themselves are fine, and a launcher
whose version cannot be parsed is assumed to be new enough.

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
