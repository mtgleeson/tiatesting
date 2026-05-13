package org.tiatesting.core.diff.diffanalyze.selector;

import org.tiatesting.core.library.LibraryImpactDrainResult;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Result returned by {@link TestSelector#selectTestsToIgnore} describing the outcome of a
 * test-selection run.
 *
 * <p>Carries the set of tests Tia chose to run, the set it chose to ignore, the optional
 * library-impact drain outcome, and an estimate of how long the selected tests should take
 * to execute. The runtime estimate is derived from the per-test {@code avgRunTime} stored in
 * the {@code tia_test_suite} table; for tests that don't yet have stats (typically newly-added
 * test files) the median {@code avgRunTime} across all tracked test suites is substituted.
 */
public class TestSelectorResult {

    private final Set<String> testsToRun;

    private final Set<String> testsToIgnore;

    private final LibraryImpactDrainResult libraryImpactDrainResult;

    private final long estimatedRunTimeMs;

    private final Set<String> selectedTestsWithoutStats;

    private final long medianRunTimeMsAppliedToMissing;

    private final Map<String, Long> selectedTestRunTimesMs;

    /**
     * Construct a {@link TestSelectorResult}.
     *
     * @param testsToRun the test suites selected to run
     * @param testsToIgnore the test suites Tia chose to skip
     * @param libraryImpactDrainResult the library-impact drain outcome, or {@code null}
     * @param estimatedRunTimeMs total estimated runtime (ms) for {@code testsToRun}, including
     *                           any median-fallback contribution for tests without stats
     * @param selectedTestsWithoutStats names of selected tests with no recorded run-time stats;
     *                                  must not be {@code null} (use an empty set instead)
     * @param medianRunTimeMsAppliedToMissing the median {@code avgRunTime} (ms) substituted for
     *                                        each test in {@code selectedTestsWithoutStats}, or
     *                                        {@code 0} if no fallback was needed or no historical
     *                                        stats exist
     * @param selectedTestRunTimesMs per-test estimated runtime (ms) keyed by test suite name,
     *                               covering every entry in {@code testsToRun}; tests without
     *                               stats carry the median value or {@code 0} when no median
     *                               is available. Must not be {@code null} (use an empty map
     *                               instead)
     */
    public TestSelectorResult(Set<String> testsToRun, Set<String> testsToIgnore,
                               LibraryImpactDrainResult libraryImpactDrainResult,
                               long estimatedRunTimeMs,
                               Set<String> selectedTestsWithoutStats,
                               long medianRunTimeMsAppliedToMissing,
                               Map<String, Long> selectedTestRunTimesMs) {
        this.testsToRun = testsToRun;
        this.testsToIgnore = testsToIgnore;
        this.libraryImpactDrainResult = libraryImpactDrainResult;
        this.estimatedRunTimeMs = estimatedRunTimeMs;
        this.selectedTestsWithoutStats = selectedTestsWithoutStats;
        this.medianRunTimeMsAppliedToMissing = medianRunTimeMsAppliedToMissing;
        this.selectedTestRunTimesMs = selectedTestRunTimesMs;
    }

    /**
     * @return the test suites Tia selected to run
     */
    public Set<String> getTestsToRun() {
        return testsToRun;
    }

    /**
     * @return the test suites Tia chose to ignore (skip) for this run
     */
    public Set<String> getTestsToIgnore() {
        return testsToIgnore;
    }

    /**
     * @return the library-impact drain outcome for this run, or {@code null} when library
     *         impact analysis is not configured or was bypassed
     */
    public LibraryImpactDrainResult getLibraryImpactDrainResult() {
        return libraryImpactDrainResult;
    }

    /**
     * @return the total estimated runtime in milliseconds for the selected tests, including
     *         any median-fallback contribution for tests without recorded stats
     */
    public long getEstimatedRunTimeMs() {
        return estimatedRunTimeMs;
    }

    /**
     * @return the names of selected tests that have no recorded run-time stats (typically
     *         newly-added test files); never {@code null}
     */
    public Set<String> getSelectedTestsWithoutStats() {
        return selectedTestsWithoutStats;
    }

    /**
     * @return the median {@code avgRunTime} (ms) substituted into the estimate for each test
     *         in {@link #getSelectedTestsWithoutStats()}; {@code 0} when no fallback was
     *         applied or no historical stats are available to derive a median from
     */
    public long getMedianRunTimeMsAppliedToMissing() {
        return medianRunTimeMsAppliedToMissing;
    }

    /**
     * @return per-test estimated runtime (ms) keyed by test suite name. Every name in
     *         {@link #getTestsToRun()} has an entry; tests without recorded stats carry the
     *         median value or {@code 0} when no median is available. Never {@code null}.
     */
    public Map<String, Long> getSelectedTestRunTimesMs() {
        return selectedTestRunTimesMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestSelectorResult that = (TestSelectorResult) o;
        return Objects.equals(testsToRun, that.testsToRun) && Objects.equals(testsToIgnore, that.testsToIgnore);
    }

    @Override
    public int hashCode() {
        return Objects.hash(testsToRun, testsToIgnore);
    }
}
