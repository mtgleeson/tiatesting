package org.tiatesting.core.library;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Resolves a TIA {@code tiaSourceLibs} CSV ({@code groupId:artifactId,...}) to a list of absolute
 * JAR file paths by matching each declared {@code artifactId} against version-stamped jar
 * filenames found in one or more configured directories (e.g. a deployment {@code lib/} directory).
 *
 * <p>This is the offline-safe alternative to pom/classpath-based resolution: it needs no Maven or
 * Gradle dependency graph, so it works in an offline build whose local repository does not contain
 * the source project's full transitive graph. It lives in {@code tia-core} so both the Maven and
 * Gradle plugins can share the same matching logic; each plugin passes its own {@code warn} and
 * {@code debug} logging sinks so output stays in that plugin's log stream.
 *
 * <p>See the "Directory-based library-jar resolution" chapter in {@code WIKI.md} for when to use
 * this mode and how it composes with {@code tiaSourceLibs}.
 */
public final class LibraryJarDirectoryResolver {

    /**
     * Filename suffixes that must never be treated as the library's main jar even when their name
     * otherwise matches the version-stamped pattern (e.g. {@code lib-1.0-sources.jar}).
     */
    private static final String[] EXCLUDED_JAR_SUFFIXES = { "-sources.jar", "-javadoc.jar" };

    private LibraryJarDirectoryResolver() {
    }

    /**
     * Resolve each {@code tiaSourceLibs} coordinate to its jar file by filename matching inside the
     * configured directories, returning the matched jars' absolute paths in insertion order with
     * duplicates removed.
     *
     * <p>Only each entry's {@code artifactId} is used for matching; the {@code groupId} is used for
     * logging and the optional third {@code :projectDir} segment is ignored in this mode. For each
     * {@code artifactId} every configured directory is scanned for a file matching (case-sensitive)
     * <code>^&lt;escaped-artifactId&gt;-\d[^/]*\.jar$</code>, excluding {@code -sources.jar} and
     * {@code -javadoc.jar}. The leading {@code -\d} guard requires a version digit immediately after
     * {@code <artifactId>-}, so {@code foo} does not match {@code foo-bar-1.0.jar}.
     *
     * <p>Edge cases are logged per coordinate and the coordinate is skipped: no match found;
     * multiple matches (ambiguous - safer than guessing a version); a directory that is missing or
     * is not a directory (warned once, that directory skipped). A debug line is emitted for each
     * matched jar.
     *
     * @param sourceLibsCsv the {@code tiaSourceLibs} CSV ({@code groupId:artifactId} or
     *                      {@code groupId:artifactId:projectDir} per entry); blank yields an empty
     *                      list.
     * @param directories the directory paths to scan for built library jars; missing entries are
     *                    warned and skipped.
     * @param warn sink for warning-level messages (no-match, ambiguous match, bad directory).
     * @param debug sink for debug-level messages (one per resolved jar).
     * @return the absolute paths of the matched jars, de-duplicated and in insertion order; never
     *         null, possibly empty.
     */
    public static List<String> resolveLibraryJars(String sourceLibsCsv, List<String> directories,
                                                  Consumer<String> warn, Consumer<String> debug) {
        List<String> resolved = new ArrayList<>();
        if (sourceLibsCsv == null || sourceLibsCsv.trim().isEmpty()) {
            return resolved;
        }

        List<Coordinate> coordinates = parseCoordinates(sourceLibsCsv, warn);
        if (coordinates.isEmpty()) {
            return resolved;
        }

        List<File> scanDirs = validDirectories(directories, warn);
        Set<String> seen = new LinkedHashSet<>();

        for (Coordinate coord : coordinates) {
            Set<String> matches = findMatchingJars(coord.artifactId, scanDirs);

            if (matches.isEmpty()) {
                warn.accept("tiaSourceLibs coordinate '" + coord.label
                        + "' not found in tiaLibraryJarsDirs " + directories + ", skipping.");
                continue;
            }
            if (matches.size() > 1) {
                warn.accept("tiaSourceLibs coordinate '" + coord.label
                        + "' matched multiple jars in tiaLibraryJarsDirs " + matches
                        + " - ambiguous, skipping.");
                continue;
            }

            String path = matches.iterator().next();
            if (seen.add(path)) {
                debug.accept("Resolved coordinate '" + coord.label + "' to " + path);
                resolved.add(path);
            }
        }

        return resolved;
    }

    /**
     * Parse the {@code tiaSourceLibs} CSV into coordinates, keeping the {@code groupId:artifactId}
     * label for logging and the bare {@code artifactId} for filename matching. Entries with fewer
     * than two colon-separated segments are warned and skipped; a third {@code projectDir} segment
     * is ignored.
     *
     * @param sourceLibsCsv the raw CSV.
     * @param warn sink for warnings about malformed entries.
     * @return the parsed coordinates in declaration order.
     */
    private static List<Coordinate> parseCoordinates(String sourceLibsCsv, Consumer<String> warn) {
        List<Coordinate> coordinates = new ArrayList<>();
        for (String raw : sourceLibsCsv.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            String[] segments = entry.split(":");
            if (segments.length < 2) {
                warn.accept("Invalid tiaSourceLibs coordinate '" + entry
                        + "' - expected groupId:artifactId or groupId:artifactId:projectDir, skipping.");
                continue;
            }
            String groupId = segments[0].trim();
            String artifactId = segments[1].trim();
            if (groupId.isEmpty() || artifactId.isEmpty()) {
                warn.accept("Invalid tiaSourceLibs coordinate '" + entry
                        + "' - expected groupId:artifactId or groupId:artifactId:projectDir, skipping.");
                continue;
            }
            coordinates.add(new Coordinate(groupId + ":" + artifactId, artifactId));
        }
        return coordinates;
    }

    /**
     * Filter the configured directory paths down to those that exist and are directories, warning
     * once per path that is missing or is not a directory.
     *
     * @param directories the configured directory paths.
     * @param warn sink for warnings about bad directories.
     * @return the usable directories as {@link File}s, in configured order.
     */
    private static List<File> validDirectories(List<String> directories, Consumer<String> warn) {
        List<File> scanDirs = new ArrayList<>();
        if (directories == null) {
            return scanDirs;
        }
        for (String dirPath : directories) {
            if (dirPath == null || dirPath.trim().isEmpty()) {
                continue;
            }
            File dir = new File(dirPath.trim());
            if (!dir.isDirectory()) {
                warn.accept("tiaLibraryJarsDirs entry '" + dirPath.trim()
                        + "' is missing or not a directory, skipping.");
                continue;
            }
            scanDirs.add(dir);
        }
        return scanDirs;
    }

    /**
     * Scan each directory for jar files whose name matches the version-stamped pattern for the
     * given {@code artifactId}, returning the matches' absolute paths. The result is a set so that
     * the same jar reached through two directory entries collapses to one path; its size drives the
     * no-match / ambiguous-match decision in the caller.
     *
     * @param artifactId the library artifactId to match on.
     * @param scanDirs the directories to scan (already validated).
     * @return the absolute paths of matching jars, in encounter order.
     */
    private static Set<String> findMatchingJars(String artifactId, List<File> scanDirs) {
        Pattern pattern = Pattern.compile("^" + Pattern.quote(artifactId) + "-\\d[^/]*\\.jar$");
        Set<String> matches = new LinkedHashSet<>();

        for (File dir : scanDirs) {
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            // Sort by name so ambiguous-match warnings and results are deterministic regardless of
            // the filesystem's directory listing order.
            List<File> sorted = new ArrayList<>(Arrays.asList(files));
            sorted.sort((a, b) -> a.getName().compareTo(b.getName()));

            for (File file : sorted) {
                if (!file.isFile()) {
                    continue;
                }
                String name = file.getName();
                if (isExcludedClassifierJar(name)) {
                    continue;
                }
                if (pattern.matcher(name).matches()) {
                    matches.add(file.getAbsolutePath());
                }
            }
        }
        return matches;
    }

    /**
     * Decide whether a jar filename is a classifier artifact ({@code -sources.jar} /
     * {@code -javadoc.jar}) that must be excluded from main-jar matching.
     *
     * @param name the jar filename.
     * @return {@code true} if the name ends with an excluded classifier suffix.
     */
    private static boolean isExcludedClassifierJar(String name) {
        for (String suffix : EXCLUDED_JAR_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A parsed {@code tiaSourceLibs} entry: the {@code groupId:artifactId} label used in log
     * messages and the bare {@code artifactId} used for filename matching.
     */
    private static final class Coordinate {
        final String label;
        final String artifactId;

        Coordinate(String label, String artifactId) {
            this.label = label;
            this.artifactId = artifactId;
        }
    }
}
