package org.tiatesting.core.testrunner;

import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TestStats;

import java.util.Collections;
import java.util.HashSet;
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
    final TestPlanOutcome planOutcome;
    final TestRunSelectionDetails selectionDetails;
    final TestJvmContribution jvmContribution;
    final TestRunIdentity runIdentity;
    final RunAttempt runAttempt;

    /**
     * Construct the collected result of a Tia-instrumented test run.
     *
     * @param testSuiteTrackers          per-suite trackers (one entry per suite the runner actually executed).
     *                                   On Surefire retry of failed tests this map accumulates across attempts -
     *                                   the mapping path needs the cumulative coverage. For what this plan
     *                                   ran use {@code planOutcome} instead.
     * @param testSuitesFailed           the suites whose <b>latest</b> execution in this JVM failed, across
     *                                   every test plan the JVM has made (Surefire retries included): a
     *                                   suite that fails and then passes on a retry is not in it, and a
     *                                   suite that failed in an earlier attempt but was not retried still
     *                                   is. This is the set the failed-suite write and the distributed
     *                                   group's {@code suites_failed} are fed from. Every suite in it has an
     *                                   entry in {@code testSuiteTrackers}.
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
     * @param planOutcome                what this test plan finished, failed, exercised and skipped -
     *                                   the per-plan figures the run's contribution and the rerun rule
     *                                   are built from. Per plan, not cumulative across Surefire
     *                                   retries, unlike {@code testSuiteTrackers}.
     * @param selectionDetails           the per-run breakdown of what drove test selection (the per-method
     *                                   and per-rule triggers plus the scalar source counts), or {@code null}
     *                                   when the caller has not populated it yet - {@link #getSelectionDetails()}
     *                                   substitutes {@link TestRunSelectionDetails#empty()} in that case so
     *                                   callers never see null
     * @param jvmContribution            this test JVM's running totals for the run, shared by every
     *                                   plan the JVM makes
     * @param runIdentity                the run id and start time the build tool assigned
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
                         TestPlanOutcome planOutcome,
                         TestRunSelectionDetails selectionDetails,
                         TestJvmContribution jvmContribution,
                         TestRunIdentity runIdentity) {
        this(testSuiteTrackers, testSuitesFailed, runnerTestSuites, suitesObserved, selectedTests,
                methodTrackersFromTestRun, testStats, libraryImpactDrainResult, ignoredTestSuiteCount,
                planOutcome, selectionDetails, jvmContribution, runIdentity, RunAttempt.FIRST);
    }

    /**
     * Construct a result carrying a resolved attempt; see {@link #withRunAttempt(RunAttempt)}.
     *
     * @param testSuiteTrackers          see the public constructor
     * @param testSuitesFailed           see the public constructor
     * @param runnerTestSuites           see the public constructor
     * @param suitesObserved             see the public constructor
     * @param selectedTests              see the public constructor
     * @param methodTrackersFromTestRun  see the public constructor
     * @param testStats                  see the public constructor
     * @param libraryImpactDrainResult   see the public constructor
     * @param ignoredTestSuiteCount      see the public constructor
     * @param planOutcome                see the public constructor
     * @param selectionDetails           see the public constructor
     * @param jvmContribution            see the public constructor
     * @param runIdentity                see the public constructor
     * @param runAttempt                 the attempt core resolved for the plan
     */
    private TestRunResult(Map<String, TestSuiteTracker> testSuiteTrackers,
                          Set<String> testSuitesFailed,
                          Set<String> runnerTestSuites,
                          Set<String> suitesObserved,
                          Set<String> selectedTests,
                          Map<Integer, MethodImpactTracker> methodTrackersFromTestRun,
                          TestStats testStats,
                          LibraryImpactDrainResult libraryImpactDrainResult,
                          int ignoredTestSuiteCount,
                          TestPlanOutcome planOutcome,
                          TestRunSelectionDetails selectionDetails,
                          TestJvmContribution jvmContribution,
                          TestRunIdentity runIdentity,
                          RunAttempt runAttempt) {
        this.testSuiteTrackers = testSuiteTrackers;
        this.testSuitesFailed = testSuitesFailed;
        this.runnerTestSuites = runnerTestSuites;
        this.suitesObserved = suitesObserved;
        this.selectedTests = selectedTests;
        this.methodTrackersFromTestRun = methodTrackersFromTestRun;
        this.testStats = testStats;
        this.libraryImpactDrainResult = libraryImpactDrainResult;
        this.ignoredTestSuiteCount = ignoredTestSuiteCount;
        this.planOutcome = planOutcome;
        this.selectionDetails = selectionDetails;
        this.jvmContribution = jvmContribution;
        this.runIdentity = runIdentity;
        this.runAttempt = runAttempt;
    }

    /**
     * Copy this result with the attempt core resolved for its plan. Package-private: only
     * {@link TestRunnerService} resolves attempts.
     *
     * @param resolvedAttempt the plan's attempt
     * @return a copy carrying the attempt
     */
    TestRunResult withRunAttempt(final RunAttempt resolvedAttempt) {
        return new TestRunResult(testSuiteTrackers, testSuitesFailed, runnerTestSuites, suitesObserved,
                selectedTests, methodTrackersFromTestRun, testStats, libraryImpactDrainResult,
                ignoredTestSuiteCount, planOutcome, selectionDetails, jvmContribution, runIdentity,
                resolvedAttempt);
    }

    /**
     * @return the per-suite trackers, one entry for every suite that executed in this JVM across
     *         all its test plans. A tracker is created only when a suite starts, never for a
     *         skipped one, so the key set is exactly "the suites this JVM executed" - which is what
     *         the failed-suite write clears before adding back this JVM's failures.
     */
    public Map<String, TestSuiteTracker> getTestSuiteTrackers() {
        return testSuiteTrackers;
    }

    /**
     * @return the suites whose latest execution in this JVM failed, across every test plan the JVM
     *         has made - see the constructor's {@code testSuitesFailed} parameter
     */
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

    /**
     * The ids of the methods this run's suites executed: the union of every suite's coverage
     * edges. Narrower than {@link #getMethodTrackersFromTestRun()}, which also holds every other
     * method of each covered class so the seal can refresh their line numbers. Used to count each
     * method's executed runs - see the "Method run stats" chapter in {@code WIKI.md}.
     *
     * @return the ids of the methods with line coverage in this run; empty if no suite ran
     */
    public Set<Integer> getCoveredMethodIds() {
        Set<Integer> covered = new HashSet<>();
        for (TestSuiteTracker suite : testSuiteTrackers.values()) {
            for (ClassImpactTracker classImpacted : suite.getClassesImpacted()) {
                covered.addAll(classImpacted.getMethodsImpacted());
            }
        }
        return covered;
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
     * <p>On a run that does not update the mapping, a plan that ran nothing but skipped suites, none
     * of them selected, is not an empty run either: it held only suites Tia ignored - with Surefire
     * {@code forkCount > 1} every class is its own plan, and an ignored class's plan looks like this
     * - so there was nothing for it to run. A plan that skipped a selected suite, or observed nothing
     * at all, still is one. A mapping run gets no such exception: it runs in one JVM whose plan sees
     * every class, so a plan that skipped only unselected classes means the selection never ran - a
     * build-tool filter ({@code -Dtest=}) naming an ignored class, say - and sealing it would advance
     * the stored commit past suites that never ran.
     *
     * <p>Read from {@link #getPlanOutcome()} rather than the cumulative tracker map, so the
     * question is asked of the attempt being persisted. A Surefire retry attempt executes the suites
     * holding the failed tests, so it does not read as an empty run; a retry contributes no run stats
     * regardless (see {@code CoreStatsIncrement.getNumRuns}).
     *
     * @param updatesMapping whether the run updates the mapping
     * @return true when no test suite executed in this attempt though the selection expected at
     *         least one to
     */
    public boolean ranNoExpectedSuites(final boolean updatesMapping) {
        if (!planOutcome.getSuitesRan().isEmpty()) {
            return false;
        }

        // A plan that skipped suites, none of which it was meant to run, held nothing to run - a
        // Surefire per-class plan of a class Tia ignored, or a distributed runner's plan of a suite
        // outside its group. It is a plan with nothing to do, not a run that failed to execute.
        Set<String> skipped = planOutcome.getSuitesSkipped();
        Set<String> selected = selectedTests == null ? Collections.<String>emptySet() : selectedTests;
        if (!updatesMapping && !skipped.isEmpty() && Collections.disjoint(skipped, selected)) {
            return false;
        }

        boolean everySuiteExpected = ignoredTestSuiteCount == 0;
        boolean suitesWereSelected = selectedTests != null && !selectedTests.isEmpty();
        return everySuiteExpected || suitesWereSelected;
    }

    /** @return what this test plan finished, failed, exercised and skipped */
    public TestPlanOutcome getPlanOutcome() {
        return planOutcome;
    }

    /** @return this test JVM's running totals for the run */
    public TestJvmContribution getJvmContribution() {
        return jvmContribution;
    }

    /** @return the run id and start time the build tool assigned */
    public TestRunIdentity getRunIdentity() {
        return runIdentity;
    }

    /**
     * @return the per-run breakdown of what drove test selection - the per-method and per-rule
     *         triggers plus the scalar source counts. Never null: substitutes
     *         {@link TestRunSelectionDetails#empty()} when the constructor was given a null
     *         {@code selectionDetails}, so callers do not need their own null check.
     */
    public TestRunSelectionDetails getSelectionDetails() {
        return selectionDetails == null ? TestRunSelectionDetails.empty() : selectionDetails;
    }

    /**
     * @return which attempt at the run this plan is: the first attempt, or a retry of failed tests
     *         in the same or a fresh JVM. Core resolves it at persist time from the run's
     *         contributions (see {@link RunAttemptResolver}); before then it reads
     *         {@link RunAttempt#FIRST}
     */
    public RunAttempt getRunAttempt() {
        return runAttempt;
    }
}
