package org.tiatesting.core.library;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link LibraryBuildFiles}: which changed paths count as build files of the source
 * project for the library build-file trigger.
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
    void anyProjectBuildFileLooksAcrossTheWholeChangeSet() {
        // given one change set with a build file among source files and one without
        // when each is checked with the project scope unknown
        boolean withBuildFile = LibraryBuildFiles.anyProjectBuildFile(
                Arrays.asList("app/src/main/java/A.java", "other/pom.xml"), null);
        boolean withoutBuildFile = LibraryBuildFiles.anyProjectBuildFile(
                Collections.singletonList("app/src/main/java/A.java"), null);

        // then any build file triggers, and a null set never does
        assertTrue(withBuildFile);
        assertFalse(withoutBuildFile);
        assertFalse(LibraryBuildFiles.anyProjectBuildFile(null, null));
    }

    @Test
    void countsTheProjectsOwnModuleAndParentBuildFiles() {
        // given a source project at services/app and build files in it, below it and above it
        String[] inScope = {"services/app/pom.xml", "services/app/core/build.gradle.kts",
                "services/pom.xml", "pom.xml", "settings.gradle", "gradle.properties",
                "gradle/libs.versions.toml", "services/gradle/libs.versions.toml"};

        // when each is checked against the project
        // then every one belongs to it
        for (String path : inScope) {
            assertTrue(LibraryBuildFiles.belongsToProject(path, "services/app"), path);
        }
    }

    @Test
    void ignoresAnotherProjectsBuildFiles() {
        // given build files of sibling projects, including one whose name only shares a prefix
        String[] outOfScope = {"services/other/pom.xml", "services/app-legacy/pom.xml",
                "tools/build.gradle", "services/other/gradle/libs.versions.toml"};

        // when each is checked against the project at services/app
        // then none belongs to it
        for (String path : outOfScope) {
            assertFalse(LibraryBuildFiles.belongsToProject(path, "services/app"), path);
        }
        assertFalse(LibraryBuildFiles.anyProjectBuildFile(Arrays.asList(outOfScope), "services/app"));
    }

    @Test
    void aProjectAtTheWorkspaceRootOwnsEveryBuildFile() {
        // given a source project at the workspace root
        // when a nested module's build file is checked
        // then it belongs to the project
        assertTrue(LibraryBuildFiles.belongsToProject("modules/core/pom.xml", ""));
    }

    @Test
    void expressesTheProjectRelativeToTheWorkspaceRoot() {
        // given a workspace root and source project directories inside, at and outside it
        // when each is relativized
        String nested = LibraryBuildFiles.projectPathInWorkspace("/workspace/", "/workspace/services/app/");
        String root = LibraryBuildFiles.projectPathInWorkspace("/workspace", "/workspace/services/..");
        String outside = LibraryBuildFiles.projectPathInWorkspace("/workspace", "/elsewhere/app");
        String sharedPrefix = LibraryBuildFiles.projectPathInWorkspace("/workspace", "/workspace-b/app");

        // then the nested one is relative, the root is empty, and the others are unknown
        assertEquals("services/app", nested);
        assertEquals("", root);
        assertNull(outside);
        assertNull(sharedPrefix);
        assertNull(LibraryBuildFiles.projectPathInWorkspace(null, "/workspace/app"));
        assertNull(LibraryBuildFiles.projectPathInWorkspace("/workspace", null));
    }
}
