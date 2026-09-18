package org.tiatesting.core.report;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestRunTrigger;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link TestRunHistoryDetailConsoleFormatter#format(TestRunHistoryEntry, List, String)}
 * and {@link TestRunHistoryDetailConsoleFormatter#notFound(String, String)}. Covers the populated
 * case (non-null counters plus source-method and static-rule triggers, ranked by count descending),
 * the empty/unrecorded case (null counters, no triggers), and the not-found message.
 */
class TestRunHistoryDetailConsoleFormatterTest {

    private static final String LF = "\n";

    /**
     * A fully-populated entry should render its branch and commit, every scalar counter label, both
     * source-method trigger names and the static-rule trigger name, all the trigger counts, and the
     * higher-count source-method trigger ranked ahead of the lower-count one.
     */
    @Test
    void format_populatedEntry_rendersSummaryCountersAndRankedTriggers() {
        // given
        TestRunTrigger highCountMethod = new TestRunTrigger(
                TestRunTrigger.Type.SOURCE_METHOD, "com.example.Foo#bar", 12);
        TestRunTrigger lowCountMethod = new TestRunTrigger(
                TestRunTrigger.Type.SOURCE_METHOD, "com.example.Baz#qux", 3);
        TestRunTrigger staticRule = new TestRunTrigger(
                TestRunTrigger.Type.STATIC_RULE, "always-run-smoke-tests", 5);
        // Deliberately supplied out of rank order, so a correct implementation must sort rather
        // than trust the caller's ordering.
        List<TestRunTrigger> triggers = Arrays.asList(lowCountMethod, staticRule, highCountMethod);

        TestRunSelectionDetails details = new TestRunSelectionDetails(triggers, 2, 1, 4, 0, 6);
        TestRunHistoryEntry entry = TestRunHistoryEntry.create("main", "abc123def456",
                1_700_000_000_000L, 42, 3, 1, 83_000L, true, 45_000L, 54,
                RunOrigin.of("local", "host"), details);

        // when
        String output = TestRunHistoryDetailConsoleFormatter.format(entry, triggers, LF);

        // then
        assertTrue(output.contains("main"), "expected branch in output:\n" + output);
        assertTrue(output.contains("abc123def456"), "expected commit in output:\n" + output);
        assertTrue(output.contains("Modified test files"), output);
        assertTrue(output.contains("New test files"), output);
        assertTrue(output.contains("Previously-failed"), output);
        assertTrue(output.contains("Unsealed-mapping"), output);
        assertTrue(output.contains("Pending library"), output);
        assertTrue(output.contains("com.example.Foo#bar"), output);
        assertTrue(output.contains("com.example.Baz#qux"), output);
        assertTrue(output.contains("always-run-smoke-tests"), output);
        assertTrue(output.contains("12"), "expected the high trigger count in output:\n" + output);
        assertTrue(output.contains("3"), "expected the low trigger count in output:\n" + output);
        assertTrue(output.contains("5"), "expected the static-rule count in output:\n" + output);

        int highIndex = output.indexOf("com.example.Foo#bar");
        int lowIndex = output.indexOf("com.example.Baz#qux");
        assertTrue(highIndex >= 0 && lowIndex >= 0, "both trigger names should appear:\n" + output);
        assertTrue(highIndex < lowIndex,
                "the higher-count method trigger should rank ahead of the lower-count one:\n" + output);
    }

    /**
     * An entry with no recorded selection breakdown - null counters, no triggers - should render a
     * dash for every scalar counter and a "(none)" line in place of each trigger section's rows,
     * rather than a misleading zero or an empty section.
     */
    @Test
    void format_emptyTriggersAndNullCounters_rendersDashesAndNoneSections() {
        // given
        TestRunHistoryEntry entry = TestRunHistoryEntry.create("main", "abc123def456",
                1_700_000_000_000L, 10, 0, 0, 5_000L, true, 0L, 0,
                RunOrigin.of("local", "host"), null);
        List<TestRunTrigger> triggers = Collections.emptyList();

        // when
        String output = TestRunHistoryDetailConsoleFormatter.format(entry, triggers, LF);

        // then
        String[] lines = output.split(LF, -1);
        boolean sawDashedCounterLine = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Modified test files:") || trimmed.startsWith("New test files:")
                    || trimmed.startsWith("Previously-failed:") || trimmed.startsWith("Unsealed-mapping:")
                    || trimmed.startsWith("Pending library:")) {
                assertTrue(trimmed.endsWith("-"), "expected a dash for an unrecorded counter: " + line);
                sawDashedCounterLine = true;
            }
        }
        assertTrue(sawDashedCounterLine, "expected at least one counter line in output:\n" + output);

        int noneCount = 0;
        for (String line : lines) {
            if (line.trim().equals("(none)")) {
                noneCount++;
            }
        }
        assertEquals(2, noneCount,
                "both the source-method and static-rule sections should render '(none)':\n" + output);
    }

    /**
     * The not-found message should read clearly and name the id that could not be located, so a CLI
     * user who mistypes or copies a stale id gets an actionable message rather than a blank result.
     */
    @Test
    void notFound_namesTheMissingIdInAClearMessage() {
        // given
        String missingId = "abc";

        // when
        String message = TestRunHistoryDetailConsoleFormatter.notFound(missingId, System.lineSeparator());

        // then
        assertTrue(message.contains(missingId), "expected the missing id in the message: " + message);
        assertTrue(message.toLowerCase().contains("no test run found"),
                "expected a clear not-found message: " + message);
    }
}
