package org.tiatesting.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies the build service hands out one class loader per set of jars, that the loader shares
 * the plugin's types (parent-first), and that closing the service drops its loaders.
 */
class VcsProviderClassLoadersTest {

    @TempDir
    File tempDir;

    @Test
    void sameJarsShareOneLoaderThatSeesTheParentTypes() throws ClassNotFoundException {
        // given
        VcsProviderClassLoaders loaders = service();
        List<File> jars = Collections.singletonList(tempDir);
        ClassLoader parent = getClass().getClassLoader();

        // when
        ClassLoader first = loaders.get(jars, parent);
        ClassLoader second = loaders.get(jars, parent);

        // then
        assertSame(first, second);
        assertSame(VCSReader.class, first.loadClass(VCSReader.class.getName()));
    }

    @Test
    void closingTheServiceDropsItsLoaders() {
        // given
        VcsProviderClassLoaders loaders = service();
        List<File> jars = Collections.singletonList(tempDir);
        ClassLoader before = loaders.get(jars, getClass().getClassLoader());

        // when
        loaders.close();

        // then
        assertNotSame(before, loaders.get(jars, getClass().getClassLoader()));
    }

    /**
     * @return the build service, registered on a throwaway project
     */
    private VcsProviderClassLoaders service() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build();
        return project.getGradle().getSharedServices()
                .registerIfAbsent(VcsProviderClassLoaders.NAME, VcsProviderClassLoaders.class, spec -> { })
                .get();
    }
}
