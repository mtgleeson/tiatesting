package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Truth table for {@link TestRunResult#ranNoExpectedSuites(boolean)} - the predicate that separates a
 * misconfigured build that executed nothing from the nothing-impacted run that legitimately executes
 * nothing, and so decides whether a run's stats and savings are recorded.
 */
class TestRunResultEmptyRunTest {

    /**
     * Build a {@link TestRunResult} with only the fields the predicate reads set meaningfully.
     *
     * @param selectedTests the suites Tia selected to run
     * @param ignoredTestSuiteCount the suites Tia's selector chose to ignore
     * @param suitesRanThisAttempt the suites that executed in this attempt
     * @return the run result under test
     */
    private TestRunResult runResult(Set<String> selectedTests, int ignoredTestSuiteCount,
                                   int suitesRanThisAttempt) {
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        Set<String> empty = new HashSet<>();
        return new TestRunResult(trackers, empty, empty, empty, selectedTests, new HashMap<>(),
                new TestStats(), null, ignoredTestSuiteCount, TestRunResultFixture.syntheticPlanOutcome(suitesRanThisAttempt, 0),
                TestRunSelectionDetails.empty(), new TestJvmContribution(), TestRunResultFixture.runIdentity());
    }

    /**
     * Build a {@link TestRunResult} for a plan with the given outcome.
     *
     * @param plan what the plan ran and skipped
     * @param selectedTests the suites Tia selected to run
     * @param ignoredTestSuiteCount the suites Tia's selector chose to ignore
     * @return the run result under test
     */
    private TestRunResult runResult(TestPlanOutcome plan, Set<String> selectedTests, int ignoredTestSuiteCount) {
        Set<String> empty = new HashSet<>();
        return new TestRunResult(new HashMap<>(), empty, empty, empty, selectedTests, new HashMap<>(),
                new TestStats(), null, ignoredTestSuiteCount, plan, TestRunSelectionDetails.empty(),
                new TestJvmContribution(), TestRunResultFixture.runIdentity());
    }

    /**
     * A Surefire per-class plan holding only a class Tia ignored had nothing to run, so it is not an
     * empty run - with {@code forkCount > 1} every ignored class gets a plan like this.
     */
    @Test
    void aPlanThatOnlySkippedUnselectedSuitesIsNotAnEmptyRun() {
        // given
        TestPlanOutcome plan = new TestPlanOutcome(Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet(), Collections.singleton("com.example.IgnoredTest"));

        // when
        boolean emptyRun = runResult(plan, Collections.singleton("com.example.SelectedTest"), 4).ranNoExpectedSuites(false);

        // then
        assertFalse(emptyRun);
    }

    /**
     * A mapping run's one plan sees every class, so a plan that skipped only unselected classes means
     * the selection never ran - a {@code -Dtest=} filter naming an ignored class, say. It stays an
     * empty run, so it cannot seal past suites that never ran.
     */
    @Test
    void aMappingPlanThatOnlySkippedUnselectedSuitesIsStillAnEmptyRun() {
        // given
        TestPlanOutcome plan = new TestPlanOutcome(Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet(), Collections.singleton("com.example.IgnoredTest"));

        // when
        boolean emptyRun = runResult(plan, Collections.singleton("com.example.SelectedTest"), 4).ranNoExpectedSuites(true);

        // then
        assertTrue(emptyRun);
    }

    /**
     * A plan that skipped a suite it was selected to run - disabled in source, say - is still an empty
     * run, as before.
     */
    @Test
    void aPlanThatSkippedASelectedSuiteIsStillAnEmptyRun() {
        // given
        TestPlanOutcome plan = new TestPlanOutcome(Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet(), Collections.singleton("com.example.SelectedTest"));

        // when
        boolean emptyRun = runResult(plan, Collections.singleton("com.example.SelectedTest"), 4).ranNoExpectedSuites(true);

        // then
        assertTrue(emptyRun);
    }

    /**
     * Nothing ignored means every suite was expected to run, so executing none of them is the
     * misconfigured shape - and the damaging one, since this is the run that would otherwise set the
     * full-suite baseline.
     */
    @Test
    void noneIgnoredAndNoneRan_isAnEmptyRun() {
        // given
        TestRunResult result = runResult(new HashSet<>(), 0, 0);

        // when
        boolean emptyRun = result.ranNoExpectedSuites(true);

        // then
        assertTrue(emptyRun);
    }

    /**
     * Suites selected but none executed is the same misconfiguration on a run that had a mapping to
     * select from.
     */
    @Test
    void suitesSelectedAndNoneRan_isAnEmptyRun() {
        // given
        TestRunResult result = runResult(new HashSet<>(Arrays.asList("com.example.ATest")), 4, 0);

        // when
        boolean emptyRun = result.ranNoExpectedSuites(true);

        // then
        assertTrue(emptyRun);
    }

    /**
     * Tia ignored every suite and selected none: there was nothing to run, so executing nothing is
     * Tia working as intended rather than an empty run.
     */
    @Test
    void nothingSelectedWithSuitesIgnored_isNotAnEmptyRun() {
        // given
        TestRunResult result = runResult(new HashSet<>(), 4, 0);

        // when
        boolean emptyRun = result.ranNoExpectedSuites(true);

        // then
        assertFalse(emptyRun);
    }

    /**
     * A run that executed a suite is never an empty run, whatever the selection asked for.
     */
    @Test
    void suitesRan_isNotAnEmptyRun() {
        // given
        TestRunResult allTestsRun = runResult(new HashSet<>(), 0, 3);
        TestRunResult selectedRun = runResult(new HashSet<>(Arrays.asList("com.example.ATest")), 4, 1);

        // when
        boolean allTestsEmpty = allTestsRun.ranNoExpectedSuites(true);
        boolean selectedEmpty = selectedRun.ranNoExpectedSuites(true);

        // then
        assertFalse(allTestsEmpty);
        assertFalse(selectedEmpty);
    }

    /**
     * A null selected-tests set (no {@code tiaSelectedTests} property reached the listener) is read as
     * "no suites named", so only the nothing-ignored case flags as an empty run.
     */
    @Test
    void nullSelectedTests_fallsBackToTheIgnoredCount() {
        // given
        TestRunResult everySuiteExpected = runResult(null, 0, 0);
        TestRunResult suitesIgnored = runResult(null, 4, 0);

        // when
        boolean everySuiteExpectedEmpty = everySuiteExpected.ranNoExpectedSuites(true);
        boolean suitesIgnoredEmpty = suitesIgnored.ranNoExpectedSuites(true);

        // then
        assertTrue(everySuiteExpectedEmpty);
        assertFalse(suitesIgnoredEmpty);
    }
}
