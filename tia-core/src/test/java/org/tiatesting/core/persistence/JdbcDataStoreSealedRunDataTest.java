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
import static org.junit.jupiter.api.Assertions.assertNull;
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
    }

    @AfterEach
    void tearDown() {
        dataStore.close();
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
        // given - a method tracker map containing a null value, which fails mid-insert
        Map<Integer, MethodImpactTracker> broken = new HashMap<>();
        broken.put(1, new MethodImpactTracker("com.example.Foo.bar()V", 1, 5));
        broken.put(2, null);

        // when
        assertThrows(RuntimeException.class, () ->
                dataStore.persistSealedRunData(new SealedRunData(coreData("commitC"), broken,
                        Collections.emptyList(), Collections.emptyList(), new ArrayList<>())));

        // then - nothing advanced
        assertEquals("commitA", dataStore.getTiaCore().getCommitValue(),
                "the commit value must not advance when the seal bundle fails");
        assertEquals(0, dataStore.getMethodsTracked().size(),
                "the catalogue must not be left half-written");
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
}
