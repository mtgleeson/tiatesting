package org.tiatesting.core.testrunner;

import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TestStats;

import java.util.Map;
import java.util.Set;

public class TestRunResult {
    final Map<String, TestSuiteTracker> testSuiteTrackers;
    final Set<String> testSuitesFailed;
    final Set<String> runnerTestSuites;
    final Set<String> suitesObserved;
    final Set<String> selectedTests;
    final Map<Integer, MethodImpactTracker> methodTrackersFromTestRun;
    final TestStats testStats;
    final LibraryImpactDrainResult libraryImpactDrainResult;
    final int ignoredTestSuiteCount;
    final int suitesRanThisAttempt;

    /**
     * Construct the collected result of a Tia-instrumented test run.
     *
     * @param testSuiteTrackers          per-suite trackers (one entry per suite the runner actually executed).
     *                                   On Surefire retry of failed tests this map accumulates across attempts -
     *                                   the mapping path needs the cumulative coverage. For the history "Ran"
     *                                   column use {@code suitesRanThisAttempt} instead.
     * @param testSuitesFailed           names of suites that failed
     * @param runnerTestSuites           every suite name Tia still considers to exist for this project - the
     *                                   project-wide set when the build tool provides a directory scan (Maven's
     *                                   {@code testClassesDir}), otherwise the suites this JVM observed. Used
     *                                   only by {@code removeDeletedTestSuites} to detect suites that have
     *                                   genuinely been deleted; it deliberately carries no information about
     *                                   how far this runner actually got, which is what {@code suitesObserved}
     *                                   is for.
     * @param suitesObserved             the suites this runner's JVM actually observed - those it saw
     *                                   <b>finish</b> or saw <b>skipped</b> - accumulated across every test
     *                                   plan the JVM makes (Surefire retries included), with no directory-scan
     *                                   override. This is the set the distributed completeness guard is fed
     *                                   from, kept distinct from {@code runnerTestSuites} precisely because
     *                                   that set can be a project-wide scan carrying no information about this
     *                                   runner's own progress - feeding the guard from it let a group complete
     *                                   before its runner had actually seen every assigned suite.
     * @param selectedTests              the suites Tia selected to run, as read from the {@code tiaSelectedTests}
     *                                   system property by the listener
     * @param methodTrackersFromTestRun  method-id to {@code MethodImpactTracker} captured during the run
     * @param testStats                  aggregated run stats (or {@code null} when stats persistence is off)
     * @param libraryImpactDrainResult   drain result deserialised from the selection step (may be {@code null})
     * @param ignoredTestSuiteCount      the count of test suites Tia chose to ignore for this run, as read
     *                                   from the {@code tiaIgnoredTestSuiteCount} system property; this is
     *                                   the value persisted to {@code tia_test_run_history} and reflects
     *                                   only Tia's selection decision, not engine-level skips or filters
     * @param suitesRanThisAttempt       the count of test suites that finished in this listener attempt only -
     *                                   not the cumulative count across Surefire retries. Persisted to
     *                                   {@code tia_test_run_history.num_suites_ran} so each retry row reports
     *                                   what that retry actually ran.
     */
    public TestRunResult(Map<String, TestSuiteTracker> testSuiteTrackers,
                         Set<String> testSuitesFailed,
                         Set<String> runnerTestSuites,
                         Set<String> suitesObserved,
                         Set<String> selectedTests,
                         Map<Integer, MethodImpactTracker> methodTrackersFromTestRun,
                         TestStats testStats,
                         LibraryImpactDrainResult libraryImpactDrainResult,
                         int ignoredTestSuiteCount,
                         int suitesRanThisAttempt) {
        this.testSuiteTrackers = testSuiteTrackers;
        this.testSuitesFailed = testSuitesFailed;
        this.runnerTestSuites = runnerTestSuites;
        this.suitesObserved = suitesObserved;
        this.selectedTests = selectedTests;
        this.methodTrackersFromTestRun = methodTrackersFromTestRun;
        this.testStats = testStats;
        this.libraryImpactDrainResult = libraryImpactDrainResult;
        this.ignoredTestSuiteCount = ignoredTestSuiteCount;
        this.suitesRanThisAttempt = suitesRanThisAttempt;
    }

    public Map<String, TestSuiteTracker> getTestSuiteTrackers() {
        return testSuiteTrackers;
    }

    public Set<String> getTestSuitesFailed() {
        return testSuitesFailed;
    }

    public Set<String> getRunnerTestSuites() {
        return runnerTestSuites;
    }

    /**
     * @return the suites this runner's JVM actually observed - those it saw finish or saw skipped -
     *         with no directory-scan override, unlike {@link #getRunnerTestSuites()}. This is what
     *         {@code TestRunnerService#persistDistributedRunnerData} feeds the distributed
     *         completeness guard from.
     */
    public Set<String> getSuitesObserved() {
        return suitesObserved;
    }

    public Set<String> getSelectedTests() {
        return selectedTests;
    }

    public Map<Integer, MethodImpactTracker> getMethodTrackersFromTestRun() {
        return methodTrackersFromTestRun;
    }

    public TestStats getTestStats() {
        return testStats;
    }

    public LibraryImpactDrainResult getLibraryImpactDrainResult() {
        return libraryImpactDrainResult;
    }

    /**
     * @return the number of test suites Tia chose to ignore for this run. Sourced from the
     *         selector's {@code TestSelectorResult.testsToIgnore} via the
     *         {@code tiaIgnoredTestSuiteCount} system property, and persisted to
     *         {@code tia_test_run_history.num_suites_ignored}. Engine-level skips (user
     *         {@code @Disabled}, surefire {@code groups} filters, etc.) are excluded
     *         from this count.
     */
    public int getIgnoredTestSuiteCount() {
        return ignoredTestSuiteCount;
    }

    /**
     * Whether this run finished having executed no test suite at all, when Tia's selection expected
     * at least one to run - the shape a misconfigured build takes, and the reason such a run must be
     * kept out of the stats.
     *
     * <p>A project whose test framework is not wired up correctly (a missing or mismatched test
     * dependency being the common one) finishes in milliseconds having executed nothing, and reports
     * itself successful because no suite failed. Folding that into the Tia-level stats is wrong
     * twice over: the duration is Tia's own overhead rather than test-execution time, and on a run
     * that ignored nothing it lands in {@code allTestsRunTime} - the full-suite baseline every later
     * savings figure is measured against - collapsing it towards nothing in a single build.
     *
     * <p><b>Expected</b> is the qualifier that carries the weight. A run where Tia ignored every
     * suite because nothing was impacted also executes nothing, and that is Tia working as intended
     * rather than a misconfiguration, so it keeps contributing its stats and its savings exactly as
     * before. The two are told apart by what the selector asked for: zero ignored suites means the
     * run was meant to execute everything, a non-empty selection means it was meant to execute those
     * suites, and an empty selection alongside ignored suites means there was nothing to run in the
     * first place.
     *
     * <p>Read from {@link #getSuitesRanThisAttempt()} rather than the cumulative tracker map, so the
     * question is asked of the attempt being persisted. A Surefire retry attempt executes the suites
     * holding the failed tests, so it does not read as an empty run; a retry contributes no run stats
     * regardless (see {@code CoreStatsIncrement.getNumRuns}).
     *
     * @return true when no test suite executed in this attempt though the selection expected at
     *         least one to
     */
    public boolean ranNoExpectedSuites() {
        if (suitesRanThisAttempt > 0) {
            return false;
        }

        boolean everySuiteExpected = ignoredTestSuiteCount == 0;
        boolean suitesWereSelected = selectedTests != null && !selectedTests.isEmpty();
        return everySuiteExpected || suitesWereSelected;
    }

    /**
     * @return the count of test suites that finished in this listener attempt only. On Surefire
     *         retry the {@link #getTestSuiteTrackers()} map carries forward earlier attempts'
     *         entries (intentionally - the mapping path needs the cumulative coverage), so its
     *         {@code size()} over-counts for the history row. This counter is per-attempt and
     *         is the value persisted to {@code tia_test_run_history.num_suites_ran}.
     */
    public int getSuitesRanThisAttempt() {
        return suitesRanThisAttempt;
    }
}
