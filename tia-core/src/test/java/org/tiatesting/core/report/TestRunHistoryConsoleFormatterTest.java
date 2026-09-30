package org.tiatesting.core.report;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.RunOrigin;
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
 * commit + id truncation, dynamic column widths, the duration / mapping cell renderings, and the
 * columns the table leaves to the detail view (branch and host).
 */
class TestRunHistoryConsoleFormatterTest {

    private static final String LF = "\n";
    private static final DateTimeFormatter LOCAL_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * An empty history list should render the explicit sentinel message and skip the table -
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
        assertTrue(output.startsWith("Displaying the latest 1 test runs from a total of 1." + LF),
                "header should report rows-shown (not the configured cap) when total < limit. Output: " + output);
        String[] lines = output.split(LF, -1);
        // header, blank, column-header, separator, data row, trailing-empty-from-final-LF
        assertEquals(6, lines.length, "Expected 6 lines (incl. trailing empty), got: " + lines.length);
        assertEquals("", lines[1], "second line should be blank");
        assertTrue(lines[2].startsWith("Date/time"), "column header missing: " + lines[2]);
        assertTrue(lines[3].startsWith("---"), "separator line missing: " + lines[3]);
        assertTrue(lines[4].contains("abc123de"), "data row missing commit: " + lines[4]);
    }

    /**
     * 5 entries with limit 20 should render all 5 - limit is a cap, not a target. The header
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
     * 25 entries with limit 5 - covers the {@code --last N} narrower-than-default path.
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
     * A column should widen to match the longest value present - dynamic-width is the difference
     * between a readable table and one that either wraps or has huge gaps. Exercised on the
     * Savings column, whose values can be longer than its header.
     */
    @Test
    void savingsColumn_widthAdaptsToLongestValue() {
        // given - 1h 23m 20s of savings, longer than the "Savings" header
        TestRunHistoryEntry longSavings = new TestRunHistoryEntry("id1", 1_700_000_000_000L, "main", "abc",
                8, 2, 0, 1000L, true, 5_000_000L, 80, 5_000_000L, 80, null, null, null, null,
                RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null, null, false);

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(
                Collections.singletonList(longSavings), 20, LF);

        // then - the header's Savings label is padded out to the width of the long value
        String longValue = "1h 23m 20s";
        String columnHeader = output.split(LF, -1)[2];
        StringBuilder expectedPadding = new StringBuilder("Savings");
        for (int i = 0; i < longValue.length() - "Savings".length(); i++) {
            expectedPadding.append(' ');
        }
        assertTrue(output.contains(longValue), "Savings value missing. Output:\n" + output);
        assertTrue(columnHeader.contains(expectedPadding.toString() + "  "),
                "Savings column should have widened to match the long value. Header line:\n" + columnHeader);
    }

    /**
     * The branch is not a table column: a history is scoped to one branch, so it would repeat the
     * same value on every row. It is on the detail view.
     */
    @Test
    void branch_isNotAColumn() {
        // given
        TestRunHistoryEntry entry = entry(2026, 5, 15, 9, 30, 42, "feature/some-branch",
                "abc", "id1", 1, 0, 0, 1000L, true);

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(
                Collections.singletonList(entry), 20, LF);

        // then
        assertFalse(output.contains("Branch"), output);
        assertFalse(output.contains("feature/some-branch"), output);
    }

    /**
     * A run with zero failed suites still renders as plain "0" - no pluralisation or hiding.
     */
    @Test
    void zeroFailed_rendersAsZero() {
        // given
        TestRunHistoryEntry entry = entry(2026, 5, 15, 9, 30, 42, "main",
                "abc", "id", 5, 0, 0, 1000L, true);

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(
                Collections.singletonList(entry), 20, LF);

        // then - find the data row (after the separator) and verify the Failed column shows 0.
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
     * Mapping flag renders as {@code yes} / {@code no} - the compact table form, not the HTML
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
     * Date/time should be rendered to the minute in the JVM's local time zone so users see times
     * that match their wall clock. Asserted by computing the expected local-time string and checking the
     * output contains it.
     */
    @Test
    void dateTime_rendersInLocalTimeZone() {
        // given - fix a UTC instant, compute its local representation in the running JVM.
        long epochMs = 1_700_000_000_000L;
        String expectedLocal = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
                .format(LOCAL_DATE_TIME);
        TestRunHistoryEntry entry = new TestRunHistoryEntry("id1", epochMs, "main", "abc",
                1, 0, 0, 1000L, true, 0L, 0, 0L, 0, null, null, null, null, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null),
                null, null, null, null, null, false);

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(
                Collections.singletonList(entry), 20, LF);

        // then
        assertTrue(output.contains(expectedLocal),
                "Expected local-time string '" + expectedLocal + "' in output:\n" + output);
        String withSeconds = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        assertFalse(output.contains(withSeconds),
                "The table renders to the minute; the seconds are on the detail view. Output:\n" + output);
    }

    /**
     * A partial run's frozen savings render as a duration and a percent; an all-tests run with no
     * savings renders {@code "-"} in both columns.
     */
    @Test
    void savingsColumns_renderDurationPercentAndDashForZero() {
        // given - one partial run that saved 4s (80%) and one all-tests run that saved nothing
        TestRunHistoryEntry partial = new TestRunHistoryEntry("id1", 1_700_000_000_000L, "main", "abc",
                8, 2, 0, 1000L, true, 4000L, 80, 4000L, 80, null, null, null, null, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null),
                null, null, null, null, null, false);
        TestRunHistoryEntry allTests = new TestRunHistoryEntry("id2", 1_699_000_000_000L, "main", "abc",
                10, 0, 0, 5000L, true, 0L, 0, 0L, 0, null, null, null, null, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null),
                null, null, null, null, null, false);

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(
                Arrays.asList(partial, allTests), 20, LF);

        // then
        assertTrue(output.contains("Savings"), "header should include a Savings column. Output:\n" + output);
        assertTrue(output.contains("Savings %"), "header should include a Savings % column. Output:\n" + output);
        assertTrue(output.contains("4s"), "partial run should show its savings duration. Output:\n" + output);
        assertTrue(output.contains("80%"), "partial run should show its savings percent. Output:\n" + output);
        assertTrue(output.contains("-"), "all-tests run should show a dash for no savings. Output:\n" + output);
    }

    /**
     * A history of single-host runs shows each run's duration as its wall clock, and leaves out the
     * Groups column, which would be a dash on every row of such a history.
     */
    @Test
    void singleHostOnlyHistory_showsTheWallClockAndOmitsGroups() {
        // given
        TestRunHistoryEntry entry = new TestRunHistoryEntry("id1", 1_700_000_000_000L, "main", "abc",
                8, 2, 0, 1000L, true, 4000L, 80, 4000L, 80, null, null, null, null, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null),
                null, null, null, null, null, false);

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(
                Collections.singletonList(entry), 20, LF);

        // then
        assertTrue(output.contains("Wall clock"),
                "every history carries a wall clock column. Output:\n" + output);
        assertFalse(output.contains("Duration"),
                "the serial duration belongs on the detail view. Output:\n" + output);
        assertTrue(output.split(LF, -1)[4].contains("1s"),
                "a single-host run's duration is its wall clock. Output:\n" + output);
        assertFalse(output.contains("Groups"),
                "a history with no distributed runs should not carry a groups column. Output:\n" + output);
    }

    /**
     * A distributed run's row shows the wall clock the build actually took, how many groups it was
     * split across and its wall-clock savings - not the serial duration or serial savings, which
     * are on the detail view.
     */
    @Test
    void distributedRun_rendersTheWallClockGroupsAndWallClockSavings() {
        // given - 20s serial across 3 of 6 groups in 8s: 40s (67%) serial savings, 2s (20%) wall clock
        TestRunHistoryEntry distributed = new TestRunHistoryEntry("id1", 1_700_000_000_000L, "main",
                "abc", 8, 2, 0, 20_000L, true, 40_000L, 67, 2_000L, 20, "run-1", Long.valueOf(8_000L),
                Integer.valueOf(3), Integer.valueOf(6), RunOrigin.of(RunOrigin.SOURCE_LOCAL, null),
                null, null, null, null, null, false);

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(
                Collections.singletonList(distributed), 20, LF);

        // then
        assertTrue(output.contains("Wall clock"), "header should include a Wall clock column. Output:\n" + output);
        assertTrue(output.contains("Groups"), "header should include a Groups column. Output:\n" + output);
        String row = output.split(LF, -1)[4];
        assertTrue(row.contains("8s"), "the Wall clock column should show the build's actual time. Row: " + row);
        assertTrue(row.contains(" 3 "), "the Groups column should show the groups used. Row: " + row);
        assertTrue(row.contains("2s") && row.contains("20%"),
                "the Savings columns should show the wall-clock savings. Row: " + row);
        assertFalse(row.contains("20s") || row.contains("40s") || row.contains("67%"),
                "the serial duration and savings belong on the detail view. Row: " + row);
    }

    /**
     * When a history mixes the two modes, the single-host rows show a dash in the Groups column
     * rather than a misleading zero - they had no groups at all.
     */
    @Test
    void mixedHistory_showsADashInTheDistributedColumnsForSingleHostRows() {
        // given
        TestRunHistoryEntry distributed = new TestRunHistoryEntry("id1", 1_700_000_000_000L, "main",
                "abc", 8, 2, 0, 20_000L, true, 4000L, 80, 4000L, 80, "run-1", Long.valueOf(8_000L),
                Integer.valueOf(3), Integer.valueOf(3), RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null, null, false);
        TestRunHistoryEntry singleHost = new TestRunHistoryEntry("id2", 1_699_000_000_000L, "main",
                "abc", 10, 0, 0, 5000L, true, 0L, 0, 0L, 0, null, null, null, null, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null),
                null, null, null, null, null, false);

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(
                Arrays.asList(distributed, singleHost), 20, LF);

        // then
        String[] lines = output.split(LF, -1);
        String singleHostRow = lines[5];
        assertTrue(singleHostRow.contains("-"),
                "the single-host row should show a dash in the Groups column. Row: " + singleHostRow);
        assertFalse(singleHostRow.contains("run-1"),
                "the single-host row belongs to no distributed run. Row: " + singleHostRow);
    }

    /**
     * Source is always rendered - every recorded run resolves one - but the host never is, even
     * when the row names one: it is on the detail view, which keeps the table narrow.
     */
    @Test
    void aRecordedOrigin_rendersTheSourceButNotTheHost() {
        // given
        TestRunHistoryEntry entry = entryWithOrigin(
                RunOrigin.of(RunOrigin.SOURCE_LOCAL, "dev-laptop-7"));

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(
                Collections.singletonList(entry), 20, LF);

        // then
        assertTrue(output.contains("Source"), output);
        assertTrue(output.contains(RunOrigin.SOURCE_LOCAL), output);
        assertFalse(output.contains("Host"), output);
        assertFalse(output.contains("dev-laptop-7"), output);
    }

    /**
     * A distributed build records a source but no host. The source still renders.
     */
    @Test
    void aDistributedRunStillRendersItsSource() {
        // given
        TestRunHistoryEntry entry = entryWithOrigin(RunOrigin.of(RunOrigin.SOURCE_CI, null));

        // when
        String output = TestRunHistoryConsoleFormatter.formatHistory(
                Collections.singletonList(entry), 20, LF);

        // then
        assertTrue(output.contains("Source"), output);
        assertTrue(output.contains(RunOrigin.SOURCE_CI), output);
    }

    /**
     * Build an entry that differs from {@link #entry} only in carrying a known run origin.
     *
     * @param origin the origin to stamp on the row
     * @return the populated entry
     */
    /**
     * A rerun row names itself in the Savings column - where it would otherwise show a dash, since
     * it is credited no savings - and marking it that way does not widen the table.
     */
    @Test
    void rerunRow_showsRerunInTheSavingsColumnWithoutWideningTheTable() {
        // given
        TestRunHistoryEntry first = entry(2026, 5, 15, 9, 30, 42, "main", "abc123de", "id-first",
                42, 3, 1, 83_000L, true);
        TestRunHistoryEntry rerun = new TestRunHistoryEntry("id-rerun", first.getRunTimestampMs() + 60_000L,
                "main", "abc123de", 1, 3, 0, 4_000L, true, 0L, 0, 0L, 0, null, null, null, null,
                RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null, null, true);

        // when
        String withoutRerun = TestRunHistoryConsoleFormatter.formatHistory(
                Collections.singletonList(first), 20, LF);
        String withRerun = TestRunHistoryConsoleFormatter.formatHistory(
                java.util.Arrays.asList(rerun, first), 20, LF);

        // then
        String[] before = withoutRerun.split(LF, -1);
        String[] after = withRerun.split(LF, -1);
        assertEquals(before[3].length(), after[3].length(), "the rerun marker must not widen the table");
        int savingsColumn = after[2].indexOf("Savings ");
        assertTrue(after[4].substring(savingsColumn).startsWith("rerun"), after[4]);
    }

    private static TestRunHistoryEntry entryWithOrigin(RunOrigin origin) {
        return new TestRunHistoryEntry("id-1", 1_700_000_000_000L, "main", "abc123",
                42, 3, 1, 83_000L, true, 0L, 0, 0L, 0, null, null, null, null, origin, null, null, null, null, null, false);
    }

    private static TestRunHistoryEntry entry(int year, int month, int day, int hour, int minute,
                                             int second, String branch, String commit, String id,
                                             int ran, int ignored, int failed, long durationMs,
                                             boolean mapping) {
        long epoch = java.time.LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new TestRunHistoryEntry(id, epoch, branch, commit, ran, ignored, failed,
                durationMs, mapping, 0L, 0, 0L, 0, null, null, null, null, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null),
                null, null, null, null, null, false);
    }

    private static List<TestRunHistoryEntry> sequentialEntries(int count) {
        List<TestRunHistoryEntry> entries = new ArrayList<>(count);
        long base = 1_700_000_000_000L;
        for (int i = 0; i < count; i++) {
            entries.add(new TestRunHistoryEntry("id" + i, base - i * 1000L, "main",
                    "c" + i, 1, 0, 0, 1000L, true, 0L, 0, 0L, 0, null, null, null, null, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null),
                    null, null, null, null, null, false));
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
