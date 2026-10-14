package org.tiatesting.junit.junit5.junit6fixtures;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A passing suite with a {@code @Nested} class, run by {@code TiaTestExecutionListenerJunit6Test}
 * through the JUnit 6 launcher. Excluded from the {@code junit6Test} task's own discovery.
 */
public class PassingWithNestedSuite {

    /**
     * Passes.
     */
    @Test
    void passes() {
    }

    /**
     * A nested class Jupiter runs inside the enclosing class's container.
     */
    @Nested
    public class Inner {

        /**
         * Passes.
         */
        @Test
        void alsoPasses() {
        }
    }
}
