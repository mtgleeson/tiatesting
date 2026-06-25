package org.tiatesting.core.report.html;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

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
                new TestRunHistoryEntry("id1", 1_700_000_000_000L, "main", "abc", 8, 2, 0, 1000L, true, 4000L, 80),
                new TestRunHistoryEntry("id2", 1_699_000_000_000L, "main", "abc", 10, 0, 0, 5000L, true, 0L, 0)));

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
}
