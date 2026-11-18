package org.tiatesting.core.library;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The matching rule for library source lines. A source line identifies where the library source
 * behind a published build lives; a null line is one recorded before lines existed, or one the
 * publish could not resolve. See the source-lines section of the library publish-time stamping
 * chapter in {@code WIKI.md}.
 */
public final class SourceLines {

    private SourceLines() {
    }

    /**
     * Report whether two source lines are the same line. An unknown (null) line matches any line,
     * so rows written before lines were recorded keep behaving as they always did.
     *
     * @param a one line, or null when unknown
     * @param b the other line, or null when unknown
     * @return true when either is null or both are equal
     */
    public static boolean matches(final String a, final String b) {
        return a == null || b == null || a.equals(b);
    }

    /**
     * Drop the source roots that lie inside a module's build output directory. A publish's source
     * line is made from its module's source roots, so it must be made from the same roots at every
     * publish. A generated-sources root under the build directory exists only once something has
     * generated into it - present after one build, absent after a clean or with annotation
     * processing off - and a line that came and went with it would read as a line switch. Such a
     * root holds no versioned source, so the publish diff loses nothing either.
     *
     * @param sourceDirs the module's source roots, as absolute paths
     * @param buildDir the module's build output directory, or null/blank when unknown
     * @return the source roots outside the build directory, in their original order
     */
    public static List<String> withoutBuildOutput(final List<String> sourceDirs, final String buildDir) {
        List<String> kept = new ArrayList<>();
        if (sourceDirs == null) {
            return kept;
        }
        if (buildDir == null || buildDir.trim().isEmpty()) {
            kept.addAll(sourceDirs);
            return kept;
        }
        Path buildPath = canonical(buildDir);
        for (String sourceDir : sourceDirs) {
            if (!canonical(sourceDir).startsWith(buildPath)) {
                kept.add(sourceDir);
            }
        }
        return kept;
    }

    /**
     * Resolve a directory to its canonical path, so two spellings of one location (a symlinked
     * temp directory, a relative segment) compare equal.
     *
     * @param dir the directory path
     * @return the canonical path, or the normalised absolute path when it cannot be resolved
     */
    private static Path canonical(final String dir) {
        File file = new File(dir);
        try {
            return file.getCanonicalFile().toPath();
        } catch (IOException e) {
            return Paths.get(file.getAbsolutePath()).normalize();
        }
    }
}
