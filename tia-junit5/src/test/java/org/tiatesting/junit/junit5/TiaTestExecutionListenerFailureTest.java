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
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.engine.support.descriptor.UriSource;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers which JUnit Platform results the listener counts as a suite failure: a test or container
 * finishing {@code FAILED} anywhere within a suite fails it, and an assumption abort does not.
 *
 * <p>The listener runs with mapping off and the history log on - enabled, but with no JaCoCo client
 * to reach, since a suite's completion would otherwise open a socket to the coverage agent. The
 * failed set is read straight off the listener rather than from a persisted row, because with
 * mapping off the failed set is never written; what is under test is the classification, not the
 * persist.
 *
 * <p>The test plan is built from minimal hand-made descriptors rather than by running a real engine,
 * so each case can produce exactly the shape it names - a class container that fails with no child
 * events, a method container with none, a dynamic test with no class-naming source.
 */
class TiaTestExecutionListenerFailureTest {

    private static final String SUITE = "com.example.ATest";
    private static final String NESTED_SUITE = "com.example.ATest$Inner";

    private static final String[] MANAGED_PROPERTIES = {
            "tiaEnabled", "tiaUpdateDBMapping", "tiaUpdateDBTestRunHistory", "tiaBranch",
            "tiaCommitValue", "tiaSelectedTests", "test", H2ConnectionSettings.PROP_DB_FILE_PATH
    };

    private Map<String, String> savedProperties;
    private File tempDir;

    private EngineDescriptor engine;
    private SimpleDescriptor suite;

    /**
     * Save and clear every system property these tests set, enable the listener with mapping off,
     * point its datastore at a fresh temp directory, and start a test plan holding one suite
     * container under one engine.
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

        tempDir = File.createTempFile("tia-junit5-failure-", "");
        tempDir.delete();
        tempDir.mkdirs();

        System.setProperty("tiaEnabled", "true");
        System.setProperty("tiaUpdateDBMapping", "false");
        System.setProperty("tiaBranch", "main");
        System.setProperty("tiaCommitValue", "commit-1");
        System.setProperty(H2ConnectionSettings.PROP_DB_FILE_PATH, tempDir.getAbsolutePath());
        System.setProperty("tiaSelectedTests", SUITE);

        engine = new EngineDescriptor(UniqueId.forEngine("test-engine"), "test-engine");
        suite = container(engine, "class", ClassSource.from(SUITE));
    }

    /**
     * Remove the temp directory and restore the system properties saved in {@link #setUp()}.
     */
    @AfterEach
    void tearDown() {
        if (tempDir != null && tempDir.exists()) {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            tempDir.delete();
        }
        for (Map.Entry<String, String> entry : savedProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Verifies a test aborted by an unmet assumption leaves its suite out of the failed set - the
     * build tool reports it as skipped, not failed.
     */
    @Test
    void executionFinished_testAborted_doesNotFailTheSuite() {
        // given
        SimpleDescriptor test = test(suite, "m1", MethodSource.from(SUITE, "m1"));
        TiaTestExecutionListener listener = startedListener();
        listener.executionStarted(id(suite));

        // when
        listener.executionFinished(id(test), TestExecutionResult.aborted(new RuntimeException("assumption")));
        listener.executionFinished(id(suite), TestExecutionResult.successful());

        // then
        assertTrue(listener.getTestSuitesFailed().isEmpty());
    }

    /**
     * Verifies a test that fails still records its suite as failed.
     */
    @Test
    void executionFinished_testFailed_failsTheSuite() {
        // given
        SimpleDescriptor test = test(suite, "m1", MethodSource.from(SUITE, "m1"));
        TiaTestExecutionListener listener = startedListener();
        listener.executionStarted(id(suite));

        // when
        listener.executionFinished(id(test), TestExecutionResult.failed(new AssertionError("boom")));
        listener.executionFinished(id(suite), TestExecutionResult.successful());

        // then
        assertEquals(Collections.singleton(SUITE), listener.getTestSuitesFailed());
    }

    /**
     * Verifies a class container that fails with no child events - a failing {@code @BeforeAll} -
     * records the suite as failed rather than as a success.
     */
    @Test
    void executionFinished_classContainerFailed_failsTheSuite() {
        // given
        TiaTestExecutionListener listener = startedListener();
        listener.executionStarted(id(suite));

        // when
        listener.executionFinished(id(suite), TestExecutionResult.failed(new IllegalStateException("beforeAll")));

        // then
        assertEquals(Collections.singleton(SUITE), listener.getTestSuitesFailed());
    }

    /**
     * Verifies a class container aborted by an unmet assumption in {@code @BeforeAll} is not a
     * failure.
     */
    @Test
    void executionFinished_classContainerAborted_doesNotFailTheSuite() {
        // given
        TiaTestExecutionListener listener = startedListener();
        listener.executionStarted(id(suite));

        // when
        listener.executionFinished(id(suite), TestExecutionResult.aborted(new RuntimeException("assumption")));

        // then
        assertTrue(listener.getTestSuitesFailed().isEmpty());
    }

    /**
     * Verifies a method-level container - a parameterized method whose argument source throws -
     * that fails with no child tests records its enclosing suite as failed.
     */
    @Test
    void executionFinished_methodContainerFailed_failsTheEnclosingSuite() {
        // given
        SimpleDescriptor parameterized = container(suite, "param", MethodSource.from(SUITE, "param"));
        TiaTestExecutionListener listener = startedListener();
        listener.executionStarted(id(suite));

        // when
        listener.executionFinished(id(parameterized), TestExecutionResult.failed(new IllegalStateException("source")));
        listener.executionFinished(id(suite), TestExecutionResult.successful());

        // then
        assertEquals(Collections.singleton(SUITE), listener.getTestSuitesFailed());
    }

    /**
     * Verifies a failed dynamic test whose source names no class is attributed to the suite found
     * by walking up the test plan, rather than being dropped or throwing.
     */
    @Test
    void executionFinished_dynamicTestWithUriSourceFailed_failsTheSuiteFoundByWalkingUp() {
        // given
        SimpleDescriptor factory = container(suite, "factory", MethodSource.from(SUITE, "factory"));
        SimpleDescriptor dynamic = test(factory, "dynamic", UriSource.from(URI.create("file:///tmp/data.csv")));
        TiaTestExecutionListener listener = startedListener();
        listener.executionStarted(id(suite));

        // when
        listener.executionFinished(id(dynamic), TestExecutionResult.failed(new AssertionError("boom")));

        // then
        assertEquals(Collections.singleton(SUITE), listener.getTestSuitesFailed());
    }

    /**
     * Verifies a failing test in a {@code @Nested} class is recorded against the nested class, the
     * same suite name its own container is tracked under.
     */
    @Test
    void executionFinished_nestedTestFailed_failsTheNestedClass() {
        // given
        SimpleDescriptor nested = container(suite, "nested", ClassSource.from(NESTED_SUITE));
        SimpleDescriptor test = test(nested, "m1", MethodSource.from(NESTED_SUITE, "m1"));
        TiaTestExecutionListener listener = startedListener();
        listener.executionStarted(id(suite));
        listener.executionStarted(id(nested));

        // when
        listener.executionFinished(id(test), TestExecutionResult.failed(new AssertionError("boom")));

        // then
        assertEquals(Collections.singleton(NESTED_SUITE), listener.getTestSuitesFailed());
    }

    /**
     * Verifies a failure on the engine's own root container, which belongs to no suite, is ignored
     * rather than recorded under a null name.
     */
    @Test
    void executionFinished_engineContainerFailed_recordsNoSuite() {
        // given
        TiaTestExecutionListener listener = startedListener();

        // when
        listener.executionFinished(TestIdentifier.from(engine), TestExecutionResult.failed(new IllegalStateException("engine")));

        // then
        Set<String> failed = listener.getTestSuitesFailed();
        assertTrue(failed.isEmpty());
    }

    /**
     * Build a listener over the descriptors added so far and start its test plan, as the launcher
     * does before any execution event.
     *
     * @return the started listener
     */
    private TiaTestExecutionListener startedListener() {
        TiaTestExecutionListener listener = new TiaTestExecutionListener(new SharedTestRunData());
        listener.testPlanExecutionStarted(TestPlan.from(Collections.singletonList(engine), new EmptyConfigurationParameters()));
        return listener;
    }

    /**
     * Wrap a descriptor as the identifier the launcher passes to listeners.
     *
     * @param descriptor the descriptor
     * @return its test identifier
     */
    private static TestIdentifier id(final TestDescriptor descriptor) {
        return TestIdentifier.from(descriptor);
    }

    /**
     * Add a container descriptor under a parent.
     *
     * @param parent the parent descriptor
     * @param segment the unique id segment value for the new node
     * @param source the container's source
     * @return the new container
     */
    private static SimpleDescriptor container(final TestDescriptor parent, final String segment, final TestSource source) {
        SimpleDescriptor descriptor = new SimpleDescriptor(parent.getUniqueId().append("container", segment),
                segment, source, TestDescriptor.Type.CONTAINER);
        parent.addChild(descriptor);
        return descriptor;
    }

    /**
     * Add a test descriptor under a parent.
     *
     * @param parent the parent descriptor
     * @param segment the unique id segment value for the new node
     * @param source the test's source
     * @return the new test
     */
    private static SimpleDescriptor test(final TestDescriptor parent, final String segment, final TestSource source) {
        SimpleDescriptor descriptor = new SimpleDescriptor(parent.getUniqueId().append("test", segment),
                segment, source, TestDescriptor.Type.TEST);
        parent.addChild(descriptor);
        return descriptor;
    }

    /**
     * A descriptor of a fixed type, enough to shape a test plan without running an engine.
     */
    private static final class SimpleDescriptor extends AbstractTestDescriptor {

        private final Type type;

        /**
         * Create a descriptor.
         *
         * @param uniqueId the descriptor's unique id
         * @param displayName the display name
         * @param source the source, or {@code null} for none
         * @param type whether it is a test or a container
         */
        SimpleDescriptor(final UniqueId uniqueId, final String displayName, final TestSource source, final Type type) {
            super(uniqueId, displayName, source);
            this.type = type;
        }

        /**
         * @return the fixed type this descriptor was created with
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
