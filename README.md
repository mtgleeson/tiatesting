# Tia Java Selective Testing Library
Tia is a free library used for selective testing with common test runners such as JUnit and Spock. Tia is distributed under the Apache 2.0 license.
Tia (pronounced Tee-ä, or Tina without the 'n') stands for test impact analysis. 

## Starting Points
- I found a bug → [Bug Report](https://github.com/mtgleeson/tiatesting/issues)
- I have an idea → [Feature Request](https://github.com/mtgleeson/tiatesting/issues)

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
        <version>0.1.2</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>        
        <plugin>
            <!-- Include the Maven plugin, used to select which tests to run and ignore the rest. -->
            <groupId>org.tiatesting</groupId>
            <artifactId>tia-junit-git-maven-plugin</artifactId>
            <version>0.1.2</version>
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
        classpath 'org.tiatesting:tia-spock-git-gradle:0.1.2'
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

## Configuration Options

## How Does Tia Work

## Supported Frameworks and Test Runners
