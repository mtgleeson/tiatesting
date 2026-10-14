package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks that {@link TestRunnerService#persistTestRunData} closes the datastore once it has
 * written, so an embedded H2 file is shut down cleanly by the test JVM. The embedded URL keeps the
 * database open for the life of the JVM and does not close it on exit, so a test JVM that only
 * persisted left the file in a crashed state, and H2's recovery on the next read-write open could
 * rewrite it back to an older version - losing the run's history row and commit advance. A cleanly
 * closed file carries {@code clean:1} in its MVStore header.
 */
class TestRunnerServiceClosesDataStoreTest {

    private File tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("tia-closes-datastore-").toFile();
    }

    @AfterEach
    void tearDown() {
        File[] files = tempDir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        tempDir.delete();
    }

    @Test
    void persistLeavesTheEmbeddedFileCleanlyClosed() throws IOException {
        // given
        TestRunnerService service = new TestRunnerService(newDataStore());

        // when
        service.persistTestRunData(true, true, "new-commit", "main", System.currentTimeMillis(),
                makeResult(), null);

        // then
        assertTrue(fileHeader().contains("clean:1"), fileHeader());
    }

    @Test
    void persistClosesTheDataStoreWhenAWriteFails() throws IOException {
        // given - a datastore whose suite mapping write fails mid-persist
        JdbcDataStore dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null)) {
            @Override
            public void persistTestSuites(final Map<String, TestSuiteTracker> testSuites) {
                throw new IllegalStateException("simulated failure");
            }
        };
        dataStore.getTiaData();
        TestRunnerService service = new TestRunnerService(dataStore);

        // when
        assertThrows(IllegalStateException.class, () -> service.persistTestRunData(true, true,
                "new-commit", "main", System.currentTimeMillis(), makeResult(), null));

        // then
        assertTrue(fileHeader().contains("clean:1"), fileHeader());
    }

    @Test
    void aLaterPersistInTheSameJvmReopensTheDataStore() {
        // given - a first test plan persisted and closed the datastore
        JdbcDataStore dataStore = newDataStore();
        TestRunnerService service = new TestRunnerService(dataStore);
        service.persistTestRunData(true, true, "first-commit", "main", System.currentTimeMillis(),
                makeResult(), null);

        // when - a retry round in the same JVM persists again
        service.persistTestRunData(true, true, "second-commit", "main", System.currentTimeMillis(),
                makeResult(), null);

        // then
        assertEquals("second-commit", dataStore.getTiaData().getCommitValue());
        assertEquals(2, dataStore.readTestRunHistory().size());
    }

    /**
     * Open an embedded datastore in the temp directory with its schema created.
     *
     * @return the datastore
     */
    private JdbcDataStore newDataStore() {
        JdbcDataStore dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
        dataStore.getTiaData();
        return dataStore;
    }

    /**
     * Read the MVStore file header, the first block of the {@code .mv.db} file.
     *
     * @return the header text, e.g. {@code H:2,block:9,...,clean:1,...}
     * @throws IOException if the file cannot be read
     */
    private String fileHeader() throws IOException {
        byte[] header = new byte[256];
        try (InputStream in = Files.newInputStream(new File(tempDir, "tiadb.mv.db").toPath())) {
            int read = in.read(header);
            return new String(header, 0, read, StandardCharsets.ISO_8859_1).trim();
        }
    }

    /**
     * Build a one-suite result that ran every suite, so the persist writes the mapping, the seal
     * and a history row.
     *
     * @return the test run result
     */
    private TestRunResult makeResult() {
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        trackers.put("com.example.SomeTest", new TestSuiteTracker("com.example.SomeTest"));
        return new TestRunResult(
                trackers, new HashSet<>(), new HashSet<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>(), new TestStats(), null, 0, 1, 0,
                TestRunSelectionDetails.empty(), RunAttempt.FIRST);
    }
}
