package org.tiatesting.core.library;

import org.junit.jupiter.api.*;
import org.tiatesting.core.model.*;
import org.tiatesting.core.persistence.h2.H2DataStore;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PendingLibraryImpactedMethodsDrainerTest {

    private H2DataStore dataStore;
    private File tempDir;
    private PendingLibraryImpactedMethodsDrainer drainer;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-drainer-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(tempDir.getAbsolutePath(), "test");
        dataStore.getTiaData(true);
        drainer = new PendingLibraryImpactedMethodsDrainer();
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
    void drainsReleaseBatchWhenResolvedVersionMeetsStamp() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, "0.9.0", null);
        dataStore.persistTrackedLibrary(lib);
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0.0", null, new HashSet<>(Arrays.asList(10, 20))));

        setupTestMappingWithMethods(10, 20);

        StubMetadataReader reader = new StubMetadataReader("1.0.0", "1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, config, tiaData);

        assertTrue(outcome.getDrainResult().hasDrainedBatches());
        assertEquals(1, outcome.getDrainResult().getDrainedBatchKeys().size());
        assertEquals("1.0.0", outcome.getDrainResult().getDrainedBatchKeys().get(0).getStampVersion());
        assertFalse(outcome.getTestsToAdd().isEmpty());
    }

    @Test
    void doesNotDrainWhenResolvedVersionBelowStamp() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, null);
        dataStore.persistTrackedLibrary(lib);
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "2.0.0", null, new HashSet<>(Arrays.asList(10))));

        setupTestMappingWithMethods(10);

        StubMetadataReader reader = new StubMetadataReader("1.0.0", "1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, config, tiaData);

        assertFalse(outcome.getDrainResult().hasDrainedBatches());
        assertTrue(outcome.getTestsToAdd().isEmpty());
    }

    @Test
    void doesNotDrainWhenResolvedVersionMatchesLastTracked() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, "1.0.0", null);
        dataStore.persistTrackedLibrary(lib);
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0.0", null, new HashSet<>(Arrays.asList(10))));

        setupTestMappingWithMethods(10);

        StubMetadataReader reader = new StubMetadataReader("1.0.0", "1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, config, tiaData);

        assertFalse(outcome.getDrainResult().hasDrainedBatches());
    }

    @Test
    void drainsSnapshotBatchWhenJarHashDiffersFromLastTracked() throws Exception {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, "oldhash");
        dataStore.persistTrackedLibrary(lib);
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0-SNAPSHOT", "stamphash", new HashSet<>(Arrays.asList(10))));

        setupTestMappingWithMethods(10);

        File fakeJar = new File(tempDir, "lib.jar");
        try (FileOutputStream fos = new FileOutputStream(fakeJar)) {
            fos.write("new-jar-content".getBytes());
        }

        StubMetadataReader reader = new StubMetadataReader("1.0-SNAPSHOT", "1.0-SNAPSHOT", fakeJar.getAbsolutePath());
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, config, tiaData);

        assertTrue(outcome.getDrainResult().hasDrainedBatches());
        assertFalse(outcome.getTestsToAdd().isEmpty());
    }

    @Test
    void doesNotDrainSnapshotWhenJarHashMatchesLastTracked() throws Exception {
        File fakeJar = new File(tempDir, "lib.jar");
        try (FileOutputStream fos = new FileOutputStream(fakeJar)) {
            fos.write("same-content".getBytes());
        }
        String jarHash = PendingLibraryImpactedMethodsRecorder.computeSha256Hash(fakeJar);

        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, jarHash);
        dataStore.persistTrackedLibrary(lib);
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0-SNAPSHOT", "stamphash", new HashSet<>(Arrays.asList(10))));

        setupTestMappingWithMethods(10);

        StubMetadataReader reader = new StubMetadataReader("1.0-SNAPSHOT", "1.0-SNAPSHOT", fakeJar.getAbsolutePath());
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, config, tiaData);

        assertFalse(outcome.getDrainResult().hasDrainedBatches());
    }

    @Test
    void drainsMultipleBatchesForSameLibrary() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, "0.5.0", null);
        dataStore.persistTrackedLibrary(lib);
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0.0", null, new HashSet<>(Arrays.asList(10))));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.1.0", null, new HashSet<>(Arrays.asList(20))));

        setupTestMappingWithMethods(10, 20);

        StubMetadataReader reader = new StubMetadataReader("1.0.0", "1.1.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, config, tiaData);

        assertEquals(2, outcome.getDrainResult().getDrainedBatchKeys().size());
    }

    @Test
    void drainsOnlyEligibleBatchesWhenMultipleExist() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, "0.5.0", null);
        dataStore.persistTrackedLibrary(lib);
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0.0", null, new HashSet<>(Arrays.asList(10))));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "3.0.0", null, new HashSet<>(Arrays.asList(20))));

        setupTestMappingWithMethods(10, 20);

        StubMetadataReader reader = new StubMetadataReader("1.0.0", "2.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, config, tiaData);

        assertEquals(1, outcome.getDrainResult().getDrainedBatchKeys().size());
        assertEquals("1.0.0", outcome.getDrainResult().getDrainedBatchKeys().get(0).getStampVersion());
    }

    @Test
    void returnsEmptyOutcomeWhenNoPendingBatches() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, null);
        dataStore.persistTrackedLibrary(lib);

        StubMetadataReader reader = new StubMetadataReader("1.0.0", "1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, config, tiaData);

        assertFalse(outcome.getDrainResult().hasDrainedBatches());
        assertTrue(outcome.getTestsToAdd().isEmpty());
    }

    @Test
    void returnsEmptyOutcomeWhenNoTrackedLibraries() {
        StubMetadataReader reader = new StubMetadataReader("1.0.0", "1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, config, tiaData);

        assertFalse(outcome.getDrainResult().hasDrainedBatches());
        assertTrue(outcome.getTestsToAdd().isEmpty());
    }

    @Test
    void observedLibraryStateRecordedForDrainedLibrary() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, "0.9.0", null);
        dataStore.persistTrackedLibrary(lib);
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0.0", null, new HashSet<>(Arrays.asList(10))));

        setupTestMappingWithMethods(10);

        StubMetadataReader reader = new StubMetadataReader("1.0.0", "1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, config, tiaData);

        LibraryImpactDrainResult.ObservedLibraryState state =
                outcome.getDrainResult().getObservedLibraryStates().get("com.example:lib");
        assertNotNull(state);
        assertEquals("1.0.0", state.getResolvedVersion());
    }

    @Test
    void resolvesTestSuitesFromMethodIdsUsingCurrentMapping() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, "0.9.0", null);
        dataStore.persistTrackedLibrary(lib);
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0.0", null, new HashSet<>(Arrays.asList(10, 20, 999))));

        setupTestMappingWithMethods(10, 20);

        StubMetadataReader reader = new StubMetadataReader("1.0.0", "1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, config, tiaData);

        assertTrue(outcome.getTestsToAdd().contains("com.example.TestA"));
        assertTrue(outcome.getTestsToAdd().contains("com.example.TestB"));
    }

    @Test
    void skipsLibraryThatCannotBeResolved() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, null);
        dataStore.persistTrackedLibrary(lib);
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0.0", null, new HashSet<>(Arrays.asList(10))));

        setupTestMappingWithMethods(10);

        StubMetadataReader reader = new StubMetadataReader(null, null, null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, config, tiaData);

        assertFalse(outcome.getDrainResult().hasDrainedBatches());
        assertTrue(outcome.getTestsToAdd().isEmpty());
    }

    /**
     * Set up TiaData with test suite mappings where TestA covers method 10 and TestB covers method 20.
     */
    private void setupTestMappingWithMethods(int... methodIds) {
        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("abc123");
        tiaData.setLastUpdated(Instant.now());

        Map<String, TestSuiteTracker> testSuites = new HashMap<>();

        TestSuiteTracker testA = new TestSuiteTracker("com.example.TestA");
        testA.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker("com/example/Service.java",
                        new HashSet<>(Arrays.asList(methodIds.length > 0 ? methodIds[0] : 10)))));
        testSuites.put("com.example.TestA", testA);

        if (methodIds.length > 1) {
            TestSuiteTracker testB = new TestSuiteTracker("com.example.TestB");
            testB.setClassesImpacted(Collections.singletonList(
                    new ClassImpactTracker("com/example/Other.java",
                            new HashSet<>(Arrays.asList(methodIds[1])))));
            testSuites.put("com.example.TestB", testB);
        }

        tiaData.setTestSuitesTracked(testSuites);
        dataStore.persistCoreData(tiaData);
        dataStore.persistTestSuites(testSuites);
    }

    private static class StubMetadataReader implements LibraryMetadataReader {
        private final String declaredVersion;
        private final String resolvedVersion;
        private final String jarFilePath;

        StubMetadataReader(String declaredVersion, String resolvedVersion, String jarFilePath) {
            this.declaredVersion = declaredVersion;
            this.resolvedVersion = resolvedVersion;
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
