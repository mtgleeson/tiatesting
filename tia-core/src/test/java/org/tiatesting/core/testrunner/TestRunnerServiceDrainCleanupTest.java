package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.*;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.*;
import org.tiatesting.core.persistence.h2.H2DataStore;

import java.io.File;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TestRunnerServiceDrainCleanupTest {

    private H2DataStore dataStore;
    private TestRunnerService service;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(tempDir.getAbsolutePath(), "test");
        dataStore.getTiaData(true);
        service = new TestRunnerService(dataStore);

        TiaData tiaData = dataStore.getTiaData(true);
        tiaData.setCommitValue("initial");
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);
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
    void deletesDrainedPendingBatchesAfterTestRun() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, "0.9.0", null);
        dataStore.persistTrackedLibrary(lib);
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.0.0", null, new HashSet<>(Arrays.asList(10))));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:lib", "1.1.0", null, new HashSet<>(Arrays.asList(20))));

        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDrainedBatch("com.example:lib", "1.0.0");
        drainResult.setObservedLibraryState("com.example:lib", "1.0.0", null);

        persistWithDrainResult(drainResult);

        List<PendingLibraryImpactedMethod> remaining = dataStore.readPendingLibraryImpactedMethods("com.example:lib");
        assertEquals(1, remaining.size());
        assertEquals("1.1.0", remaining.get(0).getStampVersion());
    }

    @Test
    void updatesTrackedLibraryVersionAfterDrain() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, "0.9.0", null);
        dataStore.persistTrackedLibrary(lib);

        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDrainedBatch("com.example:lib", "1.0.0");
        drainResult.setObservedLibraryState("com.example:lib", "1.0.0", null);

        persistWithDrainResult(drainResult);

        TrackedLibrary updated = dataStore.readTrackedLibraries().get("com.example:lib");
        assertEquals("1.0.0", updated.getLastSourceProjectVersion());
    }

    @Test
    void updatesTrackedLibraryJarHashAfterSnapshotDrain() {
        TrackedLibrary lib = new TrackedLibrary("com.example:lib", "/projects/lib", null, null, "oldhash");
        dataStore.persistTrackedLibrary(lib);

        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDrainedBatch("com.example:lib", "1.0-SNAPSHOT");
        drainResult.setObservedLibraryState("com.example:lib", "1.0-SNAPSHOT", "newhash");

        persistWithDrainResult(drainResult);

        TrackedLibrary updated = dataStore.readTrackedLibraries().get("com.example:lib");
        assertEquals("newhash", updated.getLastSourceProjectJarHash());
        assertEquals("1.0-SNAPSHOT", updated.getLastSourceProjectVersion());
    }

    @Test
    void handlesNullDrainResultGracefully() {
        persistWithDrainResult(null);
    }

    @Test
    void handlesEmptyDrainResultGracefully() {
        persistWithDrainResult(new LibraryImpactDrainResult());
    }

    @Test
    void handlesMultipleLibrariesInDrainResult() {
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:a", "/a", null, "0.1", null));
        dataStore.persistTrackedLibrary(new TrackedLibrary("com.example:b", "/b", null, "0.2", null));

        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:a", "1.0.0", null, new HashSet<>(Arrays.asList(10))));
        dataStore.persistPendingLibraryImpactedMethods(new PendingLibraryImpactedMethod(
                "com.example:b", "2.0.0", null, new HashSet<>(Arrays.asList(20))));

        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDrainedBatch("com.example:a", "1.0.0");
        drainResult.addDrainedBatch("com.example:b", "2.0.0");
        drainResult.setObservedLibraryState("com.example:a", "1.0.0", null);
        drainResult.setObservedLibraryState("com.example:b", "2.0.0", null);

        persistWithDrainResult(drainResult);

        assertTrue(dataStore.readPendingLibraryImpactedMethods("com.example:a").isEmpty());
        assertTrue(dataStore.readPendingLibraryImpactedMethods("com.example:b").isEmpty());
        assertEquals("1.0.0", dataStore.readTrackedLibraries().get("com.example:a").getLastSourceProjectVersion());
        assertEquals("2.0.0", dataStore.readTrackedLibraries().get("com.example:b").getLastSourceProjectVersion());
    }

    private void persistWithDrainResult(LibraryImpactDrainResult drainResult) {
        TestRunResult testRunResult = new TestRunResult(
                new HashMap<>(), new HashSet<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>(), new TestStats(), drainResult);
        service.persistTestRunData(true, false, "newcommit", testRunResult);
    }
}
