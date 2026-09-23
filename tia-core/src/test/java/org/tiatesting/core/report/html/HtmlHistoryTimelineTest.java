package org.tiatesting.core.report.html;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunHistoryEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Java data-preparation layer of the History timeline chart: selecting and ordering
 * the runs the chart embeds, and serialising them to a compact, script-safe JSON array.
 */
class HtmlHistoryTimelineTest {

    /**
     * Build a minimal single-host history entry carrying only the fields the timeline chart reads.
     *
     * @param id the entry id (used for the bar's detail-page link)
     * @param timestampMs the run's UTC epoch millis (drives chart ordering)
     * @param durationMs the run duration, which is a single-host run's wall clock (drives bar height)
     * @param savingsPercent the run's savings percentage, serial and wall clock alike on a
     *                       single-host run (shown on hover)
     * @param numFailed the number of failed suites (drives the pass/fail colour)
     * @return the populated entry
     */
    private static TestRunHistoryEntry entry(String id, long timestampMs, long durationMs,
                                             int savingsPercent, int numFailed) {
        return new TestRunHistoryEntry(id, timestampMs, "main", "abc", 5, 0, numFailed, durationMs,
                true, 0L, savingsPercent, 0L, savingsPercent, null, null, null, null,
                RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null, null);
    }

    /**
     * A history given in arbitrary order is returned oldest-first so the chart reads left (oldest)
     * to right (newest).
     */
    @Test
    void selectTimelineRunsOrdersOldestFirst() {
        // given
        List<TestRunHistoryEntry> history = Arrays.asList(
                entry("b", 2000L, 10L, 0, 0),
                entry("d", 4000L, 10L, 0, 0),
                entry("a", 1000L, 10L, 0, 0),
                entry("c", 3000L, 10L, 0, 0));

        // when
        List<TestRunHistoryEntry> selected = HtmlHistoryTimeline.selectTimelineRuns(history);

        // then
        List<String> ids = new ArrayList<>();
        selected.forEach(e -> ids.add(e.getId()));
        assertEquals(Arrays.asList("a", "b", "c", "d"), ids);
    }

    /**
     * When more than the embed cap of runs exist, only the most-recent {@code MAX_TIMELINE_RUNS}
     * are kept - the newest retained, the oldest dropped - still ordered oldest-first.
     */
    @Test
    void selectTimelineRunsCapsToMostRecent() {
        // given - MAX + 5 runs with strictly increasing timestamps
        int total = HtmlHistoryTimeline.MAX_TIMELINE_RUNS + 5;
        List<TestRunHistoryEntry> history = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            history.add(entry("id" + i, 1000L + i, 10L, 0, 0));
        }

        // when
        List<TestRunHistoryEntry> selected = HtmlHistoryTimeline.selectTimelineRuns(history);

        // then - kept the newest MAX, dropped the oldest 5, oldest-first ordering preserved
        assertEquals(HtmlHistoryTimeline.MAX_TIMELINE_RUNS, selected.size());
        assertEquals("id5", selected.get(0).getId());
        assertEquals("id" + (total - 1), selected.get(selected.size() - 1).getId());
    }

    /**
     * A null or empty history yields an empty selection rather than failing.
     */
    @Test
    void selectTimelineRunsHandlesNullAndEmpty() {
        // given / when / then
        assertTrue(HtmlHistoryTimeline.selectTimelineRuns(null).isEmpty());
        assertTrue(HtmlHistoryTimeline.selectTimelineRuns(Collections.emptyList()).isEmpty());
    }

    /**
     * A history smaller than the cap is returned whole (just reordered), with none dropped.
     */
    @Test
    void selectTimelineRunsReturnsAllWhenUnderCap() {
        // given
        List<TestRunHistoryEntry> history = Arrays.asList(
                entry("a", 1000L, 10L, 0, 0),
                entry("b", 2000L, 10L, 0, 0));

        // when
        List<TestRunHistoryEntry> selected = HtmlHistoryTimeline.selectTimelineRuns(history);

        // then
        assertEquals(2, selected.size());
    }

    /**
     * Each run serialises to a compact object carrying its id, timestamp, duration, savings percent
     * and a pass/fail flag, and the array preserves the given (oldest-first) order.
     */
    @Test
    void buildRunsJsonMapsFieldsInOrder() {
        // given
        List<TestRunHistoryEntry> runs = Arrays.asList(
                entry("first", 1000L, 250L, 40, 0),
                entry("second", 2000L, 999L, 12, 0));

        // when
        String json = HtmlHistoryTimeline.buildRunsJson(runs);

        // then
        assertTrue(json.startsWith("[") && json.endsWith("]"), "should be a JSON array: " + json);
        assertTrue(json.contains("\"id\":\"first\""), "id mapped: " + json);
        assertTrue(json.contains("\"t\":1000"), "timestamp mapped: " + json);
        assertTrue(json.contains("\"d\":250"), "duration mapped: " + json);
        assertTrue(json.contains("\"s\":40"), "savings percent mapped: " + json);
        assertTrue(json.indexOf("\"id\":\"first\"") < json.indexOf("\"id\":\"second\""),
                "array should preserve oldest-first order: " + json);
    }

    /**
     * A distributed run's bar is its wall clock and its hover shows its wall-clock savings, not the
     * serial duration or serial savings, so the chart reads in the same terms as the table.
     */
    @Test
    void buildRunsJsonUsesADistributedRunsWallClockFigures() {
        // given - 20s serial taking 8s across 3 of 6 groups: 67% serial, 20% wall-clock savings
        TestRunHistoryEntry distributed = new TestRunHistoryEntry("dist", 1000L, "main", "abc", 5, 0,
                0, 20_000L, true, 40_000L, 67, 2_000L, 20, "run-1", Long.valueOf(8_000L),
                Integer.valueOf(3), Integer.valueOf(6), RunOrigin.of(RunOrigin.SOURCE_CI, null),
                null, null, null, null, null);

        // when
        String json = HtmlHistoryTimeline.buildRunsJson(Collections.singletonList(distributed));

        // then
        assertTrue(json.contains("\"d\":8000"), "bar height is the wall clock: " + json);
        assertTrue(json.contains("\"s\":20"), "hover shows the wall-clock savings: " + json);
    }

    /**
     * The pass/fail flag is 1 when the run had at least one failed suite, and 0 otherwise, so the
     * client can colour the bar without re-deriving it.
     */
    @Test
    void buildRunsJsonEncodesFailFlag() {
        // given
        String passed = HtmlHistoryTimeline.buildRunsJson(
                Collections.singletonList(entry("ok", 1000L, 10L, 0, 0)));
        String failed = HtmlHistoryTimeline.buildRunsJson(
                Collections.singletonList(entry("bad", 1000L, 10L, 0, 3)));

        // when / then
        assertTrue(passed.contains("\"f\":0"), "a run with no failures is flagged 0: " + passed);
        assertTrue(failed.contains("\"f\":1"), "a run with failures is flagged 1: " + failed);
    }

    /**
     * An id carrying HTML-significant characters is unicode-escaped so the embedded JSON can never
     * terminate the surrounding {@code <script>} element.
     */
    @Test
    void buildRunsJsonEscapesIdForScriptSafety() {
        // given
        List<TestRunHistoryEntry> runs = Collections.singletonList(
                entry("</script><a>&", 1000L, 10L, 0, 0));

        // when
        String json = HtmlHistoryTimeline.buildRunsJson(runs);

        // then
        assertFalse(json.contains("</script>"), "raw </script> must not appear: " + json);
        assertTrue(json.contains("\\u003c") && json.contains("\\u0026"),
                "HTML-significant characters should be unicode-escaped: " + json);
    }
}
