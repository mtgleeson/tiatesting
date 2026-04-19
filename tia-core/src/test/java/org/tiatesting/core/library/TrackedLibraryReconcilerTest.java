package org.tiatesting.core.library;

import org.junit.jupiter.api.*;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.h2.H2DataStore;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TrackedLibraryReconcilerTest {

    private H2DataStore dataStore;
    private File tempDir;
    private TrackedLibraryReconciler reconciler;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-reconciler-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(tempDir.getAbsolutePath(), "test");
        dataStore.getTiaData(true);
        reconciler = new TrackedLibraryReconciler();
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
    void insertsNewLibrariesFromConfig() {
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Arrays.asList("com.example:libA", "com.example:libB"),
                "/projects/source", null);

        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        assertEquals(2, result.size());
        assertTrue(result.containsKey("com.example:libA"));
        assertTrue(result.containsKey("com.example:libB"));
        assertEquals("/projects/source", result.get("com.example:libA").getProjectDir());
    }

    @Test
    void deletesLibrariesRemovedFromConfig() {
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:old", "/old", null, null, null));
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:keep", "/keep", null, null, null));

        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:keep"),
                "/projects/source", null);

        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("com.example:keep"));
        assertFalse(result.containsKey("com.example:old"));
    }

    @Test
    void updatesProjectDirWhenChanged() {
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:lib", "/old/path", null, "1.0", "hash1"));

        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"),
                "/new/path", null);

        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        assertEquals("/new/path", result.get("com.example:lib").getProjectDir());
        assertEquals("1.0", result.get("com.example:lib").getLastSourceProjectVersion());
        assertEquals("hash1", result.get("com.example:lib").getLastSourceProjectJarHash());
    }

    @Test
    void doesNotUpdateWhenConfigUnchanged() {
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:lib", "/same", null, "2.0", "hash2"));

        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.singletonList("com.example:lib"),
                "/same", null);

        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        assertEquals("2.0", result.get("com.example:lib").getLastSourceProjectVersion());
        assertEquals("hash2", result.get("com.example:lib").getLastSourceProjectJarHash());
    }

    @Test
    void emptyConfigDeletesAllExisting() {
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:a", "/a", null, null, null));
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:b", "/b", null, null, null));

        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.emptyList(), "/projects/source", null);

        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        assertTrue(result.isEmpty());
    }

    @Test
    void reconcileWithEmptyDbAndEmptyConfig() {
        LibraryImpactAnalysisConfig config = new LibraryImpactAnalysisConfig(
                Collections.emptyList(), "/projects/source", null);

        Map<String, TrackedLibrary> result = reconciler.reconcile(dataStore, config);

        assertTrue(result.isEmpty());
    }
}
