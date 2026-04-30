package org.tiatesting.spock.library;

import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.library.LibraryVersionPolicy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the library metadata flat string format passed from the Gradle plugin to the test JVM via
 * system properties, and constructs a {@link LibraryImpactAnalysisConfig} backed by a
 * {@link PreResolvedLibraryMetadataReader}.
 *
 * <p>The plugin pre-resolves all metadata (declared version, source dirs, resolved version + JAR
 * path) on the Gradle side — where it has access to the Project / Tooling API — and forwards the
 * results as a single comma-separated property:
 *
 * <pre>{@code
 * tiaLibrariesMetadata = groupId:artifactId:projectDir:declaredVersion:sourceDirs:resolvedVersion:resolvedJar,
 *                        groupId:artifactId:...
 * }</pre>
 *
 * <p>Within an entry, fields are colon-separated and positional. The {@code sourceDirs} field is
 * itself a list, pipe-separated. Empty positional fields are permitted (e.g. unresolvable JAR).
 *
 * <p>Globals are passed via two additional flat properties: {@code tiaSourceProjectDir} and
 * {@code tiaLibraryVersionPolicy}.
 *
 * <p>Path values must not contain {@code ,}, {@code :} or {@code |} — this matches the existing
 * limitation on {@code tiaSourceLibs}.
 */
public final class LibraryMetadataSystemProperties {

    public static final String PROP_LIBRARIES_METADATA = "tiaLibrariesMetadata";
    public static final String PROP_SOURCE_PROJECT_DIR = "tiaSourceProjectDir";
    public static final String PROP_LIBRARY_VERSION_POLICY = "tiaLibraryVersionPolicy";

    private static final String ENTRY_DELIM = ",";
    private static final String FIELD_DELIM = ":";
    private static final String SOURCE_DIRS_DELIM = "|";

    private LibraryMetadataSystemProperties() {}

    /**
     * Read the three properties from {@link System} and build a config, or return {@code null}
     * when {@code tiaLibrariesMetadata} is unset / empty (no library config in effect).
     */
    public static LibraryImpactAnalysisConfig fromSystemProperties() {
        return fromValues(
                System.getProperty(PROP_LIBRARIES_METADATA),
                System.getProperty(PROP_SOURCE_PROJECT_DIR),
                System.getProperty(PROP_LIBRARY_VERSION_POLICY));
    }

    /**
     * Build a config from explicit values. Exposed for testing without touching system state.
     */
    public static LibraryImpactAnalysisConfig fromValues(String librariesMetadata, String sourceProjectDir,
                                                         String libraryVersionPolicy) {
        if (librariesMetadata == null || librariesMetadata.trim().isEmpty()) {
            return null;
        }

        List<PreResolvedLibraryMetadataReader.Entry> entries = parseEntries(librariesMetadata);
        if (entries.isEmpty()) {
            return null;
        }

        List<String> coordinates = new ArrayList<>(entries.size());
        Map<String, String> projectDirs = new LinkedHashMap<>();
        for (PreResolvedLibraryMetadataReader.Entry entry : entries) {
            coordinates.add(entry.getCoordinate());
            if (entry.getProjectDir() != null && !entry.getProjectDir().isEmpty()) {
                projectDirs.put(entry.getCoordinate(), entry.getProjectDir());
            }
        }

        PreResolvedLibraryMetadataReader reader = new PreResolvedLibraryMetadataReader(entries);
        LibraryVersionPolicy policy = parsePolicy(libraryVersionPolicy);

        return new LibraryImpactAnalysisConfig(coordinates, projectDirs, sourceProjectDir, reader, policy);
    }

    private static List<PreResolvedLibraryMetadataReader.Entry> parseEntries(String raw) {
        List<PreResolvedLibraryMetadataReader.Entry> entries = new ArrayList<>();
        for (String entryRaw : raw.split(ENTRY_DELIM, -1)) {
            String trimmed = entryRaw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            PreResolvedLibraryMetadataReader.Entry entry = parseEntry(trimmed);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static PreResolvedLibraryMetadataReader.Entry parseEntry(String raw) {
        // Fields (positional): groupId, artifactId, projectDir, declaredVersion, sourceDirs, resolvedVersion, resolvedJar
        String[] f = raw.split(FIELD_DELIM, -1);
        if (f.length < 2 || f[0].isEmpty() || f[1].isEmpty()) {
            return null;
        }

        String coordinate = f[0] + ":" + f[1];
        String projectDir = at(f, 2);
        String declaredVersion = at(f, 3);
        List<String> sourceDirs = parseSourceDirs(at(f, 4));
        String resolvedVersion = at(f, 5);
        String resolvedJar = at(f, 6);

        return new PreResolvedLibraryMetadataReader.Entry(
                coordinate, projectDir, declaredVersion, sourceDirs, resolvedVersion, resolvedJar);
    }

    private static String at(String[] f, int idx) {
        return idx < f.length ? f[idx] : "";
    }

    private static List<String> parseSourceDirs(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        // Pre-Java-9: cannot use String.split with a regex-ambiguous literal; quote it.
        String[] parts = raw.split(java.util.regex.Pattern.quote(SOURCE_DIRS_DELIM), -1);
        List<String> dirs = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isEmpty()) {
                dirs.add(part);
            }
        }
        return dirs;
    }

    private static LibraryVersionPolicy parsePolicy(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return LibraryVersionPolicy.BUMP_AFTER_RELEASE;
        }
        try {
            return LibraryVersionPolicy.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return LibraryVersionPolicy.BUMP_AFTER_RELEASE;
        }
    }

    /**
     * Inverse of {@link #parseEntries} — formats a list of entries into the flat string. Used by
     * the Gradle plugin to populate {@code tiaLibrariesMetadata}, and by tests.
     */
    public static String formatEntries(List<PreResolvedLibraryMetadataReader.Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (PreResolvedLibraryMetadataReader.Entry entry : entries) {
            if (entry == null || entry.getCoordinate() == null) {
                continue;
            }
            String[] coordParts = entry.getCoordinate().split(":");
            if (coordParts.length != 2) {
                // Malformed coordinate — skip rather than corrupt the encoded form.
                continue;
            }
            if (!first) {
                sb.append(ENTRY_DELIM);
            }
            first = false;
            sb.append(coordParts[0]).append(FIELD_DELIM)
              .append(coordParts[1]).append(FIELD_DELIM)
              .append(nullToEmpty(entry.getProjectDir())).append(FIELD_DELIM)
              .append(nullToEmpty(entry.getDeclaredVersion())).append(FIELD_DELIM)
              .append(joinSourceDirs(entry.getSourceDirs())).append(FIELD_DELIM)
              .append(nullToEmpty(entry.getResolvedVersion())).append(FIELD_DELIM)
              .append(nullToEmpty(entry.getResolvedJar()));
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private static String joinSourceDirs(List<String> sourceDirs) {
        if (sourceDirs == null || sourceDirs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String dir : sourceDirs) {
            if (dir == null || dir.isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append(SOURCE_DIRS_DELIM);
            }
            first = false;
            sb.append(dir);
        }
        return sb.toString();
    }
}
