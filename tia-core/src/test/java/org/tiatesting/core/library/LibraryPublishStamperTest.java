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
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 2 coverage for {@link LibraryPublishStamper}: first-publish baseline seeding, stamping the
 * methods impacted since the mapping baseline (with the ledger row and stamp written together),
 * ledger-only empty-diff publishes, the untracked-library skip, and cumulative-since-baseline
 * stamps across successive publishes. See {@code DESIGN-publish-time-stamping.md} sections 3-4.
 */
class LibraryPublishStamperTest {

    private static final String LIB = "com.example:lib";
    private static final String LIB_SRC_DIR = "/projects/lib/src/main/java";
    private static final String LIB_FILE_KEY = "com/example/Lib.java";
    private static final String OTHER_FILE_KEY = "com/example/Other.java";
    private static final int METHOD_LIB = 4242;
    private static final int METHOD_OTHER = 4343;

    private H2DataStore dataStore;
    private File tempDir;
    private LibraryPublishStamper stamper;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-stamper-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
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
                new StubVCSReader("head-1", diffFor(LIB_FILE_KEY)), LIB, "1.0.0-SNAPSHOT", null);

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
                new StubVCSReader("head-2", diffFor(LIB_FILE_KEY)), LIB, "1.0.1-SNAPSHOT", jar.getAbsolutePath());

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
                new StubVCSReader("head-2"), LIB, "1.0.1", null);

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
                new StubVCSReader("head-1", diffFor(LIB_FILE_KEY)), LIB, "1.0.0", null);

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
                new StubVCSReader("head-2", diffFor(LIB_FILE_KEY)), LIB, "1.0.1-SNAPSHOT", null);
        PublishStampResult second = stamper.stampPublish(dataStore,
                new StubVCSReader("head-3", diffFor(LIB_FILE_KEY), diffFor(OTHER_FILE_KEY)),
                LIB, "1.0.1-SNAPSHOT", null);

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
     * Stub reader returning a fixed diff set and, on content load, supplying original/changed
     * content that differs on line 5 - inside the seeded methods' 2-8 range - so method-impact
     * analysis yields the tracked method for each diffed file.
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

        @Override public void close() { }
    }
}
