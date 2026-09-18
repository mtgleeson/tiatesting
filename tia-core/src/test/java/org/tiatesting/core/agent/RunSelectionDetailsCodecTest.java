package org.tiatesting.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestRunTrigger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip and defensive-parsing tests for {@link RunSelectionDetailsCodec}, the sidecar file
 * format that carries a {@link TestRunSelectionDetails} breakdown from the build JVM (where
 * selection runs) to the forked test JVM (where the history row is written).
 */
class RunSelectionDetailsCodecTest {

    @TempDir
    Path tempDir;

    /**
     * Verify that writing a breakdown with two triggers - one of each {@link TestRunTrigger.Type}
     * - and non-zero counters, then reading it back, yields an equal set of counters and triggers.
     * This is the codec's core contract: what goes in via {@code write} must come back out via
     * {@code read}.
     */
    @Test
    void roundTripsDetailsWithTwoTriggersAndNonZeroCounters() {
        // given
        List<TestRunTrigger> triggers = Arrays.asList(
                new TestRunTrigger(TestRunTrigger.Type.SOURCE_METHOD, "com.example.Foo#bar", 3),
                new TestRunTrigger(TestRunTrigger.Type.STATIC_RULE, "forced-selection-rule", 7)
        );
        TestRunSelectionDetails details = new TestRunSelectionDetails(triggers, 1, 2, 3, 4, 5);
        File file = tempDir.resolve("selection-details.txt").toFile();

        // when
        RunSelectionDetailsCodec.write(details, file);
        TestRunSelectionDetails result = RunSelectionDetailsCodec.read(file);

        // then
        assertEquals(1, result.getNumModifiedTestFiles());
        assertEquals(2, result.getNumNewTestFiles());
        assertEquals(3, result.getNumPreviouslyFailed());
        assertEquals(4, result.getNumUnsealedMapping());
        assertEquals(5, result.getNumPendingLibrary());
        assertEquals(2, result.getTriggers().size());
        TestRunTrigger sourceMethod = result.getTriggers().get(0);
        assertEquals(TestRunTrigger.Type.SOURCE_METHOD, sourceMethod.getType());
        assertEquals("com.example.Foo#bar", sourceMethod.getName());
        assertEquals(3, sourceMethod.getTestCount());
        TestRunTrigger staticRule = result.getTriggers().get(1);
        assertEquals(TestRunTrigger.Type.STATIC_RULE, staticRule.getType());
        assertEquals("forced-selection-rule", staticRule.getName());
        assertEquals(7, staticRule.getTestCount());
    }

    /**
     * Verify that a newline embedded in a trigger name (reachable via a user-authored static rule
     * name) is neutralised to a space on write, so it cannot split one trigger record into two and
     * corrupt the parse. The name reads back with the newline replaced by a space, and exactly one
     * trigger survives the round trip.
     */
    @Test
    void neutralizesNewlineInTriggerNameOnWrite() {
        // given
        TestRunTrigger triggerWithNewline = new TestRunTrigger(
                TestRunTrigger.Type.STATIC_RULE, "line-one\nline-two", 4);
        TestRunSelectionDetails details = new TestRunSelectionDetails(
                Arrays.asList(triggerWithNewline), 0, 0, 0, 0, 0);
        File file = tempDir.resolve("selection-details-newline.txt").toFile();

        // when
        RunSelectionDetailsCodec.write(details, file);
        TestRunSelectionDetails result = RunSelectionDetailsCodec.read(file);

        // then
        assertEquals(1, result.getTriggers().size());
        assertEquals("line-one line-two", result.getTriggers().get(0).getName());
        assertEquals(4, result.getTriggers().get(0).getTestCount());
    }

    /**
     * Verify that reading a sidecar file that does not exist returns {@link
     * TestRunSelectionDetails#empty()} - no triggers, all counters zero - rather than throwing.
     * This is the case where selection produced no breakdown to carry, such as an all-tests run
     * where no sidecar file was ever written.
     */
    @Test
    void readOfMissingFileReturnsEmpty() {
        // given a file path that was never written to
        File file = tempDir.resolve("does-not-exist.txt").toFile();

        // when
        TestRunSelectionDetails result = RunSelectionDetailsCodec.read(file);

        // then
        assertEquals(0, result.getTriggers().size());
        assertEquals(0, result.getNumModifiedTestFiles());
        assertEquals(0, result.getNumNewTestFiles());
        assertEquals(0, result.getNumPreviouslyFailed());
        assertEquals(0, result.getNumUnsealedMapping());
        assertEquals(0, result.getNumPendingLibrary());
    }

    /**
     * Verify that reading a blank (zero-byte) sidecar file also returns {@link
     * TestRunSelectionDetails#empty()} rather than failing to parse the missing counters line.
     */
    @Test
    void readOfBlankFileReturnsEmpty() throws IOException {
        // given an existing but empty file
        File file = tempDir.resolve("blank.txt").toFile();
        Files.write(file.toPath(), new byte[0]);

        // when
        TestRunSelectionDetails result = RunSelectionDetailsCodec.read(file);

        // then
        assertEquals(0, result.getTriggers().size());
        assertEquals(0, result.getNumModifiedTestFiles());
    }

    /**
     * Verify that a trigger name containing characters other than tab/newline - a dot, a slash, and
     * parentheses, as in a typical fully-qualified method signature - round-trips intact, since the
     * name field is last on its line and only tabs within it are normalized.
     */
    @Test
    void triggerNameWithDotsSlashesAndParensRoundTripsIntact() {
        // given
        String name = "org/example/Foo.java#method(int,java.lang.String)";
        List<TestRunTrigger> triggers = Arrays.asList(
                new TestRunTrigger(TestRunTrigger.Type.SOURCE_METHOD, name, 1)
        );
        TestRunSelectionDetails details = new TestRunSelectionDetails(triggers, 0, 0, 0, 0, 0);
        File file = tempDir.resolve("names.txt").toFile();

        // when
        RunSelectionDetailsCodec.write(details, file);
        TestRunSelectionDetails result = RunSelectionDetailsCodec.read(file);

        // then
        assertEquals(1, result.getTriggers().size());
        assertEquals(name, result.getTriggers().get(0).getName());
    }

    /**
     * Verify that a malformed trigger line - here, a non-integer count - is skipped rather than
     * failing the whole parse, and that the valid records on either side of it still come back.
     * This defends the forked-JVM read path against a partially-corrupted sidecar file.
     */
    @Test
    void malformedLineIsSkippedButValidRecordsStillReturned() throws IOException {
        // given a hand-written file with counters, a valid trigger, a malformed trigger, and
        // another valid trigger
        File file = tempDir.resolve("malformed.txt").toFile();
        String content = "counters\t1\t2\t3\t4\t5\n"
                + "trigger\tSOURCE_METHOD\t9\tvalid.One\n"
                + "trigger\tSOURCE_METHOD\tnotAnInt\tbroken\n"
                + "trigger\tSTATIC_RULE\t11\tvalid-two\n";
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));

        // when
        TestRunSelectionDetails result = RunSelectionDetailsCodec.read(file);

        // then
        assertEquals(1, result.getNumModifiedTestFiles());
        assertEquals(2, result.getNumNewTestFiles());
        assertEquals(3, result.getNumPreviouslyFailed());
        assertEquals(4, result.getNumUnsealedMapping());
        assertEquals(5, result.getNumPendingLibrary());
        assertEquals(2, result.getTriggers().size());
        assertEquals("valid.One", result.getTriggers().get(0).getName());
        assertEquals("valid-two", result.getTriggers().get(1).getName());
        assertTrue(result.getTriggers().get(1).getType() == TestRunTrigger.Type.STATIC_RULE);
    }
}
