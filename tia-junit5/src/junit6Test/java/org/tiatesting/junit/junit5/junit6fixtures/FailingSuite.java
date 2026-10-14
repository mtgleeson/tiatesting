package org.tiatesting.junit.junit5.junit6fixtures;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * A suite whose one test fails, run by {@code TiaTestExecutionListenerJunit6Test} through the
 * JUnit 6 launcher. Excluded from the {@code junit6Test} task's own discovery.
 */
public class FailingSuite {

    /**
     * Fails.
     */
    @Test
    void fails() {
        fail("scripted failure");
    }
}
