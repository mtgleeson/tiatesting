package org.tiatesting.core.library;

import java.util.Collection;
import java.util.Locale;

/**
 * Recognises the build files whose change can move a consumer onto another library build: a
 * declared dependency version, a version property, a version catalogue or a BOM import all live in
 * one of these. A changed build file is the cheap signal that makes test selection identify every
 * tracked library's held build even with nothing pending, so a consumer that moved to another
 * source line is switched at that run. See the source-lines section of the library publish-time
 * stamping chapter in {@code WIKI.md}.
 */
public final class LibraryBuildFiles {

    private LibraryBuildFiles() {
    }

    /**
     * Report whether any of the changed paths is a Maven or Gradle build file.
     *
     * @param changedPaths repo-relative, forward-slash paths of the files changed since the stored
     *                     commit; may be null
     * @return true when at least one is a build file
     */
    public static boolean anyBuildFile(final Collection<String> changedPaths) {
        if (changedPaths == null) {
            return false;
        }
        for (String path : changedPaths) {
            if (isBuildFile(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Report whether one path is a Maven or Gradle build file: {@code pom.xml}, a Gradle build or
     * settings script (Groovy or Kotlin), {@code gradle.properties}, or a version catalogue.
     *
     * @param path a repo-relative, forward-slash path; may be null
     * @return true when the file name is a build file
     */
    static boolean isBuildFile(final String path) {
        if (path == null) {
            return false;
        }
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.equals("pom.xml")
                || name.equals("build.gradle") || name.equals("build.gradle.kts")
                || name.equals("settings.gradle") || name.equals("settings.gradle.kts")
                || name.equals("gradle.properties")
                || name.endsWith(".versions.toml");
    }
}
