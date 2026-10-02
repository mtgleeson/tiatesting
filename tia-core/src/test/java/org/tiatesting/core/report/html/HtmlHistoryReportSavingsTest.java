package org.tiatesting.core.report.html;

import org.tiatesting.core.model.SelectionMode;
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
 * Verifies the HTML history page renders the per-run savings columns: a "Savings" duration column
 * and a "Savings %" column, with a partial run showing its frozen figures and an all-tests run
 * (no savings) showing a dash.
 */
class HtmlHistoryReportSavingsTest {

    @Test
    void historyPage_showsPerRunSavingsColumns(@TempDir File tempDir) throws Exception {
        // given - a partial run that saved 4s (80%) and an all-tests run that saved nothing
        TiaData tiaData = new TiaData();
        tiaData.setTestRunHistory(Arrays.asList(
                new TestRunHistoryEntry("id1", 1_700_000_000_000L, "main", "abc", 8, 2, 0, 1000L, true, 4000L, 80, 4000L, 80,
                        null, null, null, null, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null, null, false, null),
                new TestRunHistoryEntry("id2", 1_699_000_000_000L, "main", "abc", 10, 0, 0, 5000L, true, 0L, 0, 0L, 0,
                        null, null, null, null, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null, null, false, null)));

        // when
        new HtmlHistoryReport("html", tempDir).generateReport(tiaData);
        File page = new File(tempDir, "html" + File.separator + "html" + File.separator
                + "history" + File.separator + "tia-history.html");
        String html = new String(Files.readAllBytes(page.toPath()));

        // then
        assertTrue(html.contains("Savings"), "history table should have a Savings header. Output:\n" + html);
        assertTrue(html.contains("Savings %"), "history table should have a Savings % header. Output:\n" + html);
        assertTrue(html.contains("4s"), "partial run should show its savings duration. Output:\n" + html);
        assertTrue(html.contains("80%"), "partial run should show its savings percent. Output:\n" + html);
    }

    /**
     * Verifies the Savings and Savings % cells carry their numeric sort value in {@code data-order}
     * rather than {@code data-sort}. simple-datatables reads a cell's custom sort value from
     * {@code data-order}; a number column with no {@code data-order} falls back to parsing the cell
     * text, so a dashed "-" cell parses to {@code NaN} and the column stops sorting. Both the
     * savings-bearing row and the dashed all-tests row must expose a parseable number so the two
     * columns sort correctly even when some rows show "-".
     *
     * @param tempDir a JUnit-managed temp directory the report is written into
     */
    @Test
    void historyPage_savingsColumnsCarryNumericSortValue(@TempDir File tempDir) throws Exception {
        // given a partial run that saved 4s (80%) and an all-tests run that saved nothing (dashed)
        TiaData tiaData = new TiaData();
        tiaData.setTestRunHistory(Arrays.asList(
                new TestRunHistoryEntry("id1", 1_700_000_000_000L, "main", "abc", 8, 2, 0, 1000L, true, 4000L, 80, 4000L, 80,
                        null, null, null, null, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null, null, false, null),
                new TestRunHistoryEntry("id2", 1_699_000_000_000L, "main", "abc", 10, 0, 0, 5000L, true, 0L, 0, 0L, 0,
                        null, null, null, null, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null, null, false, null)));

        // when
        new HtmlHistoryReport("html", tempDir).generateReport(tiaData);
        File page = new File(tempDir, "html" + File.separator + "html" + File.separator
                + "history" + File.separator + "tia-history.html");
        String html = new String(Files.readAllBytes(page.toPath()));

        // then the savings-bearing row exposes the millisecond and percent figures as data-order
        assertTrue(html.contains("data-order=\"4000\""),
                "Savings cell should carry its millisecond value in data-order. Output:\n" + html);
        assertTrue(html.contains("data-order=\"80\""),
                "Savings % cell should carry its percent value in data-order. Output:\n" + html);
        // and the dashed all-tests row still exposes a parseable 0 so it sorts rather than becoming NaN
        assertTrue(html.contains("data-order=\"0\">-</td>"),
                "A dashed savings cell should carry data-order=0 so it sorts numerically. Output:\n" + html);
        // and the superseded data-sort attribute is gone (simple-datatables ignores it on cells)
        assertFalse(html.contains("data-sort="),
                "Cells should not use the ignored data-sort attribute. Output:\n" + html);
    }

    /**
     * Verifies a rerun row names itself in its Savings cell, with a hover hint and a numeric sort
     * value, in place of the dash it would otherwise show - marking the row without adding a column.
     *
     * @param tempDir a JUnit-managed temp directory the report is written into
     */
    @Test
    void historyPage_rerunRowShowsRerunInItsSavingsCell(@TempDir File tempDir) throws Exception {
        // given
        TiaData tiaData = new TiaData();
        tiaData.setTestRunHistory(Collections.singletonList(
                new TestRunHistoryEntry("id1", 1_700_000_000_000L, "main", "abc", 1, 9, 0, 1000L, true, 0L, 0, 0L, 0,
                        null, null, null, null, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null, null, true, null)));

        // when
        new HtmlHistoryReport("html", tempDir).generateReport(tiaData);
        File page = new File(tempDir, "html" + File.separator + "html" + File.separator
                + "history" + File.separator + "tia-history.html");
        String html = new String(Files.readAllBytes(page.toPath()));

        // then
        assertTrue(html.contains("title=\"A rerun of failed tests - credited no savings\" data-order=\"0\">rerun</td>"),
                "the rerun row's Savings cell should read rerun, with a hint and a sort value. Output:\n" + html);
    }

    /**
     * A forced full run's Savings cell names its mode in place of the dash it would otherwise show.
     *
     * @param tempDir a JUnit-managed temp directory the report is written into
     * @throws Exception if the page cannot be written or read back
     */
    @Test
    void historyPage_forcedRunRowShowsItsModeInItsSavingsCell(@TempDir File tempDir) throws Exception {
        // given
        TiaData tiaData = new TiaData();
        tiaData.setTestRunHistory(Collections.singletonList(
                new TestRunHistoryEntry("id1", 1_700_000_000_000L, "main", "abc", 10, 0, 0, 1000L, true, 0L, 0, 0L, 0,
                        null, null, null, null, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null,
                        null, false, SelectionMode.SELECT_ALL)));

        // when
        new HtmlHistoryReport("html", tempDir).generateReport(tiaData);
        File page = new File(tempDir, "html" + File.separator + "html" + File.separator
                + "history" + File.separator + "tia-history.html");
        String html = new String(Files.readAllBytes(page.toPath()));

        // then
        assertTrue(html.contains("data-order=\"0\">Forced</td>"), "Output:\n" + html);
    }

    /**
     * Verifies each row links to its detail page - a sibling file in the same {@code history/}
     * folder named after the entry's full id - through a row-link anchor in the Date / time cell,
     * that the table is marked clickable and wired to the row click script, and that the old Id
     * column is gone.
     *
     * @param tempDir a JUnit-managed temp directory the report is written into
     */
    @Test
    void historyPage_rowLinksToItsDetailPage(@TempDir File tempDir) throws Exception {
        // given a single history entry with a full id longer than the old 8-char display truncation
        TiaData tiaData = new TiaData();
        tiaData.setTestRunHistory(Collections.singletonList(
                new TestRunHistoryEntry("a-full-history-id-1234", 1_700_000_000_000L, "main", "abc", 8, 2, 0,
                        1000L, true, 4000L, 80, 4000L, 80, null, null, null, null,
                        RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null, null, false, null)));

        // when
        new HtmlHistoryReport("html", tempDir).generateReport(tiaData);
        File page = new File(tempDir, "html" + File.separator + "html" + File.separator
                + "history" + File.separator + "tia-history.html");
        String html = new String(Files.readAllBytes(page.toPath()));

        // then the Date / time cell wraps the timestamp in a row link to the full-id detail page
        assertTrue(html.contains("<a class=\"tia-row-link\" href=\"a-full-history-id-1234.html\"><time"),
                "Date cell should link to the sibling detail page by the entry's full id. Output:\n" + html);
        // and the timestamp is marked to render without seconds, with a to-the-minute fallback
        assertTrue(html.contains("<time data-epoch-ms=\"1700000000000\" data-no-seconds>2023-11-14T22:13</time>"),
                "Table timestamp should be rendered to the minute. Output:\n" + html);
        // and the mapping column uses its short header with the full question as the hover title
        assertTrue(html.contains("<th><span title=\"Did this run update the test mapping?\">Mapping</span></th>"),
                "Mapping column should use the short header. Output:\n" + html);
        // and the Date / time column is typed "html" - simple-datatables reduces every other
        // column type to its text on render, which would strip the row link out
        assertTrue(html.contains("<th data-type=\"html\">Date / time (local)</th>"),
                "Date column should be typed html so its link survives. Output:\n" + html);
        // and the table is marked clickable with the row click script wired to it
        assertTrue(html.contains("class=\"tia-clickable-rows\""),
                "History table should carry the clickable-rows class. Output:\n" + html);
        assertTrue(html.contains("table.tia-clickable-rows"),
                "Page should include the row click script. Output:\n" + html);
        // and the Branch column is left to the detail page
        assertFalse(html.contains("<th>Branch</th>"), "Branch header should be removed. Output:\n" + html);
        // and the Id column is no longer rendered
        assertFalse(html.contains("<th>Id</th>"), "Id header should be removed. Output:\n" + html);
        assertFalse(html.contains("title=\"a-full-history-id-1234\""),
                "No cell should carry the id as a hover title. Output:\n" + html);
    }
}
