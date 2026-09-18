# Directory-based library-jar resolution

Tia's library-coverage feature collects JaCoCo coverage for in-repo libraries the source project
depends on, not just the project's own classes. To do that the forked test JVM needs the actual
library **jar files** on disk so JaCoCo can analyse their classes. Those jars are declared by
coordinate through `tiaSourceLibs` (`groupId:artifactId`, optionally with a third `:projectDir`
segment); see [Library publish-time stamping](library-publish-time-stamping.md) for how the same
coordinates drive library change tracking.

## The default path and where it breaks

By default the plugin turns each `tiaSourceLibs` coordinate into a jar path by loading the source
project's build config and walking its **resolved dependency graph**:

- Maven: `LibraryJarResolver` builds the source project's `pom.xml` with
  `setResolveDependencies(true)` and reads `MavenProject.getArtifacts()`.
- Gradle: the resolver reads the source project's `runtimeClasspath` (or the Tooling API model for
  an external build).

Either way the jar path comes from Maven/Gradle dependency resolution, which needs a populated local
repository. In an **offline** build (`mvn -o`) whose local repository does not contain the source
project's full transitive graph, resolution throws. Tia catches that, downgrades it to a warning, and
proceeds with **zero library coverage** - silently, because a missing-coverage build still passes.

## The offline-safe alternative

In many deployment layouts the exact library jars are already sitting on disk in a deployment
`lib/` directory, version-stamped as `<artifactId>-<version>.jar`. When that is true, Tia can consume
those jars directly and skip dependency resolution entirely.

Set `tiaLibraryJarsDirs` (Maven property `tiaLibraryJarsDirs`) to a CSV of directories that contain
the built library jars. When it is non-blank, Tia resolves each `tiaSourceLibs` coordinate by
**filename matching** inside those directories instead of through the pom. When it is blank, the
default pom/classpath path is used unchanged - the two are mutually exclusive per build.

`tiaSourceLibs` is still required in this mode: it is the allow-list of *which* jars to include. Only
the `artifactId` of each coordinate is used for matching; the `groupId` is used only in log messages
and the optional `:projectDir` segment is ignored.

### The matching rule

For each coordinate's `artifactId`, every configured directory is scanned for a file matching the
case-sensitive regex:

```
^<escaped-artifactId>-\d[^/]*\.jar$
```

- The leading `-\d` requires a **version digit** immediately after `<artifactId>-`, so the coordinate
  `foo` does not match `foo-bar-1.0.jar` (its character after `foo-` is `b`, not a digit).
- Files ending in `-sources.jar` or `-javadoc.jar` are excluded, so a jar and its sibling classifier
  artifacts do not read as an ambiguous double match.

Resolution is per coordinate, and each outcome is logged the same way the pom-based resolver logs:

- **One match:** its absolute path is added (a debug line is emitted per resolved jar).
- **No match:** a warning is logged and the coordinate is skipped.
- **Multiple matches** (e.g. two versions in one directory, or the same artifactId in two configured
  directories): a warning listing the candidates is logged and the coordinate is skipped. Guessing a
  version is more dangerous than collecting no coverage for that library, so the ambiguous case skips.
- **A directory that is missing or is not a directory:** a warning is logged once and that directory
  is skipped; the other directories are still searched.

The result is a de-duplicated, order-preserving list of absolute jar paths.

## Where it plugs in

The jar-list to coverage handoff is unchanged from the default path; only the **producer** of the
jar list differs. The shared matcher lives in `tia-core`
(`org.tiatesting.core.library.LibraryJarDirectoryResolver`) so both build plugins reuse it, each
passing its own warn/debug logging sink. From there:

- Maven writes the jars to `${tiaBuildDir}/library-jars.txt`; the javaagent reads that file at
  `premain` and republishes it as the `tiaLibraryJars` system property.
- Gradle/Spock sets the `tiaLibraryJars` system property on the test task directly.

`JacocoClient.loadLibraryJars()` reads `tiaLibraryJars`, and adds each existing jar file to the
JaCoCo analysis set. See
[How Tia exchanges data with the test runner (Gradle vs Maven)](test-runner-data-exchange.md) for why
the two plugins transport the value differently.

## Scope and a parallel limitation

This mode covers **coverage jars only** - the jars JaCoCo analyses. The library-impact analysis path
that reads each library's declared *version* (`LibraryMetadataReader`) still resolves through the pom
and has the same offline limitation; making that path directory-aware is a possible follow-up, not
part of this feature.
