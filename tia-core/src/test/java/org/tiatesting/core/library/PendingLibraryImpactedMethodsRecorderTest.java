package org.tiatesting.core.library;

import org.junit.jupiter.api.*;
import org.tiatesting.core.model.LibraryBuildMetadata;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.h2.H2DataStore;

import java.io.File;
import java.io.FileOutputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PendingLibraryImpactedMethodsRecorderTest {

    private H2DataStore dataStore;
    private File tempDir;
    private PendingLibraryImpactedMethodsRecorder recorder;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-recorder-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(tempDir.getAbsolutePath(), "test");
        dataStore.getTiaData(true);
        recorder = new PendingLibraryImpactedMethodsRecorder();
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
    void recordsReleaseVersionPendingMethods() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, null);
        dataStore.persistTrackedLibrary(lib);

        LibraryMetadataReader reader = new StubMetadataReader("1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        Set<Integer> methodIds = new HashSet<>(Arrays.asList(100, 200, 300));
        recorder.recordPendingImpactedMethods(dataStore, lib, methodIds, config);

        List<PendingLibraryImpactedMethod> pending = dataStore.readPendingLibraryImpactedMethods("com.example:lib");
        assertEquals(1, pending.size());
        assertEquals("1.0.0", pending.get(0).getStampVersion());
        assertNull(pending.get(0).getStampJarHash());
        assertEquals(methodIds, pending.get(0).getSourceMethodIds());
    }

    @Test
    void recordsSnapshotVersionWithJarHash() throws Exception {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, null);
        dataStore.persistTrackedLibrary(lib);

        File fakeJar = new File(tempDir, "lib-1.0-SNAPSHOT.jar");
        try (FileOutputStream fos = new FileOutputStream(fakeJar)) {
            fos.write("fake-jar-content".getBytes());
        }

        LibraryMetadataReader reader = new StubMetadataReader("1.0-SNAPSHOT", fakeJar.getAbsolutePath());
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        recorder.recordPendingImpactedMethods(dataStore, lib, new HashSet<>(Arrays.asList(10)), config);

        List<PendingLibraryImpactedMethod> pending = dataStore.readPendingLibraryImpactedMethods("com.example:lib");
        assertEquals(1, pending.size());
        assertEquals("1.0-SNAPSHOT", pending.get(0).getStampVersion());
        assertNotNull(pending.get(0).getStampJarHash());
        assertEquals(64, pending.get(0).getStampJarHash().length());
    }

    @Test
    void skipsWhenMethodIdsEmpty() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, null);
        dataStore.persistTrackedLibrary(lib);

        LibraryMetadataReader reader = new StubMetadataReader("1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        recorder.recordPendingImpactedMethods(dataStore, lib, Collections.emptySet(), config);

        List<PendingLibraryImpactedMethod> pending = dataStore.readPendingLibraryImpactedMethods("com.example:lib");
        assertTrue(pending.isEmpty());
    }

    @Test
    void skipsWhenMethodIdsNull() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, null);
        dataStore.persistTrackedLibrary(lib);

        LibraryMetadataReader reader = new StubMetadataReader("1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        recorder.recordPendingImpactedMethods(dataStore, lib, null, config);

        List<PendingLibraryImpactedMethod> pending = dataStore.readPendingLibraryImpactedMethods("com.example:lib");
        assertTrue(pending.isEmpty());
    }

    @Test
    void skipsWhenVersionCannotBeDetermined() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, null);
        dataStore.persistTrackedLibrary(lib);

        LibraryMetadataReader reader = new StubMetadataReader(null, null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        recorder.recordPendingImpactedMethods(dataStore, lib, new HashSet<>(Arrays.asList(10)), config);

        List<PendingLibraryImpactedMethod> pending = dataStore.readPendingLibraryImpactedMethods("com.example:lib");
        assertTrue(pending.isEmpty());
    }

    @Test
    void skipsWhenProjectDirIsNull() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", null, null, null, null);
        dataStore.persistTrackedLibrary(lib);

        LibraryMetadataReader reader = new StubMetadataReader("1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        recorder.recordPendingImpactedMethods(dataStore, lib, new HashSet<>(Arrays.asList(10)), config);

        List<PendingLibraryImpactedMethod> pending = dataStore.readPendingLibraryImpactedMethods("com.example:lib");
        assertTrue(pending.isEmpty());
    }

    /**
     * Under {@code BUMP_AT_RELEASE}, when the build-file version equals the library's high water
     * mark the change is destined for the next, unknown release. The stamp must therefore record
     * {@code unknownNextVersion=true} so the drainer holds it until a real version bump happens.
     * The high water mark itself does not change because no new release has been observed.
     */
    @Test
    void bumpAtReleaseStampAtHighWaterMarkFlagsUnknownNextVersion() {
        // given a tracked library at HWM 1.0.0 whose build file still declares 1.0.0
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, null);
        lib.setLastReleasedLibraryVersion("1.0.0");
        dataStore.persistTrackedLibrary(lib);
        LibraryMetadataReader reader = new StubMetadataReader("1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader,
                LibraryVersionPolicy.BUMP_AT_RELEASE);

        // when we stamp impacted methods for this library
        recorder.recordPendingImpactedMethods(dataStore, lib,
                new HashSet<>(Arrays.asList(10, 20)), config);

        // then the pending row is flagged unknownNextVersion=true and the HWM is unchanged
        List<PendingLibraryImpactedMethod> pending = dataStore.readPendingLibraryImpactedMethods("com.example:lib");
        assertEquals(1, pending.size());
        assertEquals("1.0.0", pending.get(0).getStampVersion());
        assertTrue(pending.get(0).isUnknownNextVersion());
        TrackedLibrary reloaded = dataStore.readTrackedLibraries().get("com.example:lib");
        assertEquals("1.0.0", reloaded.getLastReleasedLibraryVersion());
    }

    /**
     * Under {@code BUMP_AT_RELEASE}, when the build-file version is higher than the recorded high
     * water mark a new release has just been observed. The stamp records {@code unknownNextVersion=false}
     * (the change is destined for this version, not the next unknown one) and the high water mark
     * is advanced to the new build-file version.
     */
    @Test
    void bumpAtReleaseStampAboveHighWaterMarkAdvancesHwmAndFlagsKnown() {
        // given a tracked library at HWM 1.0.0 whose build file now declares 1.1.0
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, null);
        lib.setLastReleasedLibraryVersion("1.0.0");
        dataStore.persistTrackedLibrary(lib);
        LibraryMetadataReader reader = new StubMetadataReader("1.1.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader,
                LibraryVersionPolicy.BUMP_AT_RELEASE);

        // when we stamp impacted methods for this library
        recorder.recordPendingImpactedMethods(dataStore, lib,
                new HashSet<>(Arrays.asList(10)), config);

        // then the pending row is flagged unknownNextVersion=false and the HWM advances to 1.1.0
        List<PendingLibraryImpactedMethod> pending = dataStore.readPendingLibraryImpactedMethods("com.example:lib");
        assertEquals(1, pending.size());
        assertEquals("1.1.0", pending.get(0).getStampVersion());
        assertFalse(pending.get(0).isUnknownNextVersion());
        TrackedLibrary reloaded = dataStore.readTrackedLibraries().get("com.example:lib");
        assertEquals("1.1.0", reloaded.getLastReleasedLibraryVersion());
    }

    /**
     * Under {@code BUMP_AT_RELEASE}, a build-file version below the high water mark is a
     * regression that shouldn't happen in a healthy project. The stamper handles it conservatively:
     * record {@code unknownNextVersion=true} so the drainer holds the batch (avoiding a false drain
     * against a stale lower version) and leave the high water mark unchanged.
     */
    @Test
    void bumpAtReleaseStampBelowHighWaterMarkFlagsUnknownConservatively() {
        // given a tracked library at HWM 1.1.0 whose build file regresses to 1.0.0
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, null);
        lib.setLastReleasedLibraryVersion("1.1.0");
        dataStore.persistTrackedLibrary(lib);
        LibraryMetadataReader reader = new StubMetadataReader("1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader,
                LibraryVersionPolicy.BUMP_AT_RELEASE);

        // when we stamp impacted methods for this library
        recorder.recordPendingImpactedMethods(dataStore, lib,
                new HashSet<>(Arrays.asList(10)), config);

        // then the pending row is held (unknownNextVersion=true) and the HWM is unchanged
        List<PendingLibraryImpactedMethod> pending = dataStore.readPendingLibraryImpactedMethods("com.example:lib");
        assertEquals(1, pending.size());
        assertEquals("1.0.0", pending.get(0).getStampVersion());
        assertTrue(pending.get(0).isUnknownNextVersion());
        TrackedLibrary reloaded = dataStore.readTrackedLibraries().get("com.example:lib");
        assertEquals("1.1.0", reloaded.getLastReleasedLibraryVersion());
    }

    /**
     * Under {@code BUMP_AFTER_RELEASE} the build-file version is always the next-to-release
     * version, so every stamp is "known" — there is no holding behaviour to enable. Even when the
     * stamp version equals the current high water mark, the flag must stay {@code false}, otherwise
     * the drainer would incorrectly hold a batch that should drain at this version.
     */
    @Test
    void bumpAfterReleaseAlwaysFlagsKnownAtHighWaterMark() {
        // given a tracked library at HWM 1.0.0 whose build file declares 1.0.0
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, null);
        lib.setLastReleasedLibraryVersion("1.0.0");
        dataStore.persistTrackedLibrary(lib);
        LibraryMetadataReader reader = new StubMetadataReader("1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader,
                LibraryVersionPolicy.BUMP_AFTER_RELEASE);

        // when we stamp impacted methods under BUMP_AFTER_RELEASE
        recorder.recordPendingImpactedMethods(dataStore, lib,
                new HashSet<>(Arrays.asList(10)), config);

        // then the pending row is always flagged unknownNextVersion=false regardless of HWM equality
        List<PendingLibraryImpactedMethod> pending = dataStore.readPendingLibraryImpactedMethods("com.example:lib");
        assertFalse(pending.get(0).isUnknownNextVersion());
    }

    /**
     * Under {@code BUMP_AFTER_RELEASE} the high water mark is not used by the drainer, and it is
     * not maintained by the stamper either: the field is left at whatever value was already
     * stored (deliberately {@code null} when seeded under this policy). Maintaining it on stamp
     * events would only show partial data — versions where stamps happen — and would diverge from
     * reality whenever releases occur without source changes. Leaving the field untouched keeps
     * it consistently empty so it never surfaces misleading data.
     */
    @Test
    void bumpAfterReleaseDoesNotMaintainHwmEvenWhenStampExceedsIt() {
        // given a tracked library with no recorded HWM (the policy doesn't seed it) whose build file declares 2.0.0
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, null);
        dataStore.persistTrackedLibrary(lib);
        LibraryMetadataReader reader = new StubMetadataReader("2.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader,
                LibraryVersionPolicy.BUMP_AFTER_RELEASE);

        // when we stamp impacted methods under BUMP_AFTER_RELEASE
        recorder.recordPendingImpactedMethods(dataStore, lib,
                new HashSet<>(Arrays.asList(10)), config);

        // then the HWM stays null (the policy doesn't maintain it) and the stamp is flagged known
        TrackedLibrary reloaded = dataStore.readTrackedLibraries().get("com.example:lib");
        assertNull(reloaded.getLastReleasedLibraryVersion());
        List<PendingLibraryImpactedMethod> pending = dataStore.readPendingLibraryImpactedMethods("com.example:lib");
        assertFalse(pending.get(0).isUnknownNextVersion());
    }

    /**
     * Defensive case — when a library has no high water mark yet (e.g. it could not be seeded by
     * the reconciler), the stamper treats the first observed build-file version as the new HWM
     * and records the stamp as known ({@code unknownNextVersion=false}). This avoids a permanent
     * hold on the very first stamp for a freshly tracked library.
     */
    @Test
    void bumpAtReleaseWithNullHwmTreatsFirstStampAsKnown() {
        // given a tracked library with no recorded HWM whose build file declares 1.0.0
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, null);
        dataStore.persistTrackedLibrary(lib);
        LibraryMetadataReader reader = new StubMetadataReader("1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader,
                LibraryVersionPolicy.BUMP_AT_RELEASE);

        // when we stamp impacted methods under BUMP_AT_RELEASE
        recorder.recordPendingImpactedMethods(dataStore, lib,
                new HashSet<>(Arrays.asList(10)), config);

        // then the first stamp is flagged known and the HWM is initialised to the stamp version
        List<PendingLibraryImpactedMethod> pending = dataStore.readPendingLibraryImpactedMethods("com.example:lib");
        assertFalse(pending.get(0).isUnknownNextVersion());
        TrackedLibrary reloaded = dataStore.readTrackedLibraries().get("com.example:lib");
        assertEquals("1.0.0", reloaded.getLastReleasedLibraryVersion());
    }

    /**
     * SNAPSHOT stamps follow a hash-based detection flow that is independent of release-version
     * policy. The {@code unknownNextVersion} flag must always stay {@code false} for SNAPSHOTs and
     * the high water mark of released versions must not move, regardless of which policy is active.
     */
    @Test
    void snapshotStampKeepsUnknownNextVersionFalseRegardlessOfPolicy() throws Exception {
        // given a tracked library at HWM 1.0.0 with a SNAPSHOT JAR available for hashing
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, null);
        lib.setLastReleasedLibraryVersion("1.0.0");
        dataStore.persistTrackedLibrary(lib);
        File fakeJar = new File(tempDir, "lib-1.0-SNAPSHOT.jar");
        try (FileOutputStream fos = new FileOutputStream(fakeJar)) {
            fos.write("snapshot-jar".getBytes());
        }
        LibraryMetadataReader reader = new StubMetadataReader("1.0-SNAPSHOT", fakeJar.getAbsolutePath());
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader,
                LibraryVersionPolicy.BUMP_AT_RELEASE);

        // when we stamp impacted methods for a SNAPSHOT version under BUMP_AT_RELEASE
        recorder.recordPendingImpactedMethods(dataStore, lib,
                new HashSet<>(Arrays.asList(10)), config);

        // then the SNAPSHOT row is flagged known and the released-version HWM is unchanged
        List<PendingLibraryImpactedMethod> pending = dataStore.readPendingLibraryImpactedMethods("com.example:lib");
        assertEquals(1, pending.size());
        assertEquals("1.0-SNAPSHOT", pending.get(0).getStampVersion());
        assertFalse(pending.get(0).isUnknownNextVersion());
        TrackedLibrary reloaded = dataStore.readTrackedLibraries().get("com.example:lib");
        assertEquals("1.0.0", reloaded.getLastReleasedLibraryVersion());
    }

    @Test
    void computeSha256HashProducesDeterministicResult() throws Exception {
        File testFile = new File(tempDir, "test.bin");
        try (FileOutputStream fos = new FileOutputStream(testFile)) {
            fos.write("hello world".getBytes());
        }

        String hash1 = PendingLibraryImpactedMethodsRecorder.computeSha256Hash(testFile);
        String hash2 = PendingLibraryImpactedMethodsRecorder.computeSha256Hash(testFile);

        assertNotNull(hash1);
        assertEquals(64, hash1.length());
        assertEquals(hash1, hash2);
    }

    @Test
    void computeSha256HashReturnsNullForMissingFile() {
        String hash = PendingLibraryImpactedMethodsRecorder.computeSha256Hash(new File("/nonexistent/file.jar"));
        assertNull(hash);
    }

    private static class StubMetadataReader implements LibraryMetadataReader {
        private final String declaredVersion;
        private final String jarFilePath;

        StubMetadataReader(String declaredVersion, String jarFilePath) {
            this.declaredVersion = declaredVersion;
            this.jarFilePath = jarFilePath;
        }

        @Override
        public List<LibraryBuildMetadata> readLibraryBuildMetadata(String libraryProjectDir, List<String> coordinates) {
            if (declaredVersion == null) {
                return Collections.emptyList();
            }
            List<LibraryBuildMetadata> result = new ArrayList<>();
            for (String coord : coordinates) {
                result.add(new LibraryBuildMetadata(coord, declaredVersion));
            }
            return result;
        }

        @Override
        public List<ResolvedSourceProjectLibrary> resolveLibrariesInSourceProject(String sourceProjectDir, List<String> coordinates) {
            if (jarFilePath == null) {
                return Collections.emptyList();
            }
            List<ResolvedSourceProjectLibrary> result = new ArrayList<>();
            for (String coord : coordinates) {
                result.add(new ResolvedSourceProjectLibrary(coord, declaredVersion, jarFilePath));
            }
            return result;
        }

        @Override
        public List<String> readSourceDirectories(String libraryProjectDir) {
            return Collections.emptyList();
        }
    }
}
