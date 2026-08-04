package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Lock the atomicity of the seal bundle. The method catalogue, the library drain cleanup and the
 * commit value must land together or not at all - a catalogue or library baseline that is ahead
 * of the stored commit puts stored line numbers in a different coordinate space to the diff that
 * reads them. See the "Persist flow and crash safety" chapter in {@code WIKI.md}.
 */
class JdbcDataStoreSealedRunDataTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Build a fresh embedded H2 data store seeded with a sealed commit ("commitA") and a
     * pre-existing method catalogue. Seeding the catalogue (rather than leaving it empty) is
     * what lets the failure tests below distinguish a genuine rollback from a no-op: an empty
     * table before and after a failed seal proves nothing, but seeded rows that either survive
     * or vanish do.
     *
     * @throws Exception if the temp directory or the embedded H2 database cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-sealed-run-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test"));
        dataStore.getTiaData(true);

        TiaData seed = new TiaData();
        seed.setCommitValue("commitA");
        seed.setBranch("main");
        seed.setLastUpdated(Instant.now());
        dataStore.persistCoreData(seed);
        dataStore.persistSourceMethods(seedMethods());
    }

    /**
     * Close the data store and remove the temp directory created for it in {@link #setUp()}, so
     * repeated test runs do not leak an embedded-H2 directory per run.
     */
    @AfterEach
    void tearDown() {
        dataStore.close();
        deleteRecursively(tempDir);
    }

    @Test
    void sealPersistsCatalogueLibrariesAndCommitTogether() {
        // given
        TrackedLibrary library = new TrackedLibrary();
        library.setGroupArtifact("com.example:lib");
        library.setProjectDir("/repo/lib");
        library.setMappingBaselineCommit("commitB");
        library.setLastAppliedSeq(4L);
        dataStore.persistTrackedLibrary(library);
        library.setMappingBaselineCommit("commitC");

        // when
        dataStore.persistSealedRunData(new SealedRunData(coreData("commitC"), methods(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(library)));

        // then
        assertEquals("commitC", dataStore.getTiaCore().getCommitValue());
        assertEquals(1, dataStore.getMethodsTracked().size());
        assertEquals("commitC",
                dataStore.readTrackedLibraries().get("com.example:lib").getMappingBaselineCommit());
    }

    @Test
    void aFailureDuringTheSealLeavesTheCommitAndCatalogueUnchanged() {
        // given - a method tracker map containing a null value, which fails while the insert
        // SQL is still being built - after the seeded catalogue has already been truncated
        Map<Integer, MethodImpactTracker> broken = new HashMap<>();
        broken.put(1, new MethodImpactTracker("com.example.Foo.bar()V", 1, 5));
        broken.put(2, null);

        // when
        assertThrows(RuntimeException.class, () ->
                dataStore.persistSealedRunData(new SealedRunData(coreData("commitC"), broken,
                        Collections.emptyList(), Collections.emptyList(), new ArrayList<>())));

        // then - the seeded catalogue survived the rolled-back truncate, and the commit value
        // was never advanced; this only holds if the truncate is genuinely undone rather than
        // committed by a missed or narrowed rollback
        assertEquals("commitA", dataStore.getTiaCore().getCommitValue(),
                "the commit value must not advance when the seal bundle fails");
        assertEquals(seedMethods().keySet(), dataStore.getMethodsTracked().keySet(),
                "the seeded catalogue must survive a rolled-back truncate, not just stay empty");
    }

    @Test
    void aFailureAfterTheCatalogueWriteRollsBackTheCatalogueAndCommitTogether() {
        // given - a catalogue write that succeeds (replacing the seeded rows) followed by a
        // library step that fails: a null groupArtifact violates the tia_library primary key.
        // This is the case an accidental nested commit inside the catalogue write would miss,
        // since the catalogue would already be durably committed by the time the library step
        // fails and the outer rollback ran.
        Map<Integer, MethodImpactTracker> replacementMethods = new HashMap<>();
        replacementMethods.put(201, new MethodImpactTracker("com.example.Baz.qux()V", 10, 20));
        replacementMethods.put(202, new MethodImpactTracker("com.example.Baz.quux()V", 21, 25));

        TrackedLibrary invalidLibrary = new TrackedLibrary();
        invalidLibrary.setGroupArtifact(null);
        invalidLibrary.setProjectDir("/repo/lib");
        invalidLibrary.setMappingBaselineCommit("commitB");

        // when
        assertThrows(RuntimeException.class, () ->
                dataStore.persistSealedRunData(new SealedRunData(coreData("commitC"), replacementMethods,
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.singletonList(invalidLibrary))));

        // then - the already-written replacement catalogue was rolled back along with the failed
        // library write and the commit value, proving the whole bundle commits or rolls back as
        // one unit rather than as independently-committed steps
        assertEquals("commitA", dataStore.getTiaCore().getCommitValue(),
                "the commit value must not advance when a later step in the seal fails");
        assertEquals(seedMethods().keySet(), dataStore.getMethodsTracked().keySet(),
                "a catalogue write that already succeeded must still be rolled back when a later step in the same seal fails");
    }

    /**
     * Build core data carrying the given commit value, for use as the seal payload.
     *
     * @param commitValue the commit the bundle should seal
     * @return populated core data
     */
    private TiaData coreData(String commitValue) {
        TiaData tiaData = dataStore.getTiaCore();
        tiaData.setCommitValue(commitValue);
        tiaData.setBranch("main");
        tiaData.setLastUpdated(Instant.now());
        return tiaData;
    }

    /**
     * Build a single-entry method catalogue for the seal payload.
     *
     * @return method id to tracker map
     */
    private Map<Integer, MethodImpactTracker> methods() {
        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        methods.put(1, new MethodImpactTracker("com.example.Foo.bar()V", 1, 5));
        return methods;
    }

    /**
     * Build the multi-entry method catalogue seeded into the data store in {@link #setUp()},
     * used both to populate the table and to assert its rows survived or were rolled back.
     *
     * @return method id to tracker map, with ids distinct from {@link #methods()} and the
     *         replacement catalogues used by the failure tests
     */
    private Map<Integer, MethodImpactTracker> seedMethods() {
        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        methods.put(101, new MethodImpactTracker("com.example.Seed.one()V", 1, 5));
        methods.put(102, new MethodImpactTracker("com.example.Seed.two()V", 6, 10));
        methods.put(103, new MethodImpactTracker("com.example.Seed.three()V", 11, 15));
        return methods;
    }

    /**
     * Recursively delete a directory and its contents, best-effort. Used to clean up the
     * per-test embedded-H2 temp directory created in {@link #setUp()}.
     *
     * @param file the file or directory to delete
     */
    private void deleteRecursively(File file) {
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
