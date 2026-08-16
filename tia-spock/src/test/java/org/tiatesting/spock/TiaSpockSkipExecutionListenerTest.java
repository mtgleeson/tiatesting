package org.tiatesting.spock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover {@link TiaSpockSkipExecutionListener} directly, against real {@link TestIdentifier}
 * instances rather than the Spock listener it feeds - this is the class that actually receives the
 * JUnit Platform's {@code executionSkipped} callback in production, so its class-vs-method
 * classification is what determines which suites the distributed completeness guard ends up seeing
 * as observed.
 */
class TiaSpockSkipExecutionListenerTest {

    private final TiaSpockSkipExecutionListener listener = new TiaSpockSkipExecutionListener();

    /**
     * Clear the JVM-static observation set after each test, so one test's recorded spec name cannot
     * leak into the next.
     */
    @AfterEach
    void tearDown() {
        SharedSpockSkipObservation.suitesObservedViaSkip().clear();
    }

    /**
     * A whole spec skipped (a class-level container sourced from a {@link ClassSource}) must be
     * recorded as observed, since Spock never calls the {@code specSkipped} hook itself and this
     * listener is what stands in for it.
     */
    @Test
    void executionSkippedRecordsAClassLevelSkipAsObserved() {
        // given
        TestIdentifier identifier = TestIdentifier.from(containerDescriptor("com.example.IgnoredSpec"));

        // when
        listener.executionSkipped(identifier, "Test not selected to run based on the changes analyzed by Tia");

        // then
        assertTrue(SharedSpockSkipObservation.suitesObservedViaSkip().contains("com.example.IgnoredSpec"),
                "a class-level skip must be recorded as observed");
    }

    /**
     * A single feature (method-level) skip within a spec that otherwise ran must not be recorded:
     * the spec itself is already observed via {@code afterSpec} when it finishes, so recording the
     * method-level skip too would add nothing and risks miscounting a method source as a suite name.
     */
    @Test
    void executionSkippedIgnoresAMethodLevelSkip() {
        // given
        TestIdentifier identifier = TestIdentifier.from(
                methodDescriptor("com.example.PartiallySkippedSpec", "aSkippedFeature"));

        // when
        listener.executionSkipped(identifier, "one feature skipped");

        // then
        assertFalse(SharedSpockSkipObservation.suitesObservedViaSkip()
                        .contains("com.example.PartiallySkippedSpec"),
                "only a class-level (container) skip represents a whole suite being observed as "
                        + "skipped");
        assertTrue(SharedSpockSkipObservation.suitesObservedViaSkip().isEmpty(),
                "a method-level skip must not be recorded under any name");
    }

    /**
     * Build a minimal class-level {@link TestDescriptor}, standing in for the one {@code SpecNode}
     * reports to the JUnit Platform for a skipped spec.
     *
     * @param className the fully-qualified spec name to source the descriptor from
     * @return a container-type descriptor sourced from {@code className}
     */
    private TestDescriptor containerDescriptor(final String className) {
        return new AbstractTestDescriptor(UniqueId.forEngine("stub-spock-engine"), className,
                ClassSource.from(className)) {
            @Override
            public Type getType() {
                return Type.CONTAINER;
            }
        };
    }

    /**
     * Build a minimal test-level {@link TestDescriptor} sourced from a method, standing in for the
     * one Spock reports for a single skipped feature within a spec.
     *
     * @param className the spec's fully-qualified class name
     * @param methodName the skipped feature's method name
     * @return a test-type descriptor sourced from {@code className#methodName}
     */
    private TestDescriptor methodDescriptor(final String className, final String methodName) {
        return new AbstractTestDescriptor(UniqueId.forEngine("stub-spock-engine"), methodName,
                MethodSource.from(className, methodName)) {
            @Override
            public Type getType() {
                return Type.TEST;
            }
        };
    }
}
