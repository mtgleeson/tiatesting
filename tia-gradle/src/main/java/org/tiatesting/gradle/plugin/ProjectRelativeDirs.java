package org.tiatesting.gradle.plugin;

import org.tiatesting.core.util.StringUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Resolves the configured source and test directories ({@code sourceFilesDirs},
 * {@code testFilesDirs}) against the Gradle project directory before they reach
 * {@code TestSelector}.
 *
 * <p>{@code TestSelector} resolves a relative directory against the JVM's working directory. That
 * was the project directory while selection ran in the forked test JVM, but selection now runs in
 * the Gradle daemon, whose working directory is wherever the daemon was first started - often
 * another project. A directory resolved there matches none of the changed files, so nothing is
 * selected and every suite is skipped. Handing {@code TestSelector} absolute paths keeps the
 * daemon's working directory out of it.
 */
final class ProjectRelativeDirs {

    /**
     * Static utility; not instantiable.
     */
    private ProjectRelativeDirs() {
    }

    /**
     * Split a comma-separated directory list and resolve each entry against the project directory.
     * An entry is relative when it exists under the project directory (a leading {@code /} is
     * allowed, as the README's examples use); that resolved path is used. Any other entry is kept as
     * configured, for {@code TestSelector} to treat as an absolute path.
     *
     * @param projectDir the Gradle project directory relative entries are resolved against
     * @param csv the configured comma-separated directories; may be null
     * @return the resolved directories, or null when none is configured (as {@code TestSelector}
     *         expects)
     */
    static List<String> resolve(final File projectDir, final String csv) {
        if (csv == null) {
            return null;
        }
        List<String> entries = new ArrayList<>(Arrays.asList(csv.split(",")));
        StringUtil.sanitizeInputArray(entries);
        List<String> resolved = new ArrayList<>();
        for (String entry : entries) {
            File underProject = new File(projectDir, entry);
            resolved.add(underProject.exists() ? underProject.getAbsolutePath() : entry);
        }
        return resolved;
    }
}
