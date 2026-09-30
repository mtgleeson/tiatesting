package org.tiatesting.core.testrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Numbers the test JVMs one execution of a Gradle test task starts, so a JVM can tell whether it is
 * the task's real run or a Gradle test-retry round.
 *
 * <p>The test-retry plugin re-runs failed tests in rounds inside the same task action, and every
 * round is a fresh JVM with the same system properties, so nothing a JVM is handed says which
 * round it is. The Tia Gradle plugin therefore resets a counter file in the task's {@code doFirst}
 * - which runs once per task execution, before every round - and forwards its path; each test JVM
 * increments it once when Tia starts. The first JVM reads 1 and every later one 2 or more. It relies
 * on one test JVM per round, which the single-fork requirement already guarantees, and on nothing
 * internal to Gradle or the test-retry plugin. See the "Failed-suite tracking" chapter in
 * {@code WIKI.md}.
 *
 * <p>Maven never sets the property: a Surefire rerun happens in the first attempt's JVM, which the
 * listeners detect themselves. With no property a JVM is always the first attempt.
 */
public final class TestJvmSequence {

    /** System property carrying the counter file's path to the test JVM. */
    public static final String PROP_TEST_JVM_SEQUENCE_FILE = "tiaTestJvmSequenceFile";

    /** Name of the counter file inside the test task's temporary directory. */
    public static final String FILE_NAME = "tia-test-jvm-sequence";

    private static final Logger log = LoggerFactory.getLogger(TestJvmSequence.class);

    /*
    This JVM's attempt, resolved on first use. Cached because the file must be incremented once per
    JVM, however many times Tia asks - a second increment would make the real run read as a retry.
     */
    private static RunAttempt attemptForThisJvm;

    private TestJvmSequence() { }

    /**
     * Reset the counter for a new execution of the test task, before any of its test JVMs start.
     *
     * @param file the counter file, created along with its parent directory when missing
     * @throws IllegalStateException if the file cannot be written, since every JVM of the task would
     *                               otherwise be numbered from a previous execution's count
     */
    public static void reset(final File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.write(file.toPath(), "0".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Tia could not reset its test JVM counter at " + file, e);
        }
    }

    /**
     * Resolve which attempt this test JVM is, from the counter file named by
     * {@link #PROP_TEST_JVM_SEQUENCE_FILE}. The first call in a JVM increments the file; every later
     * call returns the same answer.
     *
     * @return {@link RunAttempt#RERUN_NEW_JVM} when an earlier JVM of this task execution already
     *         started, otherwise {@link RunAttempt#FIRST} - including when the property is unset or
     *         the file cannot be read, which is the behaviour before retries were told apart
     */
    public static synchronized RunAttempt attemptFromSystemProperties() {
        if (attemptForThisJvm == null) {
            attemptForThisJvm = resolveAttempt(System.getProperty(PROP_TEST_JVM_SEQUENCE_FILE));
        }
        return attemptForThisJvm;
    }

    /**
     * Forget this JVM's cached attempt, so a test can resolve it again. Exposed for testing.
     */
    static synchronized void clearCachedAttempt() {
        attemptForThisJvm = null;
    }

    /**
     * Increment the counter file and map the new count to an attempt.
     *
     * @param path the counter file's path, or null when none was forwarded
     * @return the attempt the new count means
     */
    private static RunAttempt resolveAttempt(final String path) {
        if (path == null || path.trim().isEmpty()) {
            return RunAttempt.FIRST;
        }
        try {
            int sequence = increment(new File(path));
            log.debug("Tia test JVM {} of this test task execution", sequence);
            return sequence > 1 ? RunAttempt.RERUN_NEW_JVM : RunAttempt.FIRST;
        } catch (IOException | RuntimeException e) {
            log.warn("Tia could not read its test JVM counter at {}; treating this JVM as the first attempt", path, e);
            return RunAttempt.FIRST;
        }
    }

    /**
     * Atomically read, increment and write back the count, under an exclusive file lock.
     *
     * @param file the counter file; a missing or empty one counts as 0
     * @return the incremented count
     * @throws IOException if the file cannot be locked, read or written
     */
    static int increment(final File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw");
             FileChannel channel = raf.getChannel();
             FileLock ignored = channel.lock()) {
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(channel.size(), 64));
            channel.read(buffer, 0);
            String stored = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8).trim();
            int next = (stored.isEmpty() ? 0 : Integer.parseInt(stored)) + 1;
            channel.truncate(0);
            channel.write(ByteBuffer.wrap(String.valueOf(next).getBytes(StandardCharsets.UTF_8)), 0);
            return next;
        }
    }
}
