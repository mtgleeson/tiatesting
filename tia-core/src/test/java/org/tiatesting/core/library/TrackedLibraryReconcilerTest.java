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
        Map<String, String> libDirs = new HashMap<>();
        libDirs.put("com.example:libA", "/projects/libA");
        libDirs.put("com.example:libB", "/projects/libB");
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Arrays.asList("com.example:libA", "com.example:libB"),
                libDirs, "/projects/source", null);

        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        assertEquals(2, result.size());
        assertTrue(result.containsKey("com.example:libA"));
        assertTrue(result.containsKey("com.example:libB"));
        assertEquals("/projects/libA", result.get("com.example:libA").getProjectDir());
    }

    @Test
    void deletesLibrariesRemovedFromConfig() {
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:old", "/old", null, null, null));
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:keep", "/keep", null, null, null));

        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:keep"),
                Collections.singletonMap("com.example:keep", "/keep"),
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
                Collections.singletonMap("com.example:lib", "/new/path"),
                "/projects/source", null);

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
                Collections.singletonMap("com.example:lib", "/same"),
                "/projects/source", null);

        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        assertEquals("2.0", result.get("com.example:lib").getLastSourceProjectVersion());
        assertEquals("hash2", result.get("com.example:lib").getLastSourceProjectJarHash());
    }

    @Test
    void emptyConfigDeletesAllExisting() {
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:a", "/a", null, null, null));
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:b", "/b", null, null, null));

        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.emptyList(), null, "/projects/source", null);

        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        assertTrue(result.isEmpty());
    }

    @Test
    void reconcileWithEmptyDbAndEmptyConfig() {
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.emptyList(), null, "/projects/source", null);

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
                Collections.singletonList("com.example:lib"),
                Collections.singletonMap("com.example:lib", "/projects/lib"),
                "/projects/source", reader);

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
                Collections.singletonList("com.example:lib"),
                Collections.singletonMap("com.example:lib", "/projects/lib"),
                "/projects/source", reader);

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
                Collections.singletonList("com.example:lib"),
                Collections.singletonMap("com.example:lib", "/projects/lib"),
                "/projects/source", reader);

        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        TrackedLibrary lib = result.get("com.example:lib");
        assertNull(lib.getLastSourceProjectVersion());
        assertNull(lib.getLastSourceProjectJarHash());
    }

    /**
     * On first insert, the reconciler must seed {@code lastReleasedLibraryVersion} (the high water
     * mark of observed released versions) from the library's current build-file version. Without
     * this seed, the stamper would treat the very first stamp as a new release and could either
     * mis-flag or fail to hold subsequent batches under {@code BUMP_AT_RELEASE}.
     */
    @Test
    void seedsLastReleasedLibraryVersionFromBuildFile() {
        // given a metadata reader that reports the library's build-file declares 1.5.0
        StubMetadataReader reader = new StubMetadataReader("1.5.0", null, "1.5.0");
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"),
                Collections.singletonMap("com.example:lib", "/projects/lib"),
                "/projects/source", reader);

        // when the reconciler inserts a new tracked library row for this coordinate
        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        // then the new row is seeded with lastReleasedLibraryVersion = build-file version
        TrackedLibrary lib = result.get("com.example:lib");
        assertEquals("1.5.0", lib.getLastReleasedLibraryVersion());
    }

    /**
     * The high water mark advances strictly forward and must never be reset by config changes.
     * When only the project directory changes (or any other reconciler-managed config field), the
     * reconciler must preserve the existing {@code lastReleasedLibraryVersion} rather than
     * re-seeding from the build file — otherwise an unrelated config edit would clobber the
     * library's accumulated release history.
     */
    @Test
    void preservesLastReleasedLibraryVersionWhenConfigChanges() {
        // given an existing tracked library at HWM 1.0.0 whose build file now reports 2.0.0
        TrackedLibrary existing = new TrackedLibrary("com.example:lib", "/old/path", null, "1.0", "hash1");
        existing.setLastReleasedLibraryVersion("1.0.0");
        dataStore.persistTrackedLibrary(existing);
        StubMetadataReader reader = new StubMetadataReader("2.0.0", null, "2.0.0");
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"),
                Collections.singletonMap("com.example:lib", "/new/path"),
                "/projects/source", reader);

        // when the reconciler runs after the project directory has changed
        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        // then the project dir updates but the existing HWM is preserved (not re-seeded from build file)
        TrackedLibrary lib = result.get("com.example:lib");
        assertEquals("/new/path", lib.getProjectDir());
        assertEquals("1.0.0", lib.getLastReleasedLibraryVersion());
    }

    /**
     * Defensive case: when the metadata reader cannot read the library's build file (e.g. the
     * library's project directory is misconfigured), the seed is left {@code null}. The stamper
     * handles a null HWM by treating the first observed version as the initial mark — so this
     * gap is a deferred initialisation, not a hard failure.
     */
    @Test
    void leavesLastReleasedLibraryVersionNullWhenBuildMetadataMissing() {
        // given a metadata reader that returns no build metadata for the library
        StubMetadataReader reader = new StubMetadataReader("2.0.0", null, null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"),
                Collections.singletonMap("com.example:lib", "/projects/lib"),
                "/projects/source", reader);

        // when the reconciler inserts a new tracked library row
        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        // then lastReleasedLibraryVersion is left null for the stamper to initialise on first stamp
        TrackedLibrary lib = result.get("com.example:lib");
        assertNull(lib.getLastReleasedLibraryVersion());
    }

    private static class StubMetadataReader implements LibraryMetadataReader {
        private final String resolvedVersion;
        private final String jarFilePath;
        private final String declaredBuildVersion;

        StubMetadataReader(String resolvedVersion, String jarFilePath) {
            this(resolvedVersion, jarFilePath, null);
        }

        StubMetadataReader(String resolvedVersion, String jarFilePath, String declaredBuildVersion) {
            this.resolvedVersion = resolvedVersion;
            this.jarFilePath = jarFilePath;
            this.declaredBuildVersion = declaredBuildVersion;
        }

        @Override
        public List<LibraryBuildMetadata> readLibraryBuildMetadata(String libraryProjectDir, List<String> coordinates) {
            if (declaredBuildVersion == null) {
                return Collections.emptyList();
            }
            List<LibraryBuildMetadata> result = new ArrayList<>();
            for (String coord : coordinates) {
                result.add(new LibraryBuildMetadata(coord, declaredBuildVersion));
            }
            return result;
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

        @Override
        public List<String> readSourceDirectories(String libraryProjectDir) {
            return Collections.emptyList();
        }
    }
}
