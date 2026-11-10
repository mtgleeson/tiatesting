package org.tiatesting.spock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spockframework.runtime.model.SpecInfo;
import org.tiatesting.core.coverage.client.JacocoClient;
import org.tiatesting.core.coverage.result.CoverageResult;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.testrunner.TestRunIdentity;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies a Spock mapping run whose coverage collection from the JaCoCo agent fails persists
 * nothing - no suite mapping, no commit advance and no history row - and that the failure is not
 * thrown into Spock. Coverage comes from a scripted client, so no JaCoCo agent is needed.
 */
class TiaSpockRunListenerCoverageFailureTest {

    private static final String BRANCH = "main";
    private static final String COMMIT = "commit-1";
    private static final String SPEC = "com.example.ATest";

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database in a new temp directory and bootstrap its schema.
     *
     * @throws Exception if the temp directory cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-spock-listener-coverage-failure-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName(BRANCH, null));
        dataStore.getTiaData();
    }

    /**
     * Close the data store so its embedded H2 database releases its file lock, then remove the
     * temp directory.
     */
    @AfterEach
    void tearDown() {
        dataStore.close();
        File[] files = tempDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        tempDir.delete();
    }

    @Test
    void aRunWhoseCoverageIsCollectedPersists() {
        // given - a coverage client that never fails
        TiaSpockRunListener listener = listenerWith(new FailingCoverageClient(false, false));
        SpecInfo spec = spec();

        // when
        listener.beforeSpec(spec);
        listener.afterSpec(spec);
        listener.finishAllTests(Collections.singleton(SPEC), System.currentTimeMillis());

        // then - the control: the same run persists when nothing fails
        assertEquals(COMMIT, dataStore.getTiaData().getCommitValue());
        assertEquals(1, dataStore.readTestRunHistory().size());
    }

    @Test
    void aFailedSpecDumpPersistsNothing() {
        // given - the dump for the spec fails
        TiaSpockRunListener listener = listenerWith(new FailingCoverageClient(false, true));
        SpecInfo spec = spec();

        // when - the spec finishes without the listener throwing into Spock
        listener.beforeSpec(spec);
        listener.afterSpec(spec);
        listener.finishAllTests(Collections.singleton(SPEC), System.currentTimeMillis());

        // then
        assertNothingPersisted();
    }

    @Test
    void aFailureAtStartupPersistsNothingAndCollectsNoCoverage() {
        // given - the agent cannot be reached when the listener is built
        FailingCoverageClient coverage = new FailingCoverageClient(true, false);
        TiaSpockRunListener listener = listenerWith(coverage);
        SpecInfo spec = spec();

        // when
        listener.beforeSpec(spec);
        listener.afterSpec(spec);
        listener.finishAllTests(Collections.singleton(SPEC), System.currentTimeMillis());

        // then
        assertEquals(0, coverage.collectCalls());
        assertNothingPersisted();
    }

    /**
     * Assert the datastore holds no commit, no tracked suite and no history row.
     */
    private void assertNothingPersisted() {
        assertNull(dataStore.getTiaData().getCommitValue());
        assertTrue(dataStore.getTestSuitesTracked().isEmpty());
        assertTrue(dataStore.readTestRunHistory().isEmpty());
    }

    /**
     * Build the listener for an ordinary mapping run that also logs history, over a given
     * coverage client.
     *
     * @param coverage the coverage client
     * @return the listener under test
     */
    private TiaSpockRunListener listenerWith(final JacocoClient coverage) {
        return new TiaSpockRunListener(BRANCH, COMMIT, dataStore, Collections.singleton(SPEC), 0,
                true, true, null, TestRunSelectionDetails.empty(), null,
                new TestRunIdentity("spock-run", 1_000L), coverage);
    }

    /**
     * @return the one spec these tests run
     */
    private static SpecInfo spec() {
        SpecInfo specInfo = new SpecInfo();
        specInfo.setPackage("com.example");
        specInfo.setName("ATest");
        return specInfo;
    }

    /**
     * A coverage client with no agent behind it, which can fail at startup or on every dump.
     */
    private static final class FailingCoverageClient extends JacocoClient {

        private final boolean failAtStartup;
        private final boolean failDumps;
        private int collectCalls;

        /**
         * @param failAtStartup whether {@link #initialize()} fails
         * @param failDumps whether every dump fails
         */
        FailingCoverageClient(final boolean failAtStartup, final boolean failDumps) {
            this.failAtStartup = failAtStartup;
            this.failDumps = failDumps;
        }

        /**
         * @throws IOException when scripted to fail at startup
         */
        @Override
        public void initialize() throws IOException {
            if (failAtStartup) {
                throw new IOException("Connection refused");
            }
        }

        /**
         * @return an empty dump
         * @throws IOException when scripted to fail dumps
         */
        @Override
        public CoverageResult collectCoverage() throws IOException {
            collectCalls++;
            if (failDumps) {
                throw new IOException("Socket closed unexpectedly.");
            }
            return new CoverageResult();
        }

        /**
         * @return how many dumps the listener asked for
         */
        int collectCalls() {
            return collectCalls;
        }
    }
}
