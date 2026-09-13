package org.tiatesting.junit.junit5;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Covers the one thing this wrapper is responsible for: constructing the JUnit 5 listener without
 * opening a Git repository.
 *
 * <p>Every test points {@code tiaProjectDir} at a temp directory with no {@code .git} anywhere above
 * it, which is the shape of a CI runner holding an exported tree. A constructor that still opened a
 * repository would fail there, and {@link TempDir} makes that failure the test's own rather than
 * something that depends on where the build happens to be checked out.
 *
 * <p>{@code TiaLauncherSessionListener} only constructs this listener when {@code tiaEnabled} is
 * true, so a disabled build does not reach the constructor through JUnit's service path. The
 * constructor is held to the rule anyway: the branch and the commit are the only things it ever
 * needed the repository for.
 */
class TiaJunit5GitListenerTest {

    private static final String[] MANAGED_PROPERTIES = {
            "tiaEnabled", "tiaUpdateDBMapping", "tiaUpdateDBTestRunHistory", "tiaBranch",
            "tiaCommitValue", "tiaProjectDir"
    };

    @TempDir
    File workspaceWithNoRepository;

    private Map<String, String> savedProperties;

    /**
     * Save and clear every system property this test touches, then point Tia's project directory at
     * a temp directory that holds no Git repository.
     */
    @BeforeEach
    void setUp() {
        savedProperties = new LinkedHashMap<>();
        for (String key : MANAGED_PROPERTIES) {
            savedProperties.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
        System.setProperty("tiaProjectDir", workspaceWithNoRepository.getAbsolutePath());
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
     * Verifies that a disabled build constructs the listener in a workspace with no repository. Tia
     * being switched off must never be the reason a test run fails.
     */
    @Test
    void constructingTheListener_withTiaDisabled_opensNoRepository() {
        // given
        System.setProperty("tiaEnabled", "false");

        // when
        TiaJunit5GitListener listener = new TiaJunit5GitListener(new SharedTestRunData());

        // then
        assertNotNull(listener);
    }

    /**
     * Verifies the same for an enabled build handed the branch and commit the Maven goal resolved -
     * the state a distributed runner executes in on a VM holding no repository.
     */
    @Test
    void constructingTheListener_withTheBranchAndCommitSupplied_opensNoRepository() {
        // given
        System.setProperty("tiaEnabled", "true");
        System.setProperty("tiaUpdateDBMapping", "false");
        System.setProperty("tiaUpdateDBTestRunHistory", "false");
        System.setProperty("tiaBranch", "main");
        System.setProperty("tiaCommitValue", "commit-1");

        // when
        TiaJunit5GitListener listener = new TiaJunit5GitListener(new SharedTestRunData());

        // then
        assertNotNull(listener);
    }
}
