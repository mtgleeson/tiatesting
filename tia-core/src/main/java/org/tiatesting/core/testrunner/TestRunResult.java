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
     * @param runnerTestSuites           every suite the runner discovered (executed + skipped + filtered)
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
                         Set<String> selectedTests,
                         Map<Integer, MethodImpactTracker> methodTrackersFromTestRun,
                         TestStats testStats,
                         LibraryImpactDrainResult libraryImpactDrainResult,
                         int ignoredTestSuiteCount,
                         int suitesRanThisAttempt) {
        this.testSuiteTrackers = testSuiteTrackers;
        this.testSuitesFailed = testSuitesFailed;
        this.runnerTestSuites = runnerTestSuites;
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
