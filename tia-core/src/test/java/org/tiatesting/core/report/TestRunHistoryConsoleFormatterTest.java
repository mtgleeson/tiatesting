package org.tiatesting.core.report;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestRunHistoryEntry;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link TestRunHistoryConsoleFormatter#formatHistory(List, int, String)}. Covers the
 * empty-history sentinel, header wording for the {@code limit} / total combinations, 8-char
 * commit + id truncation, dynamic column widths, and the duration / mapping cell renderings.
 */
class TestRunHistoryConsoleFormatterTest {

    private static final String LF = "\n";
    private static final DateTimeFormatter LOCAL_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * An empty history list should render the explicit sentinel message and skip the table —
     * matches the CLI requirement that "no history yet" exits cleanly with a friendly message.
     */
    @Test
    void emptyHistory_rendersSentinel() {
        // given
        List<TestRunHistoryEntry> entries = Collections.emptyList();

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(entries, 20, LF);

        // then
        assertEquals("No Tia test run history recorded yet.", output);
    }

    /**
     * Single entry with limit 20 should produce the header line, a blank line, a column header
     * row, a dashed separator, and one data row. The "total of 1" wording reflects the list size.
     */
    @Test
    void singleEntry_rendersHeaderAndOneRow() {
        // given
        TestRunHistoryEntry entry = entry(2026, 5, 15, 9, 30, 42, "main",
                "abc123def4567890abc123def4567890abc123de",
                "550e8400-e29b-41d4-a716-446655440000",
                42, 3, 1, 83_000L, true);

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(
                Collections.singletonList(entry), 20, LF);

        // then
        assertTrue(output.startsWith("Displaying the latest 1 test runs from a total of 1" + LF),
                "header should report rows-shown (not the configured cap) when total < limit. Output: " + output);
        String[] lines = output.split(LF, -1);
        // header, blank, column-header, separator, data row, trailing-empty-from-final-LF
        assertEquals(6, lines.length, "Expected 6 lines (incl. trailing empty), got: " + lines.length);
        assertEquals("", lines[1], "second line should be blank");
        assertTrue(lines[2].startsWith("Date/time"), "column header missing: " + lines[2]);
        assertTrue(lines[3].startsWith("---"), "separator line missing: " + lines[3]);
        assertTrue(lines[4].contains("main"), "data row missing branch: " + lines[4]);
    }

    /**
     * 5 entries with limit 20 should render all 5 — limit is a cap, not a target. The header
     * must report 5 (the count being shown), not the configured cap of 20.
     */
    @Test
    void fewerEntriesThanLimit_rendersAll() {
        // given
        List<TestRunHistoryEntry> entries = sequentialEntries(5);

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(entries, 20, LF);

        // then
        assertTrue(output.contains("Displaying the latest 5 test runs from a total of 5"),
                "header should report rows-shown (not the configured cap). Output:\n" + output);
        assertEquals(5, countDataRows(output));
    }

    /**
     * 25 entries with limit 20 should render exactly 20 rows; the header still reports the
     * full total so the user knows there's more history than fits.
     */
    @Test
    void moreEntriesThanLimit_truncatesToLimit() {
        // given
        List<TestRunHistoryEntry> entries = sequentialEntries(25);

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(entries, 20, LF);

        // then
        assertTrue(output.contains("Displaying the latest 20 test runs from a total of 25"), output);
        assertEquals(20, countDataRows(output));
    }

    /**
     * 25 entries with limit 5 — covers the {@code --last N} narrower-than-default path.
     */
    @Test
    void customLimit_rendersExactlyNRows() {
        // given
        List<TestRunHistoryEntry> entries = sequentialEntries(25);

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(entries, 5, LF);

        // then
        assertTrue(output.contains("Displaying the latest 5 test runs from a total of 25"), output);
        assertEquals(5, countDataRows(output));
    }

    /**
     * 40-char git commits and 36-char UUID ids must render as exactly 8 chars each.
     */
    @Test
    void commitAndId_truncatedToEightChars() {
        // given
        String fortyCharCommit = "abcdef0123456789abcdef0123456789abcdef01";
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        TestRunHistoryEntry entry = entry(2026, 5, 15, 9, 30, 42, "main",
                fortyCharCommit, uuid, 1, 0, 0, 1000L, true);

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(
                Collections.singletonList(entry), 20, LF);

        // then
        assertTrue(output.contains("abcdef01"), "expected first-8 of commit. Output:\n" + output);
        assertFalse(output.contains("abcdef0123"), "commit not truncated. Output:\n" + output);
        assertTrue(output.contains("550e8400"), "expected first-8 of id. Output:\n" + output);
        assertFalse(output.contains("550e8400-e29b"), "id not truncated. Output:\n" + output);
    }

    /**
     * The Branch column should widen to match the longest branch value present — dynamic-width
     * is the difference between a readable table and one that either wraps or has huge gaps.
     */
    @Test
    void branchColumn_widthAdaptsToLongestValue() {
        // given
        String longBranch = "feature/very-long-branch-name-here";
        TestRunHistoryEntry shortBranchEntry = entry(2026, 5, 15, 9, 30, 42, "main",
                "abc", "id1", 1, 0, 0, 1000L, true);
        TestRunHistoryEntry longBranchEntry = entry(2026, 5, 14, 9, 30, 42, longBranch,
                "abc", "id2", 1, 0, 0, 1000L, true);

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(
                Arrays.asList(shortBranchEntry, longBranchEntry), 20, LF);

        // then — the header line's Branch label should be left-padded with enough trailing
        // spaces that the column widens to match the long branch value.
        String[] lines = output.split(LF, -1);
        String columnHeader = lines[2];
        int extraPadding = longBranch.length() - "Branch".length();
        StringBuilder expectedPadding = new StringBuilder("Branch");
        for (int i = 0; i < extraPadding; i++) {
            expectedPadding.append(' ');
        }
        assertTrue(columnHeader.contains(expectedPadding.toString()),
                "Branch column should have widened to match the long branch. Header line:\n" + columnHeader);
    }

    /**
     * A run with zero failed suites still renders as plain "0" — no pluralisation or hiding.
     */
    @Test
    void zeroFailed_rendersAsZero() {
        // given
        TestRunHistoryEntry entry = entry(2026, 5, 15, 9, 30, 42, "main",
                "abc", "id", 5, 0, 0, 1000L, true);

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(
                Collections.singletonList(entry), 20, LF);

        // then — find the data row (after the separator) and verify the Failed column shows 0.
        String[] lines = output.split(LF, -1);
        String dataRow = lines[4];
        assertTrue(dataRow.matches(".*\\b0\\b.*"), "Failed column should contain 0. Row: " + dataRow);
    }

    /**
     * Sub-second durations keep the {@code ms} unit (matches {@code prettyDuration(ms, true)}
     * behaviour for durations below one second).
     */
    @Test
    void subSecondDuration_includesMs() {
        // given
        TestRunHistoryEntry entry = entry(2026, 5, 15, 9, 30, 42, "main",
                "abc", "id", 1, 0, 0, 750L, true);

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(
                Collections.singletonList(entry), 20, LF);

        // then
        assertTrue(output.contains("750ms"), "Sub-second duration should include ms. Output:\n" + output);
    }

    /**
     * Mapping flag renders as {@code yes} / {@code no} — the compact table form, not the HTML
     * report's "updated / not updated" wording.
     */
    @Test
    void mappingFlag_rendersAsYesOrNo() {
        // given
        TestRunHistoryEntry yes = entry(2026, 5, 15, 9, 30, 42, "main",
                "abc", "id1", 1, 0, 0, 1000L, true);
        TestRunHistoryEntry no = entry(2026, 5, 14, 9, 30, 42, "main",
                "abc", "id2", 1, 0, 0, 1000L, false);

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(
                Arrays.asList(yes, no), 20, LF);

        // then
        assertTrue(output.contains("yes"), output);
        assertTrue(output.contains("no"), output);
        assertFalse(output.contains("updated"), "table form must not use the HTML wording. Output:\n" + output);
    }

    /**
     * Date/time should be rendered in the JVM's local time zone so users see times that match
     * their wall clock. Asserted by computing the expected local-time string and checking the
     * output contains it.
     */
    @Test
    void dateTime_rendersInLocalTimeZone() {
        // given — fix a UTC instant, compute its local representation in the running JVM.
        long epochMs = 1_700_000_000_000L;
        String expectedLocal = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
                .format(LOCAL_DATE_TIME);
        TestRunHistoryEntry entry = new TestRunHistoryEntry("id1", epochMs, "main", "abc",
                1, 0, 0, 1000L, true);

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(
                Collections.singletonList(entry), 20, LF);

        // then
        assertTrue(output.contains(expectedLocal),
                "Expected local-time string '" + expectedLocal + "' in output:\n" + output);
    }

    private static TestRunHistoryEntry entry(int year, int month, int day, int hour, int minute,
                                             int second, String branch, String commit, String id,
                                             int ran, int ignored, int failed, long durationMs,
                                             boolean mapping) {
        long epoch = java.time.LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new TestRunHistoryEntry(id, epoch, branch, commit, ran, ignored, failed,
                durationMs, mapping);
    }

    private static List<TestRunHistoryEntry> sequentialEntries(int count) {
        List<TestRunHistoryEntry> entries = new ArrayList<>(count);
        long base = 1_700_000_000_000L;
        for (int i = 0; i < count; i++) {
            entries.add(new TestRunHistoryEntry("id" + i, base - i * 1000L, "main",
                    "c" + i, 1, 0, 0, 1000L, true));
        }
        return entries;
    }

    private static int countDataRows(String output) {
        // Data rows are everything after the dashed separator line, minus the trailing empty
        // produced by the final line separator.
        String[] lines = output.split(LF, -1);
        int separatorIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("---")) {
                separatorIdx = i;
                break;
            }
        }
        if (separatorIdx == -1) {
            return 0;
        }
        int rowCount = lines.length - separatorIdx - 1;
        if (lines[lines.length - 1].isEmpty()) {
            rowCount--;
        }
        return rowCount;
    }
}
