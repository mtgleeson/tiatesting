package org.tiatesting.core.report;

import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunTrigger;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders one {@link TestRunHistoryEntry}'s full selection breakdown as plain text, shared by the
 * Gradle task and Maven mojo that print the detail of a single recorded test run. Where
 * {@link TestRunHistoryConsoleFormatter} renders a table of many runs, this formatter renders one
 * run in full: its summary figures, the five scalar selection-source counters, and the ranked
 * per-method and per-rule triggers that pulled tests into the run. See the "Run history details"
 * chapter in {@code WIKI.md}.
 *
 * <p>The output shape is:
 * <pre>
 * Test run 550e8400-e29b-41d4-a716-446655440000
 * Branch:          main
 * Commit:          abc123def456
 * Date/time:       2026-05-15 09:30:42
 * Suites ran:      42
 * Suites ignored:  3
 * Suites failed:   1
 * Duration:        1m 23s
 * Savings:         45s
 *
 * Selection sources:
 *   Modified test files:  2
 *   New test files:       1
 *   Previously-failed:    0
 *   Unsealed-mapping:     -
 *   Pending library:      0
 *
 * Source method changes:
 *   12  com.example.Foo#bar
 *    3  com.example.Baz#qux
 *
 * Static rules:
 *   (none)
 * </pre>
 *
 * <p>Timestamps are rendered in the JVM's local time zone with the same
 * {@code yyyy-MM-dd HH:mm:ss} pattern {@link TestRunHistoryConsoleFormatter} uses, so the two views
 * read consistently. Durations and savings reuse {@link ReportUtils#prettyDuration}; savings render
 * as {@code "-"} when the entry recorded none ({@link TestRunHistoryEntry#getTimeSavingsMs()}
 * {@code <= 0}). Each of the five scalar selection-source counters renders {@code "-"} when its
 * boxed {@link Integer} is null (not recorded), rather than a misleading zero.
 */
public final class TestRunHistoryDetailConsoleFormatter {

    /** Rendered where a value is null / not recorded / not applicable. */
    private static final String NOT_APPLICABLE = "-";

    /** Rendered as a trigger section's whole body when it has no triggers. */
    private static final String NONE_LINE = "  (none)";

    private static final DateTimeFormatter LOCAL_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TestRunHistoryDetailConsoleFormatter() { }

    /**
     * Render one run's full selection breakdown: a header/summary section (id, branch, commit,
     * timestamp, suite counts, duration, savings), a "Selection sources" section with the five
     * scalar counters, and "Source method changes" / "Static rules" sections listing the run's
     * triggers ranked by the number of suites each accounts for.
     *
     * @param entry    the run to render; must not be null
     * @param triggers the run's triggers (source-method and static-rule, in any order). Filtered
     *                 by {@link TestRunTrigger#getType()} and sorted by
     *                 {@link TestRunTrigger#getTestCount()} descending defensively, regardless of
     *                 the order supplied; null is tolerated and treated as no triggers
     * @param lineSep  line separator (typically {@code System.lineSeparator()}) so callers control
     *                 EOL style
     * @return the formatted detail text
     */
    public static String format(TestRunHistoryEntry entry, List<TestRunTrigger> triggers, String lineSep) {
        ZoneId zone = ZoneId.systemDefault();
        StringBuilder sb = new StringBuilder();

        sb.append("Test run ").append(nullSafe(entry.getId())).append(lineSep);
        sb.append("Branch:          ").append(nullSafe(entry.getBranch())).append(lineSep);
        sb.append("Commit:          ").append(nullSafe(entry.getCommit())).append(lineSep);
        sb.append("Date/time:       ")
                .append(Instant.ofEpochMilli(entry.getRunTimestampMs()).atZone(zone).format(LOCAL_DATE_TIME))
                .append(lineSep);
        sb.append("Suites ran:      ").append(entry.getNumSuitesRan()).append(lineSep);
        sb.append("Suites ignored:  ").append(entry.getNumSuitesIgnored()).append(lineSep);
        sb.append("Suites failed:   ").append(entry.getNumSuitesFailed()).append(lineSep);
        sb.append("Duration:        ").append(ReportUtils.prettyDuration(entry.getDurationMs(), true)).append(lineSep);
        sb.append("Savings:         ").append(entry.getTimeSavingsMs() > 0
                ? ReportUtils.prettyDuration(entry.getTimeSavingsMs(), true) : NOT_APPLICABLE).append(lineSep);
        sb.append(lineSep);

        appendSelectionSources(sb, entry, lineSep);
        sb.append(lineSep);

        List<TestRunTrigger> methods = TestRunTrigger.filterByTypeSortedByCountDesc(
                triggers, TestRunTrigger.Type.SOURCE_METHOD);
        sb.append("Source method changes:").append(lineSep);
        appendTriggerLines(sb, methods, lineSep);
        sb.append(lineSep);

        List<TestRunTrigger> rules = TestRunTrigger.filterByTypeSortedByCountDesc(
                triggers, TestRunTrigger.Type.STATIC_RULE);
        sb.append("Static rules:").append(lineSep);
        appendTriggerLines(sb, rules, lineSep);

        return sb.toString();
    }

    /**
     * Produce the message shown when no history row matches the id the caller looked up, so the
     * Gradle task and Maven mojo report the same wording for a miss.
     *
     * @param id      the id that was looked up and not found
     * @param lineSep line separator, accepted for signature symmetry with {@link #format}; the
     *                message is a single line and does not use it
     * @return a one-line message naming the id that could not be found
     */
    public static String notFound(String id, String lineSep) {
        return "No test run found with id '" + id + "'.";
    }

    /**
     * Append the "Selection sources" section: the five scalar selection-source counters, each
     * rendered as the not-applicable dash when its boxed value is null (not recorded).
     *
     * @param sb      output buffer
     * @param entry   the run whose counters are rendered
     * @param lineSep line separator to terminate each line
     */
    private static void appendSelectionSources(StringBuilder sb, TestRunHistoryEntry entry, String lineSep) {
        sb.append("Selection sources:").append(lineSep);
        sb.append("  Modified test files:  ").append(orDash(entry.getNumModifiedTestFiles())).append(lineSep);
        sb.append("  New test files:       ").append(orDash(entry.getNumNewTestFiles())).append(lineSep);
        sb.append("  Previously-failed:    ").append(orDash(entry.getNumPreviouslyFailed())).append(lineSep);
        sb.append("  Unsealed-mapping:     ").append(orDash(entry.getNumUnsealedMapping())).append(lineSep);
        sb.append("  Pending library:      ").append(orDash(entry.getNumPendingLibrary())).append(lineSep);
    }

    /**
     * Append one trigger section's body lines: one {@code "  <count>  <name>"} line per trigger,
     * with counts right-aligned to the widest count in the list, or a single {@code "  (none)"}
     * line when the list is empty.
     *
     * @param sb       output buffer
     * @param triggers the triggers to render, already filtered to one type and sorted
     * @param lineSep  line separator to terminate each line
     */
    private static void appendTriggerLines(StringBuilder sb, List<TestRunTrigger> triggers, String lineSep) {
        if (triggers.isEmpty()) {
            sb.append(NONE_LINE).append(lineSep);
            return;
        }
        int width = 0;
        for (TestRunTrigger t : triggers) {
            width = Math.max(width, Integer.toString(t.getTestCount()).length());
        }
        for (TestRunTrigger t : triggers) {
            String count = Integer.toString(t.getTestCount());
            sb.append("  ");
            for (int i = count.length(); i < width; i++) {
                sb.append(' ');
            }
            sb.append(count).append("  ").append(nullSafe(t.getName())).append(lineSep);
        }
    }

    /**
     * Coalesce a null boxed counter to the not-applicable dash - used for the five selection
     * source counters, which are null when not recorded rather than defaulting to zero.
     *
     * @param value the possibly-null counter value
     * @return the counter as text, or the dash placeholder when {@code value} is null
     */
    private static String orDash(Integer value) {
        return value == null ? NOT_APPLICABLE : value.toString();
    }

    /**
     * Coalesce null to the empty string for display purposes.
     *
     * @param value the possibly-null value
     * @return {@code value}, or {@code ""} when {@code value} is null
     */
    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
