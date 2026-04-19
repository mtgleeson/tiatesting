package org.tiatesting.core.persistence.h2;

import org.junit.jupiter.api.*;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TrackedLibrary;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the pending library impacted methods CRUD operations in {@link H2DataStore}.
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
        dataStore = new H2DataStore(tempDir.getAbsolutePath(), "test");
        dataStore.getTiaData(true);

        TrackedLibrary lib = new TrackedLibrary("com.example:mylib", "/projects/mylib", null, null, null);
        dataStore.persistTrackedLibrary(lib);
    }

    @AfterEach
    void tearDown() {
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    @Test
    void persistAndReadPendingMethods() {
        Set<Integer> methodIds = new HashSet<>(Arrays.asList(10, 20, 30));
        PendingLibraryImpactedMethod pending = new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.0.0", null, methodIds);

        dataStore.persistPendingLibraryImpactedMethods(pending);

        List<PendingLibraryImpactedMethod> result = dataStore.readPendingLibraryImpactedMethods("com.example:mylib");
        assertEquals(1, result.size());
        assertEquals("1.0.0", result.get(0).getStampVersion());
        assertEquals(methodIds, result.get(0).getSourceMethodIds());
    }

    @Test
    void persistAddsNewRowsWithoutDeletingExisting() {
        Set<Integer> firstBatch = new HashSet<>(Arrays.asList(10, 20));
        PendingLibraryImpactedMethod first = new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.0.0", null, firstBatch);
        dataStore.persistPendingLibraryImpactedMethods(first);

        Set<Integer> secondBatch = new HashSet<>(Arrays.asList(30, 40));
        PendingLibraryImpactedMethod second = new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.0.0", null, secondBatch);
        dataStore.persistPendingLibraryImpactedMethods(second);

        List<PendingLibraryImpactedMethod> result = dataStore.readPendingLibraryImpactedMethods("com.example:mylib");
        assertEquals(1, result.size());
        Set<Integer> allIds = result.get(0).getSourceMethodIds();
        assertTrue(allIds.containsAll(Arrays.asList(10, 20, 30, 40)),
                "All method IDs from both persists should be present");
    }

    @Test
    void persistDuplicateMethodIdIsIdempotent() {
        Set<Integer> batch = new HashSet<>(Arrays.asList(10, 20));
        PendingLibraryImpactedMethod pending = new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.0.0", null, batch);
        dataStore.persistPendingLibraryImpactedMethods(pending);
        dataStore.persistPendingLibraryImpactedMethods(pending);

        List<PendingLibraryImpactedMethod> result = dataStore.readPendingLibraryImpactedMethods("com.example:mylib");
        assertEquals(1, result.size());
        assertEquals(batch, result.get(0).getSourceMethodIds());
    }

    @Test
    void multipleBatchesForDifferentStampVersions() {
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.0.0", null, new HashSet<>(Arrays.asList(10))));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:mylib", "2.0.0", null, new HashSet<>(Arrays.asList(20))));

        List<PendingLibraryImpactedMethod> result = dataStore.readPendingLibraryImpactedMethods("com.example:mylib");
        assertEquals(2, result.size());
    }

    @Test
    void deletePendingMethodsByStampVersion() {
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.0.0", null, new HashSet<>(Arrays.asList(10))));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:mylib", "2.0.0", null, new HashSet<>(Arrays.asList(20))));

        dataStore.deletePendingLibraryImpactedMethods("com.example:mylib", "1.0.0");

        List<PendingLibraryImpactedMethod> result = dataStore.readPendingLibraryImpactedMethods("com.example:mylib");
        assertEquals(1, result.size());
        assertEquals("2.0.0", result.get(0).getStampVersion());
    }

    @Test
    void readAllPendingAcrossMultipleLibraries() {
        TrackedLibrary lib2 = new TrackedLibrary("com.example:other", "/projects/other", null, null, null);
        dataStore.persistTrackedLibrary(lib2);

        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.0.0", null, new HashSet<>(Arrays.asList(10))));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:other", "3.0.0", null, new HashSet<>(Arrays.asList(50))));

        List<PendingLibraryImpactedMethod> all = dataStore.readAllPendingLibraryImpactedMethods();
        assertEquals(2, all.size());
    }

    @Test
    void stampJarHashIsPersistedAndReadBack() {
        PendingLibraryImpactedMethod pending = new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.0-SNAPSHOT", "abc123hash", new HashSet<>(Arrays.asList(10)));
        dataStore.persistPendingLibraryImpactedMethods(pending);

        List<PendingLibraryImpactedMethod> result = dataStore.readPendingLibraryImpactedMethods("com.example:mylib");
        assertEquals("abc123hash", result.get(0).getStampJarHash());
    }

    @Test
    void deletingTrackedLibraryCascadesPendingRows() {
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:mylib", "1.0.0", null, new HashSet<>(Arrays.asList(10, 20))));

        dataStore.deleteTrackedLibrary("com.example:mylib");

        List<PendingLibraryImpactedMethod> result = dataStore.readPendingLibraryImpactedMethods("com.example:mylib");
        assertTrue(result.isEmpty());
    }

    @Test
    void readReturnsEmptyListWhenTableDoesNotExist() {
        H2DataStore freshStore = new H2DataStore(tempDir.getAbsolutePath(), "fresh");
        List<PendingLibraryImpactedMethod> result = freshStore.readPendingLibraryImpactedMethods("com.example:mylib");
        assertTrue(result.isEmpty());
    }
}
