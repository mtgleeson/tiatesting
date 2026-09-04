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
	- [Seeing Tia's log output](#seeing-tias-log-output)
- [Configuration Options](#configuration-options)
- [Static test selection rules](#static-test-selection-rules)
- [Distributed test runs](#distributed-test-runs)
- [What is Tia](#what-is-tia)
- [How Does Tia Work](#how-does-tia-work)
- [Branch isolation (schema per branch)](#branch-isolation-schema-per-branch)
- [Using a shared H2 server](#using-a-shared-h2-server)
- [Using a different database](#using-a-different-database)
- [Supported Build Automation Tools, VCS and Test Runners](#supported-build-automation-tools-vcs-and-test-runners)
- [Credits](#credits)
- [Additional resources and solutions](#additional-resources-and-solutions)
- [Bug Report](https://github.com/mtgleeson/tiatesting/issues)
- [Feature Request](https://github.com/mtgleeson/tiatesting/issues)

## Getting Started

### Requirements

- **Maven**: 3.8.1 or newer is required for any of the Maven-based Tia plugins (`tia-junit4-git-maven-plugin`, `tia-junit4-perforce-maven-plugin`, `tia-junit5-git-maven-plugin`, `tia-junit5-perforce-maven-plugin`). The floor is enforced automatically via `<prerequisites>` in each plugin's POM — invoking a Tia plugin under an older Maven will fail with a clear "requires Maven 3.8.1" error. See the [Wiki](WIKI.md) for the design decision behind picking 3.8.1 specifically.
- **Java**: 8 or newer.
- **Gradle**: no version floor is enforced beyond what the Spock plugin's runtime requires.
- **SLF4J on the test classpath** (only needed if you want to see Tia's logging from inside the test run): Tia logs via SLF4J but deliberately does not bring `slf4j-api` or a binding along transitively, so your test project must already provide them. Most projects do. See [Seeing Tia's log output](#seeing-tias-log-output).

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
        <version>0.1.18</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>        
        <plugin>
            <!-- Include the Maven plugin, used to select which tests to run and ignore the rest. -->
            <groupId>org.tiatesting</groupId>
            <artifactId>tia-junit5-git-maven-plugin</artifactId>
            <version>0.1.18</version>
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
                <!-- No Tia systemPropertyVariables are needed here. The Tia plugin forwards
                     everything the forked test JVM requires (DB connection, project dirs, update
                     flags) to its javaagent automatically, from the Tia plugin <configuration> /
                     properties above. -->
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
        <version>0.1.18</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>        
        <plugin>
            <!-- Include the Maven plugin, used to select which tests to run and ignore the rest. -->
            <groupId>org.tiatesting</groupId>
            <artifactId>tia-junit4-git-maven-plugin</artifactId>
            <version>0.1.18</version>
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
        classpath 'org.tiatesting:tia-spock-git-gradle:0.1.18'
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
</properties>

<build>
    <plugins>
        <plugin>
            <groupId>org.tiatesting</groupId>
            <artifactId>tia-junit5-git-maven-plugin</artifactId>
            <version>0.1.18</version>
            <configuration>
                <!-- ...existing Tia plugin configuration... -->
                <tiaSourceLibs>${tiaSourceLibs}</tiaSourceLibs>
                <tiaSourceProjectDir>${tiaSourceProjectDir}</tiaSourceProjectDir>
            </configuration>
        </plugin>
    </plugins>
</build>
```

Each tracked **library module's** own build must also run Tia's `publish-lib-stamp` goal so its
publishes are recorded (see "How library change tracking works" below):

`library pom.xml`
```xml
<plugin>
    <groupId>org.tiatesting</groupId>
    <artifactId>tia-junit5-git-maven-plugin</artifactId>
    <executions>
        <execution>
            <goals><goal>publish-lib-stamp</goal></goals>
            <!-- default phase is install: fires on both mvn install and mvn deploy -->
        </execution>
    </executions>
    <configuration>
        <tiaEnabled>true</tiaEnabled>
        <tiaUpdateDBMapping>true</tiaUpdateDBMapping>
        <tiaProjectDir>${project.basedir}</tiaProjectDir>
        <!-- must point at the same Tia DB location the consuming project uses -->
        <tiaDBFilePath>/path/to/consuming-project</tiaDBFilePath>
    </configuration>
</plugin>
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
}
```

On the Gradle side the tracked **library module** applies the Tia plugin too: Tia hooks its
publish stamp onto the module's `publish` and `publishToMavenLocal` tasks automatically (see
"How library change tracking works" below), so publishing the library records the build in the
Tia publish ledger. The library module's `tia { ... }` block needs `enabled`, `updateDBMapping`
and the shared DB location.

The Gradle plugin pre-resolves library metadata (declared version, source directories, resolved version, JAR path) at task-action time and forwards it to the forked test JVM via system properties — TIA's library partitioning, reconcile, stamp, and drain phases all run inside the test JVM as part of Spock's selection lifecycle. No state is exchanged via files; the wire format is internal and not part of the public configuration surface.

**Single-fork requirement when `updateDBMapping=true`.** The Gradle/Spock path must run with `maxParallelForks=1` and `forkEvery=0` (Gradle's defaults) when persisting mapping data. Each forked JVM persists independently using only the test suites it observed, so multi-fork runs corrupt the on-disk mapping by deleting the suites other forks owned. This affects test-suite-mapping persistence in general (not specific to library tracking) — leaving Gradle's defaults in place avoids it.

#### How library change tracking works (publish-time stamping)

When a tracked library's source code changes, Tia does **not** run the impacted tests immediately — the consuming project may still be on an older build of the library, and running the tests against stale code would produce a false green. Instead, Tia records the change at the one moment its shipping identity is an unambiguous fact: when the library is **published**.

**Stamp (at library publish):** the library module's Tia publish task (`publish-lib-stamp` on Maven, the automatic `publish`/`publishToMavenLocal` hook on Gradle) records every published build in a per-library **publish ledger** — the exact version, the jar's content hash, and a monotonically increasing publish sequence number — and stamps the source methods impacted since the library's mapping baseline against that sequence. Commits alone write nothing; a change becomes drainable when it becomes consumable.

**Drain (at test selection):** on each Tia run, the consuming project resolves the library on its classpath and looks the artifact up in the ledger (by jar hash, or exact version for releases). Every pending stamp at or below the resolved build's sequence drains — builds are cumulative, so the resolved jar provably contains those changes. Tia resolves the stamped method IDs to covering test suites using the **current** test-to-source mapping and adds them to the run set. If the resolved artifact is unknown to the ledger, everything is held with a warning (holding cannot produce a false green). After a successful primary run, drained stamps are deleted and the library's applied sequence advances.

Because the identity is recorded at publish, no version-convention configuration is needed — release flows, snapshot redeploys under an unchanged version string, and version bumps that land between a commit and a Tia run are all handled by the ledger's ordering.

**Local development:** local (unpublished) library edits are picked up directly in `checkLocalChanges=true` mode — they feed normal source selection with no DB writes, so you get instant feedback before committing. Published changes your local classpath contains are drained in local runs too (read-only). For local work, prefer a reactor build from the repo root so the app resolves the library from the reactor rather than a possibly stale local repository snapshot.

**Ownership gating:** only builds that own mapping-DB writes (`updateDBMapping=true`, typically the primary CI build) write the ledger and stamps; on a developer machine the publish task is a logged no-op.

**Library removal:** if a library is removed from the `sourceLibs` configuration, Tia deletes the tracked library row and its publish ledger and pending stamps are automatically cascade-deleted.

See the [library publish-time stamping](wiki/library-publish-time-stamping.md) wiki chapter for the full model, the end-to-end sequence, and the edge cases.

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
Number of partial runs: 1
Average run time: 12s (25%)
Number of all-tests runs: 1
All tests run time: 48s
Total savings over all runs: 36s
Number of successful runs: 2 (100%)
Number of failed runs: 0 (0%)
```

"All tests run time" is the average time to run the full suite (runs where Tia ignored nothing); compare it against "Average run time" (Tia-selected runs) to see the time Tia saves. The percentage on the "Average run time" line is the selected-run time as a share of the full-suite time, and "Total savings over all runs" sums the time saved across every recorded partial run.

Once the project has run [distributed builds](#distributed-test-runs), the average-run-time line is qualified and a second one appears beneath it:

```
Average run time (serial equivalent): 638ms (96%)
Average distributed run time: 553ms (83%) over 3 distributed run(s)
```

"Average run time" is the **serial equivalent** - what each run's selection would have cost on one host - averaged over **every** run, and it is the figure savings are computed from in both modes. "Average distributed run time" is the **wall clock** those builds actually waited for, averaged over **distributed runs only**, which is why the run count is stated: the two lines average different populations, so without it the pair reads as though the same builds got faster. Both percentages are against the same full-suite baseline, so they are directly comparable. A project that has never run a distributed build sees neither the qualifier nor the second line. See [Which time is which](#which-time-is-which).

Library information is not part of the status output - see the [libraries task](#libraries--tracked-libraries-and-their-pending-changes) below. Pending failed tests (forced to re-run) are shown by the select-tests task.

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

### Libraries — tracked libraries and their pending changes
Lists every tracked library (see library impact analysis) with its recorded state - the same details as the `tia-libraries.html` report page, plus the per-batch pending detail: project dir, source dirs, last applied publish seq, mapping baseline commit, and the library's pending impacted-method batches.
Example output:
```
Tracked libraries:
	com.example:libA
		Project dir: /abs/path/to/libA
		Source dirs: /abs/path/to/libA/src/main/java
		Last applied publish seq: 2
		Mapping baseline commit: 993f44ec47f4957ff6b8ae5ebdcbce2cdf1c3a2c
		Pending batches: 1
			seq 3 @ 1.1.0 - 3 methods pending
```

**Maven, Junit5 and Git**
```
mvn tia-junit5-git:libraries
```

**Maven, Junit5 and Perforce**
```
mvn tia-junit5-perforce:libraries
```

**Maven, Junit4 and Git**
```
mvn tia-junit4-git:libraries
```

**Maven, Junit4 and Perforce**
```
mvn tia-junit4-perforce:libraries
```

**Gradle, Spock and Git**
```
gradle tia-libraries
```

### Library publishes — the publish ledger for one library
Prints a tracked library's publish ledger as a table: one row per published build with its sequence, version, jar hash, commit, publish time, and how many of the build's stamped methods still await their drain. The library is selected with a `groupId:artifactId` parameter.
Example output:
```
Publishes for library com.example:libA:
Seq | Version        | Jar hash        | Commit          | Published at         | Methods pending
----+----------------+-----------------+-----------------+----------------------+----------------
1   | 1.0.0-SNAPSHOT | 3f9358826797... | 993f44ec47f4... | 2026-07-01T10:14:02Z | -
2   | 1.1.0          | a1b2c3d4e5f6... | 1c63c66aa01b... | 2026-07-12T08:30:11Z | 3
```

**Maven (per flavor plugin, e.g. Junit5 and Git)**
```
mvn tia-junit5-git:library-publishes -DtiaLibrary=com.example:libA
```

**Gradle, Spock and Git**
```
gradle tia-library-publishes --library=com.example:libA
```

### Library pending methods — the pending impacted methods for one library
Prints one table row per pending impacted method for a tracked library: the publish it shipped in (sequence + version) and the method's tracked name and line range. The library is selected with a `groupId:artifactId` parameter.
Example output:
```
Pending impacted methods for library com.example:libA:
Seq | Version | Method id  | Method                                            | Lines
----+---------+------------+---------------------------------------------------+------
2   | 1.1.0   | -495364344 | org/example/lib/TireService.getRecommendedPressure.(Ljava/lang/String;)I | 14-23
```

**Maven (per flavor plugin, e.g. Junit5 and Git)**
```
mvn tia-junit5-git:library-pending-methods -DtiaLibrary=com.example:libA
```

**Gradle, Spock and Git**
```
gradle tia-library-pending-methods --library=com.example:libA
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
	com.example.DoorServiceTest (12s)
	com.example.ParameterizedTest (8s)

Estimated total run time: 1m 5s (22%)
Estimated savings: 3m 55s (78%)
```

The bracket after each test is its average run time; the percentages compare against the full-suite run time ("Estimated total run time" as a share of running everything, and "Estimated savings" as the share avoided).

When the test run is configured to update the mapping (`updateDBMapping=true`, i.e. the primary build that collects JaCoCo coverage), the "Estimated total run time" also includes an allowance for per-suite coverage capture plus other whole-run overhead (JVM/agent startup, the final persist), derived from the recorded full-suite run time and amortised across the selected suites. A run that does not update the mapping collects no coverage, so no overhead is added and the estimate is the plain sum of the per-suite times.

**Maven, Junit5 and Git**
```
tia-junit5-git:select-tests
```

Note: to see extra debugging including what test suites are being selected broken down by source methods:
```
tia-junit5-git:select-tests -Dorg.slf4j.simpleLogger.log.org.tiatesting=debug
```
See [Seeing Tia's log output](#seeing-tias-log-output) for more on Tia's logging.

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
with branch, 8-char commit, suite counts, duration, savings, run origin, mapping flag, and
8-char id. Defaults to the
latest 20 runs; use `-DtiaHistoryLast=N` (Maven) or `--last=N` (Gradle) to change the cap.

Example output:
```
Displaying the latest 20 test runs from a total of 47

Date/time            Branch        Commit    Ran  Ignored  Failed  Duration  Savings  Savings %  Mapping  Id
-------------------  ------------  --------  ---  -------  ------  --------  -------  ---------  -------  --------
2026-05-15 09:30:42  main          abc123de   42        3       1  1m 23s    5m 12s         79%  yes      550e8400
2026-05-14 14:22:01  feature/foo   9f8a1b2c   30        0       0  45s       -                -  no       7c3e1a09
```

When any run in view was a [distributed build](#distributed-test-runs), two further columns appear after `Duration`:

```
Date/time            Branch  Commit    Ran  Ignored  Failed  Duration  Wall clock  Groups  Savings  Savings %  Mapping  Id
-------------------  ------  --------  ---  -------  ------  --------  ----------  ------  -------  ---------  -------  --------
2026-08-17 22:35:46  main    6097d683    2        1       0  615ms     506ms            2  49ms            7%  yes      3fd70a70
2026-08-17 20:50:23  main    51e8970a    3        0       0  664ms     664ms            1  -                -  yes      17972bd5
```

- **`Duration`** is the **serial equivalent** in both modes - what that run's selection would have cost on one host. It stays the figure `Savings` is computed from, so a project's history remains comparable across the build where distributed mode was switched on.
- **`Wall clock`** is what the distributed build actually waited for: its slowest group. This is the parallel build time. A single-host run dashes it.
- **`Groups`** is how many groups the run was split across.

The two are equal when a run had one group - the second row above is a seed run, which is always a single group covering everything. Note that `Savings` is measured against `Duration`, so it never includes the speed-up from distributing; that gain is the gap between `Duration` and `Wall clock`. See [Which time is which](#which-time-is-which).

When any run in view recorded where it came from, two further columns appear after `Savings %`:

```
Date/time            Branch            Commit    Ran  Ignored  Failed  Duration  Savings  Savings %  Source  Host           Mapping  Id
-------------------  ----------------  --------  ---  -------  ------  --------  -------  ---------  ------  -------------  -------  --------
2026-05-15 09:30:42  main              abc123de  420        0       0  15m 30s   -                -  CI      build-agent-3  yes      550e8400
2026-05-14 18:06:40  feature/checkout  9911aabb   12      408       1  41s       14m 49s        95%  LOCAL   mgleeson-mbp   no       6f1a2b3c
```

- **`Source`** is `CI` or `LOCAL`, detected from the CI marker environment variables a forked test JVM inherits, or whatever `tiaRunSource` / `runSource` declared. See [configuration](#configuration).
- **`Host`** is the machine that ran it, rendered whole rather than truncated - unlike a commit hash it is read to tell machines apart, and a fixed-width prefix of several agents in one naming scheme would collapse them into one. A distributed build dashes it: no single machine ran it.

Both columns are omitted entirely from a history recorded before they existed, and a row that predates them is dashed in a mixed history.

A history with no distributed run in view renders neither extra column, and single-host rows in a mixed history dash them.

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

### Seeing Tia's log output

Tia logs from two different JVMs, and they have different requirements.

**1. The build process (the Tia goals/tasks: `select-tests`, `status`, `libraries`, `history`, the reports, and `prepare-agent` itself).**

Nothing to configure - this output appears on the console out of the box. Under Maven, Tia's goals log through Maven's own plugin log and SLF4J binding, so INFO-level messages print with your normal build output.

To see Tia's debug logging (for example, the breakdown of which source methods caused each test suite to be selected):

```
mvn tia-junit5-git:select-tests -Dorg.slf4j.simpleLogger.log.org.tiatesting=debug
```

`mvn -X` works too, but it turns on debug for the entire build. Note that Maven supplies its own SLF4J binding for this process, so a `logback.xml` in your project has no effect on it - use the system property above.

For Gradle, use `gradle tia-select-tests --info` or `--debug`.

**2. The forked test JVM (the Tia javaagent and the Tia test listener, which run alongside your tests).**

This is the part that has a requirement. Tia declares `slf4j-api` as a compile-only dependency in every module and does not bundle it into the agent jar, so **your test project must provide `slf4j-api` plus an SLF4J binding (Logback, `slf4j-simple`, Log4j2's SLF4J binding, etc.) on its test classpath.** Most real projects already have one. If yours does not:

- With `slf4j-api` present but no binding, SLF4J falls back to a no-op implementation, prints `Failed to load class org.slf4j.impl.StaticLoggerBinder`, and Tia's test-run logging is silently discarded.
- With no `slf4j-api` at all, Tia's loggers cannot initialise and the test run can fail with `NoClassDefFoundError: org/slf4j/LoggerFactory`.

A minimal setup for a project that has no logging dependencies of its own:

```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>1.7.32</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>1.7.32</version>
    <scope>test</scope>
</dependency>
```

The log level for this JVM is controlled by whichever binding you use - for example `<logger name="org.tiatesting" level="debug"/>` in `logback.xml`, or `-Dorg.slf4j.simpleLogger.log.org.tiatesting=debug` for `slf4j-simple`. System properties set on the `mvn` command line are normally copied into the forked JVM by Surefire; if you find they are not being applied, set the property explicitly in Surefire's `<systemPropertyVariables>`.

Two Surefire settings can hide this output even when a binding is present:

- `<redirectTestOutputToFile>true</redirectTestOutputToFile>` sends the fork's console output to `target/surefire-reports/*-output.txt` instead of the terminal.
- `mvn -q` suppresses INFO-level output for the build generally (the `select-tests` listing is written to standard out and still appears).

## Configuration Options

|Maven|Gradle|Possible Values|Description| Default Value                                                                                 |Mandatory|
|-----|------|---------------|-----------|-----------------------------------------------------------------------------------------------|---------|
|tiaEnabled|enabled|true, false|When true Tia will be used in the test runner and only the selected tests will be run. When disabled, tests are run as normal and no mapping or stats will be updated in the Tia DB.| false                                                                                         |true|
|tiaUpdateDBMapping|updateDBMapping|true, false|When true, Tia will analyse all changes from the VCS since the last stored commit number in the DB, up to the head commit of the workspace. Only tests impacted by the detected changes will be run. The stored mapping in the Tia DB will be updated at the end of the test run (regardless if the test run was successful or failed). **This also controls the run statistics** - the run and per-suite timings, run counts and pass/fail counts are recorded by exactly the builds that own the mapping. The two are one decision: the build whose mapping is authoritative is the build whose timings are the reference ones, so a developer's machine never shifts the averages CI's estimates and savings are computed from.| false                                                                                         |false|
|tiaCheckLocalChanges|checkLocalChanges|true, false|When true, Tia will analyse all the changes in the local workspace and only run the tests impacted by the local changes. **Note:** when updateDBMapping is true, checkLocalChanges will be disabled regardless of it's value. This is done to ensure the Tia DB is only updated based on analysed changes from VCS and not local changes.| false                                                                                         |false|
|tiaUpdateDBTestRunHistory|updateDBTestRunHistory|true, false|When true, Tia logs one row to the `tia_test_run_history` table on every Tia-enabled test run, capturing branch, commit, suite counts (ran / ignored / failed), duration, and whether the run also updated the mapping. The HTML report's "History" tab reads from this table.| true                                                                                          |false|
|tiaProjectDir|projectDir|<string>|The file path to the root folder of the project being analysed.|                                                                                               |true|
|tiaClassFilesDirs|classFilesDirs|<string>|Comma seperated list of paths to the folders containing the classes of the source code (not the test source code). Required for Jacoco to analyse the test coverage.|                                                                                               |true|
|tiaSourceFilesDirs|sourceFilesDirs|<string>|Comma seperated list of paths to the folders containing the source code of the project being analysed.|                                                                                               |true|
|tiaSourceLibs|sourceLibs|<string>|Comma separated list of `groupId:artifactId:projectDir` entries for in-repo libraries to additionally track coverage for. The `projectDir` segment is the absolute path to the library's own project root (used for loading its build file and for matching VCS diffs against the library's source tree). The `groupId:artifactId` portion is used to resolve the library version from the source project's dependencies and to add the corresponding JAR to Jacoco analysis. When the test and source projects are separate, they must use the same build system.|                                                                                               |false|
|tiaSourceProjectDir|sourceProjectDir|<string>|The file path to the root of the source project whose resolved dependencies are used to resolve `sourceLibs` to JAR files. Only needed when the project running the tests is different from the source project being tracked. For Gradle this can be the current project, a sibling subproject, or an external Gradle build.| current project                                                                               |false|
|tiaTestFilesDirs|testFilesDirs|<string>|Comma seperated list of paths to the folders containing the source code of the test files for the project being analysed.|                                                                                               |true|
|tiaDBFilePath|dbFilePath|<string>|The file path for the saved DB containing the previous analysis of the project. Used for the default embedded H2 mode. Ignored when `tiaDBUrl` / `dbUrl` is set.|                                                                                               |true (embedded mode)|
|tiaDBUrl|dbUrl|<string>|JDBC URL of an H2 database running in server (TCP) mode, e.g. `jdbc:h2:tcp://h2host:9092/tiadb;DB_CLOSE_DELAY=-1`, or a JDBC URL for another supported vendor, e.g. `jdbc:postgresql://pghost:5432/tiadb`. When set, Tia connects to that URL instead of an embedded file and `tiaDBFilePath` / `dbFilePath` is ignored. The URL is used exactly as given - the same URL on every branch - and Tia isolates each branch's mapping into its own schema within that one database automatically, derived from the current branch (see [Branch isolation](#branch-isolation-schema-per-branch)). For H2 server mode, include `;DB_CLOSE_DELAY=-1` - see [Using a shared H2 server](#using-a-shared-h2-server). For a non-H2 vendor, see [Using a different database](#using-a-different-database).|                                                                                               |false|
|tiaDBDialect|dbDialect|`h2`, `postgres`|Explicit SQL dialect override. Only needed when the dialect can't be (or shouldn't be) inferred from `tiaDBUrl` / `dbUrl`'s scheme. See [Using a different database](#using-a-different-database).| inferred from `tiaDBUrl` / `dbUrl` (defaults to `h2` when that is also unset)                 |false|
|tiaDBUser|dbUser|<string>|Database username for server-mode H2 or a non-H2 vendor (`tiaDBUrl`).|tia|false|
|tiaDBPassword|dbPassword|<string>|Database password for server-mode H2 or a non-H2 vendor (`tiaDBUrl`).| (empty)                                                                                       |false|
|N/A|schemaSuffix|<string>|Isolates this test task's datastore into its own schema, `tia_<branch>_<suffix>`. Declare one per test task on a project with more than one Tia-enabled test task: two that share a schema delete each other's tracked test suites and share one stored commit value, which costs selectivity and can silently under-select. The Gradle plugin refuses a build whose mapping-owning test tasks resolve to the same schema, naming them and this setting. Leave unset for a single-test-task project - the schema is then the `tia_<branch>` Tia has always used, so nothing moves.| (none - the plain `tia_<branch>` schema)                                                    |false|
|tiaRunSource|runSource|<string>|The label recorded in the history row's `run_source` column, overriding Tia's own detection. Leave unset unless the detection gets it wrong: Tia reads the CI marker environment variables (which a forked test JVM inherits), so a CI job is already labelled `CI` and a developer's machine `LOCAL` with nothing configured. Set it to distinguish a build the detection cannot tell apart from any other (a nightly, a performance rig), or to label a CI system Tia does not recognise. Can also be supplied as the `TIA_RUN_SOURCE` environment variable, which reaches the forked test JVM by inheritance.| detected: `CI` when a CI marker environment variable is present, else `LOCAL`                 |false|
|tiaBuildDir|N/A|<string>|The build path for the project. Used for saving files used internally by Tia. Currently only used for Maven.| ${project.build.directory}/tia                                                                |true|
|tiaVcsServerUri|N/A|<string>|Specifies the server URI of the VCS system. Only currently used for Perforce.| For Perforce it will default to use the value in the 'p4 set' command.                        |false|
|tiaVcsUserName|N/A|<string>|Specifies the username for connecting to the VCS system. Only currently used for Perforce.| For Perforce it will default to use the value in the 'p4 set' command.                        |false|
|tiaVcsPassword|N/A|<string>|Specifies the password for connecting to the VCS system. Only currently used for Perforce.| For Perforce it will default to use the locally cached p4 ticket in the users home directory. |false|
|tiaVcsClientName|N/A|<string>|Specifies the client name used when connecting to the VCS system. Only currently used for Perforce.| For Perforce it will default to use the value in the 'p4 set' command.                        |false|
|tiaDistributed|distributed|true, false|When true this build takes part in a [distributed test run](#distributed-test-runs): the selection is split into groups across CI runners that coordinate through a shared database. Requires `tiaDBUrl` / `dbUrl` (a shared datastore - embedded H2 is rejected) and `tiaCheckLocalChanges` / `checkLocalChanges` disabled.| false |false|
|tiaRunId|runId|<string>|The shared identifier every job in one distributed build must agree on, so each runner finds the same run's rows in the shared database. Must be the **same** for every job in a build and **different** for every build - a CI pipeline/run id is the natural value.| |true (when distributed)|
|tiaDistributedGroupCount|distributedGroupCount|<integer>|Split the selection into exactly this many groups, minimising the heaviest one. Mutually exclusive with `tiaDistributedTargetRunTime` - exactly one of the two must be set.| |one of the two|
|tiaDistributedTargetRunTime|distributedTargetRunTime|<milliseconds>|Work out how many groups are needed to bring the wall-clock test time under this target, and use the fewest that do. Mutually exclusive with `tiaDistributedGroupCount`. Meeting the target is best effort - see [Distributed test runs](#distributed-test-runs).| |one of the two|
|tiaDistributedMaxGroups|distributedMaxGroups|<integer>|Ceiling on the number of groups when balancing for `tiaDistributedTargetRunTime`. A spend limit, not a goal - Tia uses the fewest groups that meet the target, not this many. Rejected alongside a fixed `tiaDistributedGroupCount`, where it would be meaningless.| no ceiling |false|
|tiaDistributedRunnerKey|distributedRunnerKey|<string>|Per-runner identity used by the claim protocol to tell concurrent runners apart. Set it to something **stable across job retries** (a CI matrix index) so a retried job is handed back its own group instead of claiming a second one.| derived from run id + hostname + pid |false|
|tiaDistStatusSuites|N/A (`--suites`)|true, false|Used only by the status command ([Maven `dist-status`, Gradle `tia-dist-status`](#status---inspect-a-run-in-flight)). When true, also lists the test suite names assigned to each group. Off by default because the list is unbounded. On Gradle this is the `--suites` command-line flag rather than an extension property.| false |false|
|tiaStaticTestSelectionRules|staticTestSelectionRules|nested list of rules|User-declared rules of the form "if a changed file matches this path regex, force-run these suites". Used for change drivers Tia's coverage-driven mapping can't see (SQL migrations, properties, schema files). Rules are additive: their selected suites are unioned into the suites already selected from the coverage mapping. See [Static test selection rules](#static-test-selection-rules) below for the configuration syntax.| empty (no rules)                                                                              |false|

## Static test selection rules

The coverage-driven mapping that Tia learns from test runs is referred to as the **dynamic mapping** — it evolves automatically as runs land and the criteria for selecting tests change with it. Static test selection rules give you a second, **static mapping** that lives in your build config: it ties file-path patterns to test suites and only changes when you edit the config. Use it for change drivers Tia can't observe through bytecode coverage — SQL migrations, properties files, schema files, code generators, and so on.

Each rule has:
- `name` (optional): a human-readable label used in log output.
- `filePathPattern` (required): a Java regex matched against the repo-relative path of each changed file.
- `mode` (required): either `RUN_ALL` (run every tracked test suite when this rule fires) or `SUITE_NAMES` (run only the suites whose names match `suiteNamePatterns`).
- `suiteNamePatterns` (required for `SUITE_NAMES`, must be empty for `RUN_ALL`): a list of regexes matched against both the simple class name and the fully-qualified name of each tracked suite.

Rules fire on every Tia-enabled test run (Maven Surefire/Failsafe, Gradle `test`) as well as on the `tia-select-tests` preview task / mojo. Rules are additive: a suite selected by any rule is added to the suites already selected from the dynamic mapping.

### How `filePathPattern` matching works

The pattern is evaluated with Java's `Matcher.find()` - a **substring** match, not a whole-path match - against the repo-relative, forward-slash path of each changed file. For Git that means relative to the repository root; for Perforce, relative to the **client workspace root**, resolved through the client view - so files mapped into the workspace via stream `import`/overlay paths are matched at the workspace-relative location you see on disk, and classic (non-stream) clients work the same way. Two practical consequences:

- **In a multi-module project the path starts with the module directory**, e.g. `payments-service/src/main/resources/default.properties`. A pattern anchored as `^src/main/resources/...` therefore never matches in a module - the path starts with the module name, not `src/`. The failure is silent at default log levels (a DEBUG-only "did not match any changes, skipping" line), so it's an easy trap. Either leave the start unanchored - `src/main/resources/(default|local)\.properties$` works because substring matching finds it anywhere in the path - or use `(^|/)src/...` if you want `src` to be a complete path segment (the unanchored form would also match inside a directory named e.g. `legacy-src`). To pin a rule to one module, anchor the full path: `^payments-service/src/...`.
- **Anchor the end with `$`** when targeting specific files, so `default\.properties$` doesn't also match `default.properties.bak`.

```
tia {
    enabled = true
    updateDBMapping = true
    // ...
    staticTestSelectionRules = [
        [name             : "db-migrations",
         filePathPattern  : "src/main/resources/db/migrations/.*\\.sql\$",
         mode             : "SUITE_NAMES",
         suiteNamePatterns: [".*MigrationIT\$", "com\\.acme\\.db\\..*Spec"]],
        [name           : "build-config",
         filePathPattern: "(build\\.gradle|settings\\.gradle)\$",
         mode           : "RUN_ALL"]
    ]
}
```

### Maven example

```xml
<configuration>
    <tiaEnabled>true</tiaEnabled>
    <!-- ... -->
    <tiaStaticTestSelectionRules>
        <tiaStaticTestSelectionRule>
            <name>db-migrations</name>
            <filePathPattern>src/main/resources/db/migrations/.*\.sql$</filePathPattern>
            <mode>SUITE_NAMES</mode>
            <suiteNamePatterns>
                <suiteNamePattern>.*MigrationIT$</suiteNamePattern>
                <suiteNamePattern>com\.acme\.db\..*IT</suiteNamePattern>
            </suiteNamePatterns>
        </tiaStaticTestSelectionRule>
        <tiaStaticTestSelectionRule>
            <name>build-config</name>
            <filePathPattern>pom\.xml$</filePathPattern>
            <mode>RUN_ALL</mode>
        </tiaStaticTestSelectionRule>
    </tiaStaticTestSelectionRules>
</configuration>
```

Invalid rules (missing required fields, unknown mode, regex that fails to compile) fail at build configuration time, before any tests run, so configuration errors surface immediately rather than at test-run time.

## Distributed test runs

Normally Tia assumes it is the only thing running: one build computes one selection, runs it, and writes one set of results. Splitting that naively across CI runners gives you N independent Tia runs that each compute their own selection and each try to write their own mapping - wasteful, and against a shared database actively unsafe.

A distributed test run makes Tia aware of the topology instead: **one logical build, N runners, one shared database**. A planning step computes the selection once and splits it into groups; each runner claims exactly one group and runs only that group's suites; the runner that finishes last is elected to seal the build and write the mapping, stats and single history row.

Tests still run **sequentially inside each runner** - the parallelism is across hosts only, because Tia relies on one-suite-at-a-time execution in a single JVM to attribute coverage to the right suite.

For the mechanism (the claim protocol, the completeness guard, how the two durations are reported, what happens to a run stuck in `OPEN`) see the [distributed test runs](wiki/distributed-test-runs.md) WIKI chapter.

### Requirements

- **A shared database** - `tiaDBUrl` / `dbUrl` pointing at server-mode H2 or Postgres. Embedded H2 is rejected: the runners coordinate entirely through the datastore.
- **A single-project build.** Multi-module reactors are refused at configuration time. Use `mvn -pl <module>` to distribute one module's tests.
- **`tiaCheckLocalChanges` / `checkLocalChanges` off.** A distributed run is a primary build of a committed state.
- **One JVM per runner.** Maven `forkCount > 1` / `reuseForks=false` and Gradle `maxParallelForks > 1` / `forkEvery > 0` break the one-JVM-per-group assumption. Gradle refuses both; on Maven it is your responsibility.

### The pipeline shape

Three job types, in order:

1. **Plan** - one job runs `dist-plan`, which writes the plan and `<tiaBuildDir>/tia-run-plan.json`.
2. **Run** - N jobs, each running the tests normally with `tiaDistributed=true` and the same `tiaRunId`. Each claims one group.
3. **Complete** - each runner job runs `dist-complete` **whatever the test result**.

A fourth command, `dist-status`, sits outside that order: it is read-only, and reports the state of a run at any point during or after it. See [Status - inspect a run in flight](#status---inspect-a-run-in-flight).

Step 3 is not optional on Maven, and it is the one thing people get wrong. A test failure aborts the Maven lifecycle, so a completion chained onto the same command never runs on exactly the runners you most need it from. The group stays `CLAIMED`, the barrier never opens, and the whole build's mapping work is discarded. Give it its own always-run step. On Gradle no pipeline change is needed - the completion task is a `finalizedBy` finalizer, and finalizers run even when the task they finalize fails.

### Plan - split the selection into groups

Run once per build, before the runner jobs start. Writes the plan to the shared database and `tia-run-plan.json` alongside a console summary.

**Maven, Junit5 and Git**
```
mvn tia-junit5-git:dist-plan -DtiaDistributed=true -DtiaRunId=$CI_RUN_ID -DtiaDistributedTargetRunTime=1500000 -DtiaDistributedMaxGroups=10
```
(substitute `tia-junit5-perforce`, `tia-junit4-git` or `tia-junit4-perforce` for the other flavours)

**Gradle, Spock and Git**
```
gradle tia-dist-plan
```

`tia-run-plan.json` is a published contract - its field names, order and shape are fixed so a pipeline can parse it:
```json
{
  "runId": "gh-1284471",
  "branch": "main",
  "commit": "87a5110",
  "seedRun": false,
  "groupCount": 5,
  "avgGroupMs": 1380000,
  "heaviestGroupMs": 1450000,
  "targetMs": 1500000,
  "targetMet": true,
  "clampedToMaxGroups": false,
  "singleSuiteExceedsTarget": false,
  "fixedOverheadExceedsTarget": false,
  "totalEstimatedMs": 6900000,
  "selectedSuiteCount": 412
}
```

| Field | Type | What it is |
|---|---|---|
| `runId` | string | The `tiaRunId` this plan was written under. Every runner job must be given the same value to claim from it. |
| `branch` | string | The VCS branch the selection was made against. A runner is verified against this before it claims. |
| `commit` | string | The VCS commit the selection was made against, and the one the build seals at. |
| `seedRun` | boolean | `true` when no stored mapping existed yet for this branch, so the plan was collapsed to a single group covering the whole suite and the configured group count / target were ignored. Explains why a pipeline received one job despite asking for more. |
| `groupCount` | number | How many groups the selection was split into. **This is the field to size your job matrix from** - start exactly this many runner jobs. |
| `avgGroupMs` | number | `totalEstimatedMs / groupCount`. A shape indicator only; do not set job timeouts from it - uneven packing is exactly what it hides. |
| `heaviestGroupMs` | number | The heaviest group's estimate, including its own copy of the fixed per-JVM cost. This is the build's expected wall-clock test time, since groups run in parallel, and the figure to base a job timeout on. |
| `targetMs` | number or `null` | The configured `tiaDistributedTargetRunTime`. Rendered as JSON `null` - never `0` - in static-groups mode, where a fixed group count means there is no target. |
| `targetMet` | boolean | Whether `heaviestGroupMs` came in at or under `targetMs`. Always `true` in static-groups mode, which has no target to miss. Missing a target never fails the build or drops tests. |
| `clampedToMaxGroups` | boolean | `true` when `tiaDistributedMaxGroups` limited the group count below what the target needed. Lever: raise the ceiling. |
| `singleSuiteExceedsTarget` | boolean | `true` when one suite alone is longer than what is left of the target after the fixed per-JVM cost. Lever: split the suite or raise the target - no group count divides one suite. |
| `fixedOverheadExceedsTarget` | boolean | `true` when the fixed per-JVM cost alone meets or exceeds the target. No group count can help: every runner pays it before running anything, so adding runners only adds copies of it. Lever: raise the target. |
| `totalEstimatedMs` | number | The summed estimate of every group, each carrying its own copy of the fixed per-JVM cost. This is total machine time across the fan-out, **not** the serial-equivalent time on one host - one host pays that fixed cost once. |
| `selectedSuiteCount` | number | How many test suites Tia selected for this build, across all groups. `0` on a nothing-impacted build; also `0` on a seed run, which carries no suite names because it runs everything. |

The three `...Target` booleans are independent causes, not alternatives: any can be `true` without the others and more than one can be `true` at once, so check all three to explain a missed target.

`select-tests` also previews the grouping without persisting anything, whenever `tiaDistributedGroupCount` / `tiaDistributedTargetRunTime` is set. Both it and the plan step's console summary report **two durations**, and they answer different questions:

```
Estimated total run time (serial equivalent): 497ms (75%)
Estimated savings: 167ms (25%)
Estimated distributed run time: 275ms (41%) - the heaviest group, which is what the build waits for.

Distributed run grouping preview (not persisted):
  Groups: 2, average 248ms per group, heaviest 275ms
  Target: none (static group count)
```

The **distributed run time** is the heaviest group - what you actually wait for, and what a job timeout has to accommodate. It already includes that group's suites' share of the mapping overhead, since the balancer weights suites with it.

The **serial equivalent** is what the same selection would cost on one host. It is deliberately the same number a non-distributed build prints, because that is the figure Tia records and computes savings from in both modes, so savings keep meaning "time saved by not running unimpacted tests" rather than quietly absorbing the parallelism your CI system provided. If that line and the savings look unchanged by distributing, that is why - only the wall clock moves. The same two figures appear again in the history table after the run, as its `Duration` and `Wall clock` columns.

One caveat on the distributed estimate: each runner also re-pays the fixed per-JVM start-up cost that a single-host run pays once, and Tia's stored stats do not separate that fixed cost from the per-suite capture cost, so it cannot be added here. Treat the figure as a floor.

### Which time is which

Tia reports several different durations across its commands. They divide into exactly two kinds, and the same two words are used for them everywhere:

- **Serial equivalent** - what the selection costs on one host. Savings are computed from this in *both* modes, so "savings" keeps meaning *time saved by not running unimpacted tests* and never silently absorbs the parallelism your CI system provided.
- **Wall clock** - what the build actually waited for: the heaviest group, since the groups run in parallel.

| Command | Figure | Which kind | Notes |
|---|---|---|---|
| `select-tests` | `Estimated total run time (serial equivalent)` | serial | Predicted from stored per-suite averages. Identical in both modes. |
| `select-tests` | `Estimated savings` | serial | Baseline minus the serial estimate. |
| `select-tests` | `Estimated distributed run time` | wall clock | The heaviest group. A floor - see the caveat above. |
| `status` | `Average run time (serial equivalent)` | serial | Averaged over **every** run. The `(serial equivalent)` qualifier appears only once the project has distributed builds. |
| `status` | `Average distributed run time` | wall clock | Averaged over **distributed runs only**, which is why the run count is stated. |
| `status` | `All tests run time` | serial | The full-suite baseline every percentage above is measured against. |
| `history` | `Duration` | serial | Per run. What `Savings` is computed from. |
| `history` | `Wall clock` | wall clock | Per run. Blank/`-` for a single-host run. |
| `dist-status` | `Estimated` | serial | The planner's weight for that one group. |
| `dist-status` | `Actual` | measured | That group's measured test-execution time this run. |
| `dist-status` | `Elapsed` | measured | Wall clock since the group was **claimed** - on Maven that is `prepare-agent` at `initialize`, so it includes compilation and the rest of the build, not just tests. |

The one that catches people out: **`Savings` never includes the speed-up from distributing.** It is measured against the serial equivalent by design. The parallel gain is the gap between `Duration` and `Wall clock` in the history, not something folded into the savings figure.

Read `groupCount` to size your job matrix - for example `jq -c '[range(.groupCount)]' target/tia/tia-run-plan.json`.

Expect `groupCount` to vary between builds: a one-line change selects fewer tests and needs fewer runners than a dependency bump does. That is the feature working, not instability. Note also that the **first** distributed build on a branch is a seed run - one group with everything, ignoring your configured group count, because there is no mapping yet to split. `seedRun: true` says so.

### Complete - close out this runner's group

Run once per runner job, after its tests, **whatever the result**. Completes the group, and if this runner finished last, seals the build.

**Maven, Junit5 and Git**
```
mvn tia-junit5-git:dist-complete
```

**Gradle, Spock and Git**
```
(nothing - the tia-dist-complete task is wired as a finalizer of the test task automatically)
```

**On Maven you invoke it yourself; it is not wired into the test run.** The goal has no default lifecycle phase, so it never runs as part of `mvn test` or `mvn verify` - give it its own pipeline step after the test command, and make that step run whatever the test result (`if: always()` on GitHub Actions, or your CI system's equivalent). On Gradle there is nothing to do: `tia-dist-complete` is registered as a `finalizedBy` finalizer of the test task, and Gradle runs finalizers even when the task they finalize fails.

**Do not try to automate the Maven side by binding the goal to a phase.** An `<executions>` binding in your pom looks like the tidier option, but it reintroduces the exact problem the separate step exists to avoid: a phase-bound execution is part of the same lifecycle a test failure aborts, so it would not run on the runners whose groups most need closing. The goal's lack of a default phase is deliberate for that reason.

**It takes no arguments of its own.** Everything specific to the run - the run id, the runner key, the claimed group number, and the mapping/stats/history update flags - is read back out of `<tiaBuildDir>/fork.properties`, the same handoff file `prepare-agent` wrote for the forked test JVM. The goal never re-derives those values, and deliberately ignores its own `-D` equivalents for them: a runner key it derived for itself would carry a different process id, match no claimed row, and leave the group open forever, and the seal has to use the flags the runner actually ran under rather than whatever this invocation was passed. Your own configuration only has to supply `tiaEnabled`, `tiaBuildDir` and the database connection settings, which normally already live in your pom. The connection settings are not optional, though - if `tiaDBUrl` resolves to a private embedded H2 rather than the shared datastore the runners coordinate through, the goal fails with a message saying so, rather than silently finding no claimed row and exiting as if the group were already done.

It is safe to run unconditionally: on a build that was not distributed - no `fork.properties` file, or one carrying no distributed handoff - there is nothing to complete, and it logs that and exits successfully.

**`tiaEnabled` is the one setting that must resolve the same way for this goal as it did for the test run.** It is deliberately *not* taken from `fork.properties`, unlike everything else above - the goal checks its own `tiaEnabled` before it opens the handoff file at all, so that a build with Tia switched off never acts on a stale file left over from an earlier distributed build, and never fails on database settings it was never going to use. The consequence is a trap worth knowing about: if this goal resolves `tiaEnabled` to false, it logs that Tia is disabled, **exits successfully**, and does nothing. The claimed group stays `CLAIMED`, the barrier never opens, no runner seals the build, and the whole run's mapping, stats and history work is discarded - with a green pipeline and nothing but an INFO line saying why.

Two ways to land in that state:

- Passing `-DtiaEnabled=false` on the completion step while the runners ran with it true.
- Driving `tiaEnabled` from the command line only. It has no default value, so an unset `tiaEnabled` is false: if your runners use `mvn verify -DtiaEnabled=true` and your pom has no `<tiaEnabled>`, then a bare `mvn tia-junit5-git:dist-complete` silently no-ops.

So: if `tiaEnabled` lives in your pom (as in the [getting started](#getting-started) configuration), there is nothing to do - this goal picks it up like every other goal does. If you pass it on the command line for the test run, pass the same value on the completion step. When a run is stuck `OPEN` with a group still `CLAIMED`, this is the first thing to check - [`dist-status`](#status---inspect-a-run-in-flight) shows both.

### Status - inspect a run in flight

Prints the state of a distributed run: the run itself, every group in its plan, and the runner that claimed each one. Read-only - it claims, completes, seals and clears nothing - so it is safe to run against a build whose runners are still going, from your own machine or from a CI step watching the fan-out.

**Maven, Junit5 and Git**
```
mvn tia-junit5-git:dist-status [-DtiaRunId=$CI_RUN_ID] [-DtiaDistStatusSuites=true]
```

**Gradle, Spock and Git**
```
gradle tia-dist-status [--runId=$CI_RUN_ID] [--suites]
```

With no run id it reports the most recently planned run, which is normally the only one - each plan write clears the previous run's rows. It only needs `tiaEnabled` and the same database connection settings the runners coordinate through; pointed at a private embedded database it simply finds no run planned, and says so. It never fails the build, so a pipeline can run it unconditionally.

```
Distributed run 'gh-1284471'
  Branch:     main
  Commit:     51e8970a3f2b
  Status:     OPEN - 1 of 3 group(s) completed
  Planned:    2026-08-17 20:48:29 (10m 5s ago)
  Target:     2m
  Estimated:  4m 33s of test time across 3 group(s)
  Sealed:     not sealed (open for 10m 5s)

Groups:

Group | Status    | Runner   | Assigned | Observed | Ran | Failed | Estimated | Actual | Elapsed
------+-----------+----------+----------+----------+-----+--------+-----------+--------+--------
0     | COMPLETED | ci-job-1 | 2        | 2        | 2   | 0      | 1m 30s    | 1m 28s | 1m 30s
1     | CLAIMED   | ci-job-2 | 1        | 0        | 0   | 0      | 1m 31s    | 40s    | 9m 58s
2     | PENDING   | -        | 2        | -        | -   | -      | 1m 32s    | -      | -

  Assigned = suites the plan gave this group; Observed = suites its runner saw finish or skip.
  A group completes once Observed reaches Assigned, and the run seals once every group completes.
  Actual = measured test-execution time; Elapsed = wall clock since the group was claimed.

This run is not sealed yet. Outstanding:
  Group 1: CLAIMED by 'ci-job-2' (running for 9m 58s) - observed 0 of 1 assigned suite(s).
  Group 2: PENDING - no runner has claimed it. The pipeline fanned out fewer jobs than
    the plan's 3 group(s), so nothing will ever complete this one and the run cannot seal.
```

The two columns to read together are **Assigned** and **Observed**: a group completes when Observed reaches Assigned, and the run seals when every group completes, so that comparison is what a run stuck in `OPEN` comes down to. The outstanding block names each group still standing in the way, and calls out the two states worth acting on:

- **A `PENDING` group** means no runner ever claimed it - your matrix fanned out fewer jobs than the plan has groups. Nothing will complete it on its own. Size the matrix from `groupCount` in `tia-run-plan.json`.
- **Every group `COMPLETED` but the run still `OPEN`** means the barrier was reached and the seal itself failed. The next build's plan step will clear these rows and redo the work.

A seed run's single group shows `all` in the Assigned column rather than `0`: its plan carries no suite names, because there is no mapping yet to draw them from, while its runner executes every suite it discovers. Its Observed column shows `n/a` for the same reason - with nothing assigned, the completeness guard is satisfied without that count ever moving, so it carries no information. Read the Ran column instead.

### Full example (GitHub Actions)

```yaml
plan:
  runs-on: ubuntu-latest
  outputs:
    groups: ${{ steps.plan.outputs.groups }}
  steps:
    - run: >
        mvn tia-junit5-git:dist-plan
        -DtiaDistributed=true
        -DtiaRunId=${{ github.run_id }}
        -DtiaDistributedTargetRunTime=1500000
        -DtiaDistributedMaxGroups=10
    - id: plan
      run: echo "groups=$(jq -c '[range(.groupCount)]' target/tia/tia-run-plan.json)" >> $GITHUB_OUTPUT

test:
  needs: plan
  strategy:
    fail-fast: false
    matrix:
      group: ${{ fromJson(needs.plan.outputs.groups) }}
  steps:
    - name: Run this runner's group
      run: >
        mvn verify
        -DtiaDistributed=true
        -DtiaRunId=${{ github.run_id }}
        -DtiaDistributedRunnerKey=${{ matrix.group }}

    - name: Complete this runner's group
      if: always()          # <- the whole point: runs even when the tests failed
      run: mvn tia-junit5-git:dist-complete
```

Two details worth copying: `fail-fast: false`, so one failing group does not cancel the others and strand their claims, and a `tiaDistributedRunnerKey` taken from the matrix index, so a retried job resumes its own group rather than claiming a second one.

## What is Tia
Tia ia a free test impact analysis library. It analyses changes made to source code and automatically selects the tests to run for your test runner. It's designed as a developer productivity tool to increase the efficiency of developers by cutting down the time required to get feedback on changes. 

Tia has been designed to be unintrusive in your day-to-day work flow. Once it's setup and configured, it will automatically hook into your build automation tool test system to select the tests, and then update the mapping and record the statistics at the completion of the test run.

Through the tracking of statistics, Tia can generate reports that show how successful each test suite is, and how long it takes to run. This information can be useful in tracking poorly written or problematic tests that need attention to improve the overall health of the test suites and your builds.

## How Does Tia Work
Tia collects and stores a mapping of methods that are executed for each of your test suites. 

Tia uses Jacoco to collect the source code coverage for each test suite and store it in the DB for mapping. Tia uses an H2 DB for the data store. By default this is an embedded file-on-disk DB (`tiaDBFilePath` / `dbFilePath`). Tia can also connect to a shared H2 running in [server (TCP) mode](#using-a-shared-h2-server) by setting `tiaDBUrl` / `dbUrl` instead - see below. Whichever mode you use, Tia isolates each VCS branch's mapping into its own schema within that one database, derived automatically from the current branch - see [Branch isolation](#branch-isolation-schema-per-branch) below.

The first time Tia runs it needs to 'seed' the mapping DB by running all test suites and collecting the source code mapping for each test suite. It will also store the VCS commit value for that version of the test suite and source code mapping. Each subsequent test run then analyses the changes made and selects only the tests to run that are impacted by the source code changes. All other tests are ignored.

Typically you will want a 'primary' automated build that is configured to run Tia on each commit/submit/check-in. Only this build should be configured to update the test suite to source code mapping in the DB (tiaUpdateDBMapping=true).
Developers using Tia on their local workspace should configure Tia to analyse local changes only (tiaUpdateDBMapping=false and tiaCheckLocalChanges=true).

**Note:** The build machine(s) that are designated to be the 'primary' (which update the test suite to source code mapping) need to run the tests suites **sequentially**. This is important to allow Tia to correctly associate the source code coverage with each test suite.  

## Branch isolation (schema per branch)
Tia isolates each VCS branch's mapping, statistics and library-tracking data from every other branch. Rather than a separate database per branch, Tia connects to a single, fixed database and stores each branch's data in its own schema within it, named `tia_<branch>` (the branch name lowercased, with any character outside `[a-z0-9_]` replaced by `_` - e.g. `feature/foo` becomes `tia_feature_foo`). The schema is derived automatically from the branch reported by your VCS - there is nothing to configure, and no `{branch}` token in `tiaDBUrl` / `dbUrl`. The schema is created on first use and selected on every connection Tia opens.

This applies the same way to both datastores:
- **H2** connects to one fixed database - the embedded `tiadb` file under `tiaDBFilePath` / `dbFilePath`, or the server URL given in `tiaDBUrl` / `dbUrl` - and creates/selects a schema in it per branch.
- **Postgres** auto-creates the database named in `tiaDBUrl` / `dbUrl` when it does not yet exist, the same way H2 does, provided the connecting role holds the `CREATEDB` privilege. If you would rather not grant `CREATEDB`, pre-create the database yourself; then Tia needs only `CREATE` on that database to make the per-branch schema (e.g. `GRANT CREATE ON DATABASE tiadb TO tia;`). If the database is missing and the role lacks `CREATEDB`, Tia fails with a clear message (including the driver's own error) telling you to create the database first or grant `CREATEDB`. See [Using a different database](#using-a-different-database) below.

## Using a shared H2 server
By default Tia stores its data in an embedded H2 file on the machine running the build. If you want several builds (for example a primary CI build plus developer/local builds) to share one Tia database, you can point Tia at an H2 instance running in [server (TCP) mode](https://www.h2database.com/html/tutorial.html#using_server) instead.

Set `tiaDBUrl` / `dbUrl` (and optionally `tiaDBUser` / `tiaDBPassword`) to the server's JDBC URL. When set, the embedded `tiaDBFilePath` / `dbFilePath` is ignored.

Maven - in the `tia-*-maven-plugin` `<configuration>` (or as `${tiaDBUrl}` etc. properties):
```xml
<tiaDBUrl>jdbc:h2:tcp://h2host:9092/tiadb;DB_CLOSE_DELAY=-1</tiaDBUrl>
<tiaDBUser>tia</tiaDBUser>
<tiaDBPassword>secret</tiaDBPassword>
```

Setting these on the Tia plugin is enough for both the test-selection step and the test run itself: the Tia plugin forwards the connection (and the other properties the forked test JVM needs) to its javaagent automatically, so you do **not** need to repeat them in the Surefire `systemPropertyVariables`. (The Gradle plugin already worked this way; earlier Maven releases required manual `systemPropertyVariables` forwarding and would otherwise fall back to embedded mode, failing to open a local file such as `/tiadb.mv.db`.)

Gradle:
```groovy
tia {
    dbUrl = 'jdbc:h2:tcp://h2host:9092/tiadb;DB_CLOSE_DELAY=-1'
    dbUser = 'tia'
    dbPassword = 'secret'
}
```

### Keeping the password out of checked-in config
Putting `tiaDBPassword` / `dbPassword` directly in your POM or `build.gradle` means committing a secret to source control. To avoid that, leave the password (and optionally the user) unset in the build config and let Tia fall back to environment variables: when the configured value is blank, Tia reads `TIA_DB_PASSWORD` and `TIA_DB_USER` from the environment. CI sets those as secrets and the repo carries no credential.

```groovy
tia {
    dbUrl = 'jdbc:h2:tcp://h2host:9092/tiadb;DB_CLOSE_DELAY=-1'
    // dbUser / dbPassword omitted - taken from TIA_DB_USER / TIA_DB_PASSWORD
}
```

The build tools also support their own indirection if you prefer it: Maven resolves `<tiaDBPassword>${env.TIA_DB_PASSWORD}</tiaDBPassword>` or a property from `~/.m2/settings.xml` (which supports [encrypted passwords](https://maven.apache.org/guides/mini/guide-encryption.html)); Gradle can read from `~/.gradle/gradle.properties` or a `-P` property. Tia never logs the password (only the JDBC URL), so avoid embedding credentials directly in `dbUrl`.

The environment fallback only kicks in when the password is **not configured at all**. If your database genuinely uses an empty password, set it explicitly - `dbPassword = ''` (Gradle) or `<tiaDBPassword></tiaDBPassword>` (Maven) - and Tia uses the empty value verbatim rather than falling back to `TIA_DB_PASSWORD`.

Things to know when using server mode:
- **Start the server with `-ifNotExists`.** Tia creates the database (on first use) and its per-branch schema automatically. An H2 TCP server refuses to create a database for a remote client unless it was started with the `-ifNotExists` flag, so the first Tia run will fail without it.
- **Append `;DB_CLOSE_DELAY=-1` to the URL.** Tia opens a short-lived connection per database operation, and by default an H2 server closes a database when its last connection closes - so without this setting every Tia operation pays a full database close and reopen on the server. The symptom is a slow `select-tests` step whose time is spent blocked opening/closing JDBC connections rather than running queries (on one large reference project this was the difference between 28s and 3.5s). `DB_CLOSE_DELAY=-1` keeps the database open between connections. See the [Wiki](WIKI.md) for why this works and what it trades off.
- **The URL is used exactly as given, and is the same on every branch.** Tia does not rewrite the server URL per branch. Branch isolation is handled by the per-branch schema (see [Branch isolation](#branch-isolation-schema-per-branch) above), not by a per-branch database name.
- **Keep a single mapping writer.** As with embedded mode, only the primary build should set `tiaUpdateDBMapping=true`. All other clients should run with `tiaUpdateDBMapping=false` (mapping is owned by one writer). The other clients only update statistics.
- **Statistics are best-effort under concurrency.** Statistics counters (run counts, averages) are read-modify-write and are not locked across clients, so when multiple clients update statistics against the same database at the same time, some statistic increments can be lost. Tia treats statistics as advisory; the mapping (owned by the single writer) is unaffected. (See also the multi-fork persistence note in the [Wiki](WIKI.md).)

## Using a different database

Tia's datastore is pluggable across JDBC SQL vendors. Which vendor Tia connects to is inferred from the JDBC URL scheme in `tiaDBUrl` / `dbUrl`:

- unset -> embedded H2 (the default), stored at `tiaDBFilePath` / `dbFilePath`.
- `jdbc:h2:...` -> H2 (embedded or server mode - see [Using a shared H2 server](#using-a-shared-h2-server) above).
- `jdbc:postgresql://host:port/db` -> Postgres.

If you need to be explicit (or the URL scheme alone isn't enough), set `tiaDBDialect` / `dbDialect` to `h2` or `postgres` to override the inference.

### Postgres example

Maven - in the `tia-*-maven-plugin` `<configuration>` (or as `${tiaDBUrl}` etc. properties):
```xml
<tiaDBUrl>jdbc:postgresql://pghost:5432/tiadb</tiaDBUrl>
<tiaDBUser>tia</tiaDBUser>
<tiaDBPassword>secret</tiaDBPassword>
```

Gradle:
```groovy
tia {
    dbUrl = 'jdbc:postgresql://pghost:5432/tiadb'
    dbUser = 'tia'
    dbPassword = 'secret'
}
```

`tiaDBDialect` / `dbDialect` can be left unset here - the `jdbc:postgresql:` scheme is enough for Tia to infer `postgres`.

**Note:** Tia auto-creates the database named in `tiaDBUrl` (`tiadb` above) when it is missing, if the connecting role holds `CREATEDB`. Otherwise pre-create it and grant the role `CREATE` on it (e.g. `GRANT CREATE ON DATABASE tiadb TO tia;`) - Tia then creates the per-branch schema inside it (see [Branch isolation](#branch-isolation-schema-per-branch)). If the database is missing and the role has neither the database nor `CREATEDB`, Tia fails with a message telling you to create the database or grant `CREATEDB`.

**Note:** unlike H2 server mode, Tia does not fall back to a `TIA_DB_USER` / `TIA_DB_PASSWORD` environment variable for non-H2 vendors - that fallback is specific to `H2ConnectionSettings`. To keep a non-H2 password out of checked-in config, use the build tool's own indirection instead: Maven `${env.TIA_DB_PASSWORD}` or an encrypted `~/.m2/settings.xml` property, or Gradle `~/.gradle/gradle.properties` / a `-P` property (see [Keeping the password out of checked-in config](#keeping-the-password-out-of-checked-in-config) above).

### Declaring the JDBC driver in two places

Tia only bundles the H2 driver. For any other vendor (currently Postgres) you must add that vendor's JDBC driver as a dependency in **two** places, because Tia runs on two different classpaths:

1. **A test-scope dependency in your project.** The forked test JVM is where Tia's test-runner listener persists the mapping and stats at the end of a test run, so the driver needs to be on that JVM's classpath.
2. **A dependency of the Tia plugin itself.** Everything else - `select-tests`, reports, `reconcile`, and any other Tia goal/task you run from the build tool - runs on the build tool's own classpath (the Maven plugin's classpath, or the Gradle plugin's classpath), which is separate from your project's dependencies. Maven: add the driver as a `<dependency>` inside the Tia `<plugin>` block. Gradle: add the driver as a `classpath` dependency alongside the Tia plugin.

If either place is missing the driver, Tia throws an actionable error naming the vendor and both locations rather than failing with an opaque `No suitable driver found` from deep inside `DriverManager`.

Maven - `pom.xml`:
```xml
<dependencies>
    <!-- 1. Test-scope: for the forked test JVM, which persists the mapping/stats. -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <version>42.7.4</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.tiatesting</groupId>
            <artifactId>tia-junit5-git-maven-plugin</artifactId>
            <version>0.1.18</version>
            <!-- 2. Plugin dependency: for the build-tool side (select-tests, reports, reconcile). -->
            <dependencies>
                <dependency>
                    <groupId>org.postgresql</groupId>
                    <artifactId>postgresql</artifactId>
                    <version>42.7.4</version>
                </dependency>
            </dependencies>
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
                <tiaDBUrl>jdbc:postgresql://pghost:5432/tiadb</tiaDBUrl>
                <tiaDBUser>tia</tiaDBUser>
                <tiaDBPassword>secret</tiaDBPassword>
            </configuration>
        </plugin>
    </plugins>
</build>
```

Gradle - `build.gradle`:
```groovy
buildscript {
    dependencies {
        classpath 'org.tiatesting:tia-spock-git-gradle:0.1.18'
        // 2. Plugin classpath dependency: for the build-tool side (select-tests, reports, reconcile).
        classpath 'org.postgresql:postgresql:42.7.4'
    }
}

dependencies {
    // 1. Test-scope: for the forked test JVM, which persists the mapping/stats.
    testImplementation 'org.postgresql:postgresql:42.7.4'
}

tia {
    dbUrl = 'jdbc:postgresql://pghost:5432/tiadb'
    dbUser = 'tia'
    dbPassword = 'secret'
}
```

See the [pluggable datastore](wiki/pluggable-datastore.md) Wiki chapter for the architecture behind this (the `SqlDialect` / `ConnectionProvider` split, the registry, and why the two-classpath model exists) and for what a further vendor (e.g. MySQL) would need to add.

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
