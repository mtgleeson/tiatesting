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
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.tiatesting.core.coverage.client.JacocoClient;
import org.tiatesting.core.coverage.result.CoverageResult;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies a JUnit 5 {@code @Nested} class does not take its enclosing class's coverage. Jupiter
 * runs the enclosing class's own tests and then the nested class inside it, so the listener credits
 * the coverage collected before the nested class starts to the enclosing class. Coverage dumps come
 * from a scripted client, so no JaCoCo agent is needed, and the test plan is built from hand-made
 * descriptors.
 */
class TiaTestExecutionListenerNestedCoverageTest {

    private static final String OUTER = "com.example.CalculatorTest";
    private static final String NESTED = "com.example.CalculatorTest$Multiplication";

    private static final String[] MANAGED_PROPERTIES = {
            "tiaEnabled", "tiaUpdateDBMapping", "tiaUpdateDBTestRunHistory", "tiaBranch",
            "tiaCommitValue", "tiaSelectedTests", H2ConnectionSettings.PROP_DB_FILE_PATH
    };

    private Map<String, String> savedProperties;
    private File tempDir;

    /**
     * Save and clear the system properties these tests set, then enable the listener with mapping
     * on over a fresh temp directory.
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
        tempDir = File.createTempFile("tia-junit5-nested-", "");
        tempDir.delete();
        tempDir.mkdirs();

        System.setProperty("tiaEnabled", "true");
        System.setProperty("tiaUpdateDBMapping", "true");
        System.setProperty("tiaUpdateDBTestRunHistory", "false");
        System.setProperty("tiaBranch", "main");
        System.setProperty("tiaCommitValue", "commit-1");
        System.setProperty(H2ConnectionSettings.PROP_DB_FILE_PATH, tempDir.getAbsolutePath());
        System.setProperty("tiaSelectedTests", OUTER + "," + NESTED);
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
    void enclosingClassKeepsTheCoverageItsOwnTestsCollected() {
        // given - the outer class's test covers Calculator.add, the nested class's test covers
        // Calculator.multiply, and nothing runs after the nested class
        EngineDescriptor engine = new EngineDescriptor(UniqueId.forEngine("test-engine"), "test-engine");
        SimpleDescriptor outer = container(engine, "outer", ClassSource.from(OUTER));
        SimpleDescriptor outerTest = test(outer, "adds", MethodSource.from(OUTER, "adds"));
        SimpleDescriptor nested = container(outer, "nested", ClassSource.from(NESTED));
        SimpleDescriptor nestedTest = test(nested, "multiplies", MethodSource.from(NESTED, "multiplies"));
        ScriptedCoverageClient coverage = new ScriptedCoverageClient(
                dump("com/example/Calculator.add"), dump("com/example/Calculator.multiply"), dump());
        SharedTestRunData shared = new SharedTestRunData();
        TiaTestExecutionListener listener = new TiaTestExecutionListener(shared, coverage, System::currentTimeMillis);
        listener.testPlanExecutionStarted(TestPlan.from(Collections.singletonList(engine),
                new EmptyConfigurationParameters()));

        // when - the order Jupiter runs them in
        listener.executionStarted(id(outer));
        listener.executionStarted(id(outerTest));
        listener.executionFinished(id(outerTest), TestExecutionResult.successful());
        listener.executionStarted(id(nested));
        listener.executionStarted(id(nestedTest));
        listener.executionFinished(id(nestedTest), TestExecutionResult.successful());
        listener.executionFinished(id(nested), TestExecutionResult.successful());
        listener.executionFinished(id(outer), TestExecutionResult.successful());

        // then
        assertEquals(Collections.singleton("com/example/Calculator.add"),
                sourceFiles(shared.getTestSuiteTrackers().get(OUTER)));
        assertEquals(Collections.singleton("com/example/Calculator.multiply"),
                sourceFiles(shared.getTestSuiteTrackers().get(NESTED)));
    }

    @Test
    void aNestedClassStartingWithNoTestSinceTheLastDumpTakesNoExtraDump() {
        // given - Outer has no tests of its own and two nested siblings
        EngineDescriptor engine = new EngineDescriptor(UniqueId.forEngine("test-engine"), "test-engine");
        SimpleDescriptor outer = container(engine, "outer", ClassSource.from(OUTER));
        SimpleDescriptor first = container(outer, "first", ClassSource.from(NESTED));
        SimpleDescriptor firstTest = test(first, "one", MethodSource.from(NESTED, "one"));
        SimpleDescriptor second = container(outer, "second", ClassSource.from(OUTER + "$Division"));
        SimpleDescriptor secondTest = test(second, "two", MethodSource.from(OUTER + "$Division", "two"));
        ScriptedCoverageClient coverage = new ScriptedCoverageClient();
        TiaTestExecutionListener listener = new TiaTestExecutionListener(new SharedTestRunData(), coverage, System::currentTimeMillis);
        listener.testPlanExecutionStarted(TestPlan.from(Collections.singletonList(engine),
                new EmptyConfigurationParameters()));

        // when
        listener.executionStarted(id(outer));
        listener.executionStarted(id(first));
        listener.executionStarted(id(firstTest));
        listener.executionFinished(id(firstTest), TestExecutionResult.successful());
        listener.executionFinished(id(first), TestExecutionResult.successful());
        listener.executionStarted(id(second));
        listener.executionStarted(id(secondTest));
        listener.executionFinished(id(secondTest), TestExecutionResult.successful());
        listener.executionFinished(id(second), TestExecutionResult.successful());
        listener.executionFinished(id(outer), TestExecutionResult.successful());

        // then - one dump per class finish, none at either nested start
        assertEquals(3, coverage.collected());
    }

    @Test
    void eachClassRecordsItsOwnTimeWithoutItsNestedClasses() {
        // given - A contains B, which contains C; a manual clock
        String a = "com.example.A";
        String b = "com.example.A$B";
        String c = "com.example.A$B$C";
        EngineDescriptor engine = new EngineDescriptor(UniqueId.forEngine("test-engine"), "test-engine");
        SimpleDescriptor outer = container(engine, "a", ClassSource.from(a));
        SimpleDescriptor middle = container(outer, "b", ClassSource.from(b));
        SimpleDescriptor inner = container(middle, "c", ClassSource.from(c));
        AtomicLong now = new AtomicLong(0L);
        SharedTestRunData shared = new SharedTestRunData();
        TiaTestExecutionListener listener = new TiaTestExecutionListener(shared, new ScriptedCoverageClient(),
                now::get);
        listener.testPlanExecutionStarted(TestPlan.from(Collections.singletonList(engine),
                new EmptyConfigurationParameters()));

        // when - A runs 0-100, B 10-70, C 20-50
        listener.executionStarted(id(outer));
        now.set(10L);
        listener.executionStarted(id(middle));
        now.set(20L);
        listener.executionStarted(id(inner));
        now.set(50L);
        listener.executionFinished(id(inner), TestExecutionResult.successful());
        now.set(70L);
        listener.executionFinished(id(middle), TestExecutionResult.successful());
        now.set(100L);
        listener.executionFinished(id(outer), TestExecutionResult.successful());

        // then - own times add up to the 100ms the family really took
        assertEquals(30L, shared.getTestSuiteTrackers().get(c).getTestStats().getAvgRunTime());
        assertEquals(30L, shared.getTestSuiteTrackers().get(b).getTestStats().getAvgRunTime());
        assertEquals(40L, shared.getTestSuiteTrackers().get(a).getTestStats().getAvgRunTime());
    }

    @Test
    void aSkippedClassesNestedClassesAreObservedToo() {
        // given - Outer is skipped (disabled), so JUnit never reports Outer$Multiplication
        EngineDescriptor engine = new EngineDescriptor(UniqueId.forEngine("test-engine"), "test-engine");
        SimpleDescriptor outer = container(engine, "outer", ClassSource.from(OUTER));
        container(outer, "nested", ClassSource.from(NESTED));
        SharedTestRunData shared = new SharedTestRunData();
        TiaTestExecutionListener listener = new TiaTestExecutionListener(shared, new ScriptedCoverageClient(),
                System::currentTimeMillis);
        listener.testPlanExecutionStarted(TestPlan.from(Collections.singletonList(engine),
                new EmptyConfigurationParameters()));

        // when
        listener.executionSkipped(id(outer), "disabled");

        // then - both count as observed, so a distributed group holding both can complete
        assertEquals(new TreeSet<>(java.util.Arrays.asList(OUTER, NESTED)), new TreeSet<>(shared.getSuitesObserved()));
    }

    @Test
    void theDumpAtANestedStartIsNotChargedToTheEnclosingClass() {
        // given - every coverage dump takes 5ms on a manual clock
        EngineDescriptor engine = new EngineDescriptor(UniqueId.forEngine("test-engine"), "test-engine");
        SimpleDescriptor outer = container(engine, "outer", ClassSource.from(OUTER));
        SimpleDescriptor outerTest = test(outer, "adds", MethodSource.from(OUTER, "adds"));
        SimpleDescriptor nested = container(outer, "nested", ClassSource.from(NESTED));
        AtomicLong now = new AtomicLong(0L);
        ScriptedCoverageClient coverage = new ScriptedCoverageClient();
        coverage.onCollect(() -> now.addAndGet(5L));
        SharedTestRunData shared = new SharedTestRunData();
        TiaTestExecutionListener listener = new TiaTestExecutionListener(shared, coverage, now::get);
        listener.testPlanExecutionStarted(TestPlan.from(Collections.singletonList(engine),
                new EmptyConfigurationParameters()));

        // when - Outer's test runs 0-30; the nested class 35-60 (its dump to 65); Outer ends at 100
        listener.executionStarted(id(outer));
        listener.executionStarted(id(outerTest));
        listener.executionFinished(id(outerTest), TestExecutionResult.successful());
        now.set(30L);
        listener.executionStarted(id(nested));
        now.set(60L);
        listener.executionFinished(id(nested), TestExecutionResult.successful());
        now.set(100L);
        listener.executionFinished(id(outer), TestExecutionResult.successful());

        // then - Outer's own time leaves out both the nested class and the dump taken for it
        assertEquals(25L, shared.getTestSuiteTrackers().get(NESTED).getTestStats().getAvgRunTime());
        assertEquals(65L, shared.getTestSuiteTrackers().get(OUTER).getTestStats().getAvgRunTime());
    }

    @Test
    void topLevelClassesCollectOnlyWhenTheyFinish() {
        // given - two top-level classes, one after the other
        EngineDescriptor engine = new EngineDescriptor(UniqueId.forEngine("test-engine"), "test-engine");
        SimpleDescriptor first = container(engine, "first", ClassSource.from(OUTER));
        SimpleDescriptor second = container(engine, "second", ClassSource.from("com.example.ShapeTest"));
        ScriptedCoverageClient coverage = new ScriptedCoverageClient(
                dump("com/example/Calculator.add"), dump("com/example/Shape.area"));
        SharedTestRunData shared = new SharedTestRunData();
        TiaTestExecutionListener listener = new TiaTestExecutionListener(shared, coverage, System::currentTimeMillis);
        listener.testPlanExecutionStarted(TestPlan.from(Collections.singletonList(engine),
                new EmptyConfigurationParameters()));

        // when
        listener.executionStarted(id(first));
        listener.executionFinished(id(first), TestExecutionResult.successful());
        listener.executionStarted(id(second));
        listener.executionFinished(id(second), TestExecutionResult.successful());

        // then - one dump per class, none taken when the second class started
        assertEquals(Collections.singleton("com/example/Calculator.add"),
                sourceFiles(shared.getTestSuiteTrackers().get(OUTER)));
        assertEquals(Collections.singleton("com/example/Shape.area"),
                sourceFiles(shared.getTestSuiteTrackers().get("com.example.ShapeTest")));
        assertEquals(0, coverage.remaining());
    }

    /**
     * @param tracker a suite's tracker
     * @return the source files its mapping holds
     */
    private static Set<String> sourceFiles(final TestSuiteTracker tracker) {
        Set<String> files = new TreeSet<>();
        for (ClassImpactTracker classTracker : tracker.getClassesImpacted()) {
            files.add(classTracker.getSourceFilename());
        }
        return files;
    }

    /**
     * @param sourceFiles the source files the dump covers, one method each
     * @return a coverage dump
     */
    private static CoverageResult dump(final String... sourceFiles) {
        CoverageResult result = new CoverageResult();
        int methodId = 1;
        for (String sourceFile : sourceFiles) {
            result.getClassesInvoked().add(new ClassImpactTracker(sourceFile, Collections.singleton(methodId++)));
        }
        return result;
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
     * @param parent the parent descriptor
     * @param segment the unique id segment value
     * @param source the test's source
     * @return a new test under the parent
     */
    private static SimpleDescriptor test(final TestDescriptor parent, final String segment, final TestSource source) {
        SimpleDescriptor descriptor = new SimpleDescriptor(parent.getUniqueId().append("test", segment),
                segment, source, TestDescriptor.Type.TEST);
        parent.addChild(descriptor);
        return descriptor;
    }

    /**
     * A coverage client that returns scripted dumps in order, and an empty dump once they run out.
     */
    private static final class ScriptedCoverageClient extends JacocoClient {

        private final Deque<CoverageResult> dumps = new ArrayDeque<>();
        private int collected;
        private Runnable onCollect = () -> { };

        /**
         * @param dumps the dumps to return, in order
         */
        ScriptedCoverageClient(final CoverageResult... dumps) {
            Collections.addAll(this.dumps, dumps);
        }

        /**
         * No agent to connect to.
         */
        @Override
        public void initialize() {
        }

        /**
         * @return the next scripted dump, or an empty one
         */
        @Override
        public CoverageResult collectCoverage() {
            collected++;
            onCollect.run();
            CoverageResult next = dumps.pollFirst();
            return next != null ? next : new CoverageResult();
        }

        /**
         * @return how many scripted dumps were not collected
         */
        int remaining() {
            return dumps.size();
        }

        /**
         * Run something on every dump, such as advancing a test clock to give the dump a cost.
         *
         * @param onCollect what to run
         */
        void onCollect(final Runnable onCollect) {
            this.onCollect = onCollect;
        }

        /**
         * Count the coverage dumps the listener asked for, scripted or not.
         *
         * @return how many dumps were collected
         */
        int collected() {
            return collected;
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
