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
                new TestRunHistoryEntry("id1", 1_700_000_000_000L, "main", "abc", 8, 2, 0, 1000L, true, 4000L, 80,
                        null, null, null, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null, null),
                new TestRunHistoryEntry("id2", 1_699_000_000_000L, "main", "abc", 10, 0, 0, 5000L, true, 0L, 0,
                        null, null, null, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null, null)));

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
                new TestRunHistoryEntry("id1", 1_700_000_000_000L, "main", "abc", 8, 2, 0, 1000L, true, 4000L, 80,
                        null, null, null, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null, null),
                new TestRunHistoryEntry("id2", 1_699_000_000_000L, "main", "abc", 10, 0, 0, 5000L, true, 0L, 0,
                        null, null, null, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null, null)));

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
     * Verifies the Id column's cell is a link to the row's detail page - a sibling file in the
     * same {@code history/} folder named after the entry's full id - rather than plain text, and
     * that the full id is still available as the anchor's hover title.
     *
     * @param tempDir a JUnit-managed temp directory the report is written into
     */
    @Test
    void historyPage_idCellLinksToItsDetailPage(@TempDir File tempDir) throws Exception {
        // given a single history entry with a full id longer than the 8-char display truncation
        TiaData tiaData = new TiaData();
        tiaData.setTestRunHistory(Collections.singletonList(
                new TestRunHistoryEntry("a-full-history-id-1234", 1_700_000_000_000L, "main", "abc", 8, 2, 0,
                        1000L, true, 4000L, 80, null, null, null,
                        RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null, null)));

        // when
        new HtmlHistoryReport("html", tempDir).generateReport(tiaData);
        File page = new File(tempDir, "html" + File.separator + "html" + File.separator
                + "history" + File.separator + "tia-history.html");
        String html = new String(Files.readAllBytes(page.toPath()));

        // then the anchor href is the full id, not the truncated display text
        assertTrue(html.contains("href=\"a-full-history-id-1234.html\""),
                "Id cell should link to the sibling detail page by the entry's full id. Output:\n" + html);
        assertTrue(html.contains("title=\"a-full-history-id-1234\""),
                "Id cell should keep the full id as the hover title. Output:\n" + html);
    }
}
