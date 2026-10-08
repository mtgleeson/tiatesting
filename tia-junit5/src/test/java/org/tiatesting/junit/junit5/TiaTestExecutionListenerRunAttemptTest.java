package org.tiatesting.junit.junit5;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.testrunner.RunAttempt;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers how the listener decides which attempt a test plan is: the plan number within the JVM
 * tells a Surefire rerun from the real run, and the Gradle test JVM counter marks every plan in a
 * test-retry round's fresh JVM as a retry.
 */
class TiaTestExecutionListenerRunAttemptTest {

    /**
     * The first test plan in a JVM the counter calls the first is the real run.
     */
    @Test
    void firstPlanInFirstJvmIsTheFirstAttempt() {
        // given
        int testPlanNumber = 1;
        RunAttempt jvmAttempt = RunAttempt.FIRST;

        // when
        RunAttempt attempt = TiaTestExecutionListener.resolveRunAttempt(testPlanNumber, jvmAttempt);

        // then
        assertEquals(RunAttempt.FIRST, attempt);
    }

    /**
     * A later test plan in the same JVM is a Surefire rerun.
     */
    @Test
    void laterPlanInFirstJvmIsASameJvmRerun() {
        // given
        int testPlanNumber = 2;
        RunAttempt jvmAttempt = RunAttempt.FIRST;

        // when
        RunAttempt attempt = TiaTestExecutionListener.resolveRunAttempt(testPlanNumber, jvmAttempt);

        // then
        assertEquals(RunAttempt.RERUN_SAME_JVM, attempt);
    }

    /**
     * A Gradle test-retry round's JVM starts at plan 1 like the real run, but the counter marks it a
     * retry, so it must not be treated as the first attempt and overwrite the real run.
     */
    @Test
    void firstPlanInRetryJvmIsANewJvmRerun() {
        // given
        int testPlanNumber = 1;
        RunAttempt jvmAttempt = RunAttempt.RERUN_NEW_JVM;

        // when
        RunAttempt attempt = TiaTestExecutionListener.resolveRunAttempt(testPlanNumber, jvmAttempt);

        // then
        assertEquals(RunAttempt.RERUN_NEW_JVM, attempt);
    }

    /**
     * Every plan in a retry round's JVM stays a new-JVM retry, so the persist keeps treating it as
     * knowing only what it ran itself.
     */
    @Test
    void laterPlanInRetryJvmStaysANewJvmRerun() {
        // given
        int testPlanNumber = 2;
        RunAttempt jvmAttempt = RunAttempt.RERUN_NEW_JVM;

        // when
        RunAttempt attempt = TiaTestExecutionListener.resolveRunAttempt(testPlanNumber, jvmAttempt);

        // then
        assertEquals(RunAttempt.RERUN_NEW_JVM, attempt);
    }
}
