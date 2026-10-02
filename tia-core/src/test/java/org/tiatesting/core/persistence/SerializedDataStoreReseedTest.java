package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.CoreStatsIncrement;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The serialized store's in-memory re-seed must keep the same suites the JDBC clear-out keeps: the
 * ones the run rewrote, plus developer-disabled ones stripped of coverage. See the "Forced runs
 * and re-seed" chapter in {@code WIKI.md}.
 */
class SerializedDataStoreReseedTest {

    private SerializedDataStore dataStore;
    private File tempDir;

    /**
     * Open a fresh serialized store in a temp directory with an initial commit sealed.
     *
     * @throws Exception if the temp directory cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-serialized-reseed-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new SerializedDataStore(tempDir.getAbsolutePath(), "test");
        dataStore.persistCoreData(coreData("commitA"));
    }

    /**
     * Remove the temp directory created in {@link #setUp()}.
     */
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
     * The in-memory re-seed keeps the rewritten suite and the developer-disabled one (stripped of
     * coverage), and drops everything else and its methods.
     */
    @Test
    void reseedKeepsRewrittenAndDeveloperDisabledSuitesOnly() {
        // given
        persistSuite("Old", false, 1);
        persistSuite("Disabled", true, 3);
        dataStore.clearUnsealedTestSuites();
        persistSuite("Seen", false, 2);

        // when
        dataStore.persistSealedRunData(seal(true));

        // then
        Map<String, TestSuiteTracker> suites = dataStore.getTiaData(false).getTestSuitesTracked();
        assertEquals(new HashSet<>(Arrays.asList("Seen", "Disabled")), suites.keySet());
        assertTrue(suites.get("Disabled").getClassesImpacted().isEmpty());
        assertEquals(Collections.singleton(2), dataStore.getTiaData(false).getMethodsTracked().keySet());
    }

    /**
     * Without the re-seed flag the serialized seal deletes nothing.
     */
    @Test
    void anOrdinarySealKeepsEverySuite() {
        // given
        persistSuite("Old", false, 1);
        dataStore.clearUnsealedTestSuites();
        persistSuite("Seen", false, 2);

        // when
        dataStore.persistSealedRunData(seal(false));

        // then
        assertEquals(new HashSet<>(Arrays.asList("Old", "Seen")),
                dataStore.getTiaData(false).getTestSuitesTracked().keySet());
    }

    /**
     * Persist one suite covering the given methods, leaving it flagged unsealed.
     *
     * @param name the suite name
     * @param developerDisabled whether the suite is flagged developer-disabled
     * @param methodIds the method ids its single class covers
     */
    private void persistSuite(String name, boolean developerDisabled, Integer... methodIds) {
        TestSuiteTracker tracker = new TestSuiteTracker(name);
        tracker.setDeveloperDisabled(developerDisabled);
        tracker.setClassesImpacted(new ArrayList<>(Collections.singletonList(
                new ClassImpactTracker("com/example/" + name + ".java", Arrays.asList(methodIds)))));
        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put(name, tracker);
        dataStore.persistTestSuites(suites);
    }

    /**
     * @param commit the commit to stamp
     * @return core data carrying {@code commit}
     */
    private TiaData coreData(String commit) {
        TiaData tiaData = new TiaData();
        tiaData.setCommitValue(commit);
        tiaData.setBranch("main");
        tiaData.setLastUpdated(Instant.now());
        return tiaData;
    }

    /**
     * Build a seal whose core data is the stored data re-stamped with {@code commitB}, carrying a
     * catalogue of methods 1-3, as the assembler hands the store its current state.
     *
     * @param reseed whether the seal re-seeds
     * @return the seal payload
     */
    private SealedRunData seal(boolean reseed) {
        TiaData tiaData = dataStore.getTiaData(false);
        tiaData.setCommitValue("commitB");
        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        for (int id = 1; id <= 3; id++) {
            methods.put(id, new MethodImpactTracker("com.example.C.m" + id + "()V", 1, 2));
        }
        return new SealedRunData(tiaData, methods, Collections.emptyList(), Collections.emptyList(),
                new ArrayList<>(), CoreStatsIncrement.none(), reseed);
    }
}
