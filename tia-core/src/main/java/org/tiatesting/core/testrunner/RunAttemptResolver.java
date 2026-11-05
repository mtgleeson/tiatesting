package org.tiatesting.core.testrunner;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Decides which attempt a test plan is. A plan is a <b>rerun</b> when it exercised at least one
 * suite and every suite it exercised had already failed earlier in the run: in a first attempt each
 * suite runs exactly once (Surefire hands each class to one fork, Gradle each class to one JVM, the
 * planner each suite to one group), so only a retry of failures can meet the rule. A rerun is
 * {@link RunAttempt#RERUN_SAME_JVM} when this JVM's own earlier plans recorded those failures (a
 * Surefire rerun), otherwise {@link RunAttempt#RERUN_NEW_JVM} (a Gradle test-retry JVM). See the
 * "Failed-suite tracking" chapter in {@code WIKI.md}.
 *
 * <p>The other JVMs' failures are read only for a JVM's first plan: a Gradle retry JVM is a fresh
 * JVM, and a JVM that already recorded a plan is a fork whose later plans are either its own
 * reruns or further first-attempt classes.
 */
public final class RunAttemptResolver {

    private RunAttemptResolver() {
    }

    /**
     * Resolve a plan's attempt. Must be called before the plan is recorded into {@code jvm}.
     *
     * @param plan what the plan did
     * @param jvm this JVM's totals before the plan
     * @param failedInOtherJvms reads the suites other JVMs of the run recorded as failed; called at
     *                          most once, and only for this JVM's first plan
     * @return the plan's attempt
     */
    public static RunAttempt resolve(final TestPlanOutcome plan, final TestJvmContribution jvm,
                                     final Supplier<Set<String>> failedInOtherJvms) {
        Set<String> exercised = plan.getSuitesExercised();
        if (exercised.isEmpty()) {
            return RunAttempt.FIRST;
        }
        if (jvm.failedSuites().containsAll(exercised)) {
            return RunAttempt.RERUN_SAME_JVM;
        }
        if (jvm.hasRecordedPlans()) {
            return RunAttempt.FIRST;
        }
        return failedInOtherJvms.get().containsAll(exercised) ? RunAttempt.RERUN_NEW_JVM : RunAttempt.FIRST;
    }
}
