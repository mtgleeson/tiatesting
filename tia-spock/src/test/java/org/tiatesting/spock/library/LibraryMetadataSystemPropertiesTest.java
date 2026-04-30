package org.tiatesting.spock.library;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.library.LibraryMetadataReader;
import org.tiatesting.core.library.LibraryVersionPolicy;
import org.tiatesting.core.library.ResolvedSourceProjectLibrary;
import org.tiatesting.core.model.LibraryBuildMetadata;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryMetadataSystemPropertiesTest {

    @Test
    void unsetMetadataProducesNullConfig() {
        assertNull(LibraryMetadataSystemProperties.fromValues(null, null, null));
        assertNull(LibraryMetadataSystemProperties.fromValues("", null, null));
        assertNull(LibraryMetadataSystemProperties.fromValues("   ", null, null));
    }

    @Test
    void singleEntryRoundTripsAllFields() {
        PreResolvedLibraryMetadataReader.Entry input = new PreResolvedLibraryMetadataReader.Entry(
                "com.example:lib", "/abs/path/to/lib", "1.2.3",
                Arrays.asList("/abs/path/to/lib/src/main/java", "/abs/path/to/lib/src/main/groovy"),
                "1.2.3", "/abs/path/.gradle/caches/lib-1.2.3.jar");

        String encoded = LibraryMetadataSystemProperties.formatEntries(Collections.singletonList(input));

        LibraryImpactAnalysisConfig config = LibraryMetadataSystemProperties.fromValues(
                encoded, "/abs/source-project", "BUMP_AT_RELEASE");

        assertNotNull(config);
        assertEquals(Collections.singletonList("com.example:lib"), config.getCoordinates());
        assertEquals("/abs/path/to/lib", config.getLibraryProjectDir("com.example:lib"));
        assertEquals("/abs/source-project", config.getSourceProjectDir());
        assertEquals(LibraryVersionPolicy.BUMP_AT_RELEASE, config.getVersionPolicy());

        LibraryMetadataReader reader = config.getMetadataReader();

        List<LibraryBuildMetadata> declared = reader.readLibraryBuildMetadata(
                "/ignored", Collections.singletonList("com.example:lib"));
        assertEquals(1, declared.size());
        assertEquals("1.2.3", declared.get(0).getDeclaredVersion());

        List<ResolvedSourceProjectLibrary> resolved = reader.resolveLibrariesInSourceProject(
                "/ignored", Collections.singletonList("com.example:lib"));
        assertEquals(1, resolved.size());
        assertEquals("1.2.3", resolved.get(0).getResolvedVersion());
        assertEquals("/abs/path/.gradle/caches/lib-1.2.3.jar", resolved.get(0).getJarFilePath());

        List<String> sourceDirs = reader.readSourceDirectories("/abs/path/to/lib");
        assertEquals(2, sourceDirs.size());
        assertTrue(sourceDirs.contains("/abs/path/to/lib/src/main/java"));
        assertTrue(sourceDirs.contains("/abs/path/to/lib/src/main/groovy"));
    }

    @Test
    void multipleEntriesAreIndependent() {
        PreResolvedLibraryMetadataReader.Entry a = new PreResolvedLibraryMetadataReader.Entry(
                "com.example:a", "/projects/a", "1.0.0",
                Collections.singletonList("/projects/a/src/main/java"), "1.0.0", "/jars/a-1.0.0.jar");
        PreResolvedLibraryMetadataReader.Entry b = new PreResolvedLibraryMetadataReader.Entry(
                "com.example:b", "/projects/b", "2.0.0",
                Collections.singletonList("/projects/b/src/main/java"), "2.0.0", "/jars/b-2.0.0.jar");

        String encoded = LibraryMetadataSystemProperties.formatEntries(Arrays.asList(a, b));
        LibraryImpactAnalysisConfig config = LibraryMetadataSystemProperties.fromValues(encoded, null, null);

        assertNotNull(config);
        assertEquals(Arrays.asList("com.example:a", "com.example:b"), config.getCoordinates());

        LibraryMetadataReader reader = config.getMetadataReader();
        List<ResolvedSourceProjectLibrary> bothResolved = reader.resolveLibrariesInSourceProject(
                "/ignored", Arrays.asList("com.example:a", "com.example:b"));
        assertEquals(2, bothResolved.size());
        assertEquals("1.0.0", bothResolved.get(0).getResolvedVersion());
        assertEquals("2.0.0", bothResolved.get(1).getResolvedVersion());
    }

    @Test
    void missingFieldsDoNotProduceMetadataEntries() {
        // Only coord + projectDir present; declared/resolved/sourceDirs all empty.
        String encoded = "com.example:lib:/abs/path::::";
        LibraryImpactAnalysisConfig config = LibraryMetadataSystemProperties.fromValues(encoded, null, null);

        assertNotNull(config);
        LibraryMetadataReader reader = config.getMetadataReader();

        assertTrue(reader.readLibraryBuildMetadata("/ignored", Collections.singletonList("com.example:lib")).isEmpty(),
                "Empty declared version → no metadata entry.");
        assertTrue(reader.resolveLibrariesInSourceProject("/ignored", Collections.singletonList("com.example:lib")).isEmpty(),
                "Empty resolved version → no resolved entry.");
        assertTrue(reader.readSourceDirectories("/abs/path").isEmpty(),
                "Empty source dirs → empty list.");
    }

    @Test
    void unknownCoordinatesAreIgnored() {
        PreResolvedLibraryMetadataReader.Entry a = new PreResolvedLibraryMetadataReader.Entry(
                "com.example:a", "/projects/a", "1.0.0",
                Collections.singletonList("/projects/a/src/main/java"), "1.0.0", "/jars/a.jar");
        String encoded = LibraryMetadataSystemProperties.formatEntries(Collections.singletonList(a));
        LibraryImpactAnalysisConfig config = LibraryMetadataSystemProperties.fromValues(encoded, null, null);

        LibraryMetadataReader reader = config.getMetadataReader();
        assertTrue(reader.readLibraryBuildMetadata("/ignored", Collections.singletonList("com.example:not-tracked")).isEmpty());
        assertTrue(reader.resolveLibrariesInSourceProject("/ignored", Collections.singletonList("com.example:not-tracked")).isEmpty());
    }

    @Test
    void invalidEntriesAreSkipped() {
        // First entry is malformed (only one segment) — must be skipped without erroring.
        String encoded = "malformed,com.example:lib:/abs/path:1.0.0::1.0.0:/jar.jar";
        LibraryImpactAnalysisConfig config = LibraryMetadataSystemProperties.fromValues(encoded, null, null);

        assertNotNull(config);
        assertEquals(Collections.singletonList("com.example:lib"), config.getCoordinates());
    }

    @Test
    void policyDefaultsAndCaseInsensitive() {
        String encoded = "com.example:lib:/p:1.0.0::1.0.0:/jar.jar";

        assertEquals(LibraryVersionPolicy.BUMP_AFTER_RELEASE,
                LibraryMetadataSystemProperties.fromValues(encoded, null, null).getVersionPolicy());
        assertEquals(LibraryVersionPolicy.BUMP_AFTER_RELEASE,
                LibraryMetadataSystemProperties.fromValues(encoded, null, "  ").getVersionPolicy());
        assertEquals(LibraryVersionPolicy.BUMP_AT_RELEASE,
                LibraryMetadataSystemProperties.fromValues(encoded, null, "bump_at_release").getVersionPolicy());
        assertEquals(LibraryVersionPolicy.BUMP_AFTER_RELEASE,
                LibraryMetadataSystemProperties.fromValues(encoded, null, "garbage").getVersionPolicy(),
                "Unknown policy values must fall back to BUMP_AFTER_RELEASE.");
    }
}
