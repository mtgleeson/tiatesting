package org.tiatesting.junit.junit4;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Covers the one thing this wrapper is responsible for: constructing the JUnit 4 listener without
 * reaching a Perforce server.
 *
 * <p>Surefire instantiates this listener from its {@code listener} property, which it reads before
 * and regardless of anything Tia configures - unlike the JUnit 5 side, where the launcher session
 * listener only constructs its listener when {@code tiaEnabled} is true. So a disabled build
 * constructs this class anyway, and anything in the constructor that needs Perforce fails a test run
 * that had asked Tia to stay out of the way entirely.
 */
class TiaJunit4PerforceListenerTest {

    private static final String[] MANAGED_PROPERTIES = {
            "tiaEnabled", "tiaUpdateDBMapping", "tiaUpdateDBTestRunHistory", "tiaBranch",
            "tiaCommitValue", "tiaVcsServerUri", "tiaVcsUserName", "tiaVcsPassword",
            "tiaVcsClientName"
    };

    private Map<String, String> savedProperties;

    /**
     * Save and clear every system property this test touches, so the long-lived test JVM does not
     * carry one test's configuration into another's.
     */
    @BeforeEach
    void setUp() {
        savedProperties = new LinkedHashMap<>();
        for (String key : MANAGED_PROPERTIES) {
            savedProperties.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
    }

    /**
     * Restore the system properties saved in {@link #setUp()}.
     */
    @AfterEach
    void tearDown() {
        for (Map.Entry<String, String> entry : savedProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Verifies that a disabled build constructs the listener without touching Perforce. No
     * connection settings are configured here, so a constructor that tried to reach a server would
     * fail outright rather than quietly connect to something.
     */
    @Test
    void constructingTheListener_withTiaDisabled_reachesNoPerforceServer() {
        // given
        System.setProperty("tiaEnabled", "false");

        // when
        TiaJunit4PerforceListener listener = new TiaJunit4PerforceListener();

        // then
        assertNotNull(listener);
    }

    /**
     * Verifies the same for an enabled build handed the branch and commit the Maven goal resolved.
     * Those two values are the only thing this listener ever needed the version control system for,
     * so with them supplied it must construct without a Perforce connection - which is what lets a
     * runner execute on a VM that cannot reach the server.
     */
    @Test
    void constructingTheListener_withTheBranchAndCommitSupplied_reachesNoPerforceServer() {
        // given
        System.setProperty("tiaEnabled", "true");
        System.setProperty("tiaUpdateDBMapping", "false");
        System.setProperty("tiaUpdateDBTestRunHistory", "false");
        System.setProperty("tiaBranch", "main");
        System.setProperty("tiaCommitValue", "commit-1");

        // when
        TiaJunit4PerforceListener listener = new TiaJunit4PerforceListener();

        // then
        assertNotNull(listener);
    }
}
