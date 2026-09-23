package org.tiatesting.core.coverage.client;

import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.coverage.result.CoverageResult;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link JacocoClient} scopes coverage analysis to only the classes present in the
 * dumped execution data. Genuine execution data is produced by instrumenting a fixture class with
 * JaCoCo's runtime API and invoking it, so no coverage agent socket is required.
 */
class JacocoClientTest {

    private static final String TARGET_VM_NAME = "org/tiatesting/core/coverage/client/CoverageSampleTarget";
    private static final String OTHER_SOURCE = "org/tiatesting/core/coverage/client/CoverageSampleOther.java";
    private static final String TARGET_SOURCE = "org/tiatesting/core/coverage/client/CoverageSampleTarget.java";

    private String originalProjectDir;
    private String originalClassFilesDirs;

    /**
     * Restores any system properties the test overrode so it does not leak configuration into other
     * tests in the module.
     */
    @AfterEach
    void restoreSystemProperties() {
        restore("tiaProjectDir", originalProjectDir);
        restore("tiaClassFilesDirs", originalClassFilesDirs);
    }

    /**
     * A scoped analyze over execution data naming one executed class records that class and its
     * covered method, and does not leak any other class present in the same compiled-classes
     * directory into the result.
     *
     * @throws Exception if instrumentation, class loading, or analysis fails
     */
    @Test
    void analyzeRecordsOnlyTheExecutedClass() throws Exception {
        // given
        JacocoClient client = clientIndexedOnTestClasses();
        ExecutionDataStore executionData = executeAndCollect();

        // when
        IBundleCoverage bundle = client.analyze(executionData);
        CoverageResult result = client.collectMethodsCalled(bundle);

        // then
        List<ClassImpactTracker> classesInvoked = result.getClassesInvoked();
        assertEquals(1, classesInvoked.size(), "only the executed class should be recorded");
        assertEquals(TARGET_SOURCE, classesInvoked.get(0).getSourceFilename());
        assertFalse(classesInvoked.stream().anyMatch(c -> OTHER_SOURCE.equals(c.getSourceFilename())),
                "a class that was not executed must not appear in the result");
    }

    /**
     * The covered method is flagged as impacted for its class, while a method in the same class that
     * was never executed stays out of the impacted set (though it remains in the method catalogue).
     *
     * @throws Exception if instrumentation, class loading, or analysis fails
     */
    @Test
    void analyzeFlagsCoveredMethodButNotUncoveredOne() throws Exception {
        // given
        JacocoClient client = clientIndexedOnTestClasses();
        ExecutionDataStore executionData = executeAndCollect();

        // when
        CoverageResult result = client.collectMethodsCalled(client.analyze(executionData));

        // then
        ClassImpactTracker target = result.getClassesInvoked().get(0);
        Optional<MethodImpactTracker> covered = findMethod(result, "covered");
        Optional<MethodImpactTracker> notCovered = findMethod(result, "notCovered");
        assertTrue(covered.isPresent(), "the covered method should be in the method catalogue");
        assertTrue(notCovered.isPresent(), "all methods of a covered class stay in the catalogue");
        assertTrue(target.getMethodsImpacted().contains(covered.get().hashCode()),
                "the executed method should be flagged as impacted");
        assertFalse(target.getMethodsImpacted().contains(notCovered.get().hashCode()),
                "a method with no line coverage should not be flagged as impacted");
    }

    /**
     * Execution data naming a class that is not in the configured class dirs or jars is skipped
     * without error and contributes nothing to the result.
     *
     * @throws Exception if analysis fails
     */
    @Test
    void analyzeSkipsExecutedClassMissingFromTheIndex() throws Exception {
        // given
        JacocoClient client = clientIndexedOnTestClasses();
        ExecutionDataStore executionData = new ExecutionDataStore();
        executionData.get(0x1234L, "org/example/NotOnClasspath", 5);

        // when
        CoverageResult result = client.collectMethodsCalled(client.analyze(executionData));

        // then
        assertTrue(result.getClassesInvoked().isEmpty(), "an unknown executed class should be skipped");
    }

    /**
     * Builds a {@link JacocoClient} whose class-bytes index is populated from this module's compiled
     * test-classes directory, by pointing {@code tiaClassFilesDirs} at it and running the client's
     * own class-loading step.
     *
     * @return an initialised client ready for {@link JacocoClient#analyze(ExecutionDataStore)}
     * @throws Exception if the test-classes directory cannot be resolved
     */
    private JacocoClient clientIndexedOnTestClasses() throws Exception {
        File testClassesDir = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        originalProjectDir = System.getProperty("tiaProjectDir");
        originalClassFilesDirs = System.getProperty("tiaClassFilesDirs");
        System.setProperty("tiaProjectDir", "");
        System.setProperty("tiaClassFilesDirs", testClassesDir.getAbsolutePath());

        JacocoClient client = new JacocoClient();
        client.loadClasses();
        return client;
    }

    /**
     * Instruments {@link CoverageSampleTarget}, loads the instrumented bytes in an isolated class
     * loader, invokes {@code covered()} to generate real line coverage, and collects the resulting
     * execution data.
     *
     * @return an execution data store containing one entry for the executed target class
     * @throws Exception if instrumentation, loading, or invocation fails
     */
    private ExecutionDataStore executeAndCollect() throws Exception {
        IRuntime runtime = new LoggerRuntime();
        RuntimeData data = new RuntimeData();
        runtime.startup(data);
        try {
            Instrumenter instrumenter = new Instrumenter(runtime);
            byte[] original = readClassBytes(TARGET_VM_NAME + ".class");
            byte[] instrumented = instrumenter.instrument(original, TARGET_VM_NAME);

            MemoryClassLoader classLoader = new MemoryClassLoader();
            String binaryName = TARGET_VM_NAME.replace('/', '.');
            classLoader.addDefinition(binaryName, instrumented);
            Class<?> targetClass = classLoader.loadClass(binaryName);
            Object instance = targetClass.getDeclaredConstructor().newInstance();
            targetClass.getMethod("covered").invoke(instance);

            ExecutionDataStore executionData = new ExecutionDataStore();
            data.collect(executionData, new SessionInfoStore(), false);
            return executionData;
        } finally {
            runtime.shutdown();
        }
    }

    /**
     * Finds the first method in the coverage result whose name contains the given simple method name.
     *
     * @param result the coverage result to search
     * @param simpleMethodName the method name to look for (e.g. {@code covered})
     * @return the matching method tracker, if any
     */
    private Optional<MethodImpactTracker> findMethod(CoverageResult result, String simpleMethodName) {
        return result.getAllMethodsClassesInvoked().values().stream()
                .filter(m -> m.getMethodName().contains("." + simpleMethodName + "."))
                .findFirst();
    }

    /**
     * Reads the raw bytecode for a class from the test classpath.
     *
     * @param resourceName the class resource name (e.g. {@code org/foo/Bar.class})
     * @return the class file bytes
     * @throws Exception if the resource cannot be read
     */
    private byte[] readClassBytes(String resourceName) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found: " + resourceName);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /**
     * Restores a system property to a prior value, clearing it when the prior value was absent.
     *
     * @param key the system property key
     * @param value the value to restore, or {@code null} to clear it
     */
    private void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    /**
     * A minimal class loader that defines classes from in-memory bytecode, used to load the
     * instrumented fixture in isolation from the class already loaded on the test classpath.
     */
    private static final class MemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> definitions = new HashMap<>();

        /**
         * Registers bytecode for a binary class name to be defined on first load.
         *
         * @param name the binary class name (dotted)
         * @param bytes the class bytecode
         */
        void addDefinition(String name, byte[] bytes) {
            definitions.put(name, bytes);
        }

        /**
         * Defines a registered class from its in-memory bytes, delegating to the parent otherwise.
         *
         * @param name the binary class name being loaded
         * @param resolve whether to resolve the class
         * @return the loaded class
         * @throws ClassNotFoundException if the class is neither registered nor found by the parent
         */
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name);
            if (bytes != null) {
                Class<?> defined = defineClass(name, bytes, 0, bytes.length);
                if (resolve) {
                    resolveClass(defined);
                }
                return defined;
            }
            return super.loadClass(name, resolve);
        }
    }
}
