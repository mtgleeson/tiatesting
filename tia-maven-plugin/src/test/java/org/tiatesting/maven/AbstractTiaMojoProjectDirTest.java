package org.tiatesting.maven;

import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@code tiaProjectDir} - the base the source and test directories are resolved against -
 * is taken from the module's base directory, never Maven's working directory.
 */
class AbstractTiaMojoProjectDirTest {

    /**
     * @param moduleDir the module's base directory
     * @param tiaProjectDir the configured {@code tiaProjectDir}, or null
     * @return a mojo for a module at the given directory
     */
    private static SelectTestsMojo mojo(final File moduleDir, final String tiaProjectDir) {
        MavenProject project = new MavenProject(new Model());
        project.setFile(new File(moduleDir, "pom.xml"));
        SelectTestsMojo mojo = new SelectTestsMojo() {
            @Override
            public MavenProject getProject() {
                return project;
            }
        };
        mojo.tiaProjectDir = tiaProjectDir;
        return mojo;
    }

    @Test
    void anUnsetProjectDirIsTheModulesDirectory(@TempDir File moduleDir) {
        // given
        SelectTestsMojo mojo = mojo(moduleDir, null);

        // when
        File resolved = mojo.resolveTiaProjectDir();

        // then
        assertEquals(moduleDir, resolved);
    }

    @Test
    void aRelativeProjectDirIsTakenFromTheModulesDirectory(@TempDir File moduleDir) {
        // given
        SelectTestsMojo mojo = mojo(moduleDir, "..");

        // when
        File resolved = mojo.resolveTiaProjectDir();

        // then
        assertEquals(new File(moduleDir, ".."), resolved);
    }

    @Test
    void anAbsoluteProjectDirIsUsedAsIs(@TempDir File moduleDir, @TempDir File elsewhere) {
        // given
        SelectTestsMojo mojo = mojo(moduleDir, elsewhere.getAbsolutePath());

        // when
        File resolved = mojo.resolveTiaProjectDir();

        // then
        assertEquals(elsewhere, resolved);
    }

    @Test
    void aRelativeSourceProjectDirIsTakenFromTheModulesDirectory(@TempDir File moduleDir) {
        // given - Maven's working directory is the reactor root, not the module's
        SelectTestsMojo mojo = mojo(moduleDir, null);
        mojo.tiaSourceProjectDir = "../app";

        // when
        String resolved = mojo.getTiaSourceProjectDir();

        // then
        assertEquals(new File(moduleDir, "../app").getPath(), resolved);
    }

    @Test
    void anUnsetSourceProjectDirIsTheResolvedProjectDir(@TempDir File moduleDir) {
        // given
        SelectTestsMojo mojo = mojo(moduleDir, "..");

        // when
        String resolved = mojo.getTiaSourceProjectDir();

        // then
        assertEquals(new File(moduleDir, "..").getPath(), resolved);
    }
}
