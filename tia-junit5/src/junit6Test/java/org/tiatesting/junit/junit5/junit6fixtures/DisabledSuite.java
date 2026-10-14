package org.tiatesting.junit.junit5.junit6fixtures;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * A suite carrying the {@code @Disabled} Tia's agent adds to the suites it skips, run by {@code
 * TiaTestExecutionListenerJunit6Test} through the JUnit 6 launcher. Excluded from the
 * {@code junit6Test} task's own discovery.
 */
@Disabled("Ignored by TIA testing")
public class DisabledSuite {

    /**
     * Never runs.
     */
    @Test
    void neverRuns() {
    }
}
