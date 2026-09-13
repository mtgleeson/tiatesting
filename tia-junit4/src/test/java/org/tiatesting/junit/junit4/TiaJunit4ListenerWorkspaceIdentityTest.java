package org.tiatesting.junit.junit4;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers where this listener gets the branch and the commit from: the system properties the build
 * JVM resolved them into and the Tia agent republished here, never a repository of this JVM's own.
 *
 * <p>The absent-property cases fail rather than degrade. The branch decides which schema the run's
 * rows are written to, so a missing one would silently resolve to a different schema than the build
 * JVM used - the mapping would land somewhere no later build reads - and the commit is what a
 * single-host run stamps its mapping with, so a null one leaves the next build with no diff
 * baseline. Neither has a safe default, and both mean the agent did not run.
 */
class TiaJunit4ListenerWorkspaceIdentityTest {

    private static final String[] MANAGED_PROPERTIES = {
            "tiaEnabled", "tiaUpdateDBMapping", "tiaUpdateDBTestRunHistory", "tiaBranch",
            "tiaCommitValue", "test"
    };

    private Map<String, String> savedProperties;

    /**
     * Save and clear every system property these tests set, so the long-lived test JVM does not
     * carry one test's configuration into another's.
     */
    @BeforeEach
    void setUp() {
        savedProperties = new LinkedHashMap<>();
        for (String key : MANAGED_PROPERTIES) {
            savedProperties.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
        // Enabled, but writing no mapping: enough for the listener to need the branch and commit,
        // without it initialising a JaCoCo client or opening a datastore.
        System.setProperty("tiaEnabled", "true");
        System.setProperty("tiaUpdateDBMapping", "false");
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
     * Verifies an enabled run with no branch property fails naming it, rather than carrying a null
     * branch into the schema name.
     */
    @Test
    void constructing_withNoBranchProperty_throwsNamingTiaBranch() {
        // given
        System.setProperty("tiaCommitValue", "commit-1");

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new TiaJunit4Listener());

        // then
        assertTrue(ex.getMessage().contains("tiaBranch"),
                "message should name tiaBranch, was: " + ex.getMessage());
    }

    /**
     * Verifies an enabled run with no commit property fails naming it, for the same reason: the
     * commit a single-host run stamps its mapping with cannot be guessed.
     */
    @Test
    void constructing_withNoCommitProperty_throwsNamingTiaCommitValue() {
        // given
        System.setProperty("tiaBranch", "main");

        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new TiaJunit4Listener());

        // then
        assertTrue(ex.getMessage().contains("tiaCommitValue"),
                "message should name tiaCommitValue, was: " + ex.getMessage());
    }

    /**
     * Verifies that a run Tia is not enabled for constructs without either property. Nothing
     * consumes the two values then, and a build that switched Tia off must not be failed by it.
     */
    @Test
    void constructing_withTiaDisabledAndNeitherProperty_succeeds() {
        // given
        System.setProperty("tiaEnabled", "false");

        // when
        TiaJunit4Listener listener = new TiaJunit4Listener();

        // then
        assertNotNull(listener);
    }
}
