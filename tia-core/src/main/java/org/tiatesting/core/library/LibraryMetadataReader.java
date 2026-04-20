package org.tiatesting.core.library;

import org.tiatesting.core.model.LibraryBuildMetadata;

import java.util.List;

/**
 * Reads library build metadata (declared version) from the library's own build file
 * and resolves library artifacts on the source project's classpath. Build-system-specific
 * implementations live in the Maven and Gradle plugin modules.
 */
public interface LibraryMetadataReader {

    /**
     * Read the declared version from the library's own build file (e.g. pom.xml or build.gradle)
     * for each of the supplied {@code groupId:artifactId} coordinates. The build file is located
     * relative to the library's configured project directory.
     *
     * @param libraryProjectDir absolute path to the library's project root directory.
     * @param coordinates list of {@code groupId:artifactId} strings.
     * @return metadata for each coordinate that could be read; missing entries are skipped with a warning.
     */
    List<LibraryBuildMetadata> readLibraryBuildMetadata(String libraryProjectDir, List<String> coordinates);

    /**
     * Resolve the currently-consumed version and JAR path for each of the supplied
     * {@code groupId:artifactId} coordinates as they appear on the source project's classpath.
     *
     * @param sourceProjectDir absolute path to the source project's root directory.
     * @param coordinates list of {@code groupId:artifactId} strings.
     * @return one entry per coordinate that could be resolved; missing entries are skipped with a warning.
     */
    List<ResolvedSourceProjectLibrary> resolveLibrariesInSourceProject(String sourceProjectDir, List<String> coordinates);

    /**
     * Read the source directories configured in the library's build file (e.g. Maven's
     * {@code <sourceDirectory>} or Gradle's {@code sourceSets.main.java.srcDirs}).
     *
     * @param libraryProjectDir absolute path to the library's project root directory.
     * @return list of absolute paths to source directories, or an empty list if they cannot be determined.
     */
    List<String> readSourceDirectories(String libraryProjectDir);
}
