package org.tiatesting.core.library;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for library impact analysis, built from the user's {@code tiaSourceLibs} and
 * {@code tiaSourceProjectDir} settings. Carries the parsed coordinates, the per-library project
 * directories, the source project directory, and the build-system-specific {@link LibraryMetadataReader}.
 */
public class LibraryImpactAnalysisConfig {

    /** Parsed {@code groupId:artifactId} coordinates from the {@code tiaSourceLibs} CSV. */
    private final List<String> coordinates;

    /**
     * Per-library project directories, keyed by {@code groupId:artifactId}. Each value is the
     * absolute path to the library's own project root (for loading its build file and for
     * matching VCS diffs against the library's source tree).
     */
    private final Map<String, String> libraryProjectDirs;

    /** Absolute path to the source project root directory. */
    private final String sourceProjectDir;

    /** Build-system-specific reader for library metadata resolution. */
    private final LibraryMetadataReader metadataReader;

    /**
     * Construct a library impact analysis configuration.
     *
     * @param coordinates parsed list of {@code groupId:artifactId} strings.
     * @param libraryProjectDirs map from coordinate to library project directory.
     * @param sourceProjectDir absolute path to the source project root.
     * @param metadataReader build-system-specific reader for library metadata.
     */
    public LibraryImpactAnalysisConfig(List<String> coordinates, Map<String, String> libraryProjectDirs,
                                       String sourceProjectDir, LibraryMetadataReader metadataReader) {
        this.coordinates = coordinates != null ? coordinates : new ArrayList<>();
        this.libraryProjectDirs = libraryProjectDirs != null ? libraryProjectDirs : new HashMap<>();
        this.sourceProjectDir = sourceProjectDir;
        this.metadataReader = metadataReader;
    }

    public List<String> getCoordinates() {
        return coordinates;
    }

    /**
     * Get the project directory for a specific library coordinate.
     *
     * @param coordinate the {@code groupId:artifactId} coordinate.
     * @return the library's own project directory, or {@code null} if not specified.
     */
    public String getLibraryProjectDir(String coordinate) {
        return libraryProjectDirs.get(coordinate);
    }

    public Map<String, String> getLibraryProjectDirs() {
        return libraryProjectDirs;
    }

    public String getSourceProjectDir() {
        return sourceProjectDir;
    }

    public LibraryMetadataReader getMetadataReader() {
        return metadataReader;
    }

    /**
     * Returns {@code true} when library impact analysis is configured and should be executed.
     */
    public boolean isEnabled() {
        return !coordinates.isEmpty() && metadataReader != null;
    }
}
