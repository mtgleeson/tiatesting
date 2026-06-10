# Tia Java Selective Testing Library
Tia is a free library used for selective testing with common test runners such as JUnit and Spock. Tia is distributed under the Apache 2.0 license.
Tia (pronounced Tee-ä, or Tina without the 'n') stands for test impact analysis. 

## Starting Points
- [Getting started](#getting-started)
	- [Maven, Junit5, Git](#maven-junit5-and-git)
  	- [Maven, Junit5, Perforce](#maven-junit5-and-perforce)
  	- [Maven, Junit4, Git](#maven-junit4-and-git)
  	- [Maven, Junit4, Perforce](#maven-junit4-and-perforce)
  	- [Gradle, Spock, Git](#gradle-spock-and-git)
- [Usage](#usage)
- [Configuration Options](#configuration-options)
- [What is Tia](#what-is-tia)
- [How Does Tia Work](#how-does-tia-work)
- [Using a shared H2 server](#using-a-shared-h2-server)
- [Supported Build Automation Tools, VCS and Test Runners](#supported-build-automation-tools-vcs-and-test-runners)
- [Credits](#credits)
- [Additional resources and solutions](#additional-resources-and-solutions)
- [Bug Report](https://github.com/mtgleeson/tiatesting/issues)
- [Feature Request](https://github.com/mtgleeson/tiatesting/issues)

## Getting Started

### Requirements

- **Maven**: 3.8.1 or newer is required for any of the Maven-based Tia plugins (`tia-junit4-git-maven-plugin`, `tia-junit4-perforce-maven-plugin`, `tia-junit5-git-maven-plugin`, `tia-junit5-perforce-maven-plugin`). The floor is enforced automatically via `<prerequisites>` in each plugin's POM — invoking a Tia plugin under an older Maven will fail with a clear "requires Maven 3.8.1" error. See the [Wiki](WIKI.md) for the security rationale (CVE-2021-26291) and the design decision behind picking 3.8.1 specifically.
- **Java**: 8 or newer.
- **Gradle**: no version floor is enforced beyond what the Spock plugin's runtime requires.

### Maven, JUnit5 and Git
Tia hooks into JUnit Platform via a `LauncherSessionListener` for updating test coverage mappings and stats. The listener is auto-registered from the `tia-junit5-git` jar's own `META-INF/services/org.junit.platform.launcher.LauncherSessionListener` descriptor, so no manual file is required - just declare the dependency. The listener only activates when `tiaEnabled=true` is set as a system property, so it is a no-op for IDE runs and any build that doesn't enable Tia.

Configure your test project POM for Tia by including the following configuration in the project where you execute your tests. The following configuration is for Surefire, but Tia can be configured with Failsafe as well.
For the latest versions, see [tia-junit5-git-maven-plugin](https://central.sonatype.com/search?q=g%3Aorg.tiatesting+a%3Atia-junit5-git-maven-plugin&smo=true) and [tia-junit5-git](https://central.sonatype.com/search?q=g%3Aorg.tiatesting+a%3Atia-junit5-git&smo=true).

**Note:** If your tests live in the same project as your source code, you need to include and configure Jacoco to run in TCP server mode (see below). If your source code lives in a different project to your tests, you need to ensure your project that contains your source code is configured to run with Jacoco in TCP server mode. You can then omit the Jacoco configuration below from your test project pom.xml.

`pom.xml`
```xml
<properties>
    <tiaEnabled>true</tiaEnabled>
    <tiaUpdateDBMapping>true</tiaUpdateDBMapping>
    <tiaUpdateDBStats>true</tiaUpdateDBStats>
    <tiaUpdateDBTestRunHistory>true</tiaUpdateDBTestRunHistory>
    <tiaCheckLocalChanges>false</tiaCheckLocalChanges>
    <tiaProjectDir>.</tiaProjectDir>
    <tiaClassFilesDirs>/target/classes</tiaClassFilesDirs>
    <tiaSourceFilesDirs>/src/main/java</tiaSourceFilesDirs>
    <tiaTestFilesDirs>/src/test/java</tiaTestFilesDirs>
    <tiaDBFilePath>/some/path</tiaDBFilePath>    
</properties>

<dependencies>
    <!-- tia-junit5-git is needed for the Tia test listener used by Surefire/Failsafe. -->
    <dependency>
        <groupId>org.tiatesting</groupId>
        <artifactId>tia-junit5-git</artifactId>
        <version>0.1.18-SNAPSHOT</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>        
        <plugin>
            <!-- Include the Maven plugin, used to select which tests to run and ignore the rest. -->
            <groupId>org.tiatesting</groupId>
            <artifactId>tia-junit5-git-maven-plugin</artifactId>
            <version>0.1.18-SNAPSHOT</version>
            <executions>
                <execution>
                    <id>pre-test</id>
                    <goals>
                        <goal>prepare-agent</goal>
                    </goals>
                    <phase>test-compile</phase>
                </execution>
            </executions>
            <configuration>
                <tiaProjectDir>${tiaProjectDir}</tiaProjectDir>
                <tiaDBFilePath>${tiaDBFilePath}</tiaDBFilePath>
                <tiaSourceFilesDirs>${tiaSourceFilesDirs}</tiaSourceFilesDirs>
                <tiaTestFilesDirs>${tiaTestFilesDirs}</tiaTestFilesDirs>                
                <tiaCheckLocalChanges>${tiaCheckLocalChanges}</tiaCheckLocalChanges>
                <tiaEnabled>${tiaEnabled}</tiaEnabled>
            </configuration>
        </plugin>
        <plugin>
            <!-- Configure Surefire to use Tia. Used to update the Tia test to source code mapping and/or stats when running the tests. -->
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>3.5.2</version>
            <configuration>
                <includes>
                    <include>**/*Test.java</include>
                </includes>
                <systemPropertyVariables>
                    <tiaProjectDir>${tiaProjectDir}</tiaProjectDir>
                    <tiaClassFilesDirs>${tiaClassFilesDirs}</tiaClassFilesDirs>
                    <tiaDBFilePath>${tiaDBFilePath}</tiaDBFilePath>
                    <tiaEnabled>${tiaEnabled}</tiaEnabled>
                    <tiaUpdateDBMapping>${tiaUpdateDBMapping}</tiaUpdateDBMapping>
                    <tiaUpdateDBStats>${tiaUpdateDBStats}</tiaUpdateDBStats>
                    <tiaUpdateDBTestRunHistory>${tiaUpdateDBTestRunHistory}</tiaUpdateDBTestRunHistory>
                    <testClassesDir>${project.build.testOutputDirectory}</testClassesDir>
                </systemPropertyVariables>
            </configuration>
        </plugin>
        <plugin>
            <!-- Configure Jacoco as a TCP server, needed by Tia (which has a Jacoco client) for collecting the coverage data for each test suite. -->
            <groupId>org.jacoco</groupId>
            <artifactId>jacoco-maven-plugin</artifactId>            
            <version>0.8.7</version>
            <executions>
                <execution>
                    <id>pre-test</id>
                    <goals>
                        <goal>prepare-agent</goal>
                    </goals>
                    <phase>test-compile</phase>
                    <configuration>
                        <output>tcpserver</output>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### Maven, JUnit5 and Perforce
Use the configuration documented above for [Maven, Junit5 and Git](https://github.com/mtgleeson/tiatesting/edit/main/README.md#getting-started), but replace `tia-junit5-git` with `tia-junit5-perforce` and `tia-junit5-git-maven-plugin` with `tia-junit5-perforce-maven-plugin`.

For the latest versions, see [tia-junit5-perforce-maven-plugin](https://central.sonatype.com/search?q=g%3Aorg.tiatesting+a%3Atia-junit5-perforce-maven-plugin&smo=true) and [tia-junit5-perforce](https://central.sonatype.com/search?q=g%3Aorg.tiatesting+a%3Atia-junit5-perforce&smo=true).

### Maven, JUnit4 and Git
Include the following configuration in the project where you execute your tests. The following configuration is for Surefire, but Tia can be configured with Failsafe as well.
For the latest versions, see [tia-junit4-git-maven-plugin](https://central.sonatype.com/search?q=g%3Aorg.tiatesting+a%3Atia-junit4-git-maven-plugin&smo=true) and [tia-junit4-git](https://central.sonatype.com/search?q=g%3Aorg.tiatesting+a%3Atia-junit4-git&smo=true).

**Note:** If your tests live in the same project as your source code, you need to include and configure Jacoco to run in TCP server mode (see below). If your source code lives in a different project to your tests, you need to ensure your project that contains your source code is configured to run with Jacoco in TCP server mode. You can then omit the Jacoco configuration below from your test project pom.xml.

`pom.xml`
```xml
<properties>
    <tiaEnabled>true</tiaEnabled>
    <tiaUpdateDBMapping>true</tiaUpdateDBMapping>
    <tiaUpdateDBStats>true</tiaUpdateDBStats>
    <tiaUpdateDBTestRunHistory>true</tiaUpdateDBTestRunHistory>
    <tiaCheckLocalChanges>false</tiaCheckLocalChanges>
    <tiaProjectDir>.</tiaProjectDir>
    <tiaClassFilesDirs>/target/classes</tiaClassFilesDirs>
    <tiaSourceFilesDirs>/src/main/java</tiaSourceFilesDirs>
    <tiaTestFilesDirs>/src/test/java</tiaTestFilesDirs>
    <tiaDBFilePath>/some/path</tiaDBFilePath>    
</properties>

<dependencies>
    <!-- tia-junit4-git is needed for the Tia test listener used by Surefire/Failsafe. -->
    <dependency>
        <groupId>org.tiatesting</groupId>
        <artifactId>tia-junit4-git</artifactId>
        <version>0.1.18-SNAPSHOT</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>        
        <plugin>
            <!-- Include the Maven plugin, used to select which tests to run and ignore the rest. -->
            <groupId>org.tiatesting</groupId>
            <artifactId>tia-junit4-git-maven-plugin</artifactId>
            <version>0.1.18-SNAPSHOT</version>
            <executions>
                <execution>
                    <id>pre-test</id>
                    <goals>
                        <goal>prepare-agent</goal>
                    </goals>
                    <phase>test-compile</phase>
                </execution>
            </executions>
            <configuration>
                <tiaProjectDir>${tiaProjectDir}</tiaProjectDir>
                <tiaDBFilePath>${tiaDBFilePath}</tiaDBFilePath>
                <tiaSourceFilesDirs>${tiaSourceFilesDirs}</tiaSourceFilesDirs>
                <tiaTestFilesDirs>${tiaTestFilesDirs}</tiaTestFilesDirs>                
                <tiaCheckLocalChanges>${tiaCheckLocalChanges}</tiaCheckLocalChanges>
                <tiaEnabled>${tiaEnabled}</tiaEnabled>
            </configuration>
        </plugin>
        <plugin>
            <!-- Configure Surefire to use Tia. Used to update the Tia test to source code mapping and/or stats when running the tests. -->
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>2.19</version>
            <configuration>
                <includes>
                    <include>**/*Test.java</include>
                </includes>
                <systemPropertyVariables>
                    <tiaProjectDir>${tiaProjectDir}</tiaProjectDir>
                    <tiaClassFilesDirs>${tiaClassFilesDirs}</tiaClassFilesDirs>
                    <tiaDBFilePath>${tiaDBFilePath}</tiaDBFilePath>
                    <tiaEnabled>${tiaEnabled}</tiaEnabled>
                    <tiaUpdateDBMapping>${tiaUpdateDBMapping}</tiaUpdateDBMapping>
                    <tiaUpdateDBStats>${tiaUpdateDBStats}</tiaUpdateDBStats>
                    <tiaUpdateDBTestRunHistory>${tiaUpdateDBTestRunHistory}</tiaUpdateDBTestRunHistory>
                    <testClassesDir>${project.build.testOutputDirectory}</testClassesDir>
                </systemPropertyVariables>
                <properties>
                    <property>
                        <name>listener</name>
                        <value>org.tiatesting.junit.junit4.TiaJunit4GitListener</value>
                    </property>
                </properties>
            </configuration>
        </plugin>
        <plugin>
            <!-- Configure Jacoco as a TCP server, needed by Tia (which has a Jacoco client) for collecting the coverage data for each test suite. -->
            <groupId>org.jacoco</groupId>
            <artifactId>jacoco-maven-plugin</artifactId>            
            <version>0.8.7</version>
            <executions>
                <execution>
                    <id>pre-test</id>
                    <goals>
                        <goal>prepare-agent</goal>
                    </goals>
                    <phase>test-compile</phase>
                    <configuration>
                        <output>tcpserver</output>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### Maven, JUnit4 and Perforce
Use the configuration documented above for [Maven, Junit4 and Git](https://github.com/mtgleeson/tiatesting/edit/main/README.md#getting-started), but replace `tia-junit4-git` with `tia-junit4-perforce` and `tia-junit4-git-maven-plugin` with `tia-junit4-perforce-maven-plugin`.

For the latest versions, see [tia-junit4-perforce-maven-plugin](https://central.sonatype.com/search?q=g%3Aorg.tiatesting+a%3Atia-junit4-perforce-maven-plugin&smo=true) and [tia-junit4-perforce](https://central.sonatype.com/search?q=g%3Aorg.tiatesting+a%3Atia-junit4-perforce&smo=true).

### Gradle, Spock and Git
Include the following configuration in your project where you execute your tests. 
For the latest version, see [tia-spock-git-gradle](https://central.sonatype.com/search?q=g%3Aorg.tiatesting+a%3Atia-spock-git-gradle&smo=true).

`settings.gradle`
```
buildscript {
    repositories {      
        mavenCentral()
    }
    dependencies {
        classpath 'org.tiatesting:tia-spock-git-gradle:0.1.18-SNAPSHOT'
    }
}
```

`build.gradle`
```
plugins {
    id 'org.tiatesting.spock.gradle.git'
}

repositories {
    mavenCentral()
}

// global Tia config applied to all tasks of type test
tia {
    enabled = true    
    updateDBMapping = true
    updateDBStats = true
    checkLocalChanges = true
    projectDir = "."
    classFilesDirs ="/build/classes/java/main"
    sourceFilesDirs ="/src/main/java"
    testFilesDirs ="/src/test/groovy"
    dbFilePath = "/some/path"        
}

// you can optionally override the Tia config per test task type
test {
    tia {
        enabled = true
        checkLocalChanges = true
        updateDBMapping = true
        updateDBStats = true
    }
}
```

### Tracking coverage for libraries
If your source project depends on in-repo libraries (also published as artifacts in the same repository) and you want Tia to track and react to changes in those libraries too, use the `sourceLibs` configuration. Tia resolves the `groupId:artifactId` coordinates against the source project's resolved dependencies, locates the matching JAR file for the version actually in use, and adds it to Jacoco's analysis so library classes are included in the test-to-source mapping.

Add the library source directories to `sourceFilesDirs` as well, so VCS diff and method-impact analysis picks up library changes. If the project running the tests is different from the source project, point `sourceProjectDir` at the source project's root.

**Build-system restriction**: when the test project and source project are separate, they must use the same build system. A Maven test project can only resolve `sourceLibs` against a Maven source project, and a Gradle test project can only resolve against a Gradle source project. Cross-build-system setups are not currently supported — please open an issue if you need them.

#### Maven
The Maven plugin reads the source project's resolved dependencies by loading its `pom.xml` via Maven. `tiaSourceProjectDir` must therefore point at a directory containing a `pom.xml`.

`pom.xml`
```xml
<properties>
    <!-- ...existing Tia properties... -->
    <tiaSourceLibs>com.example:my-lib:/path/to/my-lib,com.example:other-lib:/path/to/other-lib</tiaSourceLibs>
    <!-- optional: only needed when the test project differs from the source project -->
    <tiaSourceProjectDir>/absolute/path/to/source-project</tiaSourceProjectDir>
    <!-- optional: BUMP_AFTER_RELEASE (default) or BUMP_AT_RELEASE — see policy description below -->
    <tiaLibraryVersionPolicy>BUMP_AFTER_RELEASE</tiaLibraryVersionPolicy>
</properties>

<build>
    <plugins>
        <plugin>
            <groupId>org.tiatesting</groupId>
            <artifactId>tia-junit5-git-maven-plugin</artifactId>
            <version>0.1.18-SNAPSHOT</version>
            <configuration>
                <!-- ...existing Tia plugin configuration... -->
                <tiaSourceLibs>${tiaSourceLibs}</tiaSourceLibs>
                <tiaSourceProjectDir>${tiaSourceProjectDir}</tiaSourceProjectDir>
                <tiaLibraryVersionPolicy>${tiaLibraryVersionPolicy}</tiaLibraryVersionPolicy>
            </configuration>
        </plugin>
    </plugins>
</build>
```

#### Gradle
The Gradle plugin supports three source-project configurations:

1. **Same project** — `sourceProjectDir` is omitted (or points at the current project). The plugin resolves coordinates against the current project's `runtimeClasspath`.
2. **Sibling subproject** — `sourceProjectDir` points at another subproject in the same Gradle build. The plugin resolves against that subproject's `runtimeClasspath`.
3. **External Gradle build** — `sourceProjectDir` points at a separate Gradle project on disk (but in the same VCS repository). The plugin uses the Gradle Tooling API to load that project and read its resolved classpath, which spins up a short Gradle daemon against the source project on first use (so the source project must be buildable). When tracking an in-repo library (`sourceLibs` entry pointing at an external Gradle build), the library project must apply a publishing plugin (`maven-publish` or `ivy-publish`) and declare a publication — Tia reads its declared version from the publication, since a project's own classpath cannot list itself.

`build.gradle`
```gradle
tia {
    // ...existing Tia configuration...
    sourceLibs = 'com.example:my-lib,com.example:other-lib'
    // optional: only needed when the source project differs from the test project
    sourceProjectDir = '/absolute/path/to/source-project'
    // optional: BUMP_AFTER_RELEASE (default) or BUMP_AT_RELEASE — see policy description below
    libraryVersionPolicy = 'BUMP_AFTER_RELEASE'
}
```

The Gradle plugin pre-resolves library metadata (declared version, source directories, resolved version, JAR path) at task-action time and forwards it to the forked test JVM via system properties — TIA's library partitioning, reconcile, stamp, and drain phases all run inside the test JVM as part of Spock's selection lifecycle. No state is exchanged via files; the wire format is internal and not part of the public configuration surface.

**Single-fork requirement when `updateDBMapping=true`.** The Gradle/Spock path must run with `maxParallelForks=1` and `forkEvery=0` (Gradle's defaults) when persisting mapping data. Each forked JVM persists independently using only the test suites it observed, so multi-fork runs corrupt the on-disk mapping by deleting the suites other forks owned. This affects test-suite-mapping persistence in general (not specific to library tracking) — leaving Gradle's defaults in place avoids it.

#### How library change tracking works (stamp/drain)

When a tracked library's source code changes in VCS, Tia does **not** run the impacted tests immediately. Instead, it uses a two-phase stamp/drain approach:

**Stamp phase (at commit analysis time):** When Tia detects source changes in a tracked library's directory, it identifies the impacted source methods and records them as *pending* in the Tia database, tagged with the library's current declared version (and a JAR content hash for SNAPSHOT versions). No tests are added to the run set at this point — the source project may still be consuming an older version of the library, so running the tests now would exercise stale code and produce a false green result.

**Drain phase (at test selection time):** On each test run, Tia checks whether the source project has caught up to any pending library versions:
- **Release versions:** A pending batch drains when the source project's resolved library version is >= the stamped version AND differs from the last version Tia saw on the source project. The "differs" check prevents re-draining when the library's source changes are committed but the version number hasn't been bumped yet.
- **SNAPSHOT versions:** A pending batch drains when the SHA-256 hash of the resolved JAR file differs from the last hash Tia tracked. This detects new SNAPSHOT builds even though the version string stays the same.

When a batch drains, Tia resolves which test suites exercise the pending method IDs using the **current** test-to-source mapping (not the mapping from stamp time), then adds those tests to the run set. After the test run completes successfully, the drained pending rows are deleted and the library's last-seen version/hash is updated.

**Local changes mode (`checkLocalChanges=true`):** Library diff partitioning is bypassed entirely — all changes (including library changes) are treated as source-project changes and impacted tests run immediately. No pending rows are read or written. This is the expected behavior for local development where you want instant feedback.

**Library removal:** If a library is removed from the `sourceLibs` configuration, Tia deletes the tracked library row and all its pending batches are automatically cascade-deleted.

**Primary build requirement:** The Tia run that persists mapping-DB updates (typically the primary CI run for your branch) must operate on a clean working tree. Tia resolves each tracked library's version from the source project's current on-disk build file (e.g. `pom.xml`, `build.gradle`). An uncommitted version bump is indistinguishable from a committed one at drain time, so running the persisting build with local build-file changes can cause a pending batch to drain against a version not yet in VCS — leading to incorrect test selection on subsequent runs. For local development, use `checkLocalChanges=true` (see above); the drain is bypassed in that mode.

**Release cadence — run Tia on every release:** The stamp/drain model assumes Tia observes every released library version. Multiple commits within the same release cycle are safely aggregated into a single pending batch, but a diff range that spans two releases cannot be correctly classified — the stamper reads only the library's current build-file state, not the commit history, so intermediate releases are invisible to it. In practice this means Tia must run at least once against every released state of each tracked library (typically every primary CI run is sufficient).

**Semantic versioning required for libraries:** Tia's version comparison is numeric-per-segment with lexicographic fallback. Version strings like `1.2.3` or `1.2.3-SNAPSHOT` compare correctly; pre-release qualifiers such as `1.2.0-rc1`, `1.2.0-M1`, or `1.2.0-alpha` will mis-order relative to the base version. Tracked libraries should use plain semantic versioning.

**Library version policy (`libraryVersionPolicy`):** Tia supports two release conventions used in the library's build file, configured globally for all tracked libraries. The default is `BUMP_AFTER_RELEASE`:

- `BUMP_AFTER_RELEASE` *(default)* — the build-file version is the *next* version to be released. Example (Maven release plugin convention): dev on `1.6.0-SNAPSHOT` → release cuts `1.6.0` → bump to `1.7.0-SNAPSHOT`. The declared version always points forward.
- `BUMP_AT_RELEASE` — the build-file version stays at the *last released* version during dev; the bump happens atomically with each release. Example: released `1.0` → dev commits continue declaring `1.0` → release cuts `1.1` (bump + tag in the same commit) → dev continues on `1.1`. The declared version always points backward.

Under `BUMP_AT_RELEASE`, changes stamped while the build-file version equals the last observed released version are tagged as destined for the *next, unknown release* and held by the drainer until the library's build-file version advances. Under `BUMP_AFTER_RELEASE` this holding behaviour is not needed and the logic stays as described in the stamp/drain rules above.

See [`WIKI.md`](WIKI.md) for a full explanation of the model, including worked examples under both policies.

## Usage

### Running Tia
Nothing special is needed to execute Tia. Configure Tia for your build automation tool (Maven, Gradle) and then run your tests as you normally would. Tia will select the tests to run and then hook into the test runner to capture details of the results. 
i.e. mvn test/verify, gradle test. 

For Maven, it's recommended to add the following to your ~/.settings.xml file to allow you to run truncated commands without needing to specify the org.tiatesting plugin group:
```
<pluginGroups>
    <pluginGroup>org.tiatesting</pluginGroup>
</pluginGroups>
```

### Status — current state of the Tia DB
Example output:
```
Tia Status:
DB last updated: 03/05/2024 23:05:04 PDT
Test mapping valid for commit: 70d0624e4c2c6fab629d618f0ac406d5cbf009e3

Number of tests classes with mappings: 4
Number of source methods tracked for tests: 35
Number of runs: 1
Average run time: 459ms
Number of successful runs: 1 (100%)
Number of failed runs: 0 (0%)

Tracked libraries:
	com.example:libA
		Project dir: /abs/path/to/libA
		Last source-project version: 1.0.0
		Last released version (HWM): 1.0.0

Pending failed tests:
	none

Pending library changes:
	com.example:libA
		@ 1.1.0 — 3 methods pending
```

**Maven, Junit5 and Git**
```
mvn tia-junit5-git:status
```

**Maven, Junit5 and Perforce**
```
mvn tia-junit5-perforce:status
```

**Maven, Junit4 and Git**
```
mvn tia-junit4-git:status
```

**Maven, Junit4 and Perforce**
```
mvn tia-junit4-perforce:status
```

**Gradle, Spock and Git**
```
gradle tia-status
```


### Display the selected tests based on the current state of the workspace
This will show what tests Tia will select to run based on the current state of the workspace and how Tia is configured.
Example output:
```
Selected tests to run from VCS source changes: [com.example.DoorServiceTest, com.example.ParameterizedTest]
Selected tests to run from VCS test file changes: []
Selected tests to run from new test files: []
Running previously failed tests: [com.example.DoorServiceTest]
Selected tests to run: 
	com.example.DoorServiceTest
	com.example.ParameterizedTest
```

**Maven, Junit5 and Git**
```
tia-junit5-git:select-tests
```

Note: to see extra debugging including what test suites are being selected broken down by source methods:
```
tia-junit5-git:select-tests -Dorg.slf4j.simpleLogger.log.org.tiatesting=debug
```

**Maven, Junit5 and Perforce**
```
tia-junit5-perforce:select-tests
```

**Maven, Junit4 and Git**
```
tia-junit4-git:select-tests
```

**Maven, Junit4 and Perforce**
```
tia-junit4-perforce:select-tests
```

**Gradle, Spock and Git**
```
gradle tia-select-tests
```

Note: 
To see extra informtation about what type of changes trigger the selected tests, run the command with more information:
```
gradle tia-select-tests --info
```

To see extra debugging including what test suites are being selected broken down by source methods:
```
gradle tia-select-tests --debug
```

### Display the test-run history
Prints the most recent rows from the `tia_test_run_history` table as a table — one row per run,
with branch, 8-char commit, suite counts, duration, mapping flag, and 8-char id. Defaults to the
latest 20 runs; use `-DtiaHistoryLast=N` (Maven) or `--last=N` (Gradle) to change the cap.

Example output:
```
Displaying the latest 20 test runs from a total of 47

Date/time            Branch        Commit    Ran  Ignored  Failed  Duration  Mapping  Id
-------------------  ------------  --------  ---  -------  ------  --------  -------  --------
2026-05-15 09:30:42  main          abc123de   42        3       1  1m 23s    yes      550e8400
2026-05-14 14:22:01  feature/foo   9f8a1b2c   30        0       0  45s       no       7c3e1a09
```

**Maven, Junit5 and Git**
```
tia-junit5-git:history
tia-junit5-git:history -DtiaHistoryLast=50
```

**Maven, Junit5 and Perforce**
```
tia-junit5-perforce:history
```

**Maven, Junit4 and Git**
```
tia-junit4-git:history
```

**Maven, Junit4 and Perforce**
```
tia-junit4-perforce:history
```

**Gradle, Spock and Git**
```
gradle tia-history
gradle tia-history --last=50
```

### Html Report
Generate a HTML report showing the current information about the Tia DB, the test suites and the source code.

*Example of the report summary page:*

<kbd><img width="529" border="1" alt="Screen Shot 2024-05-14 at 9 42 50 PM" src="https://github.com/mtgleeson/tiatesting/assets/1771850/e43aaf82-ee2d-4e66-ac32-b2fb73669fa9"></kbd>

*Example of the test suites index page:*

<kbd><img width="1120" alt="Screen Shot 2024-05-14 at 10 12 56 PM" src="https://github.com/mtgleeson/tiatesting/assets/1771850/a057dbfe-5277-48e7-af1f-e7b9e6834b4f"></kbd>

*Example of the source methods index page:*

<kbd><img width="992" alt="Screen Shot 2024-05-14 at 10 04 34 PM" src="https://github.com/mtgleeson/tiatesting/assets/1771850/d04b527c-f88d-452a-ab20-2d864d7a4424"></kbd>

**Maven, Junit5 and Git**
```
mvn tia-junit5-git:html-report
```

**Maven, Junit5 and Perforce**
```
mvn tia-junit5-perforce:html-report
```

**Maven, Junit4 and Git**
```
mvn tia-junit4-git:html-report
```

**Maven, Junit4 and Perforce**
```
mvn tia-junit4-perforce:html-report
```

**Gradle, Spock and Git**
```
gradle tia-html-report
```

### Text Report
Generate a basic text report showing the current information about the Tia DB, the test suites and the source code.

**Maven, Junit5 and Git**
```
mvn tia-junit5-git:html-report
```

**Maven, Junit5 and Perforce**
```
mvn tia-junit5-perforce:html-report
```

**Maven, Junit4 and Git**
```
mvn tia-junit4-git:text-report
```

**Maven, Junit4 and Perforce**
```
mvn tia-junit4-perforce:text-report
```

**Gradle, Spock and Git**
```
gradle tia-text-report
```

## Configuration Options

|Maven|Gradle|Possible Values|Description|Default Value|Mandatory|
|-----|------|---------------|-----------|-------------|---------|
|tiaEnabled|enabled|true, false|When true Tia will be used in the test runner and only the selected tests will be run. When disabled, tests are run as normal and no mapping or stats will be updated in the Tia DB.|false|true|
|tiaUpdateDBMapping|updateDBMapping|true, false|When true, Tia will analyse all changes from the VCS since the last stored commit number in the DB, up to the head commit of the workspace. Only tests impacted by the detected changes will be run. The stored mapping in the Tia DB will be updated at the end of the test run (regardless if the test run was successful or failed).|false|false|
|tiaCheckLocalChanges|checkLocalChanges|true, false|When true, Tia will analyse all the changes in the local workspace and only run the tests impacted by the local changes. **Note:** when updateDBMapping is true, checkLocalChanges will be disabled regardless of it's value. This is done to ensure the Tia DB is only updated based on analysed changes from VCS and not local changes.|false|false|
|tiaUpdateDBStats|updateDBStats|true, false|When true, Tia will update the statistics for the test run and individual test suites that were executed in the run.|false|false|
|tiaUpdateDBTestRunHistory|updateDBTestRunHistory|true, false|When true, Tia logs one row to the `tia_test_run_history` table on every Tia-enabled test run, capturing branch, commit, suite counts (ran / ignored / failed), duration, and whether the run also updated the mapping. The HTML report's "History" tab reads from this table.|true|false|
|tiaProjectDir|projectDir|<string>|The file path to the root folder of the project being analysed.||true|
|tiaClassFilesDirs|classFilesDirs|<string>|Comma seperated list of paths to the folders containing the classes of the source code (not the test source code). Required for Jacoco to analyse the test coverage.||true|
|tiaSourceFilesDirs|sourceFilesDirs|<string>|Comma seperated list of paths to the folders containing the source code of the project being analysed.||true|
|tiaSourceLibs|sourceLibs|<string>|Comma separated list of `groupId:artifactId:projectDir` entries for in-repo libraries to additionally track coverage for. The `projectDir` segment is the absolute path to the library's own project root (used for loading its build file and for matching VCS diffs against the library's source tree). The `groupId:artifactId` portion is used to resolve the library version from the source project's dependencies and to add the corresponding JAR to Jacoco analysis. When the test and source projects are separate, they must use the same build system.||false|
|tiaSourceProjectDir|sourceProjectDir|<string>|The file path to the root of the source project whose resolved dependencies are used to resolve `sourceLibs` to JAR files. Only needed when the project running the tests is different from the source project being tracked. For Gradle this can be the current project, a sibling subproject, or an external Gradle build.|current project|false|
|tiaTestFilesDirs|testFilesDirs|<string>|Comma seperated list of paths to the folders containing the source code of the test files for the project being analysed.||true|
|tiaDBFilePath|dbFilePath|<string>|The file path for the saved DB containing the previous analysis of the project. Used for the default embedded H2 mode. Ignored when `tiaDBUrl` / `dbUrl` is set.||true (embedded mode)|
|tiaDBUrl|dbUrl|<string>|JDBC URL of an H2 database running in server (TCP) mode, e.g. `jdbc:h2:tcp://h2host:9092/tiadb`. When set, Tia connects to that server instead of an embedded file and `tiaDBFilePath` / `dbFilePath` is ignored. The URL is used as given, except that an optional `{branch}` token is replaced with `tiadb-<branch>` for per-branch databases (e.g. `jdbc:h2:tcp://h2host:9092/{branch}`); only the token is replaced, so a prefix/suffix is preserved (`.../{branch}-myproject` → `.../tiadb-main-myproject`). A URL without the token is used verbatim.||false|
|tiaDBUser|dbUser|<string>|Database username for server-mode H2 (`tiaDBUrl`).|sa|false|
|tiaDBPassword|dbPassword|<string>|Database password for server-mode H2 (`tiaDBUrl`).|(empty)|false|
|tiaBuildDir|N/A|<string>|The build path for the project. Used for saving files used internally by Tia. Currently only used for Maven.|${project.build.directory}/tia|true|
|tiaVcsServerUri|N/A|<string>|Specifies the server URI of the VCS system. Only currently used for Perforce.|For Perforce it will default to use the value in the 'p4 set' command.|false|
|tiaVcsUserName|N/A|<string>|Specifies the username for connecting to the VCS system. Only currently used for Perforce.|For Perforce it will default to use the value in the 'p4 set' command.|false|
|tiaVcsPassword|N/A|<string>|Specifies the password for connecting to the VCS system. Only currently used for Perforce.|For Perforce it will default to use the locally cached p4 ticket in the users home directory.|false|
|tiaVcsClientName|N/A|<string>|Specifies the client name used when connecting to the VCS system. Only currently used for Perforce.|For Perforce it will default to use the value in the 'p4 set' command.|false|

## What is Tia
Tia ia a free test impact analysis library. It analyses changes made to source code and automatically selects the tests to run for your test runner. It's designed as a developer productivity tool to increase the efficiency of developers by cutting down the time required to get feedback on changes. 

Tia has been designed to be unintrusive in your day-to-day work flow. Once it's setup and configured, it will automatically hook into your build automation tool test system to select the tests, and then update the mapping and record the statsics at the completion of the test run.

Through the tracking of statistics, Tia can generate reports that show how successful each test suite is, and how long it takes to run. This information can be useful in tracking poorly written or problematic tests that need attention to improve the overall health of the test suites and your builds.

## How Does Tia Work
Tia collects and stores a mapping of methods that are executed for each of your test suites. 

Tia uses Jacoco to collect the source code coverage for each test suite and store it in the DB for mapping. Tia uses an H2 DB for the data store. By default this is an embedded file-on-disk DB (`tiaDBFilePath` / `dbFilePath`). Tia can also connect to a shared H2 running in [server (TCP) mode](#using-a-shared-h2-server) by setting `tiaDBUrl` / `dbUrl` instead - see below.

The first time Tia runs it needs to 'seed' the mapping DB by running all test suites and collecting the source code mapping for each test suite. It will also store the VCS commit value for that version of the test suite and source code mapping. Each subsequent test run then analyses the changes made and selects only the tests to run that are impacted by the source code changes. All other tests are ignored.

Typically you will want a 'primary' automated build that is configured to run Tia on each commit/submit/check-in. Only this build should be configured to update the test suite to source code mapping in the DB (tiaUpdateDBMapping=true).
Developers using Tia on their local workspace should configure Tia to analyse local changes only (tiaUpdateDBMapping=false and tiaCheckLocalChanges=true).

**Note:** The build machine(s) that are designated to be the 'primary' (which update the test suite to source code mapping) need to run the tests suites **sequentially**. This is important to allow Tia to correctly associate the source code coverage with each test suite.  

## Using a shared H2 server
By default Tia stores its data in an embedded H2 file on the machine running the build. If you want several builds (for example a primary CI build plus developer/local builds) to share one Tia database, you can point Tia at an H2 instance running in [server (TCP) mode](https://www.h2database.com/html/tutorial.html#using_server) instead.

Set `tiaDBUrl` / `dbUrl` (and optionally `tiaDBUser` / `tiaDBPassword`) to the server's JDBC URL. When set, the embedded `tiaDBFilePath` / `dbFilePath` is ignored.

Maven:
```xml
<tiaDBUrl>jdbc:h2:tcp://h2host:9092/tiadb</tiaDBUrl>
<tiaDBUser>tia</tiaDBUser>
<tiaDBPassword>secret</tiaDBPassword>
```
For the test run itself (Surefire), these are passed to the test JVM the same way as `tiaDBFilePath` - via the Surefire `systemPropertyVariables`.

Gradle:
```groovy
tia {
    dbUrl = 'jdbc:h2:tcp://h2host:9092/tiadb'
    dbUser = 'tia'
    dbPassword = 'secret'
}
```

### Keeping the password out of checked-in config
Putting `tiaDBPassword` / `dbPassword` directly in your POM or `build.gradle` means committing a secret to source control. To avoid that, leave the password (and optionally the user) unset in the build config and let Tia fall back to environment variables: when the configured value is blank, Tia reads `TIA_DB_PASSWORD` and `TIA_DB_USER` from the environment. CI sets those as secrets and the repo carries no credential.

```groovy
tia {
    dbUrl = 'jdbc:h2:tcp://h2host:9092/tiadb'
    // dbUser / dbPassword omitted - taken from TIA_DB_USER / TIA_DB_PASSWORD
}
```

The build tools also support their own indirection if you prefer it: Maven resolves `<tiaDBPassword>${env.TIA_DB_PASSWORD}</tiaDBPassword>` or a property from `~/.m2/settings.xml` (which supports [encrypted passwords](https://maven.apache.org/guides/mini/guide-encryption.html)); Gradle can read from `~/.gradle/gradle.properties` or a `-P` property. Tia never logs the password (only the JDBC URL), so avoid embedding credentials directly in `dbUrl`.

The environment fallback only kicks in when the password is **not configured at all**. If your database genuinely uses an empty password, set it explicitly - `dbPassword = ''` (Gradle) or `<tiaDBPassword></tiaDBPassword>` (Maven) - and Tia uses the empty value verbatim rather than falling back to `TIA_DB_PASSWORD`.

Things to know when using server mode:
- **Start the server with `-ifNotExists`.** Tia creates its schema (and the database, on first use) automatically. An H2 TCP server refuses to create a database for a remote client unless it was started with the `-ifNotExists` flag, so the first Tia run will fail without it.
- **The URL is used as given, with one optional substitution.** Unlike embedded mode, Tia does not automatically append a `tiadb-<branch>` suffix. If you want per-branch databases, put a `{branch}` token where the database name belongs (e.g. `jdbc:h2:tcp://h2host:9092/{branch}`) and Tia replaces that token with `tiadb-<branch>` (path separators in the branch name are replaced with `-`). Only the token is replaced, so you can add a prefix or suffix around it - `jdbc:h2:tcp://h2host:9092/{branch}-myproject` becomes `.../tiadb-main-myproject`. A URL without the token is used verbatim, so a fully-specified URL still takes precedence.
- **Keep a single mapping writer.** As with embedded mode, only the primary build should set `tiaUpdateDBMapping=true`. All other clients should run with `tiaUpdateDBMapping=false` (mapping is owned by one writer). The other clients only update statistics.
- **Statistics are best-effort under concurrency.** Statistics counters (run counts, averages) are read-modify-write and are not locked across clients, so when multiple clients update statistics against the same database at the same time, some statistic increments can be lost. Tia treats statistics as advisory; the mapping (owned by the single writer) is unaffected. (See also the multi-fork persistence note in the [Wiki](WIKI.md).)

## Supported Build Automation Tools, VCS and Test Runners
### Maven 3.8.1+

Maven 3.8.1 or newer is required — see [Requirements](#requirements) and the [Wiki](WIKI.md) for the rationale.

| |Git|Perforce|
|-|---|--------|
|Junit 4|✔|✔|
|Junit 5|✔|✔|
|Spock 2|x|x|

### Gradle

| |Git|Perforce|
|-|---|--------|
|Junit 4|x|x|
|Junit 5|x|x|
|Spock 2|✔|x|

## Credits
A shout out to the following libraries that Tia uses:
 - [Jacoco](https://www.eclemma.org/jacoco/)
 - [H2 Database](https://www.h2database.com/)
 - [J2HTML](https://j2html.com/)
 - [ByteBuddy](https://bytebuddy.net/)
 - [JGit](https://github.com/eclipse-jgit/jgit)
 - [P4Java](https://github.com/perforce/p4java)
 - [Java Diff Utils](https://github.com/java-diff-utils/java-diff-utils)
 - [Simple-Datatables](https://fiduswriter.github.io/simple-datatables/)
 - [Pico CSS](https://picocss.com/)

## Additional resources and solutions
 - [https://martinfowler.com/articles/rise-test-impact-analysis.html](https://martinfowler.com/articles/rise-test-impact-analysis.html)
 - [https://gradle.com/gradle-enterprise-solutions/predictive-test-selection/](https://gradle.com/gradle-enterprise-solutions/predictive-test-selection/)
 - [https://research.facebook.com/publications/predictive-test-selection/](https://research.facebook.com/publications/predictive-test-selection/)
 - [https://schibsted.com/blog/impact-testing-stop-waiting-tests-not-need-run/](https://schibsted.com/blog/impact-testing-stop-waiting-tests-not-need-run/)
 - [https://github.com/rpau/junit4git](https://github.com/rpau/junit4git)
 - [https://www.parasoft.com/products/parasoft-jtest/java-test-impact-analysis/](https://www.parasoft.com/products/parasoft-jtest/java-test-impact-analysis/)
 - [https://www.sealights.io/product/test-impact-analysis/#](https://www.sealights.io/product/test-impact-analysis/#) 
