package org.tiatesting.core.library;

import org.junit.jupiter.api.*;
import org.tiatesting.core.model.LibraryBuildMetadata;
import org.tiatesting.core.model.LibraryPublish;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reconciliation of declared {@code tiaSourceLibs} against persisted {@code tia_library} rows
 * under the publish ledger model: inserts start with null ledger state (the baseline is seeded by
 * the library's first publish, not by reconcile), config updates preserve the ledger state, and
 * removals cascade the ledger and pending stamps.
 */
class TrackedLibraryReconcilerTest {

    private static final String LIB = "com.example:lib";

    private H2DataStore dataStore;
    private File tempDir;
    private TrackedLibraryReconciler reconciler;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-reconciler-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
        dataStore.getTiaData(true);
        reconciler = new TrackedLibraryReconciler();
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
     * A newly declared library is inserted with its config-derived identity and null ledger
     * state - no source-project resolution happens at reconcile time (the baseline is seeded by
     * the first publish, the applied seq by the first drain).
     */
    @Test
    void insertsNewLibraryWithNullLedgerState() {
        // given a config declaring a never-seen library with source dirs
        LibraryImpactAnalysisConfig config = configFor(LIB, "/projects/lib",
                Collections.singletonList("/projects/lib/src/main/java"));

        // when reconcile runs
        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        // then the row is inserted with config identity and null ledger state
        TrackedLibrary inserted = result.get(LIB);
        assertNotNull(inserted);
        assertEquals("/projects/lib", inserted.getProjectDir());
        assertEquals("/projects/lib/src/main/java", inserted.getSourceDirsCsv());
        assertNull(inserted.getMappingBaselineCommit());
        assertNull(inserted.getLastAppliedSeq());
    }

    /**
     * A config change (project dir / source dirs) updates the row but preserves the ledger state
     * - those fields are owned by the publish stamper and the post-run cleanup, not by config.
     */
    @Test
    void configChangePreservesLedgerState() {
        // given a tracked library with ledger state set
        TrackedLibrary existing = new TrackedLibrary(LIB, "/projects/old", null);
        existing.setMappingBaselineCommit("baseline-1");
        existing.setLastAppliedSeq(4L);
        dataStore.persistTrackedLibrary(existing);

        // when reconcile runs with a changed project dir
        reconciler.reconcile(dataStore, configFor(LIB, "/projects/new", null));

        // then the config fields update and the ledger state survives
        TrackedLibrary updated = dataStore.readTrackedLibraries().get(LIB);
        assertEquals("/projects/new", updated.getProjectDir());
        assertEquals("baseline-1", updated.getMappingBaselineCommit());
        assertEquals(Long.valueOf(4L), updated.getLastAppliedSeq());
    }

    /**
     * An unchanged declaration leaves the persisted row untouched (no redundant write).
     */
    @Test
    void unchangedLibraryIsNotRewritten() {
        // given a tracked library matching the declared config
        TrackedLibrary existing = new TrackedLibrary(LIB, "/projects/lib", null);
        existing.setMappingBaselineCommit("baseline-1");
        dataStore.persistTrackedLibrary(existing);

        // when reconcile runs with the same config
        reconciler.reconcile(dataStore, configFor(LIB, "/projects/lib", null));

        // then the row is unchanged
        TrackedLibrary after = dataStore.readTrackedLibraries().get(LIB);
        assertEquals("baseline-1", after.getMappingBaselineCommit());
    }

    /**
     * A library removed from the declared config is deleted, cascading its publish ledger and
     * pending stamp rows.
     */
    @Test
    void removedLibraryIsDeletedWithLedgerCascade() {
        // given a tracked library with a ledger row and a stamp, no longer declared
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:gone", "/projects/gone", null));
        dataStore.persistLibraryPublish(new LibraryPublish("com.example:gone", "1.0.0", "H1", "c1", 1000L),
                new HashSet<>(Arrays.asList(10)));
        dataStore.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/lib", null));

        // when reconcile runs declaring only the other library
        reconciler.reconcile(dataStore, configFor(LIB, "/projects/lib", null));

        // then the removed library and its ledger/stamps are gone; the declared one remains
        assertFalse(dataStore.readTrackedLibraries().containsKey("com.example:gone"));
        assertTrue(dataStore.readLibraryPublishes("com.example:gone").isEmpty());
        assertTrue(dataStore.readPendingLibraryImpactedMethods("com.example:gone").isEmpty());
        assertTrue(dataStore.readTrackedLibraries().containsKey(LIB));
    }

    /**
     * Build a config declaring one library with an optional stub source-dirs answer.
     *
     * @param coordinate the declared coordinate
     * @param projectDir the declared library project dir
     * @param sourceDirs the source dirs the stub reader reports, or null for none
     * @return the config
     */
    private LibraryImpactAnalysisConfig configFor(String coordinate, String projectDir, List<String> sourceDirs) {
        Map<String, String> projectDirs = new HashMap<>();
        projectDirs.put(coordinate, projectDir);
        return new LibraryImpactAnalysisConfig(Collections.singletonList(coordinate), projectDirs,
                "/projects/source", new StubMetadataReader(sourceDirs));
    }

    /**
     * Stub reader answering only the source-directories read the reconciler uses.
     */
    private static class StubMetadataReader implements LibraryMetadataReader {
        private final List<String> sourceDirs;

        StubMetadataReader(List<String> sourceDirs) {
            this.sourceDirs = sourceDirs;
        }

        @Override
        public List<LibraryBuildMetadata> readLibraryBuildMetadata(String libraryProjectDir, List<String> coordinates) {
            return Collections.emptyList();
        }

        @Override
        public List<ResolvedSourceProjectLibrary> resolveLibrariesInSourceProject(String sourceProjectDir, List<String> coordinates) {
            return Collections.emptyList();
        }

        @Override
        public List<String> readSourceDirectories(String libraryProjectDir) {
            return sourceDirs != null ? sourceDirs : Collections.emptyList();
        }
    }
}
