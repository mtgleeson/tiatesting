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
