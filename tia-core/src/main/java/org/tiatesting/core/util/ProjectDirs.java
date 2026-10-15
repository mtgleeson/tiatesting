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

    /**
     * Resolve the configured root of the source project - the build whose resolved dependencies turn
     * the tracked library coordinates into jars - the same way as {@link #resolve}: a relative path is
     * taken from the build tool's project directory, never the JVM's working directory (for a Gradle
     * daemon, the daemon's own directory). Unset means the project being analysed, itself resolved by
     * {@link #resolve}.
     *
     * @param buildProjectDir the build tool's project directory
     * @param configuredProjectDir the configured {@code tiaProjectDir} / {@code projectDir}, or null
     * @param configuredSourceProjectDir the configured {@code tiaSourceProjectDir} /
     *                                   {@code sourceProjectDir}, or null
     * @return the source project root, absolute
     */
    public static File resolveSourceProject(final File buildProjectDir, final String configuredProjectDir,
                                            final String configuredSourceProjectDir) {
        if (configuredSourceProjectDir == null || configuredSourceProjectDir.trim().isEmpty()) {
            return resolve(buildProjectDir, configuredProjectDir);
        }
        return resolve(buildProjectDir, configuredSourceProjectDir);
    }
}
