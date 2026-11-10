package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.coverage.client.CoverageCollectionStatus;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void skippingThePersistAfterACoverageFailureWritesOnlyTheFailureAndClosesTheFile() throws IOException {
        // given - a mapping run whose coverage collection failed
        JdbcDataStore dataStore = newDataStore();
        TestRunnerService service = new TestRunnerService(dataStore);
        CoverageCollectionStatus status = new CoverageCollectionStatus();
        status.recordFailure("for the test suite com.example.SomeTest", new IOException("Connection refused"));

        // when
        service.skipPersistAfterCoverageFailure(status, new TestRunIdentity("run-1", 1_000L), null);

        // then
        assertTrue(fileHeader().contains("clean:1"), fileHeader());
        assertEquals("for the test suite com.example.SomeTest: java.io.IOException: Connection refused",
                dataStore.readTestRunCoverageFailure("run-1"));
        assertNull(dataStore.getTiaData().getCommitValue());
        assertTrue(dataStore.getTestSuitesTracked().isEmpty());
        assertTrue(dataStore.readTestRunHistory().isEmpty());
    }

    @Test
    void aLaterJvmOfARunWhoseCoverageFailedPersistsNothing() {
        // given - a first JVM of the run recorded a coverage failure and wrote nothing else
        JdbcDataStore dataStore = newDataStore();
        TestRunnerService service = new TestRunnerService(dataStore);
        TestRunResult retryRound = makeResult();
        CoverageCollectionStatus status = new CoverageCollectionStatus();
        status.recordFailure("for the test suite com.example.OtherTest", new IOException("Connection refused"));
        service.skipPersistAfterCoverageFailure(status, retryRound.getRunIdentity(), null);

        // when - a test-retry round, a fresh JVM of the same run, persists what it re-ran
        service.persistTestRunData(true, true, "new-commit", "main", System.currentTimeMillis(),
                retryRound, null);

        // then
        assertNull(dataStore.getTiaData().getCommitValue());
        assertTrue(dataStore.getTestSuitesTracked().isEmpty());
        assertTrue(dataStore.readTestRunHistory().isEmpty());
        assertTrue(dataStore.readTestRunContributions(retryRound.getRunIdentity().getRunId()).isEmpty());
    }

    @Test
    void aRunWithNoCoverageFailureRecordedPersists() {
        // given - a coverage failure recorded on some other run
        JdbcDataStore dataStore = newDataStore();
        TestRunnerService service = new TestRunnerService(dataStore);
        dataStore.markTestRunCoverageFailed("another-run", 1_000L, "at startup: java.io.IOException: refused");

        // when
        service.persistTestRunData(true, true, "new-commit", "main", System.currentTimeMillis(),
                makeResult(), null);

        // then
        assertEquals("new-commit", dataStore.getTiaData().getCommitValue());
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
                new HashSet<>(), new HashMap<>(), new TestStats(), null, 0, TestRunResultFixture.syntheticPlanOutcome(1, 0),
                TestRunSelectionDetails.empty(), new TestJvmContribution(), TestRunResultFixture.runIdentity());
    }
}
