package org.tiatesting.junit.junit4;

import org.junit.AssumptionViolatedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers which JUnit 4 notifications the listener counts as a suite failure: a failure fails the
 * suite, whether it is reported against a test or against the class itself, and an assumption
 * failure does not. Also guards the re-run behaviour: Surefire reuses one JUnit 4 listener across
 * re-runs, and the failed set holds each suite's latest outcome across them.
 *
 * <p>The listener runs with mapping off and the history log on - enabled, but with no JaCoCo client
 * to reach. The failed set is read straight off the listener, because with mapping off it is never
 * persisted; what is under test is the classification, not the persist.
 */
class TiaJunit4ListenerFailureTest {

    private static final String SUITE = SampleTest.class.getName();
    private static final String OTHER_SUITE = OtherSampleTest.class.getName();

    private static final String[] MANAGED_PROPERTIES = {
            "tiaEnabled", "tiaUpdateDBMapping", "tiaUpdateDBTestRunHistory", "tiaBranch",
            "tiaCommitValue", "tiaSelectedTests", "test", H2ConnectionSettings.PROP_DB_FILE_PATH
    };

    private Map<String, String> savedProperties;
    private File tempDir;

    /**
     * Save and clear every system property these tests set, enable the listener with mapping off,
     * and point its datastore at a fresh temp directory.
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

        tempDir = File.createTempFile("tia-junit4-failure-", "");
        tempDir.delete();
        tempDir.mkdirs();

        System.setProperty("tiaEnabled", "true");
        System.setProperty("tiaUpdateDBMapping", "false");
        System.setProperty("tiaBranch", "main");
        System.setProperty("tiaCommitValue", "commit-1");
        System.setProperty(H2ConnectionSettings.PROP_DB_FILE_PATH, tempDir.getAbsolutePath());
        System.setProperty("tiaSelectedTests", SUITE);
    }

    /**
     * Remove the temp directory and restore the system properties saved in {@link #setUp()}.
     */
    @AfterEach
    void tearDown() {
        if (tempDir != null && tempDir.exists()) {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            tempDir.delete();
        }
        for (Map.Entry<String, String> entry : savedProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Verifies a test whose assumption is not met leaves its suite out of the failed set - the build
     * tool reports it as skipped, not failed.
     *
     * @throws Exception if the listener's suite callbacks throw
     */
    @Test
    void testAssumptionFailure_doesNotFailTheSuite() throws Exception {
        // given
        TiaJunit4Listener listener = new TiaJunit4Listener();
        listener.testSuiteStarted(Description.createSuiteDescription(SampleTest.class));
        Description test = Description.createTestDescription(SampleTest.class, "m1");

        // when
        listener.testAssumptionFailure(new Failure(test, new AssumptionViolatedException("not this platform")));

        // then
        assertTrue(listener.getTestSuitesFailed().isEmpty());
    }

    /**
     * Verifies a failing test still records its suite as failed.
     *
     * @throws Exception if the listener's suite callbacks throw
     */
    @Test
    void testFailure_onATest_failsTheSuite() throws Exception {
        // given
        TiaJunit4Listener listener = new TiaJunit4Listener();
        listener.testSuiteStarted(Description.createSuiteDescription(SampleTest.class));
        Description test = Description.createTestDescription(SampleTest.class, "m1");

        // when
        listener.testFailure(new Failure(test, new AssertionError("boom")));

        // then
        assertEquals(Collections.singleton(SUITE), listener.getTestSuitesFailed());
    }

    /**
     * Verifies a class-level failure - a failing {@code @BeforeClass}, reported against the class's
     * own description - records the suite as failed.
     *
     * @throws Exception if the listener's suite callbacks throw
     */
    @Test
    void testFailure_onTheClass_failsTheSuite() throws Exception {
        // given
        TiaJunit4Listener listener = new TiaJunit4Listener();
        Description suite = Description.createSuiteDescription(SampleTest.class);
        listener.testSuiteStarted(suite);

        // when
        listener.testFailure(new Failure(suite, new IllegalStateException("beforeClass")));

        // then
        assertEquals(Collections.singleton(SUITE), listener.getTestSuitesFailed());
    }

    /**
     * Verifies that across a re-run on the same listener, a suite that failed in the first attempt
     * and was not re-run stays failed, a suite that passes on its re-run leaves the failed set, and
     * the re-run attempt counts no failures of its own.
     *
     * @throws Exception if the listener's run or suite callbacks throw
     */
    @Test
    void rerun_unretriedFailureStaysAndFlakyPassLeaves() throws Exception {
        // given
        TiaJunit4Listener listener = new TiaJunit4Listener();
        listener.testRunStarted(Description.EMPTY);
        runSuite(listener, SampleTest.class, true);
        runSuite(listener, OtherSampleTest.class, true);

        // when
        listener.testRunStarted(Description.EMPTY);
        runSuite(listener, OtherSampleTest.class, false);

        // then
        assertEquals(Collections.singleton(SUITE), listener.getTestSuitesFailed());
        assertTrue(listener.getSuitesFailedThisAttempt().isEmpty());
    }

    /**
     * Verifies a suite that fails again on its re-run stays failed and counts as a failure of the
     * re-run attempt.
     *
     * @throws Exception if the listener's run or suite callbacks throw
     */
    @Test
    void rerun_suiteFailsAgain_staysFailed() throws Exception {
        // given
        TiaJunit4Listener listener = new TiaJunit4Listener();
        listener.testRunStarted(Description.EMPTY);
        runSuite(listener, OtherSampleTest.class, true);

        // when
        listener.testRunStarted(Description.EMPTY);
        runSuite(listener, OtherSampleTest.class, true);

        // then
        assertEquals(Collections.singleton(OTHER_SUITE), listener.getTestSuitesFailed());
        assertEquals(Collections.singleton(OTHER_SUITE), listener.getSuitesFailedThisAttempt());
    }

    /**
     * Run one suite through the listener: start it, optionally fail one of its tests, and finish it.
     *
     * @param listener the listener
     * @param suiteClass the suite's class
     * @param fail whether the suite's test fails
     * @throws Exception if the listener's suite callbacks throw
     */
    private static void runSuite(final TiaJunit4Listener listener, final Class<?> suiteClass,
                                 final boolean fail) throws Exception {
        Description suite = Description.createSuiteDescription(suiteClass);
        listener.testSuiteStarted(suite);
        if (fail) {
            listener.testFailure(new Failure(Description.createTestDescription(suiteClass, "m1"),
                    new AssertionError("boom")));
        }
        listener.testSuiteFinished(suite);
    }

    /**
     * A class to name in descriptions, so {@link Description#getTestClass()} resolves and the
     * listener treats it as an ordinary suite rather than a parameterized one.
     */
    static final class SampleTest {
    }

    /**
     * A second suite class, for re-run cases that need two suites.
     */
    static final class OtherSampleTest {
    }
}
