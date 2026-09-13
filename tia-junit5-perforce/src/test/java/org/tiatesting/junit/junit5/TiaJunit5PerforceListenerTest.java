package org.tiatesting.junit.junit5;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Covers the one thing this wrapper is responsible for: constructing the JUnit 5 listener without
 * reaching a Perforce server.
 *
 * <p>Unlike the JUnit 4 side, {@code TiaLauncherSessionListener} only constructs this listener when
 * {@code tiaEnabled} is true, so a disabled build does not reach the constructor through JUnit's
 * service path. The constructor is held to the rule anyway: the branch and the commit are the only
 * things it ever needed the version control system for, and once they are supplied it must work on a
 * machine that cannot reach the server.
 */
class TiaJunit5PerforceListenerTest {

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
        TiaJunit5PerforceListener listener = new TiaJunit5PerforceListener(new SharedTestRunData());

        // then
        assertNotNull(listener);
    }

    /**
     * Verifies the same for an enabled build handed the branch and commit the Maven goal resolved -
     * the state a distributed runner executes in on a VM with no Perforce access.
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
        TiaJunit5PerforceListener listener = new TiaJunit5PerforceListener(new SharedTestRunData());

        // then
        assertNotNull(listener);
    }
}
