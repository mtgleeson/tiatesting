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
import org.tiatesting.core.staticselection.pom.PomChangeCategory;
import org.tiatesting.core.vcs.FileContentPair;
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
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())), BranchSchema.schemaName("test", null));
        dataStore.getTiaData();
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
                StaticTestSelectionConfig.EMPTY, Collections.<String>emptyList());

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
                StaticTestSelectionConfig.EMPTY, Collections.<String>emptyList());

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
     * A publish from a workspace other than the consumer's (e.g. the library's own release job)
     * diffs the publishing build's own source roots, not the consumer-workspace paths recorded on
     * the tracked library, which are not in the publishing workspace. Mapping keys are
     * package-relative, so the methods still match. The tracked row keeps the consumer's paths.
     */
    @Test
    void publishDiffsPublishingSourceDirsInsteadOfTrackedConsumerDirs() {
        // given a tracked library whose recorded source dir is in the consumer's workspace
        String consumerSrcDir = "/consumer-ws/libs/lib/src/main/java";
        TrackedLibrary lib = new TrackedLibrary(LIB, "/consumer-ws/libs/lib", consumerSrcDir);
        lib.setMappingBaselineCommit("baseline-1");
        dataStore.persistTrackedLibrary(lib);
        StubVCSReader reader = new StubVCSReader("head-2", diffFor(LIB_FILE_KEY));

        // when the library is published from its own workspace with its own source root
        PublishStampResult result = stamper.stampPublish(dataStore, reader, LIB, "1.0.1", null,
                StaticTestSelectionConfig.EMPTY, Collections.singletonList(LIB_SRC_DIR));

        // then the diff ran over the publishing source root and the impacted method is stamped
        assertEquals(PublishStampResult.Outcome.STAMPED, result.getOutcome());
        assertEquals(Collections.singleton(METHOD_LIB), result.getStampedMethodIds());
        assertEquals(Collections.singletonList(Collections.singletonList(LIB_SRC_DIR)),
                reader.getRequestedSourceDirs());

        // and the tracked library still records the consumer's source dir
        assertEquals(consumerSrcDir, dataStore.readTrackedLibraries().get(LIB).getSourceDirsCsv());
    }

    /**
     * With no publishing source roots supplied (e.g. the publishing build has none on disk), the
     * publish diff falls back to the tracked library's recorded source dirs.
     */
    @Test
    void publishFallsBackToTrackedSourceDirsWhenNoPublishingDirs() {
        // given a tracked library with a seeded baseline and a recorded source dir
        TrackedLibrary lib = new TrackedLibrary(LIB, "/projects/lib", LIB_SRC_DIR);
        lib.setMappingBaselineCommit("baseline-1");
        dataStore.persistTrackedLibrary(lib);
        StubVCSReader reader = new StubVCSReader("head-2", diffFor(LIB_FILE_KEY));

        // when the publish supplies no source roots of its own
        PublishStampResult result = stamper.stampPublish(dataStore, reader, LIB, "1.0.1", null,
                StaticTestSelectionConfig.EMPTY, Collections.<String>emptyList());

        // then the diff ran over the tracked source dir and the impacted method is stamped
        assertEquals(Collections.singleton(METHOD_LIB), result.getStampedMethodIds());
        assertEquals(Collections.singletonList(Collections.singletonList(LIB_SRC_DIR)),
                reader.getRequestedSourceDirs());
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
                new StubVCSReader("head-2"), LIB, "1.0.1", null, StaticTestSelectionConfig.EMPTY,
                Collections.<String>emptyList());

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
                StaticTestSelectionConfig.EMPTY, Collections.<String>emptyList());

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
                StaticTestSelectionConfig.EMPTY, Collections.<String>emptyList());
        PublishStampResult second = stamper.stampPublish(dataStore,
                new StubVCSReader("head-3", diffFor(LIB_FILE_KEY), diffFor(OTHER_FILE_KEY)),
                LIB, "1.0.1-SNAPSHOT", null, StaticTestSelectionConfig.EMPTY, Collections.<String>emptyList());

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
                StaticTestSelectionConfig.EMPTY, Collections.<String>emptyList());
        assertEquals(Collections.singleton(METHOD_LIB), first.getStampedMethodIds());

        // when a version-only publish follows: Lib.java still differs from the baseline (Diff 1),
        // but nothing changed since the previous publish head-2 (Diff 2 is empty)
        Map<String, Set<SourceFileDiffContext>> byBase = new HashMap<>();
        byBase.put("baseline-1", setOf(diffFor(LIB_FILE_KEY)));
        byBase.put("head-2", Collections.emptySet());
        PublishStampResult second = stamper.stampPublish(dataStore,
                new StubVCSReader("head-3", setOf(diffFor(LIB_FILE_KEY)), byBase), LIB, "1.7", null,
                StaticTestSelectionConfig.EMPTY, Collections.<String>emptyList());

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
                LIB, "1.0.1-SNAPSHOT", null, StaticTestSelectionConfig.EMPTY, Collections.<String>emptyList());

        // when the second publish's cumulative baseline diff spans both files but only Other.java
        // changed since the previous publish head-2
        Map<String, Set<SourceFileDiffContext>> byBase = new HashMap<>();
        byBase.put("baseline-1", setOf(diffFor(LIB_FILE_KEY), diffFor(OTHER_FILE_KEY)));
        byBase.put("head-2", setOf(diffFor(OTHER_FILE_KEY)));
        PublishStampResult second = stamper.stampPublish(dataStore,
                new StubVCSReader("head-3", setOf(diffFor(LIB_FILE_KEY), diffFor(OTHER_FILE_KEY)), byBase),
                LIB, "1.0.2-SNAPSHOT", null, StaticTestSelectionConfig.EMPTY, Collections.<String>emptyList());

        // then only Other.java's method is stamped for the second publish
        assertEquals(Collections.singleton(METHOD_OTHER), second.getStampedMethodIds());
        assertEquals(2L, second.getPublishSeq());
    }

    /**
     * A library's own static test selection rule that matches a file changed since the previous
     * publish is recorded as a forced-selection batch, alongside any impacted-method stamp. This
     * lets a library force a consumer's drain to select tests for a change the coverage-driven
     * stamp cannot see - here a SQL migration, outside anything method-impact analysis tracks.
     */
    @Test
    void publishRecordsForcedSelectionWhenChangedFileMatchesLibraryRule() {
        // given a tracked library with a baseline, a previous publish, and a changed SQL file
        // since that previous publish
        trackedLibraryWithBaseline(LIB_ABS_SRC_DIR, "baseline-1", "prev-commit");
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("sql-run-all", "\\.sql$", StaticTestSelectionRuleMode.RUN_ALL, null, null)), null);

        // when a publish is stamped with no tracked method change but a matching SQL file change
        PublishStampResult result = stamper.stampPublish(dataStore,
                new StubVCSReader("head-2", Collections.<SourceFileDiffContext>emptySet(), Collections.emptyMap(),
                        changedPathsAt("prev-commit", "libs/widget/src/main/resources/db/V2__add.sql")),
                LIB, "1.2.0", null, config, Collections.<String>emptyList());

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
     * A library's POM-aware rule is evaluated like a consumer's: it fires for a functional POM
     * change since the previous publish, with the POM content read over that same diff.
     */
    @Test
    void publishRecordsForcedSelectionForAFunctionalPomChange() {
        // given a library POM whose external dependency was bumped since the previous publish
        trackedLibraryWithBaseline(LIB_ABS_SRC_DIR, "baseline-1", "prev-commit");
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("pom-changes", null, StaticTestSelectionRuleMode.RUN_ALL, null,
                        EnumSet.of(PomChangeCategory.FUNCTIONAL))), null);
        StubVCSReader reader = new StubVCSReader("head-2", Collections.<SourceFileDiffContext>emptySet(),
                Collections.emptyMap(), changedPathsAt("prev-commit", "libs/widget/pom.xml"))
                .withFileContents(Collections.singletonMap("libs/widget/pom.xml",
                        new FileContentPair(libraryPom("1.0", "org.lib", "lib", "2.0"),
                                libraryPom("1.0", "org.lib", "lib", "2.1"))));

        // when
        PublishStampResult result = stamper.stampPublish(dataStore, reader, LIB, "1.2.0", null, config,
                Collections.<String>emptyList());

        // then
        assertEquals(1, result.getForcedSelections().size());
        assertEquals("pom-changes", result.getForcedSelections().get(0).getRuleName());
        assertEquals("prev-commit", reader.fileContentsBase);
    }

    /**
     * A library POM-aware rule records nothing for an inert POM change: a release version bump, or a
     * bump of another tracked library (owned by the drain).
     */
    @Test
    void publishRecordsNoForcedSelectionForAnInertPomChange() {
        // given a library POM whose only changes are its own version and a tracked library's version
        trackedLibraryWithBaseline(LIB_ABS_SRC_DIR, "baseline-1", "prev-commit");
        String[] tracked = LIB.split(":");
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("pom-changes", null, StaticTestSelectionRuleMode.RUN_ALL, null,
                        EnumSet.of(PomChangeCategory.FUNCTIONAL))), null);
        StubVCSReader reader = new StubVCSReader("head-2", Collections.<SourceFileDiffContext>emptySet(),
                Collections.emptyMap(), changedPathsAt("prev-commit", "libs/widget/pom.xml"))
                .withFileContents(Collections.singletonMap("libs/widget/pom.xml",
                        new FileContentPair(libraryPom("1.0", tracked[0], tracked[1], "3.0"),
                                libraryPom("1.1", tracked[0], tracked[1], "3.1"))));

        // when
        PublishStampResult result = stamper.stampPublish(dataStore, reader, LIB, "1.2.0", null, config,
                Collections.<String>emptyList());

        // then
        assertEquals(PublishStampResult.Outcome.STAMPED, result.getOutcome());
        assertTrue(result.getForcedSelections().isEmpty());
    }

    /**
     * A jar POM {@code org.acme:widget-app} with one dependency.
     *
     * @param version the project version.
     * @param dependencyGroupId the dependency's groupId.
     * @param dependencyArtifactId the dependency's artifactId.
     * @param dependencyVersion the dependency's version.
     * @return the POM text.
     */
    private static String libraryPom(String version, String dependencyGroupId, String dependencyArtifactId,
                                     String dependencyVersion) {
        return "<project><groupId>org.acme</groupId><artifactId>widget-app</artifactId><version>" + version
                + "</version><dependencies><dependency><groupId>" + dependencyGroupId + "</groupId><artifactId>"
                + dependencyArtifactId + "</artifactId><version>" + dependencyVersion
                + "</version></dependency></dependencies></project>";
    }

    /**
     * A changed file that matches no configured rule records no forced selection - static rules only
     * add selections on an actual pattern match, they never force anything speculatively.
     */
    @Test
    void publishRecordsNoForcedSelectionWhenNoRuleMatches() {
        // given the same setup but the changed file does not match the configured rule
        trackedLibraryWithBaseline(LIB_ABS_SRC_DIR, "baseline-1", "prev-commit");
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("sql-run-all", "\\.sql$", StaticTestSelectionRuleMode.RUN_ALL, null, null)), null);

        // when
        PublishStampResult result = stamper.stampPublish(dataStore,
                new StubVCSReader("head-2", Collections.<SourceFileDiffContext>emptySet(), Collections.emptyMap(),
                        changedPathsAt("prev-commit", "libs/widget/src/main/java/Foo.java")),
                LIB, "1.2.0", null, config, Collections.<String>emptyList());

        // then no forced selection is recorded
        assertTrue(result.getForcedSelections().isEmpty());
        assertTrue(dataStore.readPendingLibraryForcedSelections(LIB).isEmpty());
    }

    /**
     * The forced-selection evaluation is scoped to the since-previous-publish diff, so a matching
     * change on one publish does not leak into a later republish whose own diff has no matching
     * change - the same dedup guarantee the impacted-method stamp already provides, now proven for
     * forced selections: publish A's since-"prev-commit" diff contains a matching SQL file and
     * records a forced selection; publish B's since-"head-2" diff (head-2 is now the previous
     * publish commit) contains no matching file and records none.
     */
    @Test
    void publishRecordsNoForcedSelectionOnRepublishWithNoNewMatchingChange() {
        // given a tracked library with a baseline and a previous publish
        trackedLibraryWithBaseline(LIB_ABS_SRC_DIR, "baseline-1", "prev-commit");
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("sql-run-all", "\\.sql$", StaticTestSelectionRuleMode.RUN_ALL, null, null)), null);

        // when publish A's diff since "prev-commit" has a matching SQL file
        PublishStampResult first = stamper.stampPublish(dataStore,
                new StubVCSReader("head-2", Collections.<SourceFileDiffContext>emptySet(), Collections.emptyMap(),
                        changedPathsAt("prev-commit", "libs/widget/src/main/resources/db/V2__add.sql")),
                LIB, "1.2.0", null, config, Collections.<String>emptyList());

        // and publish B's diff since "head-2" (publish A's commit, now the previous publish) has no
        // matching file
        PublishStampResult second = stamper.stampPublish(dataStore,
                new StubVCSReader("head-3", Collections.<SourceFileDiffContext>emptySet(), Collections.emptyMap(),
                        changedPathsAt("head-2", "libs/widget/src/main/java/Foo.java")),
                LIB, "1.3.0", null, config, Collections.<String>emptyList());

        // then publish A recorded the forced selection and publish B recorded none
        assertEquals(1, first.getForcedSelections().size());
        assertTrue(second.getForcedSelections().isEmpty());
        assertEquals(1, dataStore.readPendingLibraryForcedSelections(LIB).size());
    }

    /**
     * A library's static rules are evaluated repo-wide, not restricted to the library's own module
     * directory: a changed file anywhere in the shared repo that matches the rule's file pattern
     * forces a selection. This is deliberate - a library may depend on a collaborating module in the
     * same repo (e.g. a service it makes HTTP calls to) whose change should force the library's
     * consumers to re-test. Scoping is the rule author's job via the rule's {@code filePathPattern}.
     */
    @Test
    void publishRecordsForcedSelectionForChangeOutsideLibraryModuleMatchingRule() {
        // given a tracked library "widget" and a rule matching any .sql file
        trackedLibraryWithBaseline(LIB_ABS_SRC_DIR, "baseline-1", "prev-commit");
        StaticTestSelectionConfig config = new StaticTestSelectionConfig(Collections.singletonList(
                new StaticTestSelectionRule("sql-run-all", "\\.sql$", StaticTestSelectionRuleMode.RUN_ALL, null, null)), null);

        // when the only changed file since the previous publish is under a sibling module "gadget"
        PublishStampResult result = stamper.stampPublish(dataStore,
                new StubVCSReader("head-2", Collections.<SourceFileDiffContext>emptySet(), Collections.emptyMap(),
                        changedPathsAt("prev-commit", "libs/gadget/src/main/resources/db/V2.sql")),
                LIB, "1.2.0", null, config, Collections.<String>emptyList());

        // then a forced selection is recorded - the rule fired on the repo-wide match
        assertEquals(1, result.getForcedSelections().size());
        assertEquals("sql-run-all", result.getForcedSelections().get(0).getRuleName());
        assertEquals(1, dataStore.readPendingLibraryForcedSelections(LIB).size());
    }

    /**
     * Build a single-entry base-commit-to-changed-paths map for {@link StubVCSReader}.
     *
     * @param baseCommit the commit key {@link VCSReader#getChangedFilePaths} is expected to be called with.
     * @param paths the repo-relative changed paths to return for that commit.
     * @return an immutable single-entry map from {@code baseCommit} to {@code paths}.
     */
    private static Map<String, Set<String>> changedPathsAt(String baseCommit, String... paths) {
        return Collections.singletonMap(baseCommit, new HashSet<>(Arrays.asList(paths)));
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
        TiaData tiaData = dataStore.getTiaData();
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
        private final Map<String, Set<String>> changedFilePathsByBase;
        private Map<String, FileContentPair> fileContents = Collections.emptyMap();
        private String fileContentsBase;
        private final List<List<String>> requestedSourceDirs = new ArrayList<>();

        StubVCSReader(String headCommit, SourceFileDiffContext... diffs) {
            this(headCommit, new HashSet<>(Arrays.asList(diffs)), Collections.emptyMap(), Collections.emptyMap());
        }

        StubVCSReader(String headCommit, Set<SourceFileDiffContext> defaultDiffs,
                      Map<String, Set<SourceFileDiffContext>> diffsByBase) {
            this(headCommit, defaultDiffs, diffsByBase, Collections.emptyMap());
        }

        /**
         * Build a stub whose {@link #getChangedFilePaths} answers per requested base commit from
         * {@code changedFilePathsByBase}, defaulting to an empty set for any commit not present -
         * for exercising forced-selection evaluation, which is scoped to the since-previous-publish
         * diff and so must be able to differ between successive publishes in the same test.
         *
         * @param headCommit the commit {@link #getHeadCommit()} returns.
         * @param defaultDiffs the diff set {@link #getDiffFiles} returns for any base not in {@code diffsByBase}.
         * @param diffsByBase the diff set {@link #getDiffFiles} returns per requested base commit.
         * @param changedFilePathsByBase the repo-relative paths {@link #getChangedFilePaths} returns
         *                                per requested base commit; a commit absent from the map
         *                                yields an empty set, matching a real "nothing changed" diff.
         */
        StubVCSReader(String headCommit, Set<SourceFileDiffContext> defaultDiffs,
                      Map<String, Set<SourceFileDiffContext>> diffsByBase,
                      Map<String, Set<String>> changedFilePathsByBase) {
            this.headCommit = headCommit;
            this.defaultDiffs = defaultDiffs;
            this.diffsByBase = diffsByBase;
            this.changedFilePathsByBase = changedFilePathsByBase;
        }

        @Override public String getBranchName() { return "test"; }
        @Override public String getHeadCommit() { return headCommit; }

        /**
         * Return the diff set configured for the requested base, recording the source dirs the
         * diff was restricted to.
         *
         * @param baseChangeNum the diff base commit.
         * @param sourceFilesDirs the source dirs requested, recorded.
         * @param testFilesDirs unused.
         * @param checkLocalChanges unused.
         * @return the diffs configured for {@code baseChangeNum}, or the default diffs.
         */
        @Override
        public Set<SourceFileDiffContext> getDiffFiles(String baseChangeNum, List<String> sourceFilesDirs,
                                                       List<String> testFilesDirs, boolean checkLocalChanges) {
            requestedSourceDirs.add(new ArrayList<>(sourceFilesDirs));
            return diffsByBase.getOrDefault(baseChangeNum, defaultDiffs);
        }

        /**
         * Return the source dirs passed to each {@link #getDiffFiles} call, in call order.
         *
         * @return the requested source dirs per diff call.
         */
        List<List<String>> getRequestedSourceDirs() {
            return requestedSourceDirs;
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
            return changedFilePathsByBase.getOrDefault(baseChangeNum, Collections.<String>emptySet());
        }

        /**
         * Serve the before / after content of files by path, for POM-aware rules.
         *
         * @param contents the content by repo-relative path.
         * @return this reader.
         */
        StubVCSReader withFileContents(Map<String, FileContentPair> contents) {
            this.fileContents = contents;
            return this;
        }

        /**
         * Serve the configured content for the requested paths and record the diff baseline.
         *
         * @param paths the requested paths.
         * @param baseChangeNum the diff baseline, recorded.
         * @param checkLocalChanges unused.
         * @return the configured content of each requested path (absent paths have none).
         */
        @Override
        public Map<String, FileContentPair> loadFileContents(final Set<String> paths, final String baseChangeNum,
                                                             final boolean checkLocalChanges) {
            fileContentsBase = baseChangeNum;
            Map<String, FileContentPair> loaded = new HashMap<>();
            for (String path : paths) {
                loaded.put(path, fileContents.containsKey(path) ? fileContents.get(path) : new FileContentPair(null, null));
            }
            return loaded;
        }

        @Override public void close() { }
    }
}
