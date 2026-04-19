package org.tiatesting.core.library;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for library impact analysis, built from the user's {@code tiaSourceLibs} and
 * {@code tiaSourceProjectDir} settings. Carries the parsed coordinates, the source project
 * directory, and the build-system-specific {@link LibraryMetadataReader} to use.
 */
public class LibraryImpactAnalysisConfig {

    /** Parsed {@code groupId:artifactId} coordinates from the {@code tiaSourceLibs} CSV. */
    private final List<String> coordinates;

    /** Absolute path to the source project root directory. */
    private final String sourceProjectDir;

    /** Build-system-specific reader for library metadata resolution. */
    private final LibraryMetadataReader metadataReader;

    /**
     * Construct a library impact analysis configuration.
     *
     * @param coordinates parsed list of {@code groupId:artifactId} strings.
     * @param sourceProjectDir absolute path to the source project root.
     * @param metadataReader build-system-specific reader for library metadata.
     */
    public LibraryImpactAnalysisConfig(List<String> coordinates, String sourceProjectDir,
                                       LibraryMetadataReader metadataReader) {
        this.coordinates = coordinates != null ? coordinates : new ArrayList<>();
        this.sourceProjectDir = sourceProjectDir;
        this.metadataReader = metadataReader;
    }

    public List<String> getCoordinates() {
        return coordinates;
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
