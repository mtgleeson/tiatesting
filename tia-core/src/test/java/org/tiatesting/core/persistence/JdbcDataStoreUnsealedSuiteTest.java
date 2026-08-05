package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodIdSet;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock the per-suite unsealed flag. A suite's mapping rows are marked unsealed when written and
 * cleared by the seal, so a run that crashes before sealing leaves exactly the suites that ran
 * flagged for a forced re-run. See the "Persist flow and crash safety" chapter in
 * {@code WIKI.md}.
 */
class JdbcDataStoreUnsealedSuiteTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-unsealed-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test"));
        dataStore.getTiaData(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        dataStore.close();
        deleteRecursively(tempDir.toPath());
    }

    @Test
    void aSuiteWithCoverageIsMarkedUnsealed() {
        // given
        Map<String, TestSuiteTracker> suites = suites(withCoverage("SuiteA"));

        // when
        dataStore.persistTestSuites(suites);

        // then
        assertTrue(dataStore.getTestSuitesTracked().get("SuiteA").isUnsealed());
    }

    @Test
    void aSuiteWithoutCoverageIsNotMarkedUnsealed() {
        // given - SuiteB ran, SuiteC did not (Tia ignored it, so it has no classes impacted)
        Map<String, TestSuiteTracker> suites = suites(withCoverage("SuiteB"), withoutCoverage("SuiteC"));

        // when
        dataStore.persistTestSuites(suites);

        // then
        assertTrue(dataStore.getTestSuitesTracked().get("SuiteB").isUnsealed());
        assertFalse(dataStore.getTestSuitesTracked().get("SuiteC").isUnsealed(),
                "a suite Tia ignored must not be flagged");
    }

    @Test
    void anExistingFlagSurvivesALaterPersistThatDidNotRunTheSuite() {
        // given - SuiteD ran and was flagged, and the run never sealed
        dataStore.persistTestSuites(suites(withCoverage("SuiteD")));

        // when - a later run persists without having run SuiteD
        dataStore.persistTestSuites(suites(withoutCoverage("SuiteD")));

        // then
        assertTrue(dataStore.getTestSuitesTracked().get("SuiteD").isUnsealed(),
                "a flag from an earlier unsealed run must not be cleared by a later persist");
    }

    @Test
    void clearingRemovesTheFlagFromEverySuite() {
        // given
        dataStore.persistTestSuites(suites(withCoverage("SuiteE"), withCoverage("SuiteF")));

        // when
        dataStore.clearUnsealedTestSuites();

        // then
        assertFalse(dataStore.getTestSuitesTracked().get("SuiteE").isUnsealed());
        assertFalse(dataStore.getTestSuitesTracked().get("SuiteF").isUnsealed());
    }

    @Test
    void aStatsOnlyPersistDoesNotClearAnExistingFlag() {
        // given - SuiteG ran and was flagged, and the run never sealed
        dataStore.persistTestSuites(suites(withCoverage("SuiteG")));

        // when - a stats-only run (e.g. a test-stats update) persists a fresh tracker for the same suite
        dataStore.persistTestSuiteStatsOnly(suites(withoutCoverage("SuiteG")));

        // then
        assertTrue(dataStore.getTestSuitesTracked().get("SuiteG").isUnsealed(),
                "a stats-only persist must not clear a flag set by an earlier unsealed run");
    }

    @Test
    void aStatsOnlyPersistOfASuiteWithCoverageNeverSetsTheFlag() {
        // given - SuiteH has never been persisted, so its flag starts false
        Map<String, TestSuiteTracker> suites = suites(withCoverage("SuiteH"));

        // when - a stats-only persist runs with a tracker that carries coverage data
        dataStore.persistTestSuiteStatsOnly(suites);

        // then
        assertFalse(dataStore.getTestSuitesTracked().get("SuiteH").isUnsealed(),
                "a stats-only persist must never set the unsealed flag, even for a suite with coverage");
    }

    @Test
    void theFlagSurvivesTheAliasedJoinReadPath() {
        // given
        dataStore.persistTestSuites(suites(withCoverage("SuiteI")));

        // when - read back through the aliased join used by getTiaData(true), not the
        // metadata-only path the other assertions in this class use
        TiaData tiaData = dataStore.getTiaData(true);

        // then
        assertTrue(tiaData.getTestSuitesTracked().get("SuiteI").isUnsealed(),
                "the suite_unsealed alias must map back to the unsealed flag");
    }

    /**
     * Build a suite tracker that has coverage this run, so its mapping rows will be written.
     *
     * @param name the suite name
     * @return the tracker
     */
    private TestSuiteTracker withCoverage(String name) {
        TestSuiteTracker tracker = new TestSuiteTracker(name);
        MethodIdSet methods = new MethodIdSet();
        methods.add(name.hashCode());
        List<ClassImpactTracker> classes = new ArrayList<>();
        classes.add(new ClassImpactTracker("com/example/" + name + ".java", methods));
        tracker.setClassesImpacted(classes);
        return tracker;
    }

    /**
     * Build a suite tracker with no coverage this run, as carried forward for a suite Tia ignored.
     *
     * @param name the suite name
     * @return the tracker
     */
    private TestSuiteTracker withoutCoverage(String name) {
        return new TestSuiteTracker(name);
    }

    /**
     * Collect trackers into the name-keyed map {@code persistTestSuites} expects.
     *
     * @param trackers the suite trackers
     * @return the map
     */
    private Map<String, TestSuiteTracker> suites(TestSuiteTracker... trackers) {
        Map<String, TestSuiteTracker> map = new HashMap<>();
        for (TestSuiteTracker tracker : Arrays.asList(trackers)) {
            map.put(tracker.getName(), tracker);
        }
        return map;
    }

    /**
     * Delete a directory tree, so each test method's embedded H2 database directory is cleaned up
     * rather than leaked into the OS temp directory.
     *
     * @param root the directory to delete, along with everything under it
     * @throws IOException if a file or directory cannot be deleted
     */
    private void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
