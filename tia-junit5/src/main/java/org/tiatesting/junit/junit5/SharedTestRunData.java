package org.tiatesting.junit.junit5;

import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This encapsulates test run data that needs to be shared between test runs/sessions.
 * When a user executes the tests, a session is created along with a TestExecutionListener. This TestExecutionListener
 * should execute all tests. But in the case of a re-run by Surefire or Failsafe, a new test run/session is created
 * along with a new TestExecutionListener.
 * We need the ability to share data from the initial session with any subsequent re-runs.
 * <p>
 * TestExecutionListener should be used by 1 test plan at a time for executing all tests.
 * The tests can be executed concurrently (if configured).
 * But, when Surefire is configured to re-run failed tests, it will create a new Test Plan, new Session and new
 */
public class SharedTestRunData {

    /*
     * We need to know which test suites have executed already in any previous test runs so we don't calculate average
     * time for re-runs.
     * Re-run will only execute failed methods in a test suite and so the run time will be shorter. We don't want to
     * take these times into account for the test suite average test time otherwise it will skew it.
     */
    private final Set<String> runnerTestSuites;

    /*
     * On re-runs, we don't want to overwrite the class mappings for the test suites.
     * i.e. the re-run test plan mappings will be only relevant for the re-run test method(s) which are a subset for the
     * overall test suite and won't account for the other test methods in the suite.
     * Class trackers from the initial session/run should be shared with any re-runs sessions so the mapping data accounts
     * for all sessions and does not get overwritten with only the subset.
     */
    private final Map<String, TestSuiteTracker> testSuiteTrackers;

    /*
     * For re-runs, we don't count overall runner stats as we only want to track how many times Tia was used for
     * selecting tests, not running them.
     */
    private final TestStats testRunStats;

    public SharedTestRunData() {
        this.runnerTestSuites = ConcurrentHashMap.newKeySet();
        this.testSuiteTrackers = new ConcurrentHashMap<>();
        this.testRunStats = new TestStats();
    }

    public Set<String> getRunnerTestSuites() {
        return runnerTestSuites;
    }

    public Map<String, TestSuiteTracker> getTestSuiteTrackers() {
        return testSuiteTrackers;
    }

    public TestStats getTestRunStats() {
        return testRunStats;
    }
}
