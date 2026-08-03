package org.tiatesting.core.library;

import org.junit.jupiter.api.*;
import org.tiatesting.core.model.*;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.staticselection.StaticTestSelectionRuleMode;

import java.io.File;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Consumer-side coverage for {@link PendingLibraryImpactedMethodsDrainer}'s forced-selection drain:
 * forced batches are gated on the same resolved-build lookup and hold rules as method-stamp
 * batches, then resolved against the consumer's own tracked suites (RUN_ALL / SUITE_NAMES) and
 * unioned into the run set. See the drain-rule section of the library publish-time stamping
 * chapter in {@code WIKI.md}.
 */
class PendingLibraryForcedSelectionDrainerTest {

    private static final String LIB = "com.example:lib";

    private JdbcDataStore dataStore;
    private File tempDir;
    private PendingLibraryImpactedMethodsDrainer drainer;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-forced-drainer-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())), BranchSchema.schemaName("test"));
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
     * RUN_ALL forced selection at or below the resolved build seq forces every consumer-tracked
     * suite, and the batch is recorded as drained for post-run deletion.
     */
    @Test
    void drainsForcedRunAllWhenResolvedBuildContainsIt() throws Exception {
        // given a tracked library with a forced RUN_ALL batch at seq 1 and the app resolving seq 1's jar
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));
        setupTrackedSuites("com.acme.AaaTest", "com.acme.BbbIT");
        File jar1 = jarFile("jar1-content");
        publishWithForcedSelection("1.0-SNAPSHOT", LibraryJarHasher.computeSha256Hash(jar1),
                new PendingLibraryForcedSelection(LIB, "1.0-SNAPSHOT", 0L, "sql-run-all",
                        StaticTestSelectionRuleMode.RUN_ALL, Collections.<String>emptyList()));

        // when
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome = drainer.drainPendingMethods(
                dataStore, configResolving("1.0-SNAPSHOT", jar1.getAbsolutePath()), tracked("com.acme.AaaTest", "com.acme.BbbIT"));

        // then all tracked suites are selected and the forced batch is marked drained
        assertEquals(new HashSet<>(Arrays.asList("com.acme.AaaTest", "com.acme.BbbIT")),
                new HashSet<>(outcome.getTestsToAdd()));
        assertEquals(1, outcome.getDrainResult().getDrainedForcedBatchKeys().size());
        assertTrue(outcome.getDrainResult().hasDrainedBatches());
    }

    /**
     * SUITE_NAMES forced selection resolves against the consumer's own tracked suites, not any
     * suite snapshot recorded at publish time - only the matching subset is unioned in.
     */
    @Test
    void drainsForcedSuiteNamesAgainstConsumerTrackedSuites() throws Exception {
        // given a forced SUITE_NAMES batch matching suites ending in "IT"
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));
        setupTrackedSuites("com.acme.AaaTest", "com.acme.BbbIT");
        File jar1 = jarFile("jar1-content");
        publishWithForcedSelection("1.0-SNAPSHOT", LibraryJarHasher.computeSha256Hash(jar1),
                new PendingLibraryForcedSelection(LIB, "1.0-SNAPSHOT", 0L, "it-suites",
                        StaticTestSelectionRuleMode.SUITE_NAMES, Collections.singletonList(".*IT$")));

        // when
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome = drainer.drainPendingMethods(
                dataStore, configResolving("1.0-SNAPSHOT", jar1.getAbsolutePath()), tracked("com.acme.AaaTest", "com.acme.BbbIT"));

        // then only the matching suite is selected
        assertEquals(Collections.singleton("com.acme.BbbIT"), new HashSet<>(outcome.getTestsToAdd()));
        assertEquals(1, outcome.getDrainResult().getDrainedForcedBatchKeys().size());
    }

    /**
     * A forced batch above the resolved build's sequence is held, exactly like a method-stamp
     * batch - draining it would force tests against a jar that does not yet contain the rule
     * match.
     */
    @Test
    void holdsForcedBatchAboveResolvedSeq() throws Exception {
        // given publishes at seq 1 (unstamped) then seq 2 (forced RUN_ALL), app resolving seq 1's jar
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));
        setupTrackedSuites("com.acme.AaaTest");
        File jar1 = jarFile("jar1-content");
        publishWithNoForcedSelection("1.0-SNAPSHOT", LibraryJarHasher.computeSha256Hash(jar1));
        publishWithForcedSelection("1.0-SNAPSHOT", "H2",
                new PendingLibraryForcedSelection(LIB, "1.0-SNAPSHOT", 0L, "sql-run-all",
                        StaticTestSelectionRuleMode.RUN_ALL, Collections.<String>emptyList()));

        // when the drain runs against the older resolved jar (seq 1)
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome = drainer.drainPendingMethods(
                dataStore, configResolving("1.0-SNAPSHOT", jar1.getAbsolutePath()), tracked("com.acme.AaaTest"));

        // then nothing drains - the forced batch at seq 2 is above the resolved seq 1
        assertTrue(outcome.getTestsToAdd().isEmpty());
        assertTrue(outcome.getDrainResult().getDrainedForcedBatchKeys().isEmpty());
        assertFalse(outcome.getDrainResult().hasDrainedBatches());
    }

    /**
     * Forced and method-stamp batches at the same resolved seq both apply in the same drain run,
     * and their selected tests are unioned together.
     */
    @Test
    void forcedAndMethodBatchesAtSameSeqBothApply() throws Exception {
        // given a publish stamping method 10 (covered by TestA) and forcing SUITE_NAMES matching TestB
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));
        setupTrackedSuitesWithMethod("com.acme.AaaTest", 10);
        Map<String, TestSuiteTracker> tracked = tracked("com.acme.AaaTest", "com.acme.BbbIT");
        File jar1 = jarFile("jar1-content");
        dataStore.persistLibraryPublish(
                new LibraryPublish(LIB, "1.0-SNAPSHOT", LibraryJarHasher.computeSha256Hash(jar1), "commit", System.currentTimeMillis()),
                new HashSet<>(Arrays.asList(10)),
                Collections.singletonList(new PendingLibraryForcedSelection(LIB, "1.0-SNAPSHOT", 0L, "it-suites",
                        StaticTestSelectionRuleMode.SUITE_NAMES, Collections.singletonList(".*IT$"))));

        // when
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome = drainer.drainPendingMethods(
                dataStore, configResolving("1.0-SNAPSHOT", jar1.getAbsolutePath()), tracked);

        // then both the method-covering test and the forced-matching test are selected
        assertEquals(new HashSet<>(Arrays.asList("com.acme.AaaTest", "com.acme.BbbIT")),
                new HashSet<>(outcome.getTestsToAdd()));
        assertEquals(1, outcome.getDrainResult().getDrainedBatchKeys().size());
        assertEquals(1, outcome.getDrainResult().getDrainedForcedBatchKeys().size());
    }

    /**
     * Persist a publish (ledger row only, no stamps) for the tracked library.
     *
     * @param version the published version
     * @param jarHash the published jar's hash
     * @return the assigned publish sequence
     */
    private long publishWithNoForcedSelection(String version, String jarHash) {
        return dataStore.persistLibraryPublish(
                new LibraryPublish(LIB, version, jarHash, "commit", System.currentTimeMillis()),
                Collections.<Integer>emptySet(), Collections.<PendingLibraryForcedSelection>emptyList());
    }

    /**
     * Persist a publish (ledger row + one forced-selection batch) for the tracked library,
     * mirroring the publish task.
     *
     * @param version the published version
     * @param jarHash the published jar's hash
     * @param forced the forced-selection batch to persist against the assigned sequence
     * @return the assigned publish sequence
     */
    private long publishWithForcedSelection(String version, String jarHash, PendingLibraryForcedSelection forced) {
        return dataStore.persistLibraryPublish(
                new LibraryPublish(LIB, version, jarHash, "commit", System.currentTimeMillis()),
                Collections.<Integer>emptySet(), Collections.singletonList(forced));
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
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(jar)) {
            fos.write(content.getBytes());
        }
        return jar;
    }

    /**
     * Persist tracked-suite rows (names only, no coverage) for the given suite names, both into
     * TiaData and the test_suite table, matching how the selector's targeted read loads them.
     *
     * @param suiteNames the suite names to track
     */
    private void setupTrackedSuites(String... suiteNames) {
        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("abc123");
        tiaData.setLastUpdated(Instant.now());

        Map<String, TestSuiteTracker> testSuites = tracked(suiteNames);
        tiaData.setTestSuitesTracked(testSuites);
        dataStore.persistCoreData(tiaData);
        dataStore.persistTestSuites(testSuites);
    }

    /**
     * Persist one tracked suite covering the given source method id, so a method-stamp batch on
     * that id resolves to the suite via {@link org.tiatesting.core.persistence.DataStore#getTestSuitesForMethods}.
     *
     * @param suiteName the suite name
     * @param methodId the source method id the suite covers
     */
    private void setupTrackedSuitesWithMethod(String suiteName, int methodId) {
        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("abc123");
        tiaData.setLastUpdated(Instant.now());

        TestSuiteTracker suite = new TestSuiteTracker(suiteName);
        suite.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker("com/example/Service.java", new HashSet<>(Arrays.asList(methodId)))));
        Map<String, TestSuiteTracker> testSuites = new HashMap<>();
        testSuites.put(suiteName, suite);

        tiaData.setTestSuitesTracked(testSuites);
        dataStore.persistCoreData(tiaData);
        dataStore.persistTestSuites(testSuites);
    }

    /**
     * Build an in-memory tracked-suites map (names only) for passing into the drain call.
     *
     * @param suiteNames the suite names
     * @return the tracked-suites map keyed by suite name
     */
    private Map<String, TestSuiteTracker> tracked(String... suiteNames) {
        Map<String, TestSuiteTracker> tracked = new LinkedHashMap<>();
        for (String name : suiteNames) {
            tracked.put(name, new TestSuiteTracker(name));
        }
        return tracked;
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
