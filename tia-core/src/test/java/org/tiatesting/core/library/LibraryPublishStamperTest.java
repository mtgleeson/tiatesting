package org.tiatesting.core.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.library.LibraryPublishStamper.PublishStampResult;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.LibraryPublish;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.PendingLibraryForcedSelection;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.staticselection.StaticTestSelectionRule;
import org.tiatesting.core.staticselection.StaticTestSelectionRuleMode;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for {@link LibraryPublishStamper}: first-publish baseline seeding, stamping the methods
 * impacted since the mapping baseline (with the ledger row and stamp written together), ledger-only
 * empty-diff publishes, the untracked-library skip, cumulative-since-baseline stamps across
 * successive publishes, and the since-previous-publish dedup filter (a version-only re-publish
 * stamps nothing; only methods in files changed since the previous publish are stamped). See the
 * publish-stamp-task and mapping-baseline sections of the library publish-time stamping chapter in {@code WIKI.md}.
 */
class LibraryPublishStamperTest {

    private static final String LIB = "com.example:lib";
    private static final String LIB_SRC_DIR = "/projects/lib/src/main/java";
    /** Absolute source dir whose trailing two segments ("libs/widget") match the forced-selection
     *  tests' repo-relative changed-file paths - mirrors how {@code readSourceDirectories} records
     *  the library's source dirs in production (always absolute). */
    private static final String LIB_ABS_SRC_DIR = "/home/dev/repo/libs/widget";
    private static final String LIB_FILE_KEY = "com/example/Lib.java";
    private static final String OTHER_FILE_KEY = "com/example/Other.java";
    private static final int METHOD_LIB = 4242;
    private static final int METHOD_OTHER = 4343;

    private JdbcDataStore dataStore;
    private File tempDir;
    private LibraryPublishStamper stamper;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-stamper-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())), BranchSchema.schemaName("test"));
        dataStore.getTiaData(true);
        stamper = new LibraryPublishStamper();
        seedMappingWithLibraryMethods();
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
     * First publish for a tracked library with no mapping baseline: the ledger row is written,
     * nothing is stamped (stamping the library's whole history would be wrong), and the baseline
     * is seeded to the publish HEAD so stamping starts from the next publish.
     */
    @Test
    void firstPublishSeedsBaselineWithoutStamping() {
        // given a tracked library with no mapping baseline
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", LIB_SRC_DIR));

        // when the first publish is stamped
        PublishStampResult result = stamper.stampPublish(dataStore,
                new StubVCSReader("head-1", diffFor(LIB_FILE_KEY)), LIB, "1.0.0-SNAPSHOT", null,
                StaticTestSelectionConfig.EMPTY);

        // then the ledger is seeded, nothing is stamped and the baseline is set to the publish HEAD
        assertEquals(PublishStampResult.Outcome.SEEDED, result.getOutcome());
        assertEquals(1L, result.getPublishSeq());
        assertTrue(result.getStampedMethodIds().isEmpty());
        assertEquals(1, dataStore.readLibraryPublishes(LIB).size());
        assertTrue(dataStore.readPendingLibraryImpactedMethods(LIB).isEmpty());
        assertEquals("head-1", dataStore.readTrackedLibraries().get(LIB).getMappingBaselineCommit());
    }

    /**
     * A publish after the baseline is seeded stamps the tracked methods impacted by the diff:
     * the ledger row carries the published identity and the stamp rows carry the assigned
     * sequence. The baseline must NOT advance - it only moves when the consumer re-covers the
     * library's suites.
     */
    @Test
    void publishStampsImpactedMethodsSinceBaseline() throws Exception {
        // given a tracked library with a seeded baseline and a jar to hash
        TrackedLibrary lib = new TrackedLibrary(LIB, "/projects/lib", LIB_SRC_DIR);
        lib.setMappingBaselineCommit("baseline-1");
        dataStore.persistTrackedLibrary(lib);
        File jar = new File(tempDir, "lib.jar");
        try (FileOutputStream fos = new FileOutputStream(jar)) {
            fos.write("jar-content".getBytes());
        }

        // when a publish with a change to the tracked library file is stamped
        PublishStampResult result = stamper.stampPublish(dataStore,
                new StubVCSReader("head-2", diffFor(LIB_FILE_KEY)), LIB, "1.0.1-SNAPSHOT", jar.getAbsolutePath(),
                StaticTestSelectionConfig.EMPTY);

        // then the impacted method is stamped against the assigned sequence
        assertEquals(PublishStampResult.Outcome.STAMPED, result.getOutcome());
        assertEquals(1L, result.getPublishSeq());
        assertEquals(Collections.singleton(METHOD_LIB), result.getStampedMethodIds());

        List<LibraryPublish> ledger = dataStore.readLibraryPublishes(LIB);
        assertEquals(1, ledger.size());
        assertEquals("1.0.1-SNAPSHOT", ledger.get(0).getPublishedVersion());
        assertNotNull(ledger.get(0).getJarHash());
        assertEquals("head-2", ledger.get(0).getCommitValue());

        List<PendingLibraryImpactedMethod> pending = dataStore.readPendingLibraryImpactedMethods(LIB);
        assertEquals(1, pending.size());
        assertEquals(Long.valueOf(1L), pending.get(0).getPublishSeq());
        assertEquals(Collections.singleton(METHOD_LIB), pending.get(0).getSourceMethodIds());

        // and the baseline is unchanged (it advances only when line numbers are re-captured)
        assertEquals("baseline-1", dataStore.readTrackedLibraries().get(LIB).getMappingBaselineCommit());
    }

    /**
     * A publish with no source changes since the baseline still writes the ledger row - the
     * publish happened and must be resolvable by the consumer's drain lookup - but no stamp.
     */
    @Test
    void emptyDiffPublishWritesLedgerRowOnly() {
        // given a tracked library with a seeded baseline
        TrackedLibrary lib = new TrackedLibrary(LIB, "/projects/lib", LIB_SRC_DIR);
        lib.setMappingBaselineCommit("baseline-1");
        dataStore.persistTrackedLibrary(lib);

        // when a publish with an empty diff is stamped
        PublishStampResult result = stamper.stampPublish(dataStore,
                new StubVCSReader("head-2"), LIB, "1.0.1", null, StaticTestSelectionConfig.EMPTY);

        // then the ledger row exists and no stamp was written
        assertEquals(PublishStampResult.Outcome.STAMPED, result.getOutcome());
        assertEquals(1L, result.getPublishSeq());
        assertTrue(result.getStampedMethodIds().isEmpty());
        assertEquals(1, dataStore.readLibraryPublishes(LIB).size());
        assertTrue(dataStore.readPendingLibraryImpactedMethods(LIB).isEmpty());
    }

    /**
     * A publish for a coordinate with no tracked-library row writes nothing - the library has
     * never been reconciled, so there is no baseline, no source dirs and no consumer tracking it.
     */
    @Test
    void publishForUntrackedLibraryWritesNothing() {
        // given no tracked library row

        // when a publish is stamped for the untracked coordinate
        PublishStampResult result = stamper.stampPublish(dataStore,
                new StubVCSReader("head-1", diffFor(LIB_FILE_KEY)), LIB, "1.0.0", null,
                StaticTestSelectionConfig.EMPTY);

        // then nothing was written
        assertEquals(PublishStampResult.Outcome.SKIPPED_NOT_TRACKED, result.getOutcome());
        assertEquals(0L, result.getPublishSeq());
        assertTrue(dataStore.readLibraryPublishes(LIB).isEmpty());
        assertTrue(dataStore.readPendingLibraryImpactedMethods(LIB).isEmpty());
    }

    /**
     * Stamps are cumulative since the mapping baseline: a second publish (baseline unmoved) whose
     * diff now spans two files stamps the superset. The union of pending methods therefore covers
     * every change since the baseline regardless of which publish the consumer drains at, and the
     * second ledger row gets the next sequence.
     */
    @Test
    void successivePublishesStampCumulativeSupersetSinceBaseline() {
        // given a tracked library with a seeded baseline
        TrackedLibrary lib = new TrackedLibrary(LIB, "/projects/lib", LIB_SRC_DIR);
        lib.setMappingBaselineCommit("baseline-1");
        dataStore.persistTrackedLibrary(lib);

        // when publish 1 changes Lib.java and publish 2's cumulative diff spans Lib.java and Other.java
        PublishStampResult first = stamper.stampPublish(dataStore,
                new StubVCSReader("head-2", diffFor(LIB_FILE_KEY)), LIB, "1.0.1-SNAPSHOT", null,
                StaticTestSelectionConfig.EMPTY);
        PublishStampResult second = stamper.stampPublish(dataStore,
                new StubVCSReader("head-3", diffFor(LIB_FILE_KEY), diffFor(OTHER_FILE_KEY)),
                LIB, "1.0.1-SNAPSHOT", null, StaticTestSelectionConfig.EMPTY);

        // then the sequences advance and the second stamp is the cumulative superset
        assertEquals(1L, first.getPublishSeq());
        assertEquals(2L, second.getPublishSeq());
        assertEquals(Collections.singleton(METHOD_LIB), first.getStampedMethodIds());
        assertEquals(new HashSet<>(Arrays.asList(METHOD_LIB, METHOD_OTHER)), second.getStampedMethodIds());

        // and the union of all pending methods covers every change since the baseline
        Set<Integer> pendingUnion = new HashSet<>();
        for (PendingLibraryImpactedMethod batch : dataStore.readPendingLibraryImpactedMethods(LIB)) {
            pendingUnion.addAll(batch.getSourceMethodIds());
        }
        assertEquals(new HashSet<>(Arrays.asList(METHOD_LIB, METHOD_OTHER)), pendingUnion);
        assertEquals(2, dataStore.readLibraryPublishes(LIB).size());
    }

    /**
     * A version-only re-publish records the ledger row but an empty stamp. Diff 1 (baseline..HEAD)
     * still reports the method, but Diff 2 (previousPublish..HEAD) reports no changed source file,
     * so the dedup filter removes it - the change is already pending from the earlier publish and
     * re-stamping it would make a consumer that resolves this later build re-run covered tests.
     */
    @Test
    void versionOnlyRepublishRecordsLedgerRowWithEmptyStamp() {
        // given a tracked library with a seeded baseline and a first publish that stamped the change
        TrackedLibrary lib = new TrackedLibrary(LIB, "/projects/lib", LIB_SRC_DIR);
        lib.setMappingBaselineCommit("baseline-1");
        dataStore.persistTrackedLibrary(lib);
        PublishStampResult first = stamper.stampPublish(dataStore,
                new StubVCSReader("head-2", diffFor(LIB_FILE_KEY)), LIB, "1.7-SNAPSHOT", null,
                StaticTestSelectionConfig.EMPTY);
        assertEquals(Collections.singleton(METHOD_LIB), first.getStampedMethodIds());

        // when a version-only publish follows: Lib.java still differs from the baseline (Diff 1),
        // but nothing changed since the previous publish head-2 (Diff 2 is empty)
        Map<String, Set<SourceFileDiffContext>> byBase = new HashMap<>();
        byBase.put("baseline-1", setOf(diffFor(LIB_FILE_KEY)));
        byBase.put("head-2", Collections.emptySet());
        PublishStampResult second = stamper.stampPublish(dataStore,
                new StubVCSReader("head-3", setOf(diffFor(LIB_FILE_KEY)), byBase), LIB, "1.7", null,
                StaticTestSelectionConfig.EMPTY);

        // then the ledger row is written but the stamp is empty, and only the first publish is pending
        assertEquals(PublishStampResult.Outcome.STAMPED, second.getOutcome());
        assertEquals(2L, second.getPublishSeq());
        assertTrue(second.getStampedMethodIds().isEmpty());
        assertEquals(2, dataStore.readLibraryPublishes(LIB).size());
        List<PendingLibraryImpactedMethod> pending = dataStore.readPendingLibraryImpactedMethods(LIB);
        assertEquals(1, pending.size());
        assertEquals(Long.valueOf(1L), pending.get(0).getPublishSeq());
        assertEquals(Collections.singleton(METHOD_LIB), pending.get(0).getSourceMethodIds());
    }

    /**
     * The dedup filter stays method-precise across files: a publish whose only change since the
     * previous publish is in Other.java stamps just that file's method, even though the cumulative
     * baseline diff (Diff 1) still reports Lib.java's already-pending method. Lib.java is dropped
     * because it did not change since the previous publish.
     */
    @Test
    void publishStampsOnlyMethodsInFilesChangedSincePreviousPublish() {
        // given a tracked library with a seeded baseline and a first publish that stamped Lib.java
        TrackedLibrary lib = new TrackedLibrary(LIB, "/projects/lib", LIB_SRC_DIR);
        lib.setMappingBaselineCommit("baseline-1");
        dataStore.persistTrackedLibrary(lib);
        stamper.stampPublish(dataStore, new StubVCSReader("head-2", diffFor(LIB_FILE_KEY)),
                LIB, "1.0.1-SNAPSHOT", null, StaticTestSelectionConfig.EMPTY);

        // when the second publish's cumulative baseline diff spans both files but only Other.java
        // changed since the previous publish head-2
        Map<String, Set<SourceFileDiffContext>> byBase = new HashMap<>();
        byBase.put("baseline-1", setOf(diffFor(LIB_FILE_KEY), diffFor(OTHER_FILE_KEY)));
        byBase.put("head-2", setOf(diffFor(OTHER_FILE_KEY)));
        PublishStampResult second = stamper.stampPublish(dataStore,
                new StubVCSReader("head-3", setOf(diffFor(LIB_FILE_KEY), diffFor(OTHER_FILE_KEY)), byBase),
                LIB, "1.0.2-SNAPSHOT", null, StaticTestSelectionConfig.EMPTY);

        // then only Other.java's method is stamped for the second publish
        assertEquals(Collections.singleton(METHOD_OTHER), second.getStampedMethodIds());
        assertEquals(2L, second.getPublishSeq());
    }

    /**
     * A library's own static test selection rule that matches a file changed since the previous
     * publish is recorded as a forced-selection batch, alongside any impacted-method stamp. This
     * lets a library force a consumer's drain to select tests for a change the coverage-driven
     * stamp cannot see - here a SQL migration under the library's source dir, outside anything
     * method-impact analysis tracks.
     */
    @Test
    void publishRecordsForcedSelectionWhenChangedFileMatchesLibraryRule() {
        // given a tracked library with a baseline, a previous publish, and a changed SQL file
        // under the library's absolute source dir since that previous publish
        trackedLibraryWithBaseline(LIB_ABS_SRC_DIR, "baseline-1", "prev-commit");
        Set<String> changedSincePrevious = new HashSet<>(Arrays.asList(
                "libs/widget/src/main/resources/db/V2__add.sql"));
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("sql-run-all", "\\.sql$", StaticTestSelectionRuleMode.RUN_ALL, null)));

        // when a publish is stamped with no tracked method change but a matching SQL file change
        PublishStampResult result = stamper.stampPublish(dataStore,
                new StubVCSReader("head-2", Collections.<SourceFileDiffContext>emptySet(), changedSincePrevious),
                LIB, "1.2.0", null, config);

        // then a RUN_ALL forced selection is recorded against the assigned publish sequence
        assertEquals(PublishStampResult.Outcome.STAMPED, result.getOutcome());
        assertEquals(1, result.getForcedSelections().size());
        assertEquals(StaticTestSelectionRuleMode.RUN_ALL, result.getForcedSelections().get(0).getMode());
        assertEquals("sql-run-all", result.getForcedSelections().get(0).getRuleName());
        List<PendingLibraryForcedSelection> persisted = dataStore.readPendingLibraryForcedSelections(LIB);
        assertEquals(1, persisted.size());
        assertEquals(result.getPublishSeq(), persisted.get(0).getPublishSeq());
    }

    /**
     * A changed file that falls under the library's source dir but matches no configured rule
     * records no forced selection - static rules only add selections on an actual pattern match,
     * they never force anything speculatively.
     */
    @Test
    void publishRecordsNoForcedSelectionWhenNoRuleMatches() {
        // given the same setup but the changed file does not match the configured rule
        trackedLibraryWithBaseline(LIB_ABS_SRC_DIR, "baseline-1", "prev-commit");
        Set<String> changedSincePrevious = new HashSet<>(Arrays.asList(
                "libs/widget/src/main/java/Foo.java"));
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("sql-run-all", "\\.sql$", StaticTestSelectionRuleMode.RUN_ALL, null)));

        // when
        PublishStampResult result = stamper.stampPublish(dataStore,
                new StubVCSReader("head-2", Collections.<SourceFileDiffContext>emptySet(), changedSincePrevious),
                LIB, "1.2.0", null, config);

        // then no forced selection is recorded
        assertTrue(result.getForcedSelections().isEmpty());
        assertTrue(dataStore.readPendingLibraryForcedSelections(LIB).isEmpty());
    }

    /**
     * Build and persist a tracked library with a mapping baseline and one previous publish ledger
     * row, for tests exercising the since-previous-publish forced-selection scope: the previous
     * publish commit is what {@link LibraryPublishStamper} diffs {@code getChangedFilePaths} from.
     *
     * @param sourceDir the library's absolute source dir, used as both project dir and source dirs CSV.
     * @param baselineCommit the mapping baseline commit to seed on the tracked library.
     * @param previousPublishCommit the commit value recorded on the seeded previous publish ledger row.
     * @return the persisted tracked library.
     */
    private TrackedLibrary trackedLibraryWithBaseline(String sourceDir, String baselineCommit,
                                                       String previousPublishCommit) {
        TrackedLibrary lib = new TrackedLibrary(LIB, sourceDir, sourceDir);
        lib.setMappingBaselineCommit(baselineCommit);
        dataStore.persistTrackedLibrary(lib);
        dataStore.persistLibraryPublish(new LibraryPublish(LIB, "1.1.0", "h", previousPublishCommit, 1L),
                Collections.<Integer>emptySet(), Collections.<PendingLibraryForcedSelection>emptyList());
        return lib;
    }

    /**
     * Build a mutable set of the given diff contexts.
     *
     * @param diffs the diff contexts to collect.
     * @return a new {@link HashSet} holding them.
     */
    private static Set<SourceFileDiffContext> setOf(SourceFileDiffContext... diffs) {
        return new HashSet<>(Arrays.asList(diffs));
    }

    /**
     * Seed a mapping where the library files {@code Lib.java} and {@code Other.java} each have one
     * tracked method spanning lines 2-8, covered by a test suite apiece.
     */
    private void seedMappingWithLibraryMethods() {
        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("seed-commit");
        tiaData.setLastUpdated(Instant.now());

        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        methods.put(METHOD_LIB, new MethodImpactTracker("com/example/Lib.method.()V", 2, 8));
        methods.put(METHOD_OTHER, new MethodImpactTracker("com/example/Other.method.()V", 2, 8));

        Map<String, TestSuiteTracker> testSuites = new HashMap<>();
        TestSuiteTracker libTest = new TestSuiteTracker("com.example.LibTest");
        libTest.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker(LIB_FILE_KEY, new HashSet<>(Collections.singletonList(METHOD_LIB)))));
        testSuites.put("com.example.LibTest", libTest);
        TestSuiteTracker otherTest = new TestSuiteTracker("com.example.OtherTest");
        otherTest.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker(OTHER_FILE_KEY, new HashSet<>(Collections.singletonList(METHOD_OTHER)))));
        testSuites.put("com.example.OtherTest", otherTest);

        tiaData.setTestSuitesTracked(testSuites);
        tiaData.setMethodsTracked(methods);
        dataStore.persistCoreData(tiaData);
        dataStore.persistTestSuites(testSuites);
        dataStore.persistSourceMethods(methods);
    }

    /**
     * Build a MODIFY diff for a library source file under the library source dir.
     *
     * @param mappingKey the stored mapping key (e.g. {@code com/example/Lib.java})
     * @return a content-less MODIFY diff context for that file
     */
    private static SourceFileDiffContext diffFor(String mappingKey) {
        String path = LIB_SRC_DIR + "/" + mappingKey;
        return new SourceFileDiffContext(path, path, ChangeType.MODIFY);
    }

    /**
     * Stub reader returning a diff set per requested base commit and, on content load, supplying
     * original/changed content that differs on line 5 - inside the seeded methods' 2-8 range - so
     * method-impact analysis yields the tracked method for each diffed file. The varargs
     * constructor returns the same diffs for any base (Diff 1 and Diff 2 see the same change); the
     * map constructor lets a test return different diffs for the baseline (Diff 1) than for the
     * previous-publish commit (Diff 2), which is how the dedup filter is exercised.
     */
    private static final class StubVCSReader implements VCSReader {
        private final String headCommit;
        private final Set<SourceFileDiffContext> defaultDiffs;
        private final Map<String, Set<SourceFileDiffContext>> diffsByBase;
        private final Set<String> changedFilePaths;

        StubVCSReader(String headCommit, SourceFileDiffContext... diffs) {
            this(headCommit, new HashSet<>(Arrays.asList(diffs)), Collections.emptyMap(), Collections.emptySet());
        }

        StubVCSReader(String headCommit, Set<SourceFileDiffContext> defaultDiffs,
                      Map<String, Set<SourceFileDiffContext>> diffsByBase) {
            this(headCommit, defaultDiffs, diffsByBase, Collections.emptySet());
        }

        /**
         * Build a stub whose {@link #getChangedFilePaths} answers with a fixed set regardless of
         * the requested base commit, for exercising the forced-selection evaluation against the
         * previous-publish-to-HEAD changed paths.
         *
         * @param headCommit the commit {@link #getHeadCommit()} returns.
         * @param defaultDiffs the diff set {@link #getDiffFiles} returns for any base not in {@code diffsByBase}.
         * @param changedFilePaths the repo-relative paths {@link #getChangedFilePaths} always returns.
         */
        StubVCSReader(String headCommit, Set<SourceFileDiffContext> defaultDiffs, Set<String> changedFilePaths) {
            this(headCommit, defaultDiffs, Collections.emptyMap(), changedFilePaths);
        }

        StubVCSReader(String headCommit, Set<SourceFileDiffContext> defaultDiffs,
                      Map<String, Set<SourceFileDiffContext>> diffsByBase, Set<String> changedFilePaths) {
            this.headCommit = headCommit;
            this.defaultDiffs = defaultDiffs;
            this.diffsByBase = diffsByBase;
            this.changedFilePaths = changedFilePaths;
        }

        @Override public String getBranchName() { return "test"; }
        @Override public String getHeadCommit() { return headCommit; }

        @Override
        public Set<SourceFileDiffContext> getDiffFiles(String baseChangeNum, List<String> sourceFilesDirs,
                                                       List<String> testFilesDirs, boolean checkLocalChanges) {
            return diffsByBase.getOrDefault(baseChangeNum, defaultDiffs);
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
            return changedFilePaths;
        }

        @Override public void close() { }
    }
}
