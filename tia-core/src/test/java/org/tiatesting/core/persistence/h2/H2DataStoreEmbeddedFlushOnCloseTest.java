package org.tiatesting.core.persistence.h2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TrackedLibrary;

import java.io.File;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the embedded-mode durability contract across the Maven plugin JVM to forked test JVM
 * handoff. In a seed run the plugin JVM reconciles and writes the tracked-library row, then closes
 * its datastore so the forked test JVM can open the same {@code .mv.db} file. If {@code close()}
 * releases the file lock without flushing the MVStore write buffer, that small write is discarded:
 * the forked JVM finds no schema, recreates the DB, and (because reconcile does not run on the
 * persist path) the tracked library is silently lost.
 *
 * <p>Reproduced in a single JVM: writing a tracked library, closing the datastore, then opening a
 * fresh datastore on the same file must still see the row - i.e. {@code close()} must flush before
 * releasing the lock.
 */
class H2DataStoreEmbeddedFlushOnCloseTest {

    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-flush-", "");
        tempDir.delete();
        tempDir.mkdirs();
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

    /**
     * A tracked-library write followed immediately by {@code close()} must survive being reopened
     * by a fresh datastore on the same on-disk database - the scenario the forked test JVM hits.
     */
    @Test
    void trackedLibraryWrittenThenClosedSurvivesReopenByFreshDatastore() {
        // given a fresh embedded datastore that creates the schema, writes a tracked library, closes
        H2DataStore first = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "master"));
        first.getTiaData(true);
        first.persistTrackedLibrary(new TrackedLibrary(
                "org.example:lib", "/projects/lib", null, "1.0-SNAPSHOT", "hash123"));
        first.close();

        // when a fresh datastore opens the same on-disk DB (as the forked test JVM would)
        H2DataStore second = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "master"));
        try {
            Map<String, TrackedLibrary> tracked = second.readTrackedLibraries();

            // then the row written before close() is still present - close() flushed it to disk
            assertTrue(tracked.containsKey("org.example:lib"),
                    "Tracked library written before close() must survive a reopen; close() must flush.");
            assertEquals("1.0-SNAPSHOT", tracked.get("org.example:lib").getLastSourceProjectVersion());
            assertEquals("hash123", tracked.get("org.example:lib").getLastSourceProjectJarHash());
        } finally {
            second.close();
        }
    }
}
