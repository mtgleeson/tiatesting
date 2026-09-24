package org.tiatesting.core.report.html;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the History page renders the timeline chart above the table when there is history, and
 * omits it entirely when there is none. The chart's SVG is drawn client-side, so these assertions
 * check the embedded run data, the chart host, the legend/control chrome, and the ordering
 * relative to the table rather than the drawn bars.
 */
class HtmlHistoryReportTimelineTest {

    /**
     * Build a minimal single-host history entry for the timeline chart.
     *
     * @param id the entry id
     * @param timestampMs the run's UTC epoch millis
     * @param durationMs the run duration
     * @param numFailed the number of failed suites
     * @return the populated entry
     */
    private static TestRunHistoryEntry entry(String id, long timestampMs, long durationMs, int numFailed) {
        return new TestRunHistoryEntry(id, timestampMs, "main", "abc", 5, 0, numFailed, durationMs,
                true, 1000L, 25, 1000L, 25, null, null, null, null,
                RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null, null);
    }

    /**
     * Read the generated history page for the given report root.
     *
     * @param tempDir the report output root the report was written under
     * @return the page's HTML
     * @throws Exception if the page cannot be read
     */
    private static String generateAndRead(File tempDir, TiaData tiaData) throws Exception {
        new HtmlHistoryReport("html", tempDir).generateReport(tiaData);
        File page = new File(tempDir, "html" + File.separator + "html" + File.separator
                + "history" + File.separator + "tia-history.html");
        return new String(Files.readAllBytes(page.toPath()));
    }

    /**
     * A history with runs renders the timeline chart - its heading, chart host, legend, "show more"
     * control and embedded run data - and places it above the history table.
     */
    @Test
    void historyPage_rendersTimelineAboveTable(@TempDir File tempDir) throws Exception {
        // given a passing and a failing run
        TiaData tiaData = new TiaData();
        tiaData.setTestRunHistory(Arrays.asList(
                entry("run-pass-1", 1_700_000_000_000L, 4000L, 0),
                entry("run-fail-2", 1_700_000_100_000L, 9000L, 2)));

        // when
        String html = generateAndRead(tempDir, tiaData);

        // then - the chart block, its chrome and embedded data are present
        assertTrue(html.contains("class=\"tia-timeline\""), "timeline block missing");
        assertTrue(html.contains("id=\"tiaTimelineChart\""), "chart host missing");
        assertTrue(html.contains("Run wall clock timeline"), "chart heading missing");
        assertTrue(html.contains("id=\"tiaTimelineMore\""), "show-more control missing");
        assertTrue(html.contains(">Passed<") && html.contains(">Failed<"), "legend labels missing");
        assertTrue(html.contains("var RUNS=["), "embedded run data missing");
        assertTrue(html.contains("\"id\":\"run-pass-1\"") && html.contains("\"id\":\"run-fail-2\""),
                "run ids should be embedded in the chart data");

        // and the chart sits above the table
        assertTrue(html.indexOf("class=\"tia-timeline\"") < html.indexOf("id=\"tiaTable\""),
                "timeline chart should render above the history table");
    }

    /**
     * The chart's y-axis gutter is sized from the widest tick label rather than a fixed width, so
     * multi-unit labels on long runs (e.g. {@code 2h 46m 40s}) are not clipped at the left edge.
     */
    @Test
    void historyPage_sizesYAxisGutterFromWidestLabel(@TempDir File tempDir) throws Exception {
        // given a run over two hours long, whose top tick label spans hours, minutes and seconds
        TiaData tiaData = new TiaData();
        tiaData.setTestRunHistory(Collections.singletonList(
                entry("run-long", 1_700_000_000_000L, 9_000_000L, 0)));

        // when
        String html = generateAndRead(tempDir, tiaData);

        // then - the gutter is derived from the measured labels, not hard-coded
        assertTrue(html.contains("padL=Math.max(56,maxLabelW+16)"),
                "y-axis gutter should grow to fit the widest tick label");
        assertFalse(html.contains("var padL=56,"), "y-axis gutter should not be a fixed width");
        assertTrue(html.contains("esc(labels[t])"), "tick labels should be the measured labels");
    }

    /**
     * A history with no runs omits the timeline chart entirely, leaving the table as the only
     * content.
     */
    @Test
    void historyPage_omitsTimelineWhenNoHistory(@TempDir File tempDir) throws Exception {
        // given no history
        TiaData tiaData = new TiaData();
        tiaData.setTestRunHistory(Collections.emptyList());

        // when
        String html = generateAndRead(tempDir, tiaData);

        // then
        assertFalse(html.contains("tia-timeline"), "no timeline chart should render for empty history");
        assertFalse(html.contains("tiaTimelineChart"), "no chart host should render for empty history");
    }
}
