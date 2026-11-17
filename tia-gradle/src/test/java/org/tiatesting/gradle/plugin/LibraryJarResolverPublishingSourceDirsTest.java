package org.tiatesting.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.plugins.GroovyPlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link LibraryJarResolver#readPublishingSourceDirs(Project)}, the source roots the
 * publish stamp diffs over: the publishing project's own main source dirs, limited to the ones
 * that exist on disk.
 */
class LibraryJarResolverPublishingSourceDirsTest {

    @TempDir
    File projectDir;

    @Test
    void returnsTheExistingMainSourceDirsOnly() throws Exception {
        // given a Groovy project with src/main/java on disk but no src/main/groovy
        File javaDir = new File(projectDir, "src/main/java");
        assertTrue(javaDir.mkdirs());
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply(GroovyPlugin.class);

        // when
        List<String> dirs = LibraryJarResolver.readPublishingSourceDirs(project);

        // then only the existing java dir is returned, canonicalised
        assertEquals(Collections.singletonList(javaDir.getCanonicalPath()), dirs);
    }

    @Test
    void leavesOutSourceDirsUnderTheBuildDirectory() throws Exception {
        // given a Java project with a versioned source dir and a generated one in the build dir
        File javaDir = new File(projectDir, "src/main/java");
        File generatedDir = new File(projectDir, "build/generated/sources/main");
        assertTrue(javaDir.mkdirs());
        assertTrue(generatedDir.mkdirs());
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply(JavaPlugin.class);
        project.getExtensions().getByType(SourceSetContainer.class).getByName("main").getJava().srcDir(generatedDir);

        // when
        List<String> dirs = LibraryJarResolver.readPublishingSourceDirs(project);

        // then only the versioned dir is returned, so the publish's source line does not depend
        // on what the build has generated
        assertEquals(Collections.singletonList(javaDir.getCanonicalPath()), dirs);
    }

    @Test
    void returnsNothingWhenNoMainSourceDirExists() {
        // given a Java project with no source dirs on disk (e.g. a resources-only library)
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply(JavaPlugin.class);

        // when
        List<String> dirs = LibraryJarResolver.readPublishingSourceDirs(project);

        // then nothing is returned, so the stamper falls back to the tracked library's recorded dirs
        assertTrue(dirs.isEmpty());
    }

    @Test
    void returnsNothingForAProjectWithoutSourceSets() {
        // given a project with no Java plugin applied
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();

        // when
        List<String> dirs = LibraryJarResolver.readPublishingSourceDirs(project);

        // then
        assertTrue(dirs.isEmpty());
    }
}
