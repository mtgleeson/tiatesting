package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A forked test JVM must not mistake a suite it did not run for one deleted from the repository.
 *
 * <p>{@code removeDeletedTestSuites} reads "tracked in the DB but not known to this runner" as
 * "deleted", which is sound only when the runner set covers the whole project. Split a run across
 * JVMs - {@code maxParallelForks > 1} or {@code forkEvery > 0} - and each fork is given a share of
 * the classes, so each would conclude that every suite the others own has been deleted and remove
 * its stored mapping. Nothing fails; the suites simply become untracked, and an untracked suite can
 * never be ignored, so they run every build thereafter.
 *
 * <p>The compiled test classes on disk answer the question independently of how the run was split,
 * because every fork sees the same directories.
 */
class TestRunnerServiceForkedSuiteDeletionTest {

    private static final String SUITE_A = "com.example.ATest";
    private static final String SUITE_B = "com.example.BTest";

    private JdbcDataStore dataStore;
    private File tempDir;
    private File classesDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-forked-deletion-", "");
        tempDir.delete();
        tempDir.mkdirs();
        classesDir = new File(tempDir, "test-classes");
        classesDir.mkdirs();

        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
        dataStore.getTiaData(true);

        TiaData core = dataStore.getTiaData(true);
        core.setCommitValue("commit-0");
        core.setLastUpdated(Instant.now());
        dataStore.persistCoreData(core);

        Map<String, TestSuiteTracker> seed = new HashMap<>();
        seed.put(SUITE_A, seeded(SUITE_A));
        seed.put(SUITE_B, seeded(SUITE_B));
        dataStore.persistTestSuites(seed);
    }

    @AfterEach
    void tearDown() {
        if (dataStore != null) {
            dataStore.close();
        }
        deleteRecursively(tempDir);
    }

    /**
     * The regression this fix exists for: a fork that ran only suite A, with both classes on disk,
     * must leave suite B's mapping alone.
     */
    @Test
    void aForkThatRanOneSuiteDoesNotDeleteTheOther() throws Exception {
        // given - both suites compiled, but this fork was given only A
        writeClassFile(SUITE_A);
        writeClassFile(SUITE_B);
        Set<String> observedByThisFork = new HashSet<>(Arrays.asList(SUITE_A));

        // when
        persistRun(observedByThisFork, scanned());

        // then
        assertTrue(dataStore.getTestSuitesTracked().containsKey(SUITE_B),
                "a suite this fork did not run, but which still exists on disk, must not be deleted");
        assertTrue(dataStore.getTestSuitesTracked().containsKey(SUITE_A));
    }

    /**
     * Genuine deletion still works: a suite whose class is gone from disk is removed, which is the
     * behaviour the runner-set inference was there to provide in the first place.
     */
    @Test
    void aSuiteDeletedFromDiskIsStillRemoved() throws Exception {
        // given - only A is compiled; B's class has been deleted from the repository
        writeClassFile(SUITE_A);
        Set<String> observedByThisFork = new HashSet<>(Arrays.asList(SUITE_A));

        // when
        persistRun(observedByThisFork, scanned());

        // then
        assertTrue(dataStore.getTestSuitesTracked().containsKey(SUITE_A));
        assertEquals(false, dataStore.getTestSuitesTracked().containsKey(SUITE_B),
                "a suite whose class no longer exists really has been deleted");
    }

    /**
     * Without the directory list the old inference stands - the observed set is all a fork has, so a
     * split run deletes the other forks' suites. Pinned so the fix cannot be quietly undone by
     * dropping the forwarding.
     */
    @Test
    void withoutTheDirectoryListAForkStillDeletesWhatItDidNotRun() throws Exception {
        // given
        writeClassFile(SUITE_A);
        writeClassFile(SUITE_B);
        Set<String> observedByThisFork = new HashSet<>(Arrays.asList(SUITE_A));

        // when - no directory scan, so the runner set is only what this fork saw
        persistRun(observedByThisFork, observedByThisFork);

        // then
        assertEquals(false, dataStore.getTestSuitesTracked().containsKey(SUITE_B),
                "this is the behaviour the directory scan exists to replace");
    }

    /**
     * A directory list where nothing exists must fail rather than scan to nothing. An empty scan
     * would read as "every suite has been deleted" and wipe the project's whole mapping - the worst
     * possible outcome of a mistyped path.
     */
    @Test
    void aDirectoryListWithNothingOnDiskIsRejected() {
        // given
        TestRunnerService service = new TestRunnerService(dataStore);

        // when / then
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.getTestClassesFromDirs(new File(tempDir, "nope").getAbsolutePath()));
        assertTrue(thrown.getMessage().contains("delete"), thrown.getMessage());
    }

    /**
     * A build tool routinely names output directories a project never produces - Gradle lists the
     * java and groovy test outputs whether or not both source sets exist - so a missing one among
     * several is normal and must simply be skipped.
     */
    @Test
    void aMissingDirectoryAmongSeveralIsSkipped() throws Exception {
        // given
        writeClassFile(SUITE_A);
        String dirs = new File(tempDir, "absent").getAbsolutePath() + "," + classesDir.getAbsolutePath();

        // when
        Set<String> found = new TestRunnerService(dataStore).getTestClassesFromDirs(dirs);

        // then
        assertEquals(new HashSet<>(Arrays.asList(SUITE_A)), found);
    }

    /**
     * @return the suite names the compiled classes on disk resolve to
     */
    private Set<String> scanned() {
        return new TestRunnerService(dataStore).getTestClassesFromDirs(classesDir.getAbsolutePath());
    }

    /**
     * Persist a mapping-owning run that executed the given suites, telling the persist which suites
     * the project is known to still have.
     *
     * @param executedSuites the suites this fork ran
     * @param runnerTestSuites the suites treated as still existing
     */
    private void persistRun(final Set<String> executedSuites, final Set<String> runnerTestSuites) {
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        for (String suite : executedSuites) {
            TestSuiteTracker tracker = new TestSuiteTracker(suite);
            tracker.getTestStats().setNumRuns(1);
            tracker.getTestStats().setAvgRunTime(100);
            trackers.put(suite, tracker);
        }

        TestRunResult result = new TestRunResult(trackers, new HashSet<>(), runnerTestSuites,
                runnerTestSuites, executedSuites, new HashMap<>(), new TestStats(), null, 1,
                executedSuites.size());
        new TestRunnerService(dataStore).persistTestRunData(true, false, "commit-1", "main",
                System.currentTimeMillis(), result, null);
    }

    /**
     * Write an empty class file for a suite, so the directory scan resolves its name.
     *
     * @param suiteName the fully qualified suite name
     * @throws IOException if the file cannot be written
     */
    private void writeClassFile(final String suiteName) throws IOException {
        File classFile = new File(classesDir, suiteName.replace('.', File.separatorChar) + ".class");
        classFile.getParentFile().mkdirs();
        Files.write(classFile.toPath(), new byte[]{1});
    }

    /**
     * A suite as a prior build left it.
     *
     * @param name the suite name
     * @return the seeded tracker
     */
    private static TestSuiteTracker seeded(final String name) {
        TestSuiteTracker tracker = new TestSuiteTracker(name);
        tracker.getTestStats().setNumRuns(4);
        tracker.getTestStats().setAvgRunTime(100);
        return tracker;
    }

    /**
     * Delete a directory tree.
     *
     * @param file the file or directory to delete
     */
    private static void deleteRecursively(final File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
