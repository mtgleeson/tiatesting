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
     * Project-wide policy for how tracked libraries manage their version number around releases.
     * Drives stamp-time classification of whether a batch is destined for the currently-declared
     * version or for the next, unknown release. Defaults to {@link LibraryVersionPolicy#BUMP_AFTER_RELEASE}
     * when not specified.
     */
    private final LibraryVersionPolicy versionPolicy;

    /**
     * Construct a library impact analysis configuration with the default version policy
     * ({@link LibraryVersionPolicy#BUMP_AFTER_RELEASE}).
     *
     * @param coordinates parsed list of {@code groupId:artifactId} strings.
     * @param libraryProjectDirs map from coordinate to library project directory.
     * @param sourceProjectDir absolute path to the source project root.
     * @param metadataReader build-system-specific reader for library metadata.
     */
    public LibraryImpactAnalysisConfig(List<String> coordinates, Map<String, String> libraryProjectDirs,
                                       String sourceProjectDir, LibraryMetadataReader metadataReader) {
        this(coordinates, libraryProjectDirs, sourceProjectDir, metadataReader, LibraryVersionPolicy.BUMP_AFTER_RELEASE);
    }

    /**
     * Construct a library impact analysis configuration.
     *
     * @param coordinates parsed list of {@code groupId:artifactId} strings.
     * @param libraryProjectDirs map from coordinate to library project directory.
     * @param sourceProjectDir absolute path to the source project root.
     * @param metadataReader build-system-specific reader for library metadata.
     * @param versionPolicy the library version policy; {@code null} is treated as the default.
     */
    public LibraryImpactAnalysisConfig(List<String> coordinates, Map<String, String> libraryProjectDirs,
                                       String sourceProjectDir, LibraryMetadataReader metadataReader,
                                       LibraryVersionPolicy versionPolicy) {
        this.coordinates = coordinates != null ? coordinates : new ArrayList<>();
        this.libraryProjectDirs = libraryProjectDirs != null ? libraryProjectDirs : new HashMap<>();
        this.sourceProjectDir = sourceProjectDir;
        this.metadataReader = metadataReader;
        this.versionPolicy = versionPolicy != null ? versionPolicy : LibraryVersionPolicy.BUMP_AFTER_RELEASE;
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

    public LibraryVersionPolicy getVersionPolicy() {
        return versionPolicy;
    }

    /**
     * Returns {@code true} when library impact analysis is configured and should be executed.
     *
     * @return {@code true} if coordinates are configured and a metadata reader is available.
     */
    public boolean isEnabled() {
        return !coordinates.isEmpty() && metadataReader != null;
    }
}
