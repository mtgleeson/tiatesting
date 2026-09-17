package org.tiatesting.core.report.html;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestRunTrigger;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the per-run HTML history detail page: the summary block, the selection-source
 * counters, and the two ranked trigger tables (source methods, static rules), including the
 * fallback note rendered when a run recorded no selection breakdown at all.
 */
class HtmlHistoryDetailReportTest {

    @Test
    void generateReport_rendersSummaryAndRankedTriggerTables(@TempDir File tempDir) throws Exception {
        // given - an entry with two source-method triggers of different counts, one static
        // rule trigger, and all five selection counters recorded
        TestRunTrigger highCountMethod = new TestRunTrigger(TestRunTrigger.Type.SOURCE_METHOD,
                "com.example.Foo#bar", 9);
        TestRunTrigger lowCountMethod = new TestRunTrigger(TestRunTrigger.Type.SOURCE_METHOD,
                "com.example.Baz#qux", 2);
        TestRunTrigger staticRule = new TestRunTrigger(TestRunTrigger.Type.STATIC_RULE,
                "force-run-smoke-tests", 5);
        List<TestRunTrigger> triggers = Arrays.asList(lowCountMethod, highCountMethod, staticRule);

        TestRunSelectionDetails details = new TestRunSelectionDetails(triggers, 3, 1, 2, 4, 0);
        TestRunHistoryEntry entry = TestRunHistoryEntry.create("main", "abc123",
                1_700_000_000_000L, 10, 2, 0, 12345L, true, 4000L, 40,
                RunOrigin.of(RunOrigin.SOURCE_LOCAL, "build-host"), details);

        // when
        new HtmlHistoryDetailReport("html", tempDir).generateReport(entry, triggers, 3);
        File page = new File(tempDir, "html" + File.separator + "html" + File.separator
                + "history" + File.separator + entry.getId() + ".html");
        String html = new String(Files.readAllBytes(page.toPath()));

        // then
        assertTrue(Files.exists(page.toPath()), "detail page should be written. Output:\n" + html);
        assertTrue(html.contains("com.example.Foo#bar"), "should contain the high-count method name. Output:\n" + html);
        assertTrue(html.contains("com.example.Baz#qux"), "should contain the low-count method name. Output:\n" + html);
        assertTrue(html.contains("force-run-smoke-tests"), "should contain the static rule name. Output:\n" + html);
        assertTrue(html.contains(">9<"), "should contain the high trigger count. Output:\n" + html);
        assertTrue(html.contains(">2<"), "should contain the low trigger count. Output:\n" + html);
        assertTrue(html.contains(">5<"), "should contain the static rule count. Output:\n" + html);
        assertTrue(html.contains("Modified test files"), "should contain the modified-test-files counter label. Output:\n" + html);
        assertTrue(html.contains("New test files"), "should contain the new-test-files counter label. Output:\n" + html);
        assertTrue(html.contains("Previously-failed"), "should contain the previously-failed counter label. Output:\n" + html);
        assertTrue(html.contains("Unsealed-mapping"), "should contain the unsealed-mapping counter label. Output:\n" + html);
        assertTrue(html.contains("Pending library"), "should contain the pending-library counter label. Output:\n" + html);

        int highIndex = html.indexOf("com.example.Foo#bar");
        int lowIndex = html.indexOf("com.example.Baz#qux");
        assertTrue(highIndex >= 0 && lowIndex >= 0 && highIndex < lowIndex,
                "higher-count method should be ranked before the lower-count one. Output:\n" + html);

        assertTrue(html.contains("data-epoch-ms=\"1700000000000\""),
                "timestamp should carry the entry's epoch ms. Output:\n" + html);
    }

    @Test
    void generateReport_rendersNoBreakdownNoteWhenNothingRecorded(@TempDir File tempDir) throws Exception {
        // given - an entry with no triggers and no selection counters recorded at all
        TestRunHistoryEntry entry = TestRunHistoryEntry.create("main", "def456",
                1_700_000_500_000L, 5, 0, 0, 6789L, false, 0L, 0,
                RunOrigin.of(RunOrigin.SOURCE_LOCAL, "build-host"), null);

        // when
        new HtmlHistoryDetailReport("html", tempDir).generateReport(entry, Collections.<TestRunTrigger>emptyList(), 0);
        File page = new File(tempDir, "html" + File.separator + "html" + File.separator
                + "history" + File.separator + entry.getId() + ".html");
        String html = new String(Files.readAllBytes(page.toPath()));

        // then
        assertTrue(html.contains("No selection breakdown was recorded for this run."),
                "should render the no-breakdown note. Output:\n" + html);
        assertFalse(html.contains("Source method changes"),
                "should not render the source-method table heading. Output:\n" + html);
        assertFalse(html.contains("Static rules"),
                "should not render the static-rule table heading. Output:\n" + html);
    }
}
