package org.tiatesting.core.library;

import org.junit.jupiter.api.*;
import org.tiatesting.core.model.*;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Seq-drain coverage for {@link PendingLibraryImpactedMethodsDrainer} - the design's scenarios:
 * leapfrog (drain everything at or below the resolved build), release-version fallback lookup,
 * stale-resolve hold, unknown-build hold, downgrade guard, snapshot version strings never
 * identifying a build, and the no-pending fast path that skips library resolution entirely.
 * See {@code DESIGN-publish-time-stamping.md} section 2.2.
 */
class PendingLibraryImpactedMethodsDrainerTest {

    private static final String LIB = "com.example:lib";

    private H2DataStore dataStore;
    private File tempDir;
    private PendingLibraryImpactedMethodsDrainer drainer;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-drainer-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
        dataStore.getTiaData(true);
        drainer = new PendingLibraryImpactedMethodsDrainer();
    }

    @AfterEach
    void tearDown() {
        dataStore.close();
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * Leapfrog: the app skips a snapshot build and resolves a later one. Looking the resolved
     * jar up by hash gives its seq, and every pending batch at or below it drains - including
     * the skipped build's batch, whose changes are physically inside the resolved jar.
     */
    @Test
    void drainsAllBatchesAtOrBelowResolvedBuildSeq() throws Exception {
        // given publishes at seq 1..3 (seq 2 and 3 stamped) and the app resolving seq 3's jar
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));
        setupTestMappingWithMethods(10, 20);
        publish("1.0-SNAPSHOT", "H1", Collections.emptySet());
        publish("1.0-SNAPSHOT", "H2", new HashSet<>(Arrays.asList(10)));
        File jar3 = jarFile("jar3-content");
        publish("1.0-SNAPSHOT", LibraryJarHasher.computeSha256Hash(jar3), new HashSet<>(Arrays.asList(20)));

        // when the drain runs with the app resolving seq 3's jar
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome = drainer.drainPendingMethods(
                dataStore, configResolving("1.0-SNAPSHOT", jar3.getAbsolutePath()));

        // then both pending batches (seq 2 and 3) drain and both covering tests are selected
        assertEquals(2, outcome.getDrainResult().getDrainedBatchKeys().size());
        assertTrue(outcome.getTestsToAdd().contains("com.example.TestA"));
        assertTrue(outcome.getTestsToAdd().contains("com.example.TestB"));
        assertEquals(Long.valueOf(3L), outcome.getDrainResult().getAppliedSeqByLibrary().get(LIB));
    }

    /**
     * Release upgrade with the version fallback: the resolved release jar's hash is not in the
     * ledger (e.g. repackaged by the repository), but the exact release version identifies the
     * publish row, and everything at or below it drains.
     */
    @Test
    void drainsViaExactReleaseVersionWhenHashUnknown() throws Exception {
        // given a stamped snapshot publish (seq 1) and its release publish (seq 2, version 1.0.0)
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));
        setupTestMappingWithMethods(10);
        publish("1.0.0-SNAPSHOT", "H1", new HashSet<>(Arrays.asList(10)));
        publish("1.0.0", "H2", Collections.emptySet());

        // when the app resolves release 1.0.0 with a jar hash the ledger has never seen
        File unknownJar = jarFile("repackaged-content");
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome = drainer.drainPendingMethods(
                dataStore, configResolving("1.0.0", unknownJar.getAbsolutePath()));

        // then the version fallback identifies seq 2 and the seq-1 stamp drains
        assertEquals(1, outcome.getDrainResult().getDrainedBatchKeys().size());
        assertEquals(1L, outcome.getDrainResult().getDrainedBatchKeys().get(0).getPublishSeq());
        assertTrue(outcome.getTestsToAdd().contains("com.example.TestA"));
        assertEquals(Long.valueOf(2L), outcome.getDrainResult().getAppliedSeqByLibrary().get(LIB));
    }

    /**
     * Stale resolve: the app resolves an older build (seq 2) while a newer stamped publish
     * (seq 3) is pending. The pending batch is above the resolved seq, so it is held - draining
     * it would test a change against a jar that does not contain it (a false green).
     */
    @Test
    void holdsBatchStampedAboveResolvedBuild() throws Exception {
        // given publishes at seq 1..3 with the seq-3 publish stamped, and the app resolving seq 2's jar
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));
        setupTestMappingWithMethods(10);
        publish("1.0-SNAPSHOT", "H1", Collections.emptySet());
        File jar2 = jarFile("jar2-content");
        publish("1.0-SNAPSHOT", LibraryJarHasher.computeSha256Hash(jar2), Collections.emptySet());
        publish("1.0-SNAPSHOT", "H3", new HashSet<>(Arrays.asList(10)));

        // when the drain runs against the older resolved jar
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome = drainer.drainPendingMethods(
                dataStore, configResolving("1.0-SNAPSHOT", jar2.getAbsolutePath()));

        // then nothing drains - the seq-3 stamp is held until a build >= seq 3 is resolved
        assertFalse(outcome.getDrainResult().hasDrainedBatches());
        assertTrue(outcome.getTestsToAdd().isEmpty());
    }

    /**
     * Unknown build: the resolved snapshot jar matches no ledger row (published before the
     * ledger existed, or its publish skipped stamping). All pending stamps are held with a
     * warning - holding cannot produce a false green; draining blindly could.
     */
    @Test
    void holdsAllBatchesWhenResolvedBuildUnknownToLedger() throws Exception {
        // given a stamped publish and the app resolving a jar the ledger has never seen
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));
        setupTestMappingWithMethods(10);
        publish("1.0-SNAPSHOT", "H1", new HashSet<>(Arrays.asList(10)));

        File unknownJar = jarFile("never-published-content");
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome = drainer.drainPendingMethods(
                dataStore, configResolving("1.0-SNAPSHOT", unknownJar.getAbsolutePath()));

        // then everything is held
        assertFalse(outcome.getDrainResult().hasDrainedBatches());
        assertTrue(outcome.getTestsToAdd().isEmpty());
        assertEquals(1, dataStore.readPendingLibraryImpactedMethods(LIB).size());
    }

    /**
     * A snapshot version string is shared by every snapshot build, so it must never be used to
     * identify a build: with an unknown hash and a SNAPSHOT resolved version, the lookup must
     * not fall back to version matching even when a ledger row carries that version string.
     */
    @Test
    void snapshotResolvedVersionNeverIdentifiesABuild() throws Exception {
        // given a stamped snapshot publish whose version string matches the resolved version
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));
        setupTestMappingWithMethods(10);
        publish("1.0-SNAPSHOT", "H1", new HashSet<>(Arrays.asList(10)));

        // when the app resolves an unknown jar under the same SNAPSHOT version string
        File unknownJar = jarFile("some-other-snapshot-build");
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome = drainer.drainPendingMethods(
                dataStore, configResolving("1.0-SNAPSHOT", unknownJar.getAbsolutePath()));

        // then the version string is not used as identity and the batch is held
        assertFalse(outcome.getDrainResult().hasDrainedBatches());
    }

    /**
     * Downgrade guard: the resolved build's seq is below the library's lastAppliedSeq (the
     * dependency was rolled back). Nothing drains and the warning path fires - those tests
     * already ran against newer code.
     */
    @Test
    void holdsEverythingWhenResolvedBuildBelowLastApplied() throws Exception {
        // given lastAppliedSeq=3 and a leftover pending batch at seq 2, resolving seq 2's jar
        TrackedLibrary lib = new TrackedLibrary(LIB, "/projects/lib", null);
        lib.setLastAppliedSeq(3L);
        dataStore.persistTrackedLibrary(lib);
        setupTestMappingWithMethods(10);
        publish("1.0-SNAPSHOT", "H1", Collections.emptySet());
        File jar2 = jarFile("jar2-content");
        publish("1.0-SNAPSHOT", LibraryJarHasher.computeSha256Hash(jar2), new HashSet<>(Arrays.asList(10)));
        publish("1.0-SNAPSHOT", "H3", Collections.emptySet());

        // when the drain runs against the downgraded resolve
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome = drainer.drainPendingMethods(
                dataStore, configResolving("1.0-SNAPSHOT", jar2.getAbsolutePath()));

        // then the downgrade guard holds everything, even batches at or below the resolved seq
        assertFalse(outcome.getDrainResult().hasDrainedBatches());
        assertTrue(outcome.getTestsToAdd().isEmpty());
    }

    /**
     * A library with pending stamps that cannot be resolved on the source project classpath is
     * held - there is no jar to test against.
     */
    @Test
    void holdsBatchesWhenLibraryNotResolvable() {
        // given a stamped publish but a metadata reader that resolves nothing
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));
        setupTestMappingWithMethods(10);
        publish("1.0-SNAPSHOT", "H1", new HashSet<>(Arrays.asList(10)));

        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList(LIB), null, "/projects/source",
                new StubMetadataReader(null, null));

        // when the drain runs
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome = drainer.drainPendingMethods(dataStore, config);

        // then everything is held
        assertFalse(outcome.getDrainResult().hasDrainedBatches());
        assertTrue(outcome.getTestsToAdd().isEmpty());
    }

    /**
     * The no-pending fast path: with nothing pending, the drain returns before resolving any
     * library on the source project - resolution is expensive (POM/Gradle model loads) and the
     * common case must not pay it.
     */
    @Test
    void skipsLibraryResolutionWhenNothingPending() {
        // given a tracked library with no pending stamps and a reader that fails on resolution
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));

        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList(LIB), null, "/projects/source",
                new StubMetadataReader(null, null) {
                    @Override
                    public List<ResolvedSourceProjectLibrary> resolveLibrariesInSourceProject(
                            String sourceProjectDir, List<String> coordinates) {
                        throw new AssertionError("resolution must not run when nothing is pending");
                    }
                });

        // when the drain runs
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome = drainer.drainPendingMethods(dataStore, config);

        // then it returns empty without touching resolution
        assertFalse(outcome.getDrainResult().hasDrainedBatches());
        assertTrue(outcome.getTestsToAdd().isEmpty());
    }

    /**
     * Persist a publish (ledger row + stamp) for the tracked library, mirroring the publish task.
     *
     * @param version the published version
     * @param jarHash the published jar's hash
     * @param methodIds the stamped method ids (empty for an unstamped publish)
     * @return the assigned publish sequence
     */
    private long publish(String version, String jarHash, Set<Integer> methodIds) {
        return dataStore.persistLibraryPublish(
                new LibraryPublish(LIB, version, jarHash, "commit", System.currentTimeMillis()), methodIds);
    }

    /**
     * Build a config whose metadata reader resolves the library at the given version and jar path.
     *
     * @param resolvedVersion the version resolved on the app classpath
     * @param jarFilePath the resolved jar path (hashed by the drain for the ledger lookup)
     * @return the library config for the drain
     */
    private LibraryImpactAnalysisConfig configResolving(String resolvedVersion, String jarFilePath) {
        return new LibraryImpactAnalysisConfig(Collections.singletonList(LIB), null, "/projects/source",
                new StubMetadataReader(resolvedVersion, jarFilePath));
    }

    /**
     * Create a file with the given content to act as a resolved jar.
     *
     * @param content the file content (drives the hash)
     * @return the created file
     */
    private File jarFile(String content) throws Exception {
        File jar = new File(tempDir, "jar-" + content.hashCode() + ".jar");
        try (FileOutputStream fos = new FileOutputStream(jar)) {
            fos.write(content.getBytes());
        }
        return jar;
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

    /**
     * Stub reader resolving the library at a fixed version and jar path on the source project.
     * Declared-version reads are unused by the drain and return empty.
     */
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
