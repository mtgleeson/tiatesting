package org.tiatesting.core.testrunner;

import org.tiatesting.core.model.TestRunContribution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One test JVM's running totals for its run, kept separately for first-attempt and rerun plans.
 * Lives for the life of the JVM - in the JUnit 5 listener's shared JVM data, or the Spock listener -
 * and is restated to the datastore on every persist as {@link TestRunContribution} rows. Thread
 * safe: plans are sequential, but the listener's callbacks may run on engine threads.
 */
public final class TestJvmContribution {

    private final String jvmId;
    private final Totals firstAttempt = new Totals();
    private final Totals reruns = new Totals();
    private long suitesTimeMs;

    /**
     * Start this JVM's totals under a new random JVM id.
     */
    public TestJvmContribution() {
        this(UUID.randomUUID().toString());
    }

    /**
     * Start this JVM's totals under a given id. Package-private for tests.
     *
     * @param jvmId the JVM id
     */
    TestJvmContribution(final String jvmId) {
        this.jvmId = jvmId;
    }

    /** @return this JVM's id */
    public String getJvmId() { return jvmId; }

    /**
     * @return true once this JVM has recorded a test plan
     */
    public synchronized boolean hasRecordedPlans() {
        return firstAttempt.used || reruns.used;
    }

    /**
     * The suites whose latest outcome in this JVM failed, across both attempt kinds: first-attempt
     * failures, minus every suite a rerun exercised, plus the reruns' own failures.
     *
     * @return a new set of failed suite names
     */
    public synchronized Set<String> failedSuites() {
        Set<String> failed = new LinkedHashSet<>(firstAttempt.failed);
        failed.removeAll(reruns.exercised);
        failed.addAll(reruns.failed);
        return failed;
    }

    /**
     * Add a finished test plan to the totals of its attempt kind.
     *
     * @param rerunPlan whether core resolved the plan as a rerun of failed tests
     * @param plan what the plan did
     * @param planEndMs the plan's end time, epoch millis
     * @param suitesTimeMs the summed measured run time of every suite this JVM has timed so far;
     *                     cumulative, so the larger of it and the stored value is kept
     */
    public synchronized void record(final boolean rerunPlan, final TestPlanOutcome plan,
                                    final long planEndMs, final long suitesTimeMs) {
        (rerunPlan ? reruns : firstAttempt).add(plan, planEndMs);
        this.suitesTimeMs = Math.max(this.suitesTimeMs, suitesTimeMs);
    }

    /**
     * Render the totals as the rows this JVM restates on a persist: one per attempt kind it has run.
     *
     * @param runId the run the JVM contributes to
     * @param groupNumber the distributed group the JVM runs, or null on a single-host run
     * @return the rows, first attempt first; empty before any plan was recorded
     */
    public synchronized List<TestRunContribution> snapshot(final String runId, final Integer groupNumber) {
        List<TestRunContribution> rows = new ArrayList<>();
        if (firstAttempt.used) {
            rows.add(new TestRunContribution(runId, jvmId, false, groupNumber, firstAttempt.ran.size(),
                    firstAttempt.failed, Collections.<String>emptySet(), firstAttempt.testTimeMs,
                    suitesTimeMs, firstAttempt.firstStartMs, firstAttempt.lastEndMs));
        }
        if (reruns.used) {
            rows.add(new TestRunContribution(runId, jvmId, true, groupNumber, reruns.ran.size(),
                    reruns.failed, reruns.exercised, reruns.testTimeMs, 0L, reruns.firstStartMs,
                    reruns.lastEndMs));
        }
        return rows;
    }

    /**
     * The running totals of one attempt kind. Guarded by the enclosing instance's monitor.
     */
    private static final class Totals {
        private final Set<String> ran = new LinkedHashSet<>();
        private final Set<String> failed = new LinkedHashSet<>();
        private final Set<String> exercised = new LinkedHashSet<>();
        private long testTimeMs;
        private long firstStartMs = Long.MAX_VALUE;
        private long lastEndMs = Long.MIN_VALUE;
        private boolean used;

        /**
         * Add one plan: its suites, its latest-outcome failures and its time.
         *
         * @param plan what the plan did
         * @param planEndMs the plan's end time
         */
        private void add(final TestPlanOutcome plan, final long planEndMs) {
            used = true;
            ran.addAll(plan.getSuitesRan());
            exercised.addAll(plan.getSuitesExercised());
            failed.removeAll(plan.getSuitesExercised());
            failed.addAll(plan.getSuitesFailed());
            testTimeMs += Math.max(0L, planEndMs - plan.getStartMs());
            firstStartMs = Math.min(firstStartMs, plan.getStartMs());
            lastEndMs = Math.max(lastEndMs, planEndMs);
        }
    }
}
