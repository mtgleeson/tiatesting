package org.tiatesting.core.library;

import org.tiatesting.core.testrunner.TestJvmContribution;
import org.tiatesting.core.testrunner.TestRunResultFixture;
import org.junit.jupiter.api.*;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.model.*;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.testrunner.RunAttempt;
import org.tiatesting.core.testrunner.TestRunResult;
import org.tiatesting.core.testrunner.TestRunnerService;
import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.MappingDrift;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for the publish-time stamping lifecycle: the library's publish
 * task stamps ({@link LibraryPublishStamper}), the consumer's drain selects
 * ({@link PendingLibraryImpactedMethodsDrainer}) and the post-test-run persist cleans up
 * ({@link TestRunnerService}). Each test walks publish -> resolve -> drain -> cleanup against a
 * real H2 datastore. See the library publish-time stamping chapter in {@code WIKI.md}.
 */
class LibraryImpactEndToEndTest {

    private static final String LIB = "com.example:lib";
    private static final String LIB_SRC_DIR = "/projects/lib/src/main/java";
    private static final String LIB_FILE_KEY = "com/example/Service.java";
    private static final int METHOD_ID = 10;

    private JdbcDataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-e2e-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())), BranchSchema.schemaName("test", null));
        dataStore.getTiaData();

        TiaData tiaData = dataStore.getTiaData();
        tiaData.setCommitValue("initial");
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);
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
     * The full lifecycle: seed the baseline via the first publish, stamp a change on the second
     * publish, resolve that build on the consumer, drain its covering tests, and clean up after
     * the run - the stamp is deleted, last_applied_seq advances to the resolved seq and the
     * mapping baseline moves to the commit the resolved build was published at.
     */
    @Test
    void publishStampDrainCleanupLifecycle() throws Exception {
        // given a tracked library with a seeded mapping and a seeded first publish
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", LIB_SRC_DIR));
        setupTestMappingWithMethods(METHOD_ID);
        LibraryPublishStamper stamper = new LibraryPublishStamper();
        File jar1 = jarFile("build-1");
        stamper.stampPublish(dataStore, new StubVCSReader("c0"), LIB, "1.0.0-SNAPSHOT", jar1.getAbsolutePath(),
                StaticTestSelectionConfig.EMPTY, Collections.<String>emptyList());

        // PUBLISH: the second publish diffs baseline c0 -> HEAD and stamps the changed method
        File jar2 = jarFile("build-2");
        LibraryPublishStamper.PublishStampResult stamp = stamper.stampPublish(dataStore,
                new StubVCSReader("c1", libraryDiff()), LIB, "1.0.0-SNAPSHOT", jar2.getAbsolutePath(),
                StaticTestSelectionConfig.EMPTY, Collections.<String>emptyList());
        assertEquals(LibraryPublishStamper.PublishStampResult.Outcome.STAMPED, stamp.getOutcome());
        assertEquals(2L, stamp.getPublishSeq());
        assertEquals(Collections.singleton(METHOD_ID), stamp.getStampedMethodIds());

        // DRAIN: the consumer resolves build 2's jar; the ledger identifies seq 2 and the stamp drains
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                new PendingLibraryImpactedMethodsDrainer().drainPendingMethods(
                        dataStore, configResolving("1.0.0-SNAPSHOT", jar2.getAbsolutePath()),
                        Collections.<String, TestSuiteTracker>emptyMap(), false);
        assertTrue(outcome.getDrainResult().hasDrainedBatches());
        assertTrue(outcome.getTestsToAdd().contains("com.example.TestA"));

        // CLEANUP: the post-test-run persist deletes the stamp and advances the library state
        persistWithDrainResult(outcome.getDrainResult(), "newcommit");
        assertTrue(dataStore.readPendingLibraryImpactedMethods(LIB).isEmpty());
        TrackedLibrary updated = dataStore.readTrackedLibraries().get(LIB);
        assertEquals(Long.valueOf(2L), updated.getLastAppliedSeq());
        assertEquals("c1", updated.getMappingBaselineCommit());
    }

    /**
     * Crash safety: if the run crashes before the cleanup persists, the stamps stay pending and
     * the next drain against the same resolved build re-drains them - overselection only, never
     * underselection.
     */
    @Test
    void crashBeforeCleanupReDrainsOnNextRun() throws Exception {
        // given a stamped publish drained once with NO cleanup applied (simulated crash)
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", LIB_SRC_DIR));
        setupTestMappingWithMethods(METHOD_ID);
        File jar = jarFile("build-1");
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.0.0-SNAPSHOT",
                LibraryJarHasher.computeSha256Hash(jar), "c1", 1000L),
                new HashSet<>(Collections.singletonList(METHOD_ID)),
                Collections.<PendingLibraryForcedSelection>emptyList());

        LibraryImpactAnalysisConfig config = configResolving("1.0.0-SNAPSHOT", jar.getAbsolutePath());
        PendingLibraryImpactedMethodsDrainer drainer = new PendingLibraryImpactedMethodsDrainer();
        PendingLibraryImpactedMethodsDrainer.DrainOutcome first = drainer.drainPendingMethods(
                dataStore, config, Collections.<String, TestSuiteTracker>emptyMap(), false);
        assertTrue(first.getDrainResult().hasDrainedBatches());
        // (crash: persistTestRunData never runs - stamps and applied seq unchanged)

        // when the next run drains again
        PendingLibraryImpactedMethodsDrainer.DrainOutcome second = drainer.drainPendingMethods(
                dataStore, config, Collections.<String, TestSuiteTracker>emptyMap(), false);

        // then the same batch re-drains with the same tests - self-correcting overselection
        assertTrue(second.getDrainResult().hasDrainedBatches());
        assertTrue(second.getTestsToAdd().contains("com.example.TestA"));
    }

    /**
     * Deleting a tracked library cascade-deletes its publish ledger and pending stamp rows -
     * the reconciliation flow for a library removed from {@code tiaSourceLibs}.
     */
    @Test
    void libraryRemovalCascadesLedgerAndPendingRows() {
        // given a tracked library with a ledger row and a stamp
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.0.0", "H1", "c1", 1000L),
                new HashSet<>(Arrays.asList(10, 20)), Collections.<PendingLibraryForcedSelection>emptyList());
        assertEquals(1, dataStore.readLibraryPublishes(LIB).size());
        assertEquals(1, dataStore.readPendingLibraryImpactedMethods(LIB).size());

        // when the tracked library is deleted
        dataStore.deleteTrackedLibrary(LIB);

        // then the ledger and pending rows are gone
        assertTrue(dataStore.readLibraryPublishes(LIB).isEmpty());
        assertTrue(dataStore.readPendingLibraryImpactedMethods(LIB).isEmpty());
        assertFalse(dataStore.readTrackedLibraries().containsKey(LIB));
    }

    /**
     * Two independent libraries publish, drain and clean up independently, each against its own
     * ledger sequence.
     */
    @Test
    void twoLibrariesIndependentLifecycles() throws Exception {
        // given two tracked libraries, each with one stamped publish
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:a", "/projects/a", null));
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:b", "/projects/b", null));
        setupTestMappingWithMethods(10, 20);
        File jarA = jarFile("build-a");
        File jarB = jarFile("build-b");
        dataStore.persistLibraryPublish(new LibraryPublish("com.example:a", "1.0.0",
                LibraryJarHasher.computeSha256Hash(jarA), "c1", 1000L), new HashSet<>(Arrays.asList(10)),
                Collections.<PendingLibraryForcedSelection>emptyList());
        dataStore.persistLibraryPublish(new LibraryPublish("com.example:b", "2.0.0",
                LibraryJarHasher.computeSha256Hash(jarB), "c2", 2000L), new HashSet<>(Arrays.asList(20)),
                Collections.<PendingLibraryForcedSelection>emptyList());

        // when both libraries resolve their published jars and the drain runs
        Map<String, String> jarByCoord = new HashMap<>();
        jarByCoord.put("com.example:a", jarA.getAbsolutePath());
        jarByCoord.put("com.example:b", jarB.getAbsolutePath());
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Arrays.asList("com.example:a", "com.example:b"), null, "/projects/source",
                new PerCoordinateReader(jarByCoord));
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                new PendingLibraryImpactedMethodsDrainer().drainPendingMethods(
                        dataStore, config, Collections.<String, TestSuiteTracker>emptyMap(), false);

        // then both drain, and cleanup advances each library independently
        assertEquals(2, outcome.getDrainResult().getDrainedBatchKeys().size());
        persistWithDrainResult(outcome.getDrainResult(), "newcommit");
        assertTrue(dataStore.readPendingLibraryImpactedMethods("com.example:a").isEmpty());
        assertTrue(dataStore.readPendingLibraryImpactedMethods("com.example:b").isEmpty());
        assertEquals(Long.valueOf(1L), dataStore.readTrackedLibraries().get("com.example:a").getLastAppliedSeq());
        assertEquals(Long.valueOf(1L), dataStore.readTrackedLibraries().get("com.example:b").getLastAppliedSeq());
    }

    /**
     * The Maven fork transport: the drain result survives serialize/deserialize and the
     * deserialized result still drives the cleanup correctly.
     */
    @Test
    void drainResultSerializationRoundTripDrivesCleanup() throws Exception {
        // given a stamped publish drained against its resolved jar
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));
        setupTestMappingWithMethods(METHOD_ID);
        File jar = jarFile("build-1");
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.0.0",
                LibraryJarHasher.computeSha256Hash(jar), "c1", 1000L),
                new HashSet<>(Collections.singletonList(METHOD_ID)),
                Collections.<PendingLibraryForcedSelection>emptyList());
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                new PendingLibraryImpactedMethodsDrainer().drainPendingMethods(
                        dataStore, configResolving("1.0.0", jar.getAbsolutePath()),
                        Collections.<String, TestSuiteTracker>emptyMap(), false);

        // when the drain result crosses the plugin-to-fork boundary via serialization
        File serFile = new File(tempDir, "drain-result.ser");
        LibraryImpactDrainResultSerializer.serialize(outcome.getDrainResult(), serFile);
        LibraryImpactDrainResult deserialized =
                LibraryImpactDrainResultSerializer.deserialize(serFile.getAbsolutePath());

        // then the deserialized result drives the cleanup
        assertNotNull(deserialized);
        persistWithDrainResult(deserialized, "newcommit");
        assertTrue(dataStore.readPendingLibraryImpactedMethods(LIB).isEmpty());
        assertEquals(Long.valueOf(1L), dataStore.readTrackedLibraries().get(LIB).getLastAppliedSeq());
    }

    /**
     * A library change committed before the consumer's sealed commit but released after it must
     * still be stamped. The consumer's jar was published at changelist 480; the library is edited
     * again at 490 (unreleased); the consumer seals at 500 while still holding the 480 jar. The
     * release at 510 contains the 490 edit, which the consumer's jar does not - so the publish
     * must stamp it. Diffing from the consumer's sealed commit (500) would miss it.
     */
    @Test
    void libraryChangeCommittedBeforeTheConsumerSealsIsStampedWhenReleasedLater() throws Exception {
        // given a library seeded at 470, edited at 475 and released at 480, with the consumer
        // draining that release and sealing at 500 - after a further unreleased edit at 490
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", LIB_SRC_DIR));
        setupTestMappingWithMethods(METHOD_ID);
        LibraryPublishStamper stamper = new LibraryPublishStamper();
        long[] editChangelists = {475L, 490L};
        stamper.stampPublish(dataStore, new ChangelistVCSReader(470L, editChangelists), LIB, "1.0",
                jarFile("build-470").getAbsolutePath(), StaticTestSelectionConfig.EMPTY,
                Collections.<String>emptyList());
        File jar480 = jarFile("build-480");
        stamper.stampPublish(dataStore, new ChangelistVCSReader(480L, editChangelists), LIB, "1.1",
                jar480.getAbsolutePath(), StaticTestSelectionConfig.EMPTY, Collections.<String>emptyList());
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                new PendingLibraryImpactedMethodsDrainer().drainPendingMethods(
                        dataStore, configResolving("1.1", jar480.getAbsolutePath()),
                        Collections.<String, TestSuiteTracker>emptyMap(), false);
        assertTrue(outcome.getDrainResult().hasDrainedBatches());
        persistWithDrainResult(outcome.getDrainResult(), "500");
        // The cleanup helper's run result carries no suites, which empties the mapping; put back
        // what the real run would have re-captured for the suite covering the drained method.
        setupTestMappingWithMethods(METHOD_ID);

        // when the library is released at 510, containing the edit made at 490
        LibraryPublishStamper.PublishStampResult stamp = stamper.stampPublish(dataStore,
                new ChangelistVCSReader(510L, editChangelists), LIB, "1.2",
                jarFile("build-510").getAbsolutePath(), StaticTestSelectionConfig.EMPTY,
                Collections.<String>emptyList());

        // then the method edited at 490 is stamped, because the consumer's jar does not contain it
        assertEquals(Collections.singleton(METHOD_ID), stamp.getStampedMethodIds());
    }

    /**
     * Build a MODIFY diff for the library source file (line 5 changes inside the tracked
     * method's 2-8 range).
     *
     * @return the diff context
     */
    private static SourceFileDiffContext libraryDiff() {
        String path = LIB_SRC_DIR + "/" + LIB_FILE_KEY;
        return new SourceFileDiffContext(path, path, ChangeType.MODIFY);
    }

    /**
     * Create a file with the given content to act as a built/resolved jar.
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
     * Build a config whose reader resolves the library at the given version and jar path.
     *
     * @param resolvedVersion the version resolved on the app classpath
     * @param jarFilePath the resolved jar path
     * @return the library config
     */
    private LibraryImpactAnalysisConfig configResolving(String resolvedVersion, String jarFilePath) {
        return new LibraryImpactAnalysisConfig(Collections.singletonList(LIB), null, "/projects/source",
                new StubMetadataReader(resolvedVersion, jarFilePath));
    }

    /**
     * Run the post-test-run persist applying the given drain result (selective run).
     *
     * @param drainResult the drain result to apply
     * @param commitValue the commit the run seals
     */
    private void persistWithDrainResult(LibraryImpactDrainResult drainResult, String commitValue) {
        TestRunnerService service = new TestRunnerService(dataStore);
        TestRunResult testRunResult = new TestRunResult(
                new HashMap<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>(), new TestStats(), drainResult, 1, TestRunResultFixture.syntheticPlanOutcome(0, 0),
                TestRunSelectionDetails.empty(), new TestJvmContribution(), TestRunResultFixture.runIdentity());
        // history logging is off in this end-to-end test to keep the focus on library drain cleanup
        service.persistTestRunData(true, false, commitValue, "main", System.currentTimeMillis(), testRunResult, null);
    }

    /**
     * Seed the mapping: Service.java's method (first given id) covered by TestA, Other.java's
     * (second id) by TestB. Method line ranges are 2-8.
     *
     * @param methodIds the tracked method ids to seed
     */
    private void setupTestMappingWithMethods(int... methodIds) {
        TiaData tiaData = dataStore.getTiaData();
        tiaData.setCommitValue("abc123");
        tiaData.setLastUpdated(Instant.now());

        Map<String, TestSuiteTracker> testSuites = new HashMap<>();

        TestSuiteTracker testA = new TestSuiteTracker("com.example.TestA");
        testA.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker(LIB_FILE_KEY,
                        new HashSet<>(Arrays.asList(methodIds.length > 0 ? methodIds[0] : 10)))));
        testSuites.put("com.example.TestA", testA);

        if (methodIds.length > 1) {
            TestSuiteTracker testB = new TestSuiteTracker("com.example.TestB");
            testB.setClassesImpacted(Collections.singletonList(
                    new ClassImpactTracker("com/example/Other.java",
                            new HashSet<>(Arrays.asList(methodIds[1])))));
            testSuites.put("com.example.TestB", testB);
        }

        Map<Integer, MethodImpactTracker> methodTrackers = new HashMap<>();
        for (int id : methodIds) {
            methodTrackers.put(id, new MethodImpactTracker("com.example.Method" + id, 2, 8));
        }
        tiaData.setMethodsTracked(methodTrackers);
        tiaData.setTestSuitesTracked(testSuites);
        dataStore.persistCoreData(tiaData);
        dataStore.persistTestSuites(testSuites);
        dataStore.persistSourceMethods(methodTrackers);
    }

    /**
     * Stub VCS reader for the publish stamper: fixed head commit, fixed diff set, and content
     * whose change falls on line 5 (inside the tracked 2-8 range).
     */
    private static final class StubVCSReader implements VCSReader {
        private final String headCommit;
        private final Set<SourceFileDiffContext> diffs;

        StubVCSReader(String headCommit, SourceFileDiffContext... diffs) {
            this.headCommit = headCommit;
            this.diffs = new HashSet<>(Arrays.asList(diffs));
        }

        @Override public String getBranchName() { return "test"; }
        @Override public String getHeadCommit() { return headCommit; }
        @Override public boolean isHeadBehind(String commit) { return false; }

        @Override
        public Set<SourceFileDiffContext> getDiffFiles(String baseChangeNum, List<String> sourceFilesDirs,
                                                       List<String> testFilesDirs, boolean checkLocalChanges) {
            return diffs;
        }

        @Override
        public void loadContentForDiffs(Collection<SourceFileDiffContext> diffsToLoad, String baseChangeNum,
                                        boolean checkLocalChanges) {
            String original = "l1\nl2\nl3\nl4\nl5-old\nl6\nl7\nl8\nl9\nl10\n";
            String changed = "l1\nl2\nl3\nl4\nl5-new\nl6\nl7\nl8\nl9\nl10\n";
            for (SourceFileDiffContext diff : diffsToLoad) {
                diff.setSourceContentOriginal(original);
                diff.setSourceContentNew(changed);
            }
        }

        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            return new HashSet<>();
        }

        /**
         * Content-by-path loading is not exercised by these tests.
         *
         * @param paths unused.
         * @param baseChangeNum unused.
         * @param checkLocalChanges unused.
         * @return never returns.
         */
        @Override
        public Map<String, FileContentPair> loadFileContents(final Set<String> paths, final String baseChangeNum,
                                                             final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("loadFileContents is not used by this test");
        }

        /**
         * No locally changed file differs from its mapped version.
         *
         * @param baseChangeNum ignored
         * @param localDiffs ignored
         * @return {@link MappingDrift#none()}
         */
        @Override
        public MappingDrift findMappingDrift(String baseChangeNum,
                Collection<SourceFileDiffContext> localDiffs) {
            return MappingDrift.none();
        }

        @Override public void close() { }
    }

    /**
     * Stub VCS reader with real range semantics over numeric changelists: a diff from a base
     * reports the library file as modified only when one of the edit changelists falls after the
     * base and at or before the head. Lets a test place a library edit before or after another
     * commit and see whether a given diff range includes it.
     */
    private static final class ChangelistVCSReader implements VCSReader {
        private final long head;
        private final long[] editChangelists;

        /**
         * @param head the workspace head changelist
         * @param editChangelists the changelists at which the library file was edited
         */
        ChangelistVCSReader(long head, long[] editChangelists) {
            this.head = head;
            this.editChangelists = editChangelists;
        }

        @Override public String getBranchName() { return "test"; }
        @Override public String getHeadCommit() { return String.valueOf(head); }
        @Override public boolean isHeadBehind(String commit) { return false; }

        /**
         * Report the library file as modified when an edit lies in the range (base, head].
         *
         * @param baseChangeNum the changelist to diff from
         * @param sourceFilesDirs ignored
         * @param testFilesDirs ignored
         * @param checkLocalChanges ignored
         * @return the library diff when the range contains an edit, otherwise an empty set
         */
        @Override
        public Set<SourceFileDiffContext> getDiffFiles(String baseChangeNum, List<String> sourceFilesDirs,
                                                       List<String> testFilesDirs, boolean checkLocalChanges) {
            long base = Long.parseLong(baseChangeNum);
            for (long edit : editChangelists) {
                if (edit > base && edit <= head) {
                    return new HashSet<>(Collections.singletonList(libraryDiff()));
                }
            }
            return new HashSet<>();
        }

        /**
         * Load content whose change falls on line 5, inside the tracked 2-8 range.
         *
         * @param diffsToLoad the diffs to give content to
         * @param baseChangeNum ignored
         * @param checkLocalChanges ignored
         */
        @Override
        public void loadContentForDiffs(Collection<SourceFileDiffContext> diffsToLoad, String baseChangeNum,
                                        boolean checkLocalChanges) {
            String original = "l1\nl2\nl3\nl4\nl5-old\nl6\nl7\nl8\nl9\nl10\n";
            String changed = "l1\nl2\nl3\nl4\nl5-new\nl6\nl7\nl8\nl9\nl10\n";
            for (SourceFileDiffContext diff : diffsToLoad) {
                diff.setSourceContentOriginal(original);
                diff.setSourceContentNew(changed);
            }
        }

        /**
         * No repo-wide changed paths: forced selection is not exercised here.
         *
         * @param baseChangeNum ignored
         * @param checkLocalChanges ignored
         * @return an empty set
         */
        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            return new HashSet<>();
        }

        /**
         * Content-by-path loading is not exercised by this test.
         *
         * @param paths unused
         * @param baseChangeNum unused
         * @param checkLocalChanges unused
         * @return never returns
         */
        @Override
        public Map<String, FileContentPair> loadFileContents(final Set<String> paths, final String baseChangeNum,
                                                             final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("loadFileContents is not used by this test");
        }

        /**
         * No locally changed file differs from its mapped version.
         *
         * @param baseChangeNum ignored
         * @param localDiffs ignored
         * @return {@link MappingDrift#none()}
         */
        @Override
        public MappingDrift findMappingDrift(String baseChangeNum,
                Collection<SourceFileDiffContext> localDiffs) {
            return MappingDrift.none();
        }

        @Override public void close() { }
    }

    /**
     * Stub metadata reader resolving the library at a fixed version and jar path.
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
            List<ResolvedSourceProjectLibrary> result = new ArrayList<>();
            for (String coord : coordinates) {
                result.add(new ResolvedSourceProjectLibrary(coord, resolvedVersion, jarFilePath));
            }
            return result;
        }

        @Override
        public List<String> readSourceDirectories(String libraryProjectDir) {
            return Collections.singletonList(LIB_SRC_DIR);
        }
    }

    /**
     * Stub reader resolving each coordinate to its own jar path.
     */
    private static final class PerCoordinateReader implements LibraryMetadataReader {
        private final Map<String, String> jarByCoord;

        PerCoordinateReader(Map<String, String> jarByCoord) {
            this.jarByCoord = jarByCoord;
        }

        @Override
        public List<LibraryBuildMetadata> readLibraryBuildMetadata(String libraryProjectDir, List<String> coordinates) {
            return Collections.emptyList();
        }

        @Override
        public List<ResolvedSourceProjectLibrary> resolveLibrariesInSourceProject(String sourceProjectDir, List<String> coordinates) {
            List<ResolvedSourceProjectLibrary> result = new ArrayList<>();
            for (String coord : coordinates) {
                result.add(new ResolvedSourceProjectLibrary(coord, "resolved", jarByCoord.get(coord)));
            }
            return result;
        }

        @Override
        public List<String> readSourceDirectories(String libraryProjectDir) {
            return Collections.emptyList();
        }
    }
}
