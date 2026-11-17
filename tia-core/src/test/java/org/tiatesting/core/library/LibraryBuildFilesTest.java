package org.tiatesting.core.library;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link LibraryBuildFiles}: which changed paths count as build files for the
 * library build-file trigger.
 */
class LibraryBuildFilesTest {

    @Test
    void recognisesMavenAndGradleBuildFiles() {
        // given the build files a dependency version can live in
        String[] buildFiles = {"app/pom.xml", "pom.xml", "app/build.gradle", "app/build.gradle.kts",
                "settings.gradle", "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml"};

        // when each is checked
        // then every one is a build file
        for (String path : buildFiles) {
            assertTrue(LibraryBuildFiles.isBuildFile(path), path);
        }
    }

    @Test
    void ignoresSourceAndLookalikeFiles() {
        // given source files and names that only resemble build files
        String[] others = {"app/src/main/java/Pom.java", "app/pom.xml.orig", "docs/build.gradle.md",
                "app/src/main/resources/application.properties", null};

        // when each is checked
        // then none is a build file
        for (String path : others) {
            assertFalse(LibraryBuildFiles.isBuildFile(path), String.valueOf(path));
        }
    }

    @Test
    void anyBuildFileLooksAcrossTheWholeChangeSet() {
        // given one change set with a build file among source files and one without
        // when each is checked
        boolean withBuildFile = LibraryBuildFiles.anyBuildFile(
                Arrays.asList("app/src/main/java/A.java", "app/pom.xml"));
        boolean withoutBuildFile = LibraryBuildFiles.anyBuildFile(
                Collections.singletonList("app/src/main/java/A.java"));

        // then only the first triggers, and a null set never does
        assertTrue(withBuildFile);
        assertFalse(withoutBuildFile);
        assertFalse(LibraryBuildFiles.anyBuildFile(null));
    }
}
