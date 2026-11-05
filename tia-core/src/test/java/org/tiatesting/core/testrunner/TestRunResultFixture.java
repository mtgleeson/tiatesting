package org.tiatesting.core.testrunner;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Builds the run identity and plan outcomes the {@link TestRunResult} constructor takes, for tests
 * that only care about counts.
 */
public final class TestRunResultFixture {

    /** A run id a test can share across several results, so their contributions add up to one run. */
    public static final String RUN_ID = "fixture-run";

    private TestRunResultFixture() {
    }

    /**
     * A new run for every call, so independent persists in one test stay independent runs - as they
     * were when every persist wrote its own history row. A test that means several plans of one run
     * uses {@link #runIdentity(String)} instead.
     *
     * @return a new run identity
     */
    public static TestRunIdentity runIdentity() {
        return TestRunIdentity.newRun(1000L);
    }

    /**
     * A run shared by every result built with the same id.
     *
     * @param runId the run id
     * @return the identity
     */
    public static TestRunIdentity runIdentity(final String runId) {
        return new TestRunIdentity(runId, 1000L);
    }

    /**
     * Build a plan outcome that exercised the suites it ran.
     *
     * @param ran the suites the plan finished
     * @param failed the suites that failed
     * @param startMs the plan start
     * @return the outcome
     */
    public static TestPlanOutcome planOutcome(final Set<String> ran, final Set<String> failed, final long startMs) {
        return new TestPlanOutcome(ran, failed, ran, Collections.<String>emptySet(), startMs);
    }

    /**
     * Build a plan outcome from counts, naming the suites {@code fixture-suite-N}; the first
     * {@code failed} of them fail.
     *
     * @param ran how many suites the plan finished
     * @param failed how many of them failed
     * @param startMs the plan start
     * @return the outcome
     */
    public static TestPlanOutcome syntheticPlanOutcome(final int ran, final int failed, final long startMs) {
        Set<String> ranSuites = new LinkedHashSet<>();
        Set<String> failedSuites = new LinkedHashSet<>();
        for (int i = 0; i < ran; i++) {
            ranSuites.add("fixture-suite-" + i);
            if (i < failed) {
                failedSuites.add("fixture-suite-" + i);
            }
        }
        return planOutcome(ranSuites, failedSuites, startMs);
    }
}
