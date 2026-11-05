package org.tiatesting.core.testrunner;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What one test plan in a test JVM did: the suites it finished, the suites that failed in it, the
 * suites it exercised (ran at least one test of, or recorded a failure for) and the suites it
 * skipped. The exercised set is what the rerun rule reads: an enclosing class whose own tests did
 * not run in a rerun of one of its {@code @Nested} classes finishes as a container but is not
 * exercised. See {@link RunAttemptResolver}.
 */
public final class TestPlanOutcome {

    private final Set<String> suitesRan;
    private final Set<String> suitesFailed;
    private final Set<String> suitesExercised;
    private final Set<String> suitesSkipped;
    private final long startMs;

    /**
     * Snapshot a plan's sets; the inputs may be live concurrent sets.
     *
     * @param suitesRan the suites the plan finished
     * @param suitesFailed the suites that failed in the plan
     * @param suitesExercised the suites the plan ran a test of; the failed suites are added to it
     * @param suitesSkipped the suites the plan skipped
     * @param startMs the plan's start time, epoch millis
     */
    public TestPlanOutcome(final Set<String> suitesRan, final Set<String> suitesFailed,
                           final Set<String> suitesExercised, final Set<String> suitesSkipped,
                           final long startMs) {
        this.suitesRan = Collections.unmodifiableSet(new LinkedHashSet<>(suitesRan));
        this.suitesFailed = Collections.unmodifiableSet(new LinkedHashSet<>(suitesFailed));
        Set<String> exercised = new LinkedHashSet<>(suitesExercised);
        exercised.addAll(suitesFailed);
        this.suitesExercised = Collections.unmodifiableSet(exercised);
        this.suitesSkipped = Collections.unmodifiableSet(new LinkedHashSet<>(suitesSkipped));
        this.startMs = startMs;
    }

    /** @return the suites the plan finished */
    public Set<String> getSuitesRan() { return suitesRan; }

    /** @return the suites that failed in the plan */
    public Set<String> getSuitesFailed() { return suitesFailed; }

    /** @return the suites the plan ran a test of or recorded a failure for */
    public Set<String> getSuitesExercised() { return suitesExercised; }

    /** @return the suites the plan skipped */
    public Set<String> getSuitesSkipped() { return suitesSkipped; }

    /** @return the plan's start time, epoch millis */
    public long getStartMs() { return startMs; }
}
