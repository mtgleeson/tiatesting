package org.tiatesting.core.testrunner;

/**
 * Which attempt at a test task's run a persist describes. A build tool can retry failed tests after
 * the first run, and each retry persists on its own; the attempt tells the persist whether it is
 * looking at the real run or at a retry of part of it.
 *
 * <p>The two retry kinds differ in what the persisting JVM already knows. A Surefire rerun happens
 * in the same JVM as the first attempt, so the listener's trackers still carry the first attempt's
 * coverage and suites. A Gradle test-retry round is a fresh JVM that knows only what it ran itself.
 * See the "Failed-suite tracking" chapter in {@code WIKI.md}.
 */
public enum RunAttempt {

    /** The first attempt: the real run of the test task. */
    FIRST,

    /** A retry of failed tests in the same JVM as the first attempt (Surefire reruns). */
    RERUN_SAME_JVM,

    /** A retry of failed tests in a fresh JVM (a Gradle test-retry round). */
    RERUN_NEW_JVM;

    /**
     * @return true for either kind of retry, false for the first attempt
     */
    public boolean isRerun() {
        return this != FIRST;
    }
}
