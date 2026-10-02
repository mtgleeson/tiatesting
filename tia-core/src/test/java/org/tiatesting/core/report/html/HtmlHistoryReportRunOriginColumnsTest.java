package org.tiatesting.core.report.html;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the HTML history page surfaces where each run came from as a Source column, and leaves
 * the executing machine to the run's detail page so the table fits the page without scrolling.
 */
class HtmlHistoryReportRunOriginColumnsTest {

    /**
     * A row that knows its origin renders its run source, but not its host - that is shown on the
     * run's detail page instead.
     *
     * @param tempDir JUnit-supplied directory the report is written into
     * @throws Exception if the report cannot be written or read back
     */
    @Test
    void aKnownOrigin_showsTheSourceColumnButNotTheHost(@TempDir File tempDir) throws Exception {
        // given
        TiaData tiaData = new TiaData();
        tiaData.setTestRunHistory(Collections.singletonList(
                entry("id1", 1_700_000_000_000L,
                        RunOrigin.of(RunOrigin.SOURCE_LOCAL, "dev-laptop-7"))));

        // when
        String html = generateAndRead(tiaData, tempDir);

        // then
        assertTrue(html.contains(">Source<"),
                "history table should have a Source header. Output:\n" + html);
        assertTrue(html.contains(RunOrigin.SOURCE_LOCAL),
                "the row should carry its run source. Output:\n" + html);
        assertFalse(html.contains(">Host<"),
                "the host is left to the detail page. Output:\n" + html);
        assertFalse(html.contains("dev-laptop-7"),
                "the row should not carry its host. Output:\n" + html);
    }

    /**
     * A distributed build records a source but no host. The source still renders: gating it on the
     * host would hide it entirely on a history made up of distributed builds.
     *
     * @param tempDir JUnit-supplied directory the report is written into
     * @throws Exception if the report cannot be written or read back
     */
    @Test
    void aDistributedRunStillRendersItsSource(@TempDir File tempDir) throws Exception {
        // given
        TiaData tiaData = new TiaData();
        tiaData.setTestRunHistory(Collections.singletonList(
                entry("id1", 1_700_000_000_000L, RunOrigin.of(RunOrigin.SOURCE_CI, null))));

        // when
        String html = generateAndRead(tiaData, tempDir);

        // then
        assertTrue(html.contains(">Source<"),
                "the source renders even with no host. Output:\n" + html);
        assertTrue(html.contains(RunOrigin.SOURCE_CI),
                "the row should carry its run source. Output:\n" + html);
    }

    /**
     * Build a history row that varies only in its id, timestamp and origin. Savings are non-zero so
     * the savings cells never dash.
     *
     * @param id the entry id
     * @param timestampMs the run timestamp in UTC epoch millis
     * @param origin the origin to stamp on the row
     * @return the populated entry
     */
    private TestRunHistoryEntry entry(final String id, final long timestampMs,
                                      final RunOrigin origin) {
        return new TestRunHistoryEntry(id, timestampMs, "main", "abc", 8, 2, 0, 20_000L,
                true, 4000L, 80, 4000L, 80, null, null, null, null, origin, null, null, null, null, null, false, null);
    }

    /**
     * Generate the history page into a temp directory and read it back as a string.
     *
     * @param tiaData the data whose history list is rendered
     * @param tempDir the directory to write the report tree into
     * @return the rendered HTML
     * @throws Exception if the report cannot be written or read back
     */
    private String generateAndRead(final TiaData tiaData, final File tempDir) throws Exception {
        new HtmlHistoryReport("html", tempDir).generateReport(tiaData);
        File page = new File(tempDir, "html" + File.separator + "html" + File.separator
                + "history" + File.separator + "tia-history.html");
        return new String(Files.readAllBytes(page.toPath()));
    }
}
