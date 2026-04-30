package org.tiatesting.spock.library;

import org.tiatesting.core.library.LibraryMetadataReader;
import org.tiatesting.core.library.ResolvedSourceProjectLibrary;
import org.tiatesting.core.model.LibraryBuildMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link LibraryMetadataReader} backed by metadata pre-resolved on the plugin side and forwarded
 * to the test JVM. Consumers (Spock global extension and others) build this reader from a parsed
 * representation of the {@code tiaLibrariesMetadata} system property — see
 * {@link LibraryMetadataSystemProperties}.
 *
 * <p>The reader is a pure lookup table: {@link #readLibraryBuildMetadata},
 * {@link #resolveLibrariesInSourceProject}, and {@link #readSourceDirectories} all return the
 * stored values for the requested coordinates and ignore the {@code libraryProjectDir} /
 * {@code sourceProjectDir} arguments — those are inputs to the plugin-side resolver, not the
 * test-JVM-side reader. Missing data produces an empty result, matching the contract of the
 * Maven/Gradle build-system-specific readers.
 */
public class PreResolvedLibraryMetadataReader implements LibraryMetadataReader {

    private final Map<String, Entry> entriesByCoord;

    public PreResolvedLibraryMetadataReader(List<Entry> entries) {
        Map<String, Entry> map = new LinkedHashMap<>();
        if (entries != null) {
            for (Entry entry : entries) {
                if (entry != null && entry.getCoordinate() != null) {
                    map.put(entry.getCoordinate(), entry);
                }
            }
        }
        this.entriesByCoord = map;
    }

    @Override
    public List<LibraryBuildMetadata> readLibraryBuildMetadata(String libraryProjectDir, List<String> coordinates) {
        List<LibraryBuildMetadata> result = new ArrayList<>();
        if (coordinates == null) {
            return result;
        }
        for (String coord : coordinates) {
            Entry entry = entriesByCoord.get(coord);
            if (entry != null && isNonEmpty(entry.getDeclaredVersion())) {
                result.add(new LibraryBuildMetadata(coord, entry.getDeclaredVersion()));
            }
        }
        return result;
    }

    @Override
    public List<ResolvedSourceProjectLibrary> resolveLibrariesInSourceProject(String sourceProjectDir,
                                                                              List<String> coordinates) {
        List<ResolvedSourceProjectLibrary> result = new ArrayList<>();
        if (coordinates == null) {
            return result;
        }
        for (String coord : coordinates) {
            Entry entry = entriesByCoord.get(coord);
            if (entry == null) {
                continue;
            }
            // Match LibraryJarResolver: only report when at least the resolved version is known.
            // A null jarPath is acceptable — the consumer (drainer / hash compare) handles it.
            if (isNonEmpty(entry.getResolvedVersion())) {
                String jarPath = isNonEmpty(entry.getResolvedJar()) ? entry.getResolvedJar() : null;
                result.add(new ResolvedSourceProjectLibrary(coord, entry.getResolvedVersion(), jarPath));
            }
        }
        return result;
    }

    @Override
    public List<String> readSourceDirectories(String libraryProjectDir) {
        for (Entry entry : entriesByCoord.values()) {
            if (libraryProjectDir != null && libraryProjectDir.equals(entry.getProjectDir())) {
                return entry.getSourceDirs() != null ? entry.getSourceDirs() : Collections.<String>emptyList();
            }
        }
        return Collections.emptyList();
    }

    private static boolean isNonEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    /**
     * One library's pre-resolved metadata. Any field may be empty / null when the plugin couldn't
     * resolve it; the reader treats missing fields as "not available" and omits them from results.
     */
    public static final class Entry {
        private final String coordinate;
        private final String projectDir;
        private final String declaredVersion;
        private final List<String> sourceDirs;
        private final String resolvedVersion;
        private final String resolvedJar;

        public Entry(String coordinate, String projectDir, String declaredVersion, List<String> sourceDirs,
                     String resolvedVersion, String resolvedJar) {
            this.coordinate = coordinate;
            this.projectDir = projectDir;
            this.declaredVersion = declaredVersion;
            this.sourceDirs = sourceDirs != null ? Collections.unmodifiableList(new ArrayList<>(sourceDirs)) : Collections.<String>emptyList();
            this.resolvedVersion = resolvedVersion;
            this.resolvedJar = resolvedJar;
        }

        public String getCoordinate() { return coordinate; }
        public String getProjectDir() { return projectDir; }
        public String getDeclaredVersion() { return declaredVersion; }
        public List<String> getSourceDirs() { return sourceDirs; }
        public String getResolvedVersion() { return resolvedVersion; }
        public String getResolvedJar() { return resolvedJar; }
    }
}
