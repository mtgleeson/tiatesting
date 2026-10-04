package org.tiatesting.core.report.html;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.SelectionMode;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestRunTrigger;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

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

        TestRunSelectionDetails details = new TestRunSelectionDetails(triggers, 3, 1, 2, 4, 0, SelectionMode.SELECTIVE);
        TestRunHistoryEntry entry = TestRunHistoryEntry.create("main", "abc123",
                1_700_000_000_000L, 10, 2, 0, 12345L, true, 4000L, 40,
                RunOrigin.of(RunOrigin.SOURCE_LOCAL, "build-host"), details, false);

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

    /**
     * A distributed run's summary shows both its times, both its savings and the groups it used
     * against the groups it had available, plus its source with a dashed host - it ran on several
     * machines and names none.
     *
     * @param tempDir JUnit-supplied directory the report is written into
     * @throws Exception if the page cannot be written or read back
     */
    @Test
    void generateReport_distributedRun_rendersBothTimesBothSavingsAndTheGroups(@TempDir File tempDir)
            throws Exception {
        // given - 60s baseline; 1 of 6 groups used for 2s: 58s (97%) serial, 8s (80%) wall clock
        TestRunHistoryEntry entry = TestRunHistoryEntry.createForDistributedRun("main", "abc123",
                "ci-run-1", 1_700_000_000_000L, 3, 7, 0, 2_000L, true, 58_000L, 97, 8_000L, 80,
                2_000L, 1, 6, RunOrigin.of(RunOrigin.SOURCE_CI, null), null);

        // when
        String html = generateAndRead(entry, tempDir);
        String text = visibleText(html);

        // then
        assertTrue(text.contains("Wall clock: 2s"), "should show the wall clock. Output:\n" + html);
        assertTrue(text.contains("Serial duration: 2s"), "should show the serial duration. Output:\n" + html);
        assertTrue(text.contains("Groups used: 1"), "should show the groups used. Output:\n" + html);
        assertTrue(text.contains("Groups available: 6"), "should show the groups available. Output:\n" + html);
        assertTrue(text.contains("Wall-clock savings: 8s (80%)"),
                "should show the wall-clock savings. Output:\n" + html);
        assertTrue(text.contains("Serial savings: 58s (97%)"),
                "should show the serial savings. Output:\n" + html);
        assertTrue(text.contains("Source: " + RunOrigin.SOURCE_CI), "should show the source. Output:\n" + html);
        assertTrue(text.contains("Host: -"), "a distributed run names no host. Output:\n" + html);
    }

    /**
     * A single-host run's two savings are the same figure, it has no groups to show, and its
     * summary names its source and the machine that ran it (the History table leaves the host
     * out).
     *
     * @param tempDir JUnit-supplied directory the report is written into
     * @throws Exception if the page cannot be written or read back
     */
    @Test
    void generateReport_singleHostRun_rendersEqualSavingsAndDashedGroups(@TempDir File tempDir)
            throws Exception {
        // given
        TestRunHistoryEntry entry = TestRunHistoryEntry.create("main", "abc123",
                1_700_000_000_000L, 10, 2, 0, 12_000L, true, 4_000L, 25,
                RunOrigin.of(RunOrigin.SOURCE_LOCAL, "laptop"), null, false);

        // when
        String html = generateAndRead(entry, tempDir);
        String text = visibleText(html);

        // then
        assertTrue(text.contains("Wall clock: 12s"), "the duration is the wall clock. Output:\n" + html);
        assertTrue(text.contains("Groups used: -"), "no groups on a single host. Output:\n" + html);
        assertTrue(text.contains("Groups available: -"), "no groups on a single host. Output:\n" + html);
        assertTrue(text.contains("Wall-clock savings: 4s (25%)"), "Output:\n" + html);
        assertTrue(text.contains("Serial savings: 4s (25%)"), "Output:\n" + html);
        assertTrue(text.contains("Source: " + RunOrigin.SOURCE_LOCAL), "should show the source. Output:\n" + html);
        assertTrue(text.contains("Host: laptop"), "should show the host. Output:\n" + html);
        assertTrue(text.contains("Rerun: no"), "the real run is not a rerun. Output:\n" + html);
    }

    /**
     * A rerun of failed tests says so in its summary.
     *
     * @param tempDir JUnit-supplied directory the report is written into
     * @throws Exception if the page cannot be written or read back
     */
    @Test
    void generateReport_rerun_rendersRerunYes(@TempDir File tempDir) throws Exception {
        // given
        TestRunHistoryEntry entry = TestRunHistoryEntry.create("main", "abc123",
                1_700_000_000_000L, 1, 9, 0, 4_000L, true, 0L, 0,
                RunOrigin.of(RunOrigin.SOURCE_CI, "agent"), null, true);

        // when
        String html = generateAndRead(entry, tempDir);
        String text = visibleText(html);

        // then
        assertTrue(text.contains("Rerun: yes"), "Output:\n" + html);
    }

    /**
     * A re-seed run names its mode and replaces the all-zero selection counters with one line
     * saying the selection was overridden.
     *
     * @param tempDir JUnit-supplied directory the report is written into
     * @throws Exception if the page cannot be written or read back
     */
    @Test
    void generateReport_reseed_rendersTheModeAndTheOverrideNote(@TempDir File tempDir) throws Exception {
        // given
        TestRunHistoryEntry entry = TestRunHistoryEntry.create("main", "abc123",
                1_700_000_000_000L, 10, 0, 0, 4_000L, true, 0L, 0,
                RunOrigin.of(RunOrigin.SOURCE_CI, "agent"),
                TestRunSelectionDetails.forFullRun(SelectionMode.RESEED), false);

        // when
        String html = generateAndRead(entry, tempDir);
        String text = visibleText(html);

        // then
        assertTrue(text.contains("Selection: Re-seed"), "Output:\n" + html);
        assertTrue(html.contains("Selection overridden - all tests run"), "Output:\n" + html);
        assertFalse(html.contains("Modified test files"), "Output:\n" + html);
    }

    /**
     * Every summary and selection-source field label carries a hover tooltip explaining it,
     * including the Selection line a forced run adds.
     *
     * @param tempDir JUnit-supplied directory the report is written into
     * @throws Exception if the page cannot be written or read back
     */
    @Test
    void generateReport_everyFieldLabelCarriesATooltip(@TempDir File tempDir) throws Exception {
        // given - a forced single-host run, so the Selection line renders, then a selective run
        // with every selection counter recorded, so the selection-source lines render
        TestRunHistoryEntry forced = TestRunHistoryEntry.create("main", "abc123",
                1_700_000_000_000L, 10, 0, 0, 4_000L, true, 0L, 0,
                RunOrigin.of(RunOrigin.SOURCE_CI, "agent"),
                TestRunSelectionDetails.forFullRun(SelectionMode.SELECT_ALL), false);
        TestRunHistoryEntry selective = TestRunHistoryEntry.create("main", "def456",
                1_700_000_500_000L, 10, 2, 0, 4_000L, true, 0L, 0,
                RunOrigin.of(RunOrigin.SOURCE_LOCAL, "laptop"),
                new TestRunSelectionDetails(Collections.<TestRunTrigger>emptyList(), 1, 1, 1, 1, 1,
                        SelectionMode.SELECTIVE), false);

        // when
        String forcedHtml = generateAndRead(forced, tempDir);
        String selectiveHtml = generateAndRead(selective, tempDir);

        // then
        List<String> forcedLabels = Arrays.asList("Date / time", "Branch", "Commit", "Source", "Host",
                "Rerun", "Selection", "Suites ran", "Wall clock", "Serial duration", "Groups used",
                "Groups available", "Wall-clock savings", "Serial savings");
        for (String label : forcedLabels) {
            assertTrue(Pattern.compile("<span class=\"tia-stat-hint\" data-tooltip=\"[^\"]+\">"
                            + Pattern.quote(label) + "</span>: ").matcher(forcedHtml).find(),
                    "'" + label + "' should carry a tooltip. Output:\n" + forcedHtml);
        }
        List<String> selectionSourceLabels = Arrays.asList("Modified test files", "New test files",
                "Previously-failed", "Unsealed-mapping", "Pending library");
        for (String label : selectionSourceLabels) {
            assertTrue(Pattern.compile("<span class=\"tia-stat-hint\" data-tooltip=\"[^\"]+\">"
                            + Pattern.quote(label) + "</span>: ").matcher(selectiveHtml).find(),
                    "'" + label + "' should carry a tooltip. Output:\n" + selectiveHtml);
        }
    }

    /**
     * Generate one run's detail page into a temp directory and read it back as a string.
     *
     * @param entry the run the page describes
     * @param tempDir the directory to write the report tree into
     * @return the rendered HTML
     * @throws Exception if the page cannot be written or read back
     */
    private String generateAndRead(TestRunHistoryEntry entry, File tempDir) throws Exception {
        new HtmlHistoryDetailReport("html", tempDir)
                .generateReport(entry, Collections.<TestRunTrigger>emptyList(), 0);
        File page = new File(tempDir, "html" + File.separator + "html" + File.separator
                + "history" + File.separator + entry.getId() + ".html");
        return new String(Files.readAllBytes(page.toPath()));
    }

    /**
     * Strip the tags from a rendered page, leaving the text a reader sees, so a field's label and
     * value can be matched as one string even though the label sits in its own tooltip span.
     *
     * @param html the rendered HTML
     * @return the page text without tags
     */
    private static String visibleText(String html) {
        return html.replaceAll("<[^>]+>", "");
    }

    @Test
    void generateReport_rendersNoBreakdownNoteWhenNothingRecorded(@TempDir File tempDir) throws Exception {
        // given - an entry with no triggers and no selection counters recorded at all
        TestRunHistoryEntry entry = TestRunHistoryEntry.create("main", "def456",
                1_700_000_500_000L, 5, 0, 0, 6789L, false, 0L, 0,
                RunOrigin.of(RunOrigin.SOURCE_LOCAL, "build-host"), null, false);

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
