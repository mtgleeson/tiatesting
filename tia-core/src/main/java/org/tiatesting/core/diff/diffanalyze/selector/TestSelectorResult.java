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

    private final long allTestsRunTimeMs;

    private final long captureOverheadMs;

    private final long fixedOverheadMs;

    private final boolean runAllTests;

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
     * @param allTestsRunTimeMs the Tia-level all-tests-run baseline (ms): the recorded average
     *                          time to run the full suite, used to show estimated savings. May be
     *                          {@code 0} when no full-suite run has been recorded yet
     * @param captureOverheadMs the coverage-collection time (ms) the selected suites would add
     *                          between them. Per-suite, so it scales with the selection and is the
     *                          part a distributed build divides across its groups. Added to
     *                          {@code estimatedRunTimeMs} only when the run being estimated will
     *                          collect coverage; {@code 0} when not derivable
     * @param fixedOverheadMs the time (ms) a test JVM pays once however few suites it runs - engine
     *                        start-up, class loading, the final coverage dump. Charged once per
     *                        runner, so a distributed build pays a copy per group rather than
     *                        dividing it, which is the whole reason it is carried separately from
     *                        {@code captureOverheadMs}. Added only for a coverage run; {@code 0}
     *                        for an empty selection, and until a distributed build has measured it
     * @param runAllTests {@code true} only when no mapping is stored yet for the tracked branch
     *                    and every test must run because Tia has nothing to select against;
     *                    {@code false} for a normal selection. Both {@code testsToRun} and
     *                    {@code testsToIgnore} are empty in the {@code true} case, which is why
     *                    this flag exists - an empty {@code testsToRun} means "nothing impacted"
     *                    only when this is {@code false}; when it is {@code true} it means the
     *                    opposite, "everything must run"
     */
    public TestSelectorResult(Set<String> testsToRun, Set<String> testsToIgnore,
                               LibraryImpactDrainResult libraryImpactDrainResult,
                               long estimatedRunTimeMs,
                               Set<String> selectedTestsWithoutStats,
                               long medianRunTimeMsAppliedToMissing,
                               Map<String, Long> selectedTestRunTimesMs,
                               long allTestsRunTimeMs, long captureOverheadMs,
                               long fixedOverheadMs, boolean runAllTests) {
        this.testsToRun = testsToRun;
        this.testsToIgnore = testsToIgnore;
        this.libraryImpactDrainResult = libraryImpactDrainResult;
        this.estimatedRunTimeMs = estimatedRunTimeMs;
        this.selectedTestsWithoutStats = selectedTestsWithoutStats;
        this.medianRunTimeMsAppliedToMissing = medianRunTimeMsAppliedToMissing;
        this.selectedTestRunTimesMs = selectedTestRunTimesMs;
        this.allTestsRunTimeMs = allTestsRunTimeMs;
        this.captureOverheadMs = captureOverheadMs;
        this.fixedOverheadMs = fixedOverheadMs;
        this.runAllTests = runAllTests;
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

    /**
     * @return the Tia-level all-tests-run baseline (ms): the recorded average time to run the
     *         full suite. {@code 0} when no full-suite run has been recorded yet, in which case
     *         the savings figures cannot be computed
     */
    public long getAllTestsRunTimeMs() {
        return allTestsRunTimeMs;
    }

    /**
     * @return the coverage-collection time (ms) the selected suites would add between them.
     *         Per-suite, so it scales with the selection and is what a distributed build divides
     *         across its groups. Callers add this to {@link #getEstimatedRunTimeMs()} only when the
     *         run being estimated collects coverage; {@code 0} when there is nothing to derive it
     *         from
     */
    public long getCaptureOverheadMs() {
        return captureOverheadMs;
    }

    /**
     * @return the time (ms) a test JVM pays once however few suites it runs. Charged once per
     *         runner, so a distributed build pays a copy per group rather than dividing it - which
     *         is why it is carried apart from {@link #getCaptureOverheadMs()}. Added only for a
     *         coverage run; {@code 0} for an empty selection, and until a distributed build has
     *         measured it
     */
    public long getFixedOverheadMs() {
        return fixedOverheadMs;
    }

    /**
     * @return {@code true} only when no mapping is stored yet for the tracked branch and every
     *         test had to be selected because Tia has nothing to select against; {@code false}
     *         for a normal selection. An empty {@link #getTestsToRun()} means "nothing impacted"
     *         only when this is {@code false} - callers that treat an empty {@code testsToRun} as
     *         "run nothing" must check this flag first, since a {@code true} value means the
     *         opposite: everything must run
     */
    public boolean isRunAllTests() {
        return runAllTests;
    }

    /**
     * Compare two results by the selection decision they carry: the suites to run, the suites to
     * ignore, and {@link #isRunAllTests()}.
     *
     * <p>{@code runAllTests} is part of the comparison because without it the two opposite
     * instructions this class exists to tell apart compare equal: a seed run ("run everything")
     * and a selection that found nothing impacted ("run nothing") both carry an empty
     * {@code testsToRun} and an empty {@code testsToIgnore}, and differ only in this flag. The
     * remaining fields are estimates and diagnostics derived from the selection rather than part
     * of it, so they are deliberately left out.
     *
     * @param o the object to compare against
     * @return true when the other object is a {@link TestSelectorResult} carrying the same
     *         selection decision
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestSelectorResult that = (TestSelectorResult) o;
        return runAllTests == that.runAllTests && Objects.equals(testsToRun, that.testsToRun)
                && Objects.equals(testsToIgnore, that.testsToIgnore);
    }

    /**
     * Hash the same three fields {@link #equals(Object)} compares, so the two empty-list cases
     * {@code runAllTests} distinguishes do not collide in a hash-based collection either.
     *
     * @return the hash of the selection decision this result carries
     */
    @Override
    public int hashCode() {
        return Objects.hash(testsToRun, testsToIgnore, runAllTests);
    }
}
