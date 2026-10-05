package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.CoreStatsIncrement;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock the re-seed clear-out the seal performs when {@link SealedRunData#isReseed()} is set: every
 * suite the run did not rewrite (not flagged unsealed) is deleted with its classes and edges unless
 * it is developer-disabled, orphan rows are swept, failed entries for deleted suites are pruned,
 * and everything else - core stats, history, observed suites' stats - is kept. Runs against
 * embedded H2; {@link PostgresReseedSealTest} re-runs it against Postgres. See the "Forced runs and
 * re-seed" chapter in {@code WIKI.md}.
 */
class ReseedSealTest {

    DataStore dataStore;
    private File tempDir;

    /**
     * Open the store under test. Overridden by the Postgres mirror so the same assertions run
     * against the other vendor's SQL.
     *
     * @return an open datastore the fixture owns and closes
     * @throws Exception if the store cannot be opened
     */
    DataStore openStore() throws Exception {
        tempDir = File.createTempFile("tia-reseed-seal-", "");
        tempDir.delete();
        tempDir.mkdirs();
        return new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
    }

    /**
     * Open a fresh store and seal an initial commit, so each test starts from a stored mapping.
     *
     * @throws Exception if the store cannot be opened
     */
    @BeforeEach
    void setUp() throws Exception {
        dataStore = openStore();
        dataStore.getTiaData();
        dataStore.persistCoreData(coreData("commitA"));
    }

    /**
     * Close the store and remove any temp directory {@link #openStore()} created.
     */
    @AfterEach
    void tearDown() {
        if (dataStore != null) {
            dataStore.close();
        }
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * A re-seed deletes a suite the run did not rewrite, with its edges and its
     * now-unreferenced methods.
     */
    @Test
    void reseedDropsSuitesTheRunDidNotObserve() {
        // given - "Old" was sealed by an earlier run; "Seen" was just rewritten by this run
        persistSealedSuite("Old", false, 1);
        persistSuite("Seen", false, 2);

        // when
        dataStore.persistSealedRunData(seal(true, catalogue(1, 2)));

        // then
        assertEquals(Collections.singleton("Seen"), dataStore.getTestSuitesTracked().keySet());
        assertEquals(Collections.singleton(2), dataStore.getUniqueMethodIdsTracked());
        assertEquals(Collections.singleton(2), dataStore.getMethodsTracked().keySet());
    }

    /**
     * A developer-disabled suite the run did not rewrite keeps its row and flag but loses its
     * edges.
     */
    @Test
    void reseedKeepsADeveloperDisabledSuiteButStripsItsEdges() {
        // given
        persistSealedSuite("Disabled", true, 1);
        persistSuite("Seen", false, 2);

        // when
        dataStore.persistSealedRunData(seal(true, catalogue(1, 2)));

        // then
        Map<String, TestSuiteTracker> suites = dataStore.getTestSuitesTracked();
        assertEquals(new HashSet<>(Arrays.asList("Disabled", "Seen")), suites.keySet());
        assertTrue(suites.get("Disabled").isDeveloperDisabled());
        assertEquals(Collections.singleton(2), dataStore.getUniqueMethodIdsTracked());
    }

    /**
     * A suite the re-seed run rewrote keeps its accumulated stats.
     */
    @Test
    void reseedKeepsTheStatsOfObservedSuites() {
        // given - "Seen" has two stored runs and is rewritten by this run, contributing a third
        persistSealedSuite("Seen", false, 2);
        persistSealedSuite("Seen", false, 2);
        persistSuite("Seen", false, 2);

        // when
        dataStore.persistSealedRunData(seal(true, catalogue(2)));

        // then
        assertEquals(3L, dataStore.getTestSuitesTracked().get("Seen").getTestStats().getNumRuns());
    }

    /**
     * Failed-set entries naming a deleted suite are pruned; the observed suite's entry stays.
     */
    @Test
    void reseedPrunesFailedEntriesForDeletedSuites() {
        // given
        persistSealedSuite("Old", false, 1);
        dataStore.persistTestSuitesFailed(Collections.<String>emptySet(),
                new HashSet<>(Arrays.asList("Old", "Seen")));
        persistSuite("Seen", false, 2);

        // when
        dataStore.persistSealedRunData(seal(true, catalogue(1, 2)));

        // then
        assertEquals(Collections.singleton("Seen"), dataStore.getTiaData().getTestSuitesFailed());
    }

    /**
     * A re-seed advances the commit as any seal does and leaves history rows alone.
     */
    @Test
    void reseedLeavesCoreAndHistoryAlone() {
        // given
        dataStore.persistTestRunHistoryEntry(TestRunHistoryEntry.create("main", "commitA",
                1_700_000_000_000L, 1, 0, 0, 1_000L, true, 0L, 0,
                RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, false));
        persistSealedSuite("Old", false, 1);
        persistSuite("Seen", false, 2);

        // when
        dataStore.persistSealedRunData(seal(true, catalogue(1, 2)));

        // then
        assertEquals("commitB", dataStore.getTiaCore().getCommitValue());
        assertEquals(1, dataStore.readTestRunHistory().size());
    }

    /**
     * Without the re-seed flag the seal deletes nothing.
     */
    @Test
    void anOrdinarySealKeepsUnobservedSuites() {
        // given
        persistSealedSuite("Old", false, 1);
        persistSuite("Seen", false, 2);

        // when
        dataStore.persistSealedRunData(seal(false, catalogue(1, 2)));

        // then
        assertEquals(new HashSet<>(Arrays.asList("Old", "Seen")), dataStore.getTestSuitesTracked().keySet());
        assertEquals(new HashSet<>(Arrays.asList(1, 2)), dataStore.getUniqueMethodIdsTracked());
    }

    /**
     * A failure after the clear-out inside the seal rolls the clear-out back with everything else.
     */
    @Test
    void aFailedReseedSealRollsBackTheClearOut() {
        // given - a core row with no last-updated time fails the commit write, which runs after
        // the clear-out inside the same transaction
        persistSealedSuite("Old", false, 1);
        persistSuite("Seen", false, 2);
        TiaData broken = coreData("commitB");
        broken.setLastUpdated(null);
        SealedRunData failingSeal = new SealedRunData(broken, catalogue(1, 2),
                Collections.emptyList(), Collections.emptyList(), new ArrayList<>(),
                CoreStatsIncrement.none(), true);

        // when
        Executable seal = () -> dataStore.persistSealedRunData(failingSeal);

        // then
        assertThrows(RuntimeException.class, seal);
        assertEquals(new HashSet<>(Arrays.asList("Old", "Seen")), dataStore.getTestSuitesTracked().keySet());
        assertEquals(new HashSet<>(Arrays.asList(1, 2)), dataStore.getUniqueMethodIdsTracked());
        assertEquals("commitA", dataStore.getTiaCore().getCommitValue());
    }

    /**
     * Persist one suite covering the given methods, exactly as a run's own edge write does - which
     * leaves it flagged unsealed, i.e. "rewritten by the run being sealed".
     *
     * @param name the suite name
     * @param developerDisabled whether the suite is flagged developer-disabled
     * @param methodIds the method ids its single class covers
     */
    void persistSuite(String name, boolean developerDisabled, Integer... methodIds) {
        TestSuiteTracker tracker = new TestSuiteTracker(name);
        tracker.setDeveloperDisabled(developerDisabled);
        TestStats stats = new TestStats();
        stats.setNumRuns(1);
        tracker.setTestStats(stats);
        tracker.setClassesImpacted(Collections.singletonList(
                new ClassImpactTracker("com/example/" + name + ".java", Arrays.asList(methodIds))));
        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put(name, tracker);
        dataStore.persistTestSuites(suites);
    }

    /**
     * Persist a suite and seal it straight away, modelling a suite an earlier run wrote and sealed.
     *
     * @param name the suite name
     * @param developerDisabled whether the suite is flagged developer-disabled
     * @param methodIds the method ids its single class covers
     */
    void persistSealedSuite(String name, boolean developerDisabled, Integer... methodIds) {
        persistSuite(name, developerDisabled, methodIds);
        dataStore.clearUnsealedTestSuites();
    }

    /**
     * Build a method catalogue holding one tracker per id.
     *
     * @param ids the method ids
     * @return the catalogue keyed by id
     */
    Map<Integer, MethodImpactTracker> catalogue(Integer... ids) {
        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        for (Integer id : ids) {
            methods.put(id, new MethodImpactTracker("com.example.C.m" + id + "()V", 1, 2));
        }
        return methods;
    }

    /**
     * @param commit the commit to stamp
     * @return core data carrying {@code commit} on branch main
     */
    TiaData coreData(String commit) {
        TiaData tiaData = new TiaData();
        tiaData.setCommitValue(commit);
        tiaData.setBranch("main");
        tiaData.setLastUpdated(Instant.now());
        return tiaData;
    }

    /**
     * @param reseed whether the seal re-seeds
     * @param methods the method catalogue to write
     * @return a seal of commit {@code commitB}
     */
    SealedRunData seal(boolean reseed, Map<Integer, MethodImpactTracker> methods) {
        return new SealedRunData(coreData("commitB"), methods, Collections.emptyList(),
                Collections.emptyList(), new ArrayList<>(), CoreStatsIncrement.none(), reseed);
    }
}
