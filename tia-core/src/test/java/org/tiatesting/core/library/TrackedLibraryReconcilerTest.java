package org.tiatesting.core.library;

import org.junit.jupiter.api.*;
import org.tiatesting.core.model.LibraryBuildMetadata;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.h2.H2DataStore;

import java.io.File;
import java.io.FileOutputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TrackedLibraryReconcilerTest {

    private H2DataStore dataStore;
    private File tempDir;
    private TrackedLibraryReconciler reconciler;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-reconciler-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(tempDir.getAbsolutePath(), "test");
        dataStore.getTiaData(true);
        reconciler = new TrackedLibraryReconciler();
    }

    @AfterEach
    void tearDown() {
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    @Test
    void insertsNewLibrariesFromConfig() {
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Arrays.asList("com.example:libA", "com.example:libB"),
                "/projects/source", null);

        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        assertEquals(2, result.size());
        assertTrue(result.containsKey("com.example:libA"));
        assertTrue(result.containsKey("com.example:libB"));
        assertEquals("/projects/source", result.get("com.example:libA").getProjectDir());
    }

    @Test
    void deletesLibrariesRemovedFromConfig() {
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:old", "/old", null, null, null));
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:keep", "/keep", null, null, null));

        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:keep"),
                "/projects/source", null);

        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("com.example:keep"));
        assertFalse(result.containsKey("com.example:old"));
    }

    @Test
    void updatesProjectDirWhenChanged() {
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:lib", "/old/path", null, "1.0", "hash1"));

        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"),
                "/new/path", null);

        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        assertEquals("/new/path", result.get("com.example:lib").getProjectDir());
        assertEquals("1.0", result.get("com.example:lib").getLastSourceProjectVersion());
        assertEquals("hash1", result.get("com.example:lib").getLastSourceProjectJarHash());
    }

    @Test
    void doesNotUpdateWhenConfigUnchanged() {
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:lib", "/same", null, "2.0", "hash2"));

        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"),
                "/same", null);

        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        assertEquals("2.0", result.get("com.example:lib").getLastSourceProjectVersion());
        assertEquals("hash2", result.get("com.example:lib").getLastSourceProjectJarHash());
    }

    @Test
    void emptyConfigDeletesAllExisting() {
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:a", "/a", null, null, null));
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:b", "/b", null, null, null));

        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.emptyList(), "/projects/source", null);

        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        assertTrue(result.isEmpty());
    }

    @Test
    void reconcileWithEmptyDbAndEmptyConfig() {
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.emptyList(), "/projects/source", null);

        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        assertTrue(result.isEmpty());
    }

    /**
     * Verifies that when a new release-version library is inserted, the reconciler seeds the
     * baseline {@code lastSourceProjectVersion} by resolving the library on the source project's
     * classpath. This prevents the drainer from immediately draining pending batches on the first
     * run, which would produce a false green.
     */
    @Test
    void seedsBaselineVersionForNewReleaseLibrary() {
        StubMetadataReader reader = new StubMetadataReader("2.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), "/projects/source", reader);

        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        TrackedLibrary lib = result.get("com.example:lib");
        assertEquals("2.0.0", lib.getLastSourceProjectVersion());
        assertNull(lib.getLastSourceProjectJarHash());
    }

    /**
     * Verifies that when a new SNAPSHOT library is inserted, the reconciler seeds both the
     * baseline {@code lastSourceProjectVersion} and {@code lastSourceProjectJarHash} by
     * resolving the library and computing the JAR's SHA-256 hash. This prevents the SNAPSHOT
     * drain rule from immediately triggering because {@code lastSourceProjectJarHash} would
     * otherwise be {@code null}.
     */
    @Test
    void seedsBaselineVersionAndJarHashForNewSnapshotLibrary() throws Exception {
        File fakeJar = new File(tempDir, "lib-snapshot.jar");
        try (FileOutputStream fos = new FileOutputStream(fakeJar)) {
            fos.write("snapshot-jar-content".getBytes());
        }
        String expectedHash = PendingLibraryImpactedMethodsRecorder.computeSha256Hash(fakeJar);

        StubMetadataReader reader = new StubMetadataReader("1.0-SNAPSHOT", fakeJar.getAbsolutePath());
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), "/projects/source", reader);

        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        TrackedLibrary lib = result.get("com.example:lib");
        assertEquals("1.0-SNAPSHOT", lib.getLastSourceProjectVersion());
        assertEquals(expectedHash, lib.getLastSourceProjectJarHash());
    }

    /**
     * Verifies that when the metadata reader cannot resolve the library on the source project's
     * classpath (e.g. the library is not yet a dependency), the baseline fields remain {@code null}.
     * The drainer handles this correctly because it also won't be able to resolve the library,
     * so {@code resolvedJarHash} / {@code resolvedVersion} will be {@code null} and the drain
     * will be skipped.
     */
    @Test
    void baselineRemainsNullWhenLibraryCannotBeResolved() {
        StubMetadataReader reader = new StubMetadataReader(null, null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), "/projects/source", reader);

        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        TrackedLibrary lib = result.get("com.example:lib");
        assertNull(lib.getLastSourceProjectVersion());
        assertNull(lib.getLastSourceProjectJarHash());
    }

    private static class StubMetadataReader implements LibraryMetadataReader {
        private final String resolvedVersion;
        private final String jarFilePath;

        StubMetadataReader(String resolvedVersion, String jarFilePath) {
            this.resolvedVersion = resolvedVersion;
            this.jarFilePath = jarFilePath;
        }

        @Override
        public List<LibraryBuildMetadata> readLibraryBuildMetadata(String libraryProjectDir, List<String> coordinates) {
            return Collections.emptyList();
        }

        @Override
        public List<ResolvedSourceProjectLibrary> resolveLibrariesInSourceProject(String sourceProjectDir,
                                                                                   List<String> coordinates) {
            if (resolvedVersion == null) {
                return Collections.emptyList();
            }
            List<ResolvedSourceProjectLibrary> result = new ArrayList<>();
            for (String coord : coordinates) {
                result.add(new ResolvedSourceProjectLibrary(coord, resolvedVersion, jarFilePath));
            }
            return result;
        }
    }
}
