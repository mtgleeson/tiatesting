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
     * Hold rule: when a release batch is stamped {@code unknownNextVersion=true} (the change is
     * destined for the next, unknown release) and the source project still resolves to the same
     * version as the stamp, the drainer must hold the batch rather than fire it. Without this
     * check, a batch tagged for "the next release" would drain prematurely against the current
     * release.
     */
    @Test
    void releaseBatchWithUnknownNextVersionHeldWhenResolvedEqualsStamp() {
        // given a pending batch flagged unknownNextVersion=true at stamp 1.0.0
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, "0.9.0", null);
        dataStore.persistTrackedLibrary(lib);
        PendingLibraryImpactedMethod pending = new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0.0", null, new HashSet<>(Arrays.asList(10)));
        pending.setUnknownNextVersion(true);
        dataStore.persistPendingLibraryImpactedMethods(pending);
        setupTestMappingWithMethods(10);
        StubMetadataReader reader = new StubMetadataReader("1.0.0", "1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        // when the source project still resolves to 1.0.0 (matching the stamp)
        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, config, tiaData);

        // then the batch is held — no tests selected, no drain recorded
        assertFalse(outcome.getDrainResult().hasDrainedBatches());
        assertTrue(outcome.getTestsToAdd().isEmpty());
    }

    /**
     * Once the source project resolves to a higher version than the stamp, the batch's "next,
     * unknown release" has materialised and the drainer must release the hold and run the
     * pending tests against the new version.
     */
    @Test
    void releaseBatchWithUnknownNextVersionDrainsWhenResolvedAdvancesPastStamp() {
        // given a pending batch flagged unknownNextVersion=true at stamp 1.0.0
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, "1.0.0", null);
        dataStore.persistTrackedLibrary(lib);
        PendingLibraryImpactedMethod pending = new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0.0", null, new HashSet<>(Arrays.asList(10)));
        pending.setUnknownNextVersion(true);
        dataStore.persistPendingLibraryImpactedMethods(pending);
        setupTestMappingWithMethods(10);
        StubMetadataReader reader = new StubMetadataReader("1.1.0", "1.1.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        // when the source project advances past the stamp (resolves to 1.1.0)
        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, config, tiaData);

        // then the batch drains normally
        assertTrue(outcome.getDrainResult().hasDrainedBatches());
        assertEquals(1, outcome.getDrainResult().getDrainedBatchKeys().size());
    }

    /**
     * Pre-existing behaviour for a release batch with {@code unknownNextVersion=false}: the hold
     * rule does not apply. This is the {@code BUMP_AFTER_RELEASE} default, where the build-file
     * version is always the next-to-release version, so a stamp at the resolved version should
     * fire as soon as the resolved version differs from the library's last-tracked version.
     */
    @Test
    void releaseBatchWithKnownFlagDrainsAtStampVersion() {
        // given a pending batch flagged unknownNextVersion=false at stamp 1.0.0
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, "0.9.0", null);
        dataStore.persistTrackedLibrary(lib);
        PendingLibraryImpactedMethod pending = new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0.0", null, new HashSet<>(Arrays.asList(10)));
        pending.setUnknownNextVersion(false);
        dataStore.persistPendingLibraryImpactedMethods(pending);
        setupTestMappingWithMethods(10);
        StubMetadataReader reader = new StubMetadataReader("1.0.0", "1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        // when the source project resolves to 1.0.0 (matching the stamp) but is past last-tracked 0.9.0
        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, config, tiaData);

        // then the batch drains because the hold rule does not apply when unknownNextVersion=false
        assertTrue(outcome.getDrainResult().hasDrainedBatches());
    }

    /**
     * Bug fix: when the pending stamp is a SNAPSHOT but the source project has moved to a
     * release version, the drainer must not run a JAR-hash compare (the resolved JAR is for a
     * different version line, so hashes always differ and would falsely drain). Instead, the
     * SNAPSHOT's target release (stamp with {@code -SNAPSHOT} stripped) must be compared against
     * the resolved release. A SNAPSHOT targeting a version higher than the resolved release is
     * destined for a future release and must be held.
     */
    @Test
    void snapshotStampIsHeldWhenSourceIsReleaseBelowSnapshotTarget() throws Exception {
        // given a 1.2.0-SNAPSHOT pending stamp and a source project resolved to release 1.1.0
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, "1.0.0", "oldhash");
        dataStore.persistTrackedLibrary(lib);
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.2.0-SNAPSHOT", "stamphash", new HashSet<>(Arrays.asList(10))));
        setupTestMappingWithMethods(10);
        File fakeReleaseJar = new File(tempDir, "lib-1.1.0.jar");
        try (FileOutputStream fos = new FileOutputStream(fakeReleaseJar)) {
            fos.write("release-jar-1.1.0".getBytes());
        }
        StubMetadataReader reader = new StubMetadataReader("1.1.0", "1.1.0", fakeReleaseJar.getAbsolutePath());
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        // when the drainer runs
        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, config, tiaData);

        // then the batch is held — SNAPSHOT's target 1.2.0 is above the resolved release 1.1.0
        assertFalse(outcome.getDrainResult().hasDrainedBatches());
        assertTrue(outcome.getTestsToAdd().isEmpty());
    }

    /**
     * Bug fix companion case: when the pending stamp is a SNAPSHOT and the source project has
     * moved to a release at or above the SNAPSHOT's target release, the SNAPSHOT's changes have
     * been incorporated into a consumed release and the batch must drain. The release-style
     * comparison uses the stamp with {@code -SNAPSHOT} stripped.
     */
    @Test
    void snapshotStampDrainsWhenSourceIsReleaseAtOrAboveSnapshotTarget() throws Exception {
        // given a 1.0.0-SNAPSHOT pending stamp and a source project resolved to release 1.1.0
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, "0.9.0", "oldhash");
        dataStore.persistTrackedLibrary(lib);
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0.0-SNAPSHOT", "stamphash", new HashSet<>(Arrays.asList(10))));
        setupTestMappingWithMethods(10);
        File fakeReleaseJar = new File(tempDir, "lib-1.1.0.jar");
        try (FileOutputStream fos = new FileOutputStream(fakeReleaseJar)) {
            fos.write("release-jar-1.1.0".getBytes());
        }
        StubMetadataReader reader = new StubMetadataReader("1.1.0", "1.1.0", fakeReleaseJar.getAbsolutePath());
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        // when the drainer runs
        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, config, tiaData);

        // then the batch drains — SNAPSHOT's target 1.0.0 is below the resolved release 1.1.0
        assertTrue(outcome.getDrainResult().hasDrainedBatches());
        assertEquals(1, outcome.getDrainResult().getDrainedBatchKeys().size());
    }

    /**
     * The {@code unknownNextVersion} flag is meaningful only on the release-version drain path.
     * SNAPSHOT batches use a JAR-content-hash flow and must drain whenever the resolved hash
     * differs from the library's last-tracked hash, irrespective of the flag's value.
     */
    @Test
    void snapshotBatchUnaffectedByUnknownNextVersionFlag() throws Exception {
        // given a SNAPSHOT pending batch with the flag set true and a JAR with a different hash than last-tracked
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, "oldhash");
        dataStore.persistTrackedLibrary(lib);
        PendingLibraryImpactedMethod pending = new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0-SNAPSHOT", "stamphash", new HashSet<>(Arrays.asList(10)));
        pending.setUnknownNextVersion(true);
        dataStore.persistPendingLibraryImpactedMethods(pending);
        setupTestMappingWithMethods(10);
        File fakeJar = new File(tempDir, "lib.jar");
        try (FileOutputStream fos = new FileOutputStream(fakeJar)) {
            fos.write("new-jar-content".getBytes());
        }
        StubMetadataReader reader = new StubMetadataReader("1.0-SNAPSHOT", "1.0-SNAPSHOT", fakeJar.getAbsolutePath());
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), null, "/projects/source", reader);

        // when the drainer runs against the new SNAPSHOT JAR
        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, config, tiaData);

        // then the batch drains via the SNAPSHOT hash path — the unknownNextVersion flag is ignored
        assertTrue(outcome.getDrainResult().hasDrainedBatches());
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
