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
     * An entry that exists under the project directory (a leading {@code /} is allowed, as the
     * README's examples use) resolves there. Otherwise an absolute path that exists is kept as
     * configured, and anything else resolves under the project directory anyway - a directory the
     * build has not created yet must never fall back to the daemon's working directory, where
     * another project's directory of the same name may exist. {@code TestSelector} warns about an
     * entry that does not exist.
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
            File asConfigured = new File(entry);
            boolean keepAsConfigured = !underProject.exists() && asConfigured.isAbsolute() && asConfigured.exists();
            resolved.add(keepAsConfigured ? entry : underProject.getAbsolutePath());
        }
        return resolved;
    }
}
