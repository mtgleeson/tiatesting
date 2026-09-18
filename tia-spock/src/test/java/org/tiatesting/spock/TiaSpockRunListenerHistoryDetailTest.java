package org.tiatesting.spock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestRunTrigger;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that {@link TiaSpockRunListener} forwards the {@link TestRunSelectionDetails} it was
 * constructed with into the {@link org.tiatesting.core.testrunner.TestRunResult} it builds at
 * {@code finishAllTests}, and that the breakdown reaches the persisted history row and trigger
 * rows exactly as {@code TestRunnerServiceHistoryDetailTest} in tia-core proves for the underlying
 * {@code TestRunnerService.persistTestRunData} call. Task 3.2 wired the Maven/JUnit side of this
 * transport via a sidecar file the run listener there reads back; the Spock/Gradle path has no
 * sidecar since selection and persist share one JVM, so this test is the equivalent proof for the
 * in-process threading added in Task 3.4: {@code TiaSpockGlobalExtension} resolves the details
 * from {@code TestSelectorResult.getSelectionDetails()} and hands them straight to the listener's
 * constructor.
 *
 * <p>Driven against a real embedded-H2 {@link JdbcDataStore}, following the same fixture pattern
 * as {@link TiaSpockRunListenerDistributedTest}: what is under test is which rows the persist
 * writes, and a fake store would only assert that the listener calls the methods the test already
 * knows it calls.
 */
class TiaSpockRunListenerHistoryDetailTest {

    private static final String BRANCH = "main";
    private static final String COMMIT = "commit-1";

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database in a new temp directory and bootstrap its schema, so
     * each test starts from an isolated store with a prior commit stamp already in place, as every
     * store a real run persists into already has one.
     *
     * @throws Exception if the temp directory cannot be created or schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-spock-listener-history-detail-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName(BRANCH, null));
        dataStore.getTiaData(true);

        TiaData tiaData = dataStore.getTiaCore();
        tiaData.setCommitValue("prior-commit");
        tiaData.setBranch(BRANCH);
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);
    }

    /**
     * Close the data store so its embedded H2 database releases its file lock, then remove the
     * temp directory.
     */
    @AfterEach
    void tearDown() {
        if (dataStore != null) {
            dataStore.close();
        }
        if (tempDir != null && tempDir.exists()) {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            tempDir.delete();
        }
    }

    /**
     * A listener constructed with a populated {@link TestRunSelectionDetails} - as {@code
     * TiaSpockGlobalExtension} now builds it from {@code TestSelectorResult.getSelectionDetails()}
     * on the ordinary (non-distributed) selection path - persists that breakdown's five scalar
     * counters onto the history row and its triggers into the trigger table, when the run finishes
     * and is persisted as a single host.
     */
    @Test
    void finishAllTests_withSelectionDetails_persistsCountersAndTriggers() {
        // given - a listener built the way the extension builds it for an ordinary build, with a
        // non-empty selection breakdown carrying two triggers and non-zero scalar counters
        List<TestRunTrigger> triggers = Arrays.asList(
                new TestRunTrigger(TestRunTrigger.Type.SOURCE_METHOD, "com.example.Foo.save", 2),
                new TestRunTrigger(TestRunTrigger.Type.STATIC_RULE, "ForceOnDbChange", 1));
        TestRunSelectionDetails details = new TestRunSelectionDetails(triggers, 3, 1, 2, 1, 4);
        TiaSpockRunListener listener = new TiaSpockRunListener(BRANCH, COMMIT, dataStore,
                Collections.singleton("com.example.ATest"), 0, false, true, null,
                details, null);

        // when - the one spec this JVM ran finishes, then the run is persisted
        listener.finishAllTests(Collections.singleton("com.example.ATest"), System.currentTimeMillis());

        // then - the history row carries the five scalar counters, and the trigger table carries
        // the two triggers keyed on that row's id
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
        assertEquals(1, history.size(), "exactly one history row expected: " + history);
        TestRunHistoryEntry row = history.get(0);
        assertNotNull(row);
        assertEquals(Integer.valueOf(3), row.getNumModifiedTestFiles());
        assertEquals(Integer.valueOf(1), row.getNumNewTestFiles());
        assertEquals(Integer.valueOf(2), row.getNumPreviouslyFailed());
        assertEquals(Integer.valueOf(1), row.getNumUnsealedMapping());
        assertEquals(Integer.valueOf(4), row.getNumPendingLibrary());

        List<TestRunTrigger> persistedTriggers = dataStore.readTestRunTriggers(row.getId());
        assertEquals(2, persistedTriggers.size());
        assertEquals(triggers.get(0), persistedTriggers.get(0));
        assertEquals(triggers.get(1), persistedTriggers.get(1));
    }

    /**
     * A listener constructed with {@link TestRunSelectionDetails#empty()} - as the extension builds
     * it for a distributed runner, whose build-level breakdown is written by the sealer rather than
     * by any one runner - persists the history row with every scalar counter recorded as an
     * explicit zero and writes no trigger rows, rather than leaving the counters null.
     */
    @Test
    void finishAllTests_withEmptySelectionDetails_persistsZeroCountersAndNoTriggers() {
        // given - a listener built with no selection breakdown to attribute, the shape the
        // extension produces on the distributed path
        TiaSpockRunListener listener = new TiaSpockRunListener(BRANCH, COMMIT, dataStore,
                Collections.singleton("com.example.ATest"), 0, false, true, null,
                TestRunSelectionDetails.empty(), null);

        // when
        listener.finishAllTests(Collections.singleton("com.example.ATest"), System.currentTimeMillis());

        // then - counters default to zero, and no trigger rows exist for this entry
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
        assertEquals(1, history.size());
        TestRunHistoryEntry row = history.get(0);
        assertEquals(Integer.valueOf(0), row.getNumModifiedTestFiles());
        assertEquals(0, dataStore.readTestRunTriggers(row.getId()).size());
    }
}
