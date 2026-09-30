package org.tiatesting.core.testrunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the test JVM counter a Gradle test task resets once per execution and each test JVM
 * increments, which is how a Gradle test-retry round - a fresh JVM - knows it is not the real run.
 */
class TestJvmSequenceTest {

    private String savedProperty;

    /**
     * Save and clear the counter property and the JVM's cached attempt, so each test resolves from
     * scratch.
     */
    @BeforeEach
    void setUp() {
        savedProperty = System.getProperty(TestJvmSequence.PROP_TEST_JVM_SEQUENCE_FILE);
        System.clearProperty(TestJvmSequence.PROP_TEST_JVM_SEQUENCE_FILE);
        TestJvmSequence.clearCachedAttempt();
    }

    /**
     * Restore the counter property and clear the cached attempt.
     */
    @AfterEach
    void tearDown() {
        if (savedProperty == null) {
            System.clearProperty(TestJvmSequence.PROP_TEST_JVM_SEQUENCE_FILE);
        } else {
            System.setProperty(TestJvmSequence.PROP_TEST_JVM_SEQUENCE_FILE, savedProperty);
        }
        TestJvmSequence.clearCachedAttempt();
    }

    /**
     * Verifies a reset counter numbers the task's test JVMs 1, 2, 3.
     *
     * @param dir a temp directory for the counter file
     * @throws Exception if the counter file cannot be read or written
     */
    @Test
    void increment_afterReset_countsFromOne(@TempDir File dir) throws Exception {
        // given
        File file = new File(dir, TestJvmSequence.FILE_NAME);
        TestJvmSequence.reset(file);

        // when
        int first = TestJvmSequence.increment(file);
        int second = TestJvmSequence.increment(file);
        int third = TestJvmSequence.increment(file);

        // then
        assertEquals(1, first);
        assertEquals(2, second);
        assertEquals(3, third);
    }

    /**
     * Verifies a reset starts a new task execution's count again, so the previous execution's
     * retry rounds do not make the next real run read as a retry.
     *
     * @param dir a temp directory for the counter file
     * @throws Exception if the counter file cannot be read or written
     */
    @Test
    void reset_startsANewExecutionFromZero(@TempDir File dir) throws Exception {
        // given
        File file = new File(dir, "nested/" + TestJvmSequence.FILE_NAME);
        TestJvmSequence.reset(file);
        TestJvmSequence.increment(file);
        TestJvmSequence.increment(file);

        // when
        TestJvmSequence.reset(file);

        // then
        assertEquals("0", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    /**
     * Verifies the first JVM of a task execution is the first attempt, and resolving again in the
     * same JVM neither re-increments the file nor changes the answer.
     *
     * @param dir a temp directory for the counter file
     * @throws Exception if the counter file cannot be read
     */
    @Test
    void attempt_firstJvm_isFirstAndResolvedOnce(@TempDir File dir) throws Exception {
        // given
        File file = new File(dir, TestJvmSequence.FILE_NAME);
        TestJvmSequence.reset(file);
        System.setProperty(TestJvmSequence.PROP_TEST_JVM_SEQUENCE_FILE, file.getAbsolutePath());

        // when
        RunAttempt first = TestJvmSequence.attemptFromSystemProperties();
        RunAttempt again = TestJvmSequence.attemptFromSystemProperties();

        // then
        assertEquals(RunAttempt.FIRST, first);
        assertEquals(RunAttempt.FIRST, again);
        assertEquals("1", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    /**
     * Verifies a later JVM of the same task execution - a test-retry round - is a fresh-JVM rerun.
     *
     * @param dir a temp directory for the counter file
     */
    @Test
    void attempt_laterJvm_isAFreshJvmRerun(@TempDir File dir) {
        // given
        File file = new File(dir, TestJvmSequence.FILE_NAME);
        TestJvmSequence.reset(file);
        System.setProperty(TestJvmSequence.PROP_TEST_JVM_SEQUENCE_FILE, file.getAbsolutePath());
        TestJvmSequence.attemptFromSystemProperties();
        TestJvmSequence.clearCachedAttempt();

        // when
        RunAttempt retry = TestJvmSequence.attemptFromSystemProperties();

        // then
        assertEquals(RunAttempt.RERUN_NEW_JVM, retry);
    }

    /**
     * Verifies a JVM given no counter - Maven, or a build without the Tia Gradle plugin - is the
     * first attempt.
     */
    @Test
    void attempt_noCounterProperty_isFirst() {
        // given / when
        RunAttempt attempt = TestJvmSequence.attemptFromSystemProperties();

        // then
        assertEquals(RunAttempt.FIRST, attempt);
    }

    /**
     * Verifies a counter that cannot be used - here a directory - falls back to the first attempt
     * rather than failing the test JVM.
     *
     * @param dir a temp directory standing in for an unusable counter file
     */
    @Test
    void attempt_unusableCounter_fallsBackToFirst(@TempDir File dir) {
        // given
        System.setProperty(TestJvmSequence.PROP_TEST_JVM_SEQUENCE_FILE, dir.getAbsolutePath());

        // when
        RunAttempt attempt = TestJvmSequence.attemptFromSystemProperties();

        // then
        assertEquals(RunAttempt.FIRST, attempt);
    }
}
