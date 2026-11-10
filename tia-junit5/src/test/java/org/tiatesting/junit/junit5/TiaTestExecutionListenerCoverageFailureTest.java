package org.tiatesting.junit.junit5;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.tiatesting.core.coverage.client.JacocoClient;
import org.tiatesting.core.coverage.result.CoverageResult;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.DataStoreFactory;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.testrunner.TestRunIdentity;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies a mapping run whose coverage collection from the JaCoCo agent fails persists none of
 * its results - no suite mapping, no commit advance and no history row - and records the failure
 * on the run's row, while the test plan itself carries on.
 * Coverage comes from a scripted client that can be told to fail, so no JaCoCo agent is needed,
 * and the test plan is built from hand-made descriptors.
 */
class TiaTestExecutionListenerCoverageFailureTest {

    private static final String FIRST = "com.example.CalculatorTest";
    private static final String SECOND = "com.example.ShapeTest";

    private static final String[] MANAGED_PROPERTIES = {
            "tiaEnabled", "tiaUpdateDBMapping", "tiaUpdateDBTestRunHistory", "tiaBranch",
            "tiaCommitValue", "tiaSelectedTests", H2ConnectionSettings.PROP_DB_FILE_PATH,
            TestRunIdentity.PROP_TEST_RUN_ID, TestRunIdentity.PROP_TEST_RUN_START_MS
    };

    private Map<String, String> savedProperties;
    private File tempDir;
    private EngineDescriptor engine;
    private SimpleDescriptor first;
    private SimpleDescriptor second;

    /**
     * Save and clear the system properties these tests set, then enable the listener with mapping
     * and history on over a fresh temp directory, and build a plan of two top-level classes.
     *
     * @throws Exception if the temp directory cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        savedProperties = new LinkedHashMap<>();
        for (String key : MANAGED_PROPERTIES) {
            savedProperties.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
        tempDir = File.createTempFile("tia-junit5-coverage-failure-", "");
        tempDir.delete();
        tempDir.mkdirs();

        System.setProperty("tiaEnabled", "true");
        System.setProperty("tiaUpdateDBMapping", "true");
        System.setProperty("tiaUpdateDBTestRunHistory", "true");
        System.setProperty("tiaBranch", "main");
        System.setProperty("tiaCommitValue", "commit-1");
        System.setProperty(TestRunIdentity.PROP_TEST_RUN_ID, "run-1");
        System.setProperty(TestRunIdentity.PROP_TEST_RUN_START_MS, "1000");
        System.setProperty(H2ConnectionSettings.PROP_DB_FILE_PATH, tempDir.getAbsolutePath());
        System.setProperty("tiaSelectedTests", FIRST + "," + SECOND);

        engine = new EngineDescriptor(UniqueId.forEngine("test-engine"), "test-engine");
        first = container(engine, "first", ClassSource.from(FIRST));
        second = container(engine, "second", ClassSource.from(SECOND));
    }

    /**
     * Remove the temp directory and restore the saved system properties.
     */
    @AfterEach
    void tearDown() {
        File[] files = tempDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        tempDir.delete();
        for (Map.Entry<String, String> entry : savedProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    @Test
    void aRunWhoseCoverageIsCollectedPersists() {
        // given - a coverage client that never fails
        FailingCoverageClient coverage = new FailingCoverageClient(false, Integer.MAX_VALUE);
        TiaTestExecutionListener listener = startedListener(new SharedTestRunData(), coverage);

        // when
        runBothSuites(listener);
        listener.testPlanExecutionFinished(testPlan());

        // then - the control: the same run persists when nothing fails
        DataStore dataStore = DataStoreFactory.fromSystemProperties("main", true);
        assertEquals("commit-1", dataStore.getTiaData().getCommitValue());
        assertEquals(2, dataStore.getTestSuitesTracked().size());
        assertEquals(1, dataStore.readTestRunHistory().size());
    }

    @Test
    void aFailedSuiteDumpPersistsNothing() {
        // given - the dump for the first suite fails
        FailingCoverageClient coverage = new FailingCoverageClient(false, 0);
        SharedTestRunData shared = new SharedTestRunData();
        TiaTestExecutionListener listener = startedListener(shared, coverage);

        // when - the plan runs to its end without the listener throwing
        runBothSuites(listener);
        listener.testPlanExecutionFinished(testPlan());

        // then - nothing of the run's results, only the failure on the run's row
        assertTrue(shared.getCoverageCollectionStatus().hasFailed());
        assertNothingPersisted();
        assertEquals("for the test suite " + FIRST + ": java.io.IOException: Socket closed unexpectedly.",
                DataStoreFactory.fromSystemProperties("main", true).readTestRunCoverageFailure("run-1"));
    }

    @Test
    void aFailedSuiteDumpStopsFurtherCollectionButNotTheSuiteBookkeeping() {
        // given - the dump for the first suite fails
        FailingCoverageClient coverage = new FailingCoverageClient(false, 0);
        SharedTestRunData shared = new SharedTestRunData();
        TiaTestExecutionListener listener = startedListener(shared, coverage);

        // when
        runBothSuites(listener);

        // then - one attempted dump, and both suites still recorded as observed
        assertEquals(1, coverage.collectCalls());
        assertTrue(shared.getSuitesObserved().contains(FIRST));
        assertTrue(shared.getSuitesObserved().contains(SECOND));
    }

    @Test
    void aFailureAtStartupPersistsNothingAndCollectsNoCoverage() {
        // given - the agent cannot be reached when the listener starts
        FailingCoverageClient coverage = new FailingCoverageClient(true, Integer.MAX_VALUE);
        TiaTestExecutionListener listener = startedListener(new SharedTestRunData(), coverage);

        // when
        runBothSuites(listener);
        listener.testPlanExecutionFinished(testPlan());

        // then
        assertEquals(0, coverage.collectCalls());
        assertNothingPersisted();
    }

    @Test
    void aLaterTestPlanOfTheSameJvmPersistsNothingEither() {
        // given - a first plan whose dump failed, then a re-run with a new listener and a client
        // that would now succeed
        SharedTestRunData shared = new SharedTestRunData();
        TiaTestExecutionListener firstPlan = startedListener(shared, new FailingCoverageClient(false, 0));
        runBothSuites(firstPlan);
        firstPlan.testPlanExecutionFinished(testPlan());
        FailingCoverageClient rerunCoverage = new FailingCoverageClient(false, Integer.MAX_VALUE);
        TiaTestExecutionListener rerun = startedListener(shared, rerunCoverage);

        // when
        runBothSuites(rerun);
        rerun.testPlanExecutionFinished(testPlan());

        // then
        assertEquals(0, rerunCoverage.collectCalls());
        assertNothingPersisted();
    }

    /**
     * Assert the datastore holds no commit, no tracked suite and no history row.
     */
    private void assertNothingPersisted() {
        DataStore dataStore = DataStoreFactory.fromSystemProperties("main", true);
        assertNull(dataStore.getTiaData().getCommitValue());
        assertTrue(dataStore.getTestSuitesTracked().isEmpty());
        assertTrue(dataStore.readTestRunHistory().isEmpty());
    }

    /**
     * Start and finish both suites of the plan, one after the other.
     *
     * @param listener the listener to drive
     */
    private void runBothSuites(final TiaTestExecutionListener listener) {
        listener.executionStarted(id(first));
        listener.executionFinished(id(first), TestExecutionResult.successful());
        listener.executionStarted(id(second));
        listener.executionFinished(id(second), TestExecutionResult.successful());
    }

    /**
     * Build a listener over shared state and a coverage client, and start its test plan.
     *
     * @param shared the JVM's shared state
     * @param coverage the coverage client
     * @return the started listener
     */
    private TiaTestExecutionListener startedListener(final SharedTestRunData shared, final JacocoClient coverage) {
        TiaTestExecutionListener listener = new TiaTestExecutionListener(shared, coverage, System::currentTimeMillis);
        listener.testPlanExecutionStarted(testPlan());
        return listener;
    }

    /**
     * @return the test plan over the two suites
     */
    private TestPlan testPlan() {
        return TestPlan.from(Collections.singletonList(engine), new EmptyConfigurationParameters());
    }

    /**
     * @param descriptor the descriptor
     * @return its test identifier
     */
    private static TestIdentifier id(final TestDescriptor descriptor) {
        return TestIdentifier.from(descriptor);
    }

    /**
     * @param parent the parent descriptor
     * @param segment the unique id segment value
     * @param source the container's source
     * @return a new container under the parent
     */
    private static SimpleDescriptor container(final TestDescriptor parent, final String segment, final TestSource source) {
        SimpleDescriptor descriptor = new SimpleDescriptor(parent.getUniqueId().append("container", segment),
                segment, source, TestDescriptor.Type.CONTAINER);
        parent.addChild(descriptor);
        return descriptor;
    }

    /**
     * A coverage client with no agent behind it, which can fail at startup or from a given dump on.
     */
    private static final class FailingCoverageClient extends JacocoClient {

        private final boolean failAtStartup;
        private final int failFromCall;
        private int collectCalls;

        /**
         * @param failAtStartup whether {@link #initialize()} fails
         * @param failFromCall the zero-based dump from which every dump fails
         */
        FailingCoverageClient(final boolean failAtStartup, final int failFromCall) {
            this.failAtStartup = failAtStartup;
            this.failFromCall = failFromCall;
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
         * @throws IOException from the scripted dump on
         */
        @Override
        public CoverageResult collectCoverage() throws IOException {
            if (collectCalls++ >= failFromCall) {
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

    /**
     * A descriptor of a fixed type, enough to shape a test plan without running an engine.
     */
    private static final class SimpleDescriptor extends AbstractTestDescriptor {

        private final Type type;

        /**
         * @param uniqueId the descriptor's unique id
         * @param displayName the display name
         * @param source the source
         * @param type whether it is a test or a container
         */
        SimpleDescriptor(final UniqueId uniqueId, final String displayName, final TestSource source, final Type type) {
            super(uniqueId, displayName, source);
            this.type = type;
        }

        /**
         * @return the fixed type
         */
        @Override
        public Type getType() {
            return type;
        }
    }

    /**
     * Configuration parameters with no values, all the test plan needs.
     */
    private static final class EmptyConfigurationParameters implements ConfigurationParameters {

        /**
         * @param key the parameter key
         * @return always empty
         */
        @Override
        public Optional<String> get(final String key) {
            return Optional.empty();
        }

        /**
         * @param key the parameter key
         * @return always empty
         */
        @Override
        public Optional<Boolean> getBoolean(final String key) {
            return Optional.empty();
        }

        /**
         * Deprecated in the interface but still abstract, so it must be implemented.
         *
         * @return always zero
         */
        @Override
        @SuppressWarnings("deprecation")
        public int size() {
            return 0;
        }

        /**
         * @return always empty
         */
        @Override
        public Set<String> keySet() {
            return Collections.emptySet();
        }
    }
}
