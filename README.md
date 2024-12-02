# Tia Java Selective Testing Library
Tia is a free library used for selective testing with common test runners such as JUnit and Spock. Tia is distributed under the Apache 2.0 license.
Tia (pronounced Tee-ä, or Tina without the 'n') stands for test impact analysis. 

## Starting Points
- [Getting started](#getting-started)
- [Usage](#usage)
- [Configuration Options](#configuration-options)
- [What is Tia](#what-is-tia)
- [How Does Tia Work](#how-does-tia-work)
- [Supported Build Automation Tools, VCS and Test Runners](#supported-build-automation-tools-vcs-and-test-runners)
- [Credits](#credits)
- [Additional resources and solutions](#additional-resources-and-solutions)
- [Bug Report](https://github.com/mtgleeson/tiatesting/issues)
- [Feature Request](https://github.com/mtgleeson/tiatesting/issues)

## Getting Started

### Maven, JUnit4 and Git
Include the following configuration in the project where you execute your tests. The following configuration is for Surefire, but Tia can be configured with Failsafe as well.
For the latest versions, see [tia-junit-git-maven-plugin](https://central.sonatype.com/search?q=g%3Aorg.tiatesting+a%3Atia-junit-git-maven-plugin&smo=true) and [tia-junit-git](https://central.sonatype.com/search?q=g%3Aorg.tiatesting+a%3Atia-junit-git&smo=true).

**Note:** If your tests live in the same project as your source code, you need to include and configure Jacoco to run in TCP server mode (see below). If your source code lives in a different project to your tests, you need to ensure your project that contains your source code is configured to run with Jacoco in TCP server mode. You can then omit the Jacoco configuration below from your test project pom.xml.

`pom.xml`
```xml
<properties>
    <tiaEnabled>true</tiaEnabled>
    <tiaUpdateDBMapping>true</tiaUpdateDBMapping>
    <tiaUpdateDBStats>true</tiaUpdateDBStats>
    <tiaCheckLocalChanges>false</tiaCheckLocalChanges>
    <tiaProjectDir>.</tiaProjectDir>
    <tiaClassFilesDirs>/target/classes</tiaClassFilesDirs>
    <tiaSourceFilesDirs>/src/main/java</tiaSourceFilesDirs>
    <tiaTestFilesDirs>/src/test/java</tiaTestFilesDirs>
    <tiaDBFilePath>/some/path</tiaDBFilePath>    
</properties>

<dependencies>
    <!-- tia-junit-git is needed for the Tia test listener used by Surefire/Failsafe. -->
    <dependency>
        <groupId>org.tiatesting</groupId>
        <artifactId>tia-junit-git</artifactId>
        <version>0.1.6-SNAPSHOT</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>        
        <plugin>
            <!-- Include the Maven plugin, used to select which tests to run and ignore the rest. -->
            <groupId>org.tiatesting</groupId>
            <artifactId>tia-junit-git-maven-plugin</artifactId>
            <version>0.1.6-SNAPSHOT</version>
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
                    <testClassesDir>${project.build.testOutputDirectory}</testClassesDir>
                </systemPropertyVariables>
                <properties>
                    <property>
                        <name>listener</name>
                        <value>org.tiatesting.junit.junit4.TiaTestingJunit4GitListener</value>
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

### Maven, JUnit and Perforce
Use the configuration documented above for [Maven, Junit and Git](https://github.com/mtgleeson/tiatesting/edit/main/README.md#getting-started), but replace `tia-junit-git` with `tia-junit-perforce` and `tia-junit-git-maven-plugin` with `tia-junit-perforce-maven-plugin`.

For the latest versions, see [tia-junit-perforce-maven-plugin](https://central.sonatype.com/search?q=g%3Aorg.tiatesting+a%3Atia-junit-perforce-maven-plugin&smo=true) and [tia-junit-perforce](https://central.sonatype.com/search?q=g%3Aorg.tiatesting+a%3Atia-junit-perforce&smo=true).

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
        classpath 'org.tiatesting:tia-spock-git-gradle:0.1.6-SNAPSHOT'
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

### General Information about the state of Tia
Example output:
```
Tia Info:
DB last updated: 03/05/2024 23:05:04 PDT
Test mapping valid for commit: 70d0624e4c2c6fab629d618f0ac406d5cbf009e3

Number of tests classes with mappings: 4
Number of source methods tracked for tests: 35
Number of runs: 1
Average run time: 459ms
Number of successful runs: 1 (100%)
Number of failed runs: 0 (0%)

Pending failed tests: 
none
```

**Maven, Junit4 and Git**
```
mvn tia-junit-git:info
```

**Maven, Junit4 and Perforce**
```
mvn tia-junit-perforce:info
```

**Gradle, Spock and Git**
```
gradle tia-info
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

**Maven, Junit4 and Git**
```
tia-junit-git:select-tests
```

Note: to see extra debugging including what test suites are being selected broken down by source methods:
```
tia-junit-git:select-tests -Dorg.slf4j.simpleLogger.log.org.tiatesting=debug
```

**Maven, Junit4 and Perforce**
```
tia-junit-perforce:select-tests
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

### Html Report
Generate a HTML report showing the current information about the Tia DB, the test suites and the source code.

*Example of the report summary page:*

<kbd><img width="529" border="1" alt="Screen Shot 2024-05-14 at 9 42 50 PM" src="https://github.com/mtgleeson/tiatesting/assets/1771850/e43aaf82-ee2d-4e66-ac32-b2fb73669fa9"></kbd>

*Example of the test suites index page:*

<kbd><img width="1120" alt="Screen Shot 2024-05-14 at 10 12 56 PM" src="https://github.com/mtgleeson/tiatesting/assets/1771850/a057dbfe-5277-48e7-af1f-e7b9e6834b4f"></kbd>

*Example of the source methods index page:*

<kbd><img width="992" alt="Screen Shot 2024-05-14 at 10 04 34 PM" src="https://github.com/mtgleeson/tiatesting/assets/1771850/d04b527c-f88d-452a-ab20-2d864d7a4424"></kbd>

**Maven, Junit4 and Git**
```
mvn tia-junit-git:html-report
```

**Maven, Junit4 and Perforce**
```
mvn tia-junit-perforce:html-report
```

**Gradle, Spock and Git**
```
gradle tia-html-report
```

### Text Report
Generate a basic text report showing the current information about the Tia DB, the test suites and the source code.

**Maven, Junit4 and Git**
```
mvn tia-junit-git:text-report
```

**Maven, Junit4 and Perforce**
```
mvn tia-junit-perforce:text-report
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
|tiaProjectDir|projectDir|<string>|The file path to the root folder of the project being analysed.||true|
|tiaClassFilesDirs|classFilesDirs|<string>|Comma seperated list of paths to the folders containing the classes of the source code (not the test source code). Required for Jacoco to analyse the test coverage.||true|
|tiaSourceFilesDirs|sourceFilesDirs|<string>|Comma seperated list of paths to the folders containing the source code of the project being analysed.||true|
|tiaTestFilesDirs|testFilesDirs|<string>|Comma seperated list of paths to the folders containing the source code of the test files for the project being analysed.||true|
|tiaDBFilePath|dbFilePath|<string>|The file path for the saved DB containing the previous analysis of the project.||true|
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

Tia uses Jacoco to collect the source code coverage for each test suite and store it in the DB for mapping. Tia uses an embedded H2 DB for the data store.

The first time Tia runs it needs to 'seed' the mapping DB by running all test suites and collecting the source code mapping for each test suite. It will also store the VCS commit value for that version of the test suite and source code mapping. Each subsequent test run then analyses the changes made and selects only the tests to run that are impacted by the source code changes. All other tests are ignored.

Typically you will want a 'primary' automated build that is configured to run Tia on each commit/submit/check-in. Only this build should be configured to update the test suite to source code mapping in the DB (tiaUpdateDBMapping=true). 
Developers using Tia on their local workspace should configure Tia to analyse local changes only (tiaUpdateDBMapping=false and tiaCheckLocalChanges=true). 

## Supported Build Automation Tools, VCS and Test Runners
### Maven 3

| |Git|Perforce|
|-|---|--------|
|Junit 4|✔|✔|
|Junit 5|x|x|
|Spock 2|x|x|

### Gradle

| |Git|Perforce|
|-|---|--------|
|Junit 4|x|x|
|Junit 5|x|x|
|Spock 2|✔|x|

## Credits
A shout out to the following libraries that Tia uses:
 - Jacoco
 - H2
 - J2HTML
 - ByteBuddy
 - JGit
 - P4Java
 - Java Diff Utils
 - Simple-Datatables

## Additional resources and solutions
 - [https://martinfowler.com/articles/rise-test-impact-analysis.html](https://martinfowler.com/articles/rise-test-impact-analysis.html)
 - [https://gradle.com/gradle-enterprise-solutions/predictive-test-selection/](https://gradle.com/gradle-enterprise-solutions/predictive-test-selection/)
 - [https://research.facebook.com/publications/predictive-test-selection/](https://research.facebook.com/publications/predictive-test-selection/)
 - [https://schibsted.com/blog/impact-testing-stop-waiting-tests-not-need-run/](https://schibsted.com/blog/impact-testing-stop-waiting-tests-not-need-run/)
 - [https://github.com/rpau/junit4git](https://github.com/rpau/junit4git)
 - [https://www.parasoft.com/products/parasoft-jtest/java-test-impact-analysis/](https://www.parasoft.com/products/parasoft-jtest/java-test-impact-analysis/)
 - [https://www.sealights.io/product/test-impact-analysis/#](https://www.sealights.io/product/test-impact-analysis/#) 
