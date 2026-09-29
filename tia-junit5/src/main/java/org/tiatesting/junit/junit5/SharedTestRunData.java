package org.tiatesting.junit.junit5;

import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This encapsulates test run data that needs to be shared between test runs/sessions.
 * When a user executes the tests, a session is created along with a TestExecutionListener. This TestExecutionListener
 * should execute all tests. But in the case of a re-run by Surefire or Failsafe (up to Surefire 3.5.3 at least), a
 * new test run/session is created along with a new TestExecutionListener.
 * We need the ability to share data from the initial session with any subsequent re-runs.
 * <p>
 * TestExecutionListener should be used by 1 test plan at a time for executing all tests.
 * The tests can be executed concurrently (if configured).
 * <p>
 * Nothing here depends on a re-run getting a new listener: a later Surefire that re-runs on the same launcher
 * session, and so the same listener, reads and writes this same shared state. The listener keeps its per-attempt
 * state in sets it clears when each test plan starts, so it behaves the same under either model.
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
     * The suites this JVM has actually observed - those it has seen finish or seen skipped - with
     * no directory-scan override. Unlike runnerTestSuites, which testClassesDir can replace with a
     * project-wide scan for deletion detection, this set exists purely to answer "how far did this
     * runner get", which is what the distributed completeness guard needs fed to it. It must live
     * here rather than on the listener so a Surefire retry's new listener instance sees what earlier
     * attempts already observed, exactly like runnerTestSuites does.
     */
    private final Set<String> suitesObserved;

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

    /*
     * The suites whose latest execution in this JVM failed, across every test plan. A suite is removed when it
     * starts - in any attempt - and added back if it fails in that execution, so a flaky suite that passes on a
     * re-run leaves the set, while a suite that failed in an earlier attempt and was not re-run stays in it. It
     * lives here rather than on the listener so that holds across re-runs that get a new listener instance. See
     * the "Failed-suite tracking" chapter in WIKI.md.
     */
    private final Set<String> testSuitesFailed;

    /**
     * Create empty shared state for a new test JVM, before its first test plan.
     */
    public SharedTestRunData() {
        this.runnerTestSuites = ConcurrentHashMap.newKeySet();
        this.suitesObserved = ConcurrentHashMap.newKeySet();
        this.testSuiteTrackers = new ConcurrentHashMap<>();
        this.testRunStats = new TestStats();
        this.testSuitesFailed = ConcurrentHashMap.newKeySet();
    }

    public Set<String> getRunnerTestSuites() {
        return runnerTestSuites;
    }

    /**
     * @return the mutable, shared set of suites this JVM has observed finish or skip so far,
     *         accumulated across every test plan (including Surefire retries) the JVM makes.
     */
    public Set<String> getSuitesObserved() {
        return suitesObserved;
    }

    public Map<String, TestSuiteTracker> getTestSuiteTrackers() {
        return testSuiteTrackers;
    }

    public TestStats getTestRunStats() {
        return testRunStats;
    }

    /**
     * @return the mutable, shared set of suites whose latest execution in this JVM failed,
     *         maintained across every test plan (including Surefire re-runs) the JVM makes
     */
    public Set<String> getTestSuitesFailed() {
        return testSuitesFailed;
    }
}
