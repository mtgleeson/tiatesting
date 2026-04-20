package org.tiatesting.core.library;

import org.junit.jupiter.api.*;
import org.tiatesting.core.model.*;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.testrunner.TestRunResult;
import org.tiatesting.core.testrunner.TestRunnerService;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests that exercise the full stamp → drain → cleanup lifecycle
 * for library impact analysis. Each test walks through all three phases using a real
 * H2DataStore to verify the complete flow from pending method insertion through to
 * post-test-run cleanup.
 */
class LibraryImpactEndToEndTest {

    private H2DataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-e2e-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(tempDir.getAbsolutePath(), "test");
        dataStore.getTiaData(true);

        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("initial");
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);
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

    /**
     * Verifies the complete stamp → drain → cleanup lifecycle for a release version library change.
     *
     * <p>Steps:
     * <ol>
     *   <li>Set up a tracked library at last_source_project_version 0.9.0 with two test suites
     *       mapped to methods 10 and 20.</li>
     *   <li><b>Stamp:</b> Use the {@link PendingLibraryImpactedMethodsRecorder} to record methods
     *       10 and 20 as pending at the library's declared version 1.0.0. Verify the pending batch
     *       is persisted with the correct stamp version and method count.</li>
     *   <li><b>Drain:</b> Configure the source project as now resolving the library at version 1.0.0
     *       (which differs from last_source_project_version 0.9.0, satisfying the drain rule). Run
     *       the drainer and verify the batch is drained and both TestA and TestB are added to the
     *       run set.</li>
     *   <li><b>Cleanup:</b> Pass the drain result through {@link TestRunnerService#persistTestRunData}
     *       to simulate the post-test-run persist. Verify the pending rows are deleted and the
     *       tracked library's last_source_project_version is updated to 1.0.0.</li>
     * </ol>
     */
    @Test
    void releaseVersionStampDrainCleanupLifecycle() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, "0.9.0", null);
        dataStore.persistTrackedLibrary(lib);
        setupTestMappingWithMethods(10, 20);

        // STAMP: record pending methods at version 1.0.0
        PendingLibraryImpactedMethodsRecorder recorder = new PendingLibraryImpactedMethodsRecorder();
        StubMetadataReader stampReader = new StubMetadataReader("1.0.0", "0.9.0", null);
        LibraryImpactAnalysisConfig stampConfig = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), "/projects/source", stampReader);
        recorder.recordPendingImpactedMethods(dataStore, lib, new HashSet<>(Arrays.asList(10, 20)), stampConfig);

        List<PendingLibraryImpactedMethod> stamped = dataStore.readPendingLibraryImpactedMethods("com.example:lib");
        assertEquals(1, stamped.size());
        assertEquals("1.0.0", stamped.get(0).getStampVersion());
        assertEquals(2, stamped.get(0).getSourceMethodIds().size());

        // DRAIN: source project now at 1.0.0 (differs from last_source_project_version 0.9.0)
        StubMetadataReader drainReader = new StubMetadataReader("1.0.0", "1.0.0", null);
        LibraryImpactAnalysisConfig drainConfig = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), "/projects/source", drainReader);
        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer drainer = new PendingLibraryImpactedMethodsDrainer();
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                drainer.drainPendingMethods(dataStore, drainConfig, tiaData);

        assertTrue(outcome.getDrainResult().hasDrainedBatches());
        assertTrue(outcome.getTestsToAdd().contains("com.example.TestA"));
        assertTrue(outcome.getTestsToAdd().contains("com.example.TestB"));

        // CLEANUP: simulate post-test-run persist
        TestRunnerService service = new TestRunnerService(dataStore);
        TestRunResult testRunResult = new TestRunResult(
                new HashMap<>(), new HashSet<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>(), new TestStats(), outcome.getDrainResult());
        service.persistTestRunData(true, false, "newcommit", testRunResult);

        assertTrue(dataStore.readPendingLibraryImpactedMethods("com.example:lib").isEmpty());
        TrackedLibrary updated = dataStore.readTrackedLibraries().get("com.example:lib");
        assertEquals("1.0.0", updated.getLastSourceProjectVersion());
    }

    /**
     * Verifies that when multiple pending batches exist at different stamp versions, only the
     * batches whose stamp version is at or below the source project's resolved version are drained.
     * Batches stamped at a higher version than the source project's current version remain pending.
     *
     * <p>Steps:
     * <ol>
     *   <li>Set up a tracked library at last_source_project_version 0.5.0 with three test suites.</li>
     *   <li><b>Stamp:</b> Insert two pending batches — one at version 1.0.0 (method 10) and one at
     *       version 3.0.0 (method 20).</li>
     *   <li><b>Drain:</b> Configure the source project as resolving the library at version 2.0.0.
     *       Run the drainer and verify only the 1.0.0 batch is drained (2.0.0 >= 1.0.0) while
     *       the 3.0.0 batch is skipped (2.0.0 < 3.0.0).</li>
     *   <li><b>Cleanup:</b> Persist the drain result and verify the 1.0.0 pending rows are deleted
     *       while the 3.0.0 batch remains. Verify the tracked library's last_source_project_version
     *       is updated to 2.0.0 (the resolved version, not the stamp version).</li>
     * </ol>
     */
    @Test
    void multiVersionSkipOnlyDrainsEligibleBatches() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, "0.5.0", null);
        dataStore.persistTrackedLibrary(lib);
        setupTestMappingWithMethods(10, 20, 30);

        // STAMP: two batches at different versions
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0.0", null, new HashSet<>(Arrays.asList(10))));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "3.0.0", null, new HashSet<>(Arrays.asList(20))));

        // DRAIN: source project at 2.0.0 — only 1.0.0 batch should drain
        StubMetadataReader reader = new StubMetadataReader("1.0.0", "2.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), "/projects/source", reader);
        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                new PendingLibraryImpactedMethodsDrainer().drainPendingMethods(dataStore, config, tiaData);

        assertEquals(1, outcome.getDrainResult().getDrainedBatchKeys().size());
        assertEquals("1.0.0", outcome.getDrainResult().getDrainedBatchKeys().get(0).getStampVersion());

        // CLEANUP
        persistWithDrainResult(outcome.getDrainResult());

        List<PendingLibraryImpactedMethod> remaining = dataStore.readPendingLibraryImpactedMethods("com.example:lib");
        assertEquals(1, remaining.size());
        assertEquals("3.0.0", remaining.get(0).getStampVersion());

        TrackedLibrary updated = dataStore.readTrackedLibraries().get("com.example:lib");
        assertEquals("2.0.0", updated.getLastSourceProjectVersion());
    }

    /**
     * Verifies the complete stamp → drain → cleanup lifecycle for a SNAPSHOT version library change.
     * SNAPSHOT versions use JAR content hashing (SHA-256) instead of version comparison to determine
     * when the source project has picked up a new build, because the version string remains the same
     * across SNAPSHOT rebuilds.
     *
     * <p>Steps:
     * <ol>
     *   <li>Create a fake JAR file with known content and compute its SHA-256 hash. Set up a tracked
     *       library with this hash as last_source_project_jar_hash.</li>
     *   <li><b>Stamp:</b> Insert a pending batch at version "1.0-SNAPSHOT" with the initial JAR hash
     *       as the stamp_jar_hash.</li>
     *   <li>Overwrite the fake JAR with different content to simulate a new SNAPSHOT build being
     *       published. Verify the new hash differs from the initial hash.</li>
     *   <li><b>Drain:</b> Configure the metadata reader to resolve the library JAR at the updated
     *       file path. The drainer computes the new JAR's hash and compares it to the library's
     *       last_source_project_jar_hash — since they differ, the batch drains.</li>
     *   <li><b>Cleanup:</b> Persist the drain result and verify the pending rows are deleted and
     *       the tracked library's last_source_project_jar_hash is updated to the new hash.</li>
     * </ol>
     */
    @Test
    void snapshotStampDrainCleanupLifecycle() throws Exception {
        File fakeJar = new File(tempDir, "lib-snapshot.jar");
        try (FileOutputStream fos = new FileOutputStream(fakeJar)) {
            fos.write("initial-content".getBytes());
        }
        String initialHash = PendingLibraryImpactedMethodsRecorder.computeSha256Hash(fakeJar);

        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, initialHash);
        dataStore.persistTrackedLibrary(lib);
        setupTestMappingWithMethods(10);

        // STAMP: pending at SNAPSHOT version
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0-SNAPSHOT", initialHash, new HashSet<>(Arrays.asList(10))));

        // Update JAR content to simulate a new SNAPSHOT build
        try (FileOutputStream fos = new FileOutputStream(fakeJar)) {
            fos.write("updated-content-new-build".getBytes());
        }
        String newHash = PendingLibraryImpactedMethodsRecorder.computeSha256Hash(fakeJar);
        assertNotEquals(initialHash, newHash);

        // DRAIN: JAR hash differs from last tracked
        StubMetadataReader reader = new StubMetadataReader("1.0-SNAPSHOT", "1.0-SNAPSHOT", fakeJar.getAbsolutePath());
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), "/projects/source", reader);
        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                new PendingLibraryImpactedMethodsDrainer().drainPendingMethods(dataStore, config, tiaData);

        assertTrue(outcome.getDrainResult().hasDrainedBatches());
        assertFalse(outcome.getTestsToAdd().isEmpty());

        // CLEANUP
        persistWithDrainResult(outcome.getDrainResult());

        assertTrue(dataStore.readPendingLibraryImpactedMethods("com.example:lib").isEmpty());
        TrackedLibrary updated = dataStore.readTrackedLibraries().get("com.example:lib");
        assertEquals(newHash, updated.getLastSourceProjectJarHash());
    }

    /**
     * Verifies the {@code last_source_project_version} guard that prevents a pending batch from
     * being drained when the source project's resolved version matches the library's last tracked
     * version. This guard exists because library source changes are stamped with the library's
     * current declared version at commit time — before the version is bumped. If the source project
     * already has that same version resolved, the pending changes have not actually been picked up
     * yet (the version bump hasn't happened), so draining would be premature and produce a false
     * green result.
     *
     * <p>Steps:
     * <ol>
     *   <li>Set up a tracked library with last_source_project_version = 1.0.0.</li>
     *   <li><b>Stamp:</b> Insert a pending batch at stamp version 1.0.0 (simulating a change
     *       committed while the library is still at 1.0.0).</li>
     *   <li><b>Drain:</b> Configure the source project as resolving the library at version 1.0.0.
     *       Even though resolved (1.0.0) >= stamp (1.0.0), the drain should be blocked because
     *       resolved == last_source_project_version — indicating the source project hasn't actually
     *       moved to a new version.</li>
     *   <li>Verify the drain result has no drained batches, no tests are added to the run set,
     *       and the pending row remains in the database.</li>
     * </ol>
     */
    @Test
    void lastSourceProjectVersionGuardPreventsDoubleDrain() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, "1.0.0", null);
        dataStore.persistTrackedLibrary(lib);
        setupTestMappingWithMethods(10);

        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0.0", null, new HashSet<>(Arrays.asList(10))));

        // resolved == last_source_project_version == 1.0.0 → should NOT drain
        StubMetadataReader reader = new StubMetadataReader("1.0.0", "1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), "/projects/source", reader);
        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                new PendingLibraryImpactedMethodsDrainer().drainPendingMethods(dataStore, config, tiaData);

        assertFalse(outcome.getDrainResult().hasDrainedBatches());
        assertTrue(outcome.getTestsToAdd().isEmpty());

        // pending row should still exist
        assertEquals(1, dataStore.readPendingLibraryImpactedMethods("com.example:lib").size());
    }

    /**
     * Verifies that deleting a tracked library from the {@code tia_library} table automatically
     * cascade-deletes all of its pending rows from {@code tia_pending_library_impacted_method}.
     * This is important for the reconciliation flow: when a library is removed from the
     * {@code tiaSourceLibs} configuration, the reconciler deletes the tracked library row, and
     * any pending impacted methods for that library should be cleaned up automatically by the
     * database's foreign key cascade constraint.
     *
     * <p>Steps:
     * <ol>
     *   <li>Set up a tracked library and insert two pending batches at different stamp versions,
     *       each containing different method IDs.</li>
     *   <li>Verify both pending batches exist in the database.</li>
     *   <li>Delete the tracked library via {@code dataStore.deleteTrackedLibrary}.</li>
     *   <li>Verify all pending rows for that library are gone (cascade delete) and the library
     *       itself no longer exists in the tracked libraries table.</li>
     * </ol>
     */
    @Test
    void libraryRemovalCascadesDeletesPendingRows() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, null);
        dataStore.persistTrackedLibrary(lib);

        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0.0", null, new HashSet<>(Arrays.asList(10, 20))));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "2.0.0", null, new HashSet<>(Arrays.asList(30))));

        assertEquals(2, dataStore.readPendingLibraryImpactedMethods("com.example:lib").size());

        // Removing the tracked library should cascade-delete all pending rows
        dataStore.deleteTrackedLibrary("com.example:lib");

        assertTrue(dataStore.readPendingLibraryImpactedMethods("com.example:lib").isEmpty());
        assertFalse(dataStore.readTrackedLibraries().containsKey("com.example:lib"));
    }

    /**
     * Verifies that the stamp → drain → cleanup lifecycle works correctly when two independent
     * tracked libraries each have their own pending batches. Each library should be drained and
     * cleaned up independently, with the correct resolved version recorded for each.
     *
     * <p>Steps:
     * <ol>
     *   <li>Set up two tracked libraries ("com.example:a" and "com.example:b"), both with
     *       last_source_project_version 0.5.0, and three test suites mapped to methods 10, 20,
     *       and 30.</li>
     *   <li><b>Stamp:</b> Insert a pending batch for library A at version 1.0.0 (method 10) and
     *       a pending batch for library B at version 2.0.0 (method 20).</li>
     *   <li><b>Drain:</b> Configure a custom metadata reader that resolves library A at 1.0.0 and
     *       library B at 2.0.0. Both differ from their respective last_source_project_version of
     *       0.5.0, so both batches should drain. Verify two drained batch keys are produced.</li>
     *   <li><b>Cleanup:</b> Persist the drain result and verify both libraries' pending rows are
     *       deleted and each library's last_source_project_version is updated to its respective
     *       resolved version (1.0.0 for A, 2.0.0 for B).</li>
     * </ol>
     */
    @Test
    void twoLibrariesIndependentStampDrainCleanup() {
        TrackedLibrary libA = new TrackedLibrary("com.example:a", "/projects/a", null, "0.5.0", null);
        TrackedLibrary libB = new TrackedLibrary("com.example:b", "/projects/b", null, "0.5.0", null);
        dataStore.persistTrackedLibrary(libA);
        dataStore.persistTrackedLibrary(libB);
        setupTestMappingWithMethods(10, 20, 30);

        // STAMP: different methods for each library
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:a", "1.0.0", null, new HashSet<>(Arrays.asList(10))));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:b", "2.0.0", null, new HashSet<>(Arrays.asList(20))));

        // DRAIN: both libraries resolved to versions that qualify
        StubMetadataReader reader = new StubMetadataReader(null, null, null) {
            @Override
            public List<ResolvedSourceProjectLibrary> resolveLibrariesInSourceProject(String sourceProjectDir,
                                                                                       List<String> coordinates) {
                List<ResolvedSourceProjectLibrary> result = new ArrayList<>();
                for (String coord : coordinates) {
                    if ("com.example:a".equals(coord)) {
                        result.add(new ResolvedSourceProjectLibrary(coord, "1.0.0", null));
                    } else if ("com.example:b".equals(coord)) {
                        result.add(new ResolvedSourceProjectLibrary(coord, "2.0.0", null));
                    }
                }
                return result;
            }
        };
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Arrays.asList("com.example:a", "com.example:b"), "/projects/source", reader);
        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                new PendingLibraryImpactedMethodsDrainer().drainPendingMethods(dataStore, config, tiaData);

        assertEquals(2, outcome.getDrainResult().getDrainedBatchKeys().size());

        // CLEANUP
        persistWithDrainResult(outcome.getDrainResult());

        assertTrue(dataStore.readPendingLibraryImpactedMethods("com.example:a").isEmpty());
        assertTrue(dataStore.readPendingLibraryImpactedMethods("com.example:b").isEmpty());
        assertEquals("1.0.0", dataStore.readTrackedLibraries().get("com.example:a").getLastSourceProjectVersion());
        assertEquals("2.0.0", dataStore.readTrackedLibraries().get("com.example:b").getLastSourceProjectVersion());
    }

    /**
     * Verifies the full drain → serialize → deserialize → cleanup flow that occurs in Maven-based
     * test projects. In Maven, the plugin JVM performs test selection (including draining) but
     * tests execute in a separate forked JVM. The drain result must be serialized to a file by the
     * plugin JVM, then deserialized by the test listener in the forked JVM so it can be passed to
     * {@link TestRunnerService} for post-test-run cleanup. This test ensures the drain result
     * survives the serialization round-trip and still produces correct cleanup behavior.
     *
     * <p>Steps:
     * <ol>
     *   <li>Set up a tracked library with a pending batch and a test suite mapping.</li>
     *   <li><b>Drain:</b> Run the drainer to produce a drain result with one drained batch.</li>
     *   <li><b>Serialize:</b> Write the drain result to a file using
     *       {@link LibraryImpactDrainResultSerializer#serialize}.</li>
     *   <li><b>Deserialize:</b> Read the drain result back from the file using
     *       {@link LibraryImpactDrainResultSerializer#deserialize}. Verify the deserialized result
     *       has the same drained batch keys as the original.</li>
     *   <li><b>Cleanup:</b> Pass the deserialized drain result through
     *       {@link TestRunnerService#persistTestRunData} and verify the pending rows are deleted
     *       and the tracked library version is updated — confirming the deserialized result drives
     *       cleanup correctly.</li>
     * </ol>
     */
    @Test
    void drainResultSerializationRoundTrip() throws Exception {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, "0.9.0", null);
        dataStore.persistTrackedLibrary(lib);
        setupTestMappingWithMethods(10);

        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0.0", null, new HashSet<>(Arrays.asList(10))));

        StubMetadataReader reader = new StubMetadataReader("1.0.0", "1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"), "/projects/source", reader);
        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                new PendingLibraryImpactedMethodsDrainer().drainPendingMethods(dataStore, config, tiaData);

        // Simulate cross-JVM transport via serialization
        File serFile = new File(tempDir, "drain-result.ser");
        LibraryImpactDrainResultSerializer.serialize(outcome.getDrainResult(), serFile);
        LibraryImpactDrainResult deserialized = LibraryImpactDrainResultSerializer.deserialize(serFile.getAbsolutePath());

        assertNotNull(deserialized);
        assertTrue(deserialized.hasDrainedBatches());
        assertEquals(outcome.getDrainResult().getDrainedBatchKeys().size(),
                deserialized.getDrainedBatchKeys().size());

        // CLEANUP with deserialized result (as would happen in forked test JVM)
        persistWithDrainResult(deserialized);

        assertTrue(dataStore.readPendingLibraryImpactedMethods("com.example:lib").isEmpty());
        assertEquals("1.0.0", dataStore.readTrackedLibraries().get("com.example:lib").getLastSourceProjectVersion());
    }

    /**
     * Verifies that the {@link TrackedLibraryReconciler} correctly removes a library that is no
     * longer in the {@code tiaSourceLibs} configuration, cascade-deletes its pending rows, and
     * that the remaining library's pending batches are unaffected and can still be drained and
     * cleaned up normally. This simulates the scenario where a user removes a library from their
     * configuration after it has already accumulated pending impacted methods.
     *
     * <p>Steps:
     * <ol>
     *   <li>Set up two tracked libraries ("com.example:keep" and "com.example:remove") with
     *       pending batches for each, and test suite mappings for their methods.</li>
     *   <li><b>Reconcile:</b> Run the reconciler with a config that only declares "com.example:keep".
     *       The reconciler deletes "com.example:remove" from the tracked libraries table, and the
     *       foreign key cascade automatically deletes its pending rows.</li>
     *   <li>Verify "com.example:remove" is gone from both the tracked libraries and pending tables,
     *       while "com.example:keep" and its pending batch remain intact.</li>
     *   <li><b>Drain:</b> Run the drainer for the remaining "com.example:keep" library and verify
     *       its pending batch drains successfully.</li>
     *   <li><b>Cleanup:</b> Persist the drain result and verify the pending rows for
     *       "com.example:keep" are deleted.</li>
     * </ol>
     */
    @Test
    void reconcilerRemovesLibraryThenStampDrainWorkForRemaining() {
        TrackedLibrary libKeep = new TrackedLibrary("com.example:keep", "/projects/keep", null, "0.5.0", null);
        TrackedLibrary libRemove = new TrackedLibrary("com.example:remove", "/projects/remove", null, null, null);
        dataStore.persistTrackedLibrary(libKeep);
        dataStore.persistTrackedLibrary(libRemove);

        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:remove", "1.0.0", null, new HashSet<>(Arrays.asList(10))));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:keep", "1.0.0", null, new HashSet<>(Arrays.asList(20))));

        setupTestMappingWithMethods(10, 20);

        // Reconcile: config only declares "keep", so "remove" gets deleted (cascade cleans pending)
        StubMetadataReader reader = new StubMetadataReader("1.0.0", "1.0.0", null);
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:keep"), "/projects/source", reader);
        TrackedLibraryReconciler reconciler = new TrackedLibraryReconciler();
        reconciler.reconcile(dataStore, config);

        assertFalse(dataStore.readTrackedLibraries().containsKey("com.example:remove"));
        assertTrue(dataStore.readPendingLibraryImpactedMethods("com.example:remove").isEmpty());

        // "keep" library pending rows still exist
        assertEquals(1, dataStore.readPendingLibraryImpactedMethods("com.example:keep").size());

        // DRAIN the remaining library
        TiaData tiaData = dataStore.getTiaData(true);
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                new PendingLibraryImpactedMethodsDrainer().drainPendingMethods(dataStore, config, tiaData);

        assertTrue(outcome.getDrainResult().hasDrainedBatches());

        // CLEANUP
        persistWithDrainResult(outcome.getDrainResult());
        assertTrue(dataStore.readPendingLibraryImpactedMethods("com.example:keep").isEmpty());
    }

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

        if (methodIds.length > 2) {
            TestSuiteTracker testC = new TestSuiteTracker("com.example.TestC");
            testC.setClassesImpacted(Collections.singletonList(
                    new ClassImpactTracker("com/example/Third.java",
                            new HashSet<>(Arrays.asList(methodIds[2])))));
            testSuites.put("com.example.TestC", testC);
        }

        Map<Integer, MethodImpactTracker> methodTrackers = new HashMap<>();
        for (int id : methodIds) {
            methodTrackers.put(id, new MethodImpactTracker("com.example.Method" + id, 1, 10));
        }
        tiaData.setMethodsTracked(methodTrackers);
        tiaData.setTestSuitesTracked(testSuites);
        dataStore.persistCoreData(tiaData);
        dataStore.persistTestSuites(testSuites);
        dataStore.persistSourceMethods(methodTrackers);
    }

    private void persistWithDrainResult(LibraryImpactDrainResult drainResult) {
        TestRunnerService service = new TestRunnerService(dataStore);
        TestRunResult testRunResult = new TestRunResult(
                new HashMap<>(), new HashSet<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>(), new TestStats(), drainResult);
        service.persistTestRunData(true, false, "newcommit", testRunResult);
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
