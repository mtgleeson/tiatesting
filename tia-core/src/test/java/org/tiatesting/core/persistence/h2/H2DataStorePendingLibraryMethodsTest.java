package org.tiatesting.core.persistence.h2;

import org.junit.jupiter.api.*;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TrackedLibrary;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the pending library impacted methods CRUD operations in {@link H2DataStore} under the
 * publish-seq keying: batches are keyed {@code (groupArtifact, publishSeq)}, persists merge by
 * method id within a sequence, and deletion is per publish sequence.
 * Uses an in-memory-like temp directory H2 database per test to ensure isolation.
 */
class H2DataStorePendingLibraryMethodsTest {

    private H2DataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-test-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
        dataStore.getTiaData(true);

        TrackedLibrary lib = new TrackedLibrary("com.example:mylib", "/projects/mylib", null);
        dataStore.persistTrackedLibrary(lib);
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

    @Test
    void persistAndReadPendingMethods() {
        // given a pending batch at seq 1
        Set<Integer> methodIds = new HashSet<>(Arrays.asList(10, 20, 30));
        PendingLibraryImpactedMethod pending = new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.0.0", 1L, methodIds);

        // when it is persisted and read back
        dataStore.persistPendingLibraryImpactedMethods(pending);

        // then the batch round-trips with its seq, display version and methods
        List<PendingLibraryImpactedMethod> result = dataStore.readPendingLibraryImpactedMethods("com.example:mylib");
        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getPublishSeq());
        assertEquals("1.0.0", result.get(0).getStampVersion());
        assertEquals(methodIds, result.get(0).getSourceMethodIds());
    }

    @Test
    void persistAddsNewRowsWithoutDeletingExisting() {
        // given two persists for the same publish seq with different method ids
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.0.0", 1L, new HashSet<>(Arrays.asList(10, 20))));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.0.0", 1L, new HashSet<>(Arrays.asList(30, 40))));

        // then the rows merge into one batch with all method ids
        List<PendingLibraryImpactedMethod> result = dataStore.readPendingLibraryImpactedMethods("com.example:mylib");
        assertEquals(1, result.size());
        assertTrue(result.get(0).getSourceMethodIds().containsAll(Arrays.asList(10, 20, 30, 40)),
                "All method IDs from both persists should be present");
    }

    @Test
    void persistDuplicateMethodIdIsIdempotent() {
        // given the same batch persisted twice
        Set<Integer> batch = new HashSet<>(Arrays.asList(10, 20));
        PendingLibraryImpactedMethod pending = new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.0.0", 1L, batch);
        dataStore.persistPendingLibraryImpactedMethods(pending);
        dataStore.persistPendingLibraryImpactedMethods(pending);

        // then the batch is unchanged
        List<PendingLibraryImpactedMethod> result = dataStore.readPendingLibraryImpactedMethods("com.example:mylib");
        assertEquals(1, result.size());
        assertEquals(batch, result.get(0).getSourceMethodIds());
    }

    @Test
    void multipleBatchesForDifferentPublishSeqs() {
        // given batches at two publish seqs - including the same version string (snapshot rebuilds)
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.0-SNAPSHOT", 1L, new HashSet<>(Arrays.asList(10))));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.0-SNAPSHOT", 2L, new HashSet<>(Arrays.asList(20))));

        // then they stay distinct batches keyed by seq
        List<PendingLibraryImpactedMethod> result = dataStore.readPendingLibraryImpactedMethods("com.example:mylib");
        assertEquals(2, result.size());
    }

    @Test
    void deletePendingMethodsByPublishSeq() {
        // given batches at seq 1 and 2
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.0.0", 1L, new HashSet<>(Arrays.asList(10))));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:mylib", "2.0.0", 2L, new HashSet<>(Arrays.asList(20))));

        // when seq 1 is deleted
        dataStore.deletePendingLibraryImpactedMethods("com.example:mylib", 1L);

        // then only seq 2 remains
        List<PendingLibraryImpactedMethod> result = dataStore.readPendingLibraryImpactedMethods("com.example:mylib");
        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).getPublishSeq());
    }

    @Test
    void readAllPendingAcrossMultipleLibraries() {
        // given batches for two libraries
        TrackedLibrary lib2 = new TrackedLibrary("com.example:other", "/projects/other", null);
        dataStore.persistTrackedLibrary(lib2);

        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.0.0", 1L, new HashSet<>(Arrays.asList(10))));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:other", "3.0.0", 1L, new HashSet<>(Arrays.asList(50))));

        // then the all-libraries read returns both
        List<PendingLibraryImpactedMethod> all = dataStore.readAllPendingLibraryImpactedMethods();
        assertEquals(2, all.size());
    }

    @Test
    void deletingTrackedLibraryCascadesPendingRows() {
        // given a pending batch for the tracked library
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.0.0", 1L, new HashSet<>(Arrays.asList(10, 20))));

        // when the library is deleted
        dataStore.deleteTrackedLibrary("com.example:mylib");

        // then its pending rows are gone
        List<PendingLibraryImpactedMethod> result = dataStore.readPendingLibraryImpactedMethods("com.example:mylib");
        assertTrue(result.isEmpty());
    }

    @Test
    void readReturnsEmptyListWhenTableDoesNotExist() {
        // given a fresh datastore whose DB has not been bootstrapped
        H2DataStore freshStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "fresh"));

        // then the pending read returns empty
        List<PendingLibraryImpactedMethod> result = freshStore.readPendingLibraryImpactedMethods("com.example:mylib");
        assertTrue(result.isEmpty());
        freshStore.close();
    }
}
