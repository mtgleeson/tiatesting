package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Covers the one rule that tells a retry of failed tests from a first attempt: a plan that ran at
 * least one suite, every one of which had already failed earlier in the run.
 */
class RunAttemptResolverTest {

    private static Set<String> names(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    private static TestPlanOutcome plan(String... exercised) {
        return new TestPlanOutcome(names(exercised), names(), names(exercised), names(), 0L);
    }

    private static Supplier<Set<String>> others(String... failed) {
        return () -> names(failed);
    }

    @Test
    void aSurefireRerunInTheSameJvmIsASameJvmRerun() {
        // given a JVM whose first plan failed suite A
        TestJvmContribution jvm = new TestJvmContribution("jvm-a");
        jvm.record(false, new TestPlanOutcome(names("A", "B"), names("A"), names("A", "B"), names(), 0L), 10L, 0L);

        // when
        RunAttempt attempt = RunAttemptResolver.resolve(plan("A"), jvm, others());

        // then
        assertEquals(RunAttempt.RERUN_SAME_JVM, attempt);
    }

    @Test
    void aGradleRetryJvmIsANewJvmRerun() {
        // given a fresh JVM, and an earlier JVM of the run that failed A
        TestJvmContribution jvm = new TestJvmContribution("jvm-r");

        // when
        RunAttempt attempt = RunAttemptResolver.resolve(plan("A"), jvm, others("A"));

        // then
        assertEquals(RunAttempt.RERUN_NEW_JVM, attempt);
    }

    @Test
    void anotherClassPlanInAMultiForkJvmIsAFirstAttempt() {
        // given a fork that already ran class A as its first plan
        TestJvmContribution jvm = new TestJvmContribution("jvm-a");
        jvm.record(false, plan("A"), 10L, 0L);

        // when
        RunAttempt attempt = RunAttemptResolver.resolve(plan("B"), jvm, others());

        // then
        assertEquals(RunAttempt.FIRST, attempt);
    }

    @Test
    void theFirstPlanOfAParallelForkIsAFirstAttempt() {
        // given a fresh fork while another fork has recorded a failure in a different suite
        TestJvmContribution jvm = new TestJvmContribution("jvm-b");

        // when
        RunAttempt attempt = RunAttemptResolver.resolve(plan("B"), jvm, others("A"));

        // then
        assertEquals(RunAttempt.FIRST, attempt);
    }

    @Test
    void aPlanThatRanNothingIsAFirstAttemptAndReadsNothing() {
        // given
        TestJvmContribution jvm = new TestJvmContribution("jvm-a");
        final boolean[] read = {false};

        // when
        RunAttempt attempt = RunAttemptResolver.resolve(plan(), jvm, () -> {
            read[0] = true;
            return Collections.emptySet();
        });

        // then
        assertEquals(RunAttempt.FIRST, attempt);
        assertFalse(read[0]);
    }
}
