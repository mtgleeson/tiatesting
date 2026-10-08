package org.tiatesting.core.util;

import java.io.File;

/**
 * Resolves the configured project root ({@code tiaProjectDir} / {@code projectDir}) the same way for
 * every build tool.
 */
public final class ProjectDirs {

    /**
     * Static utility; not instantiable.
     */
    private ProjectDirs() {
    }

    /**
     * Resolve the configured root of the project being analysed against the build tool's own project
     * directory (the Maven module's or the Gradle project's): unset means that directory, an absolute
     * path is used as is, and a relative one is taken from that directory - never the JVM's working
     * directory, which a Gradle daemon or a Maven build run from a reactor root does not share. The
     * VCS is read from the result, and the configured source and test directories are resolved
     * against it, as the class directories are in the test JVM.
     *
     * @param buildProjectDir the build tool's project directory
     * @param configured the configured {@code tiaProjectDir} / {@code projectDir}, or null
     * @return the project root, absolute
     */
    public static File resolve(final File buildProjectDir, final String configured) {
        if (configured == null || configured.trim().isEmpty()) {
            return buildProjectDir.getAbsoluteFile();
        }
        File dir = new File(configured);
        return dir.isAbsolute() ? dir : new File(buildProjectDir.getAbsoluteFile(), configured);
    }
}
