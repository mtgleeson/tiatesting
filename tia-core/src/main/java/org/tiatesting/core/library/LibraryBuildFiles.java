package org.tiatesting.core.library;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Locale;

/**
 * Recognises the build files whose change can move a consumer onto another library build: a
 * declared dependency version, a version property, a version catalogue or a BOM import all live in
 * one of these. A changed build file is the cheap signal that makes test selection identify every
 * tracked library's held build even with nothing pending, so a consumer that moved to another
 * source line is switched at that run.
 *
 * <p>Only the source project's own build files count: those in its directory or below it (its
 * modules), and those in the directories above it up to the workspace root (parent POMs, root
 * settings scripts, {@code gradle.properties}, a {@code gradle/} version catalogue). A build file
 * of another project in the same repository or client view cannot change what this project
 * resolves. See the source-lines section of the library publish-time stamping chapter in
 * {@code WIKI.md}.
 */
public final class LibraryBuildFiles {

    private static final String VERSION_CATALOGUE_SUFFIX = ".versions.toml";
    private static final String GRADLE_DIR = "gradle";

    private LibraryBuildFiles() {
    }

    /**
     * Report whether any of the changed paths is a build file of the source project.
     *
     * @param changedPaths workspace-root-relative, forward-slash paths of the files changed since
     *                     the stored commit; may be null
     * @param projectPath the source project directory relative to the workspace root, forward-slash,
     *                    empty for the root itself; null when unknown, in which case every build
     *                    file counts
     * @return true when at least one changed path is a build file of the source project
     */
    public static boolean anyProjectBuildFile(final Collection<String> changedPaths, final String projectPath) {
        if (changedPaths == null) {
            return false;
        }
        for (String path : changedPaths) {
            if (isBuildFile(path) && (projectPath == null || belongsToProject(path, projectPath))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Express the source project directory relative to the workspace root, the form the changed
     * paths use. Both directories are compared canonically, so a symlinked root or a {@code ..}
     * segment does not hide a match.
     *
     * @param workspaceRoot the absolute workspace root (Git work tree or Perforce client root);
     *                      may be null when the VCS cannot name one
     * @param sourceProjectDir the absolute source project directory; may be null
     * @return the forward-slash relative path, empty when the project is the root, or null when
     *         either directory is unknown or the project lies outside the workspace
     */
    public static String projectPathInWorkspace(final String workspaceRoot, final String sourceProjectDir) {
        if (workspaceRoot == null || sourceProjectDir == null) {
            return null;
        }
        String root = canonical(workspaceRoot);
        String project = canonical(sourceProjectDir);
        if (project.equals(root)) {
            return "";
        }
        String rootPrefix = root.endsWith("/") ? root : root + "/";
        if (!project.startsWith(rootPrefix)) {
            return null;
        }
        return trimTrailingSlash(project.substring(rootPrefix.length()));
    }

    /**
     * Report whether one path is a Maven or Gradle build file: {@code pom.xml}, a Gradle build or
     * settings script (Groovy or Kotlin), {@code gradle.properties}, or a version catalogue.
     *
     * @param path a workspace-root-relative, forward-slash path; may be null
     * @return true when the file name is a build file
     */
    static boolean isBuildFile(final String path) {
        if (path == null) {
            return false;
        }
        String name = fileName(path).toLowerCase(Locale.ROOT);
        return name.equals("pom.xml")
                || name.equals("build.gradle") || name.equals("build.gradle.kts")
                || name.equals("settings.gradle") || name.equals("settings.gradle.kts")
                || name.equals("gradle.properties")
                || name.endsWith(VERSION_CATALOGUE_SUFFIX);
    }

    /**
     * Report whether a build file can affect the source project: it sits in the project directory,
     * below it, or in a directory above it. A version catalogue in a {@code gradle/} directory
     * belongs to the build rooted one level up, where Gradle looks for it.
     *
     * @param path the build file's workspace-root-relative, forward-slash path
     * @param projectPath the source project directory relative to the workspace root, empty for
     *                    the root
     * @return true when the build file's directory is the project's, a descendant or an ancestor
     */
    static boolean belongsToProject(final String path, final String projectPath) {
        String dir = parentDir(path);
        if (fileName(path).toLowerCase(Locale.ROOT).endsWith(VERSION_CATALOGUE_SUFFIX)
                && fileName(dir).equals(GRADLE_DIR)) {
            dir = parentDir(dir);
        }
        return isSameOrBelow(dir, projectPath) || isSameOrBelow(projectPath, dir);
    }

    /**
     * Report whether one relative directory is another or lies below it.
     *
     * @param dir the directory to test, empty for the root
     * @param base the directory it may lie in, empty for the root
     * @return true when {@code dir} equals {@code base} or is a descendant of it
     */
    private static boolean isSameOrBelow(final String dir, final String base) {
        return base.isEmpty() || dir.equals(base) || dir.startsWith(base + "/");
    }

    /**
     * Return the directory part of a forward-slash path.
     *
     * @param path the path
     * @return everything before the last slash, empty when there is none
     */
    private static String parentDir(final String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /**
     * Return the last segment of a forward-slash path.
     *
     * @param path the path
     * @return everything after the last slash
     */
    private static String fileName(final String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Normalise a directory to its canonical, forward-slash form without a trailing slash, falling
     * back to the absolute path when it cannot be canonicalised.
     *
     * @param dir the directory
     * @return the normalised path
     */
    private static String canonical(final String dir) {
        File file = new File(dir);
        String resolved;
        try {
            resolved = file.getCanonicalPath();
        } catch (IOException e) {
            resolved = file.getAbsolutePath();
        }
        return trimTrailingSlash(resolved.replace('\\', '/'));
    }

    /**
     * Remove a trailing slash, keeping a lone {@code /}.
     *
     * @param path the path
     * @return the path without a trailing slash
     */
    private static String trimTrailingSlash(final String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
