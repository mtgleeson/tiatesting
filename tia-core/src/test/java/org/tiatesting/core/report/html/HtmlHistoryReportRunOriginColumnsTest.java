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
 * Verifies the HTML history page surfaces where each run came from - the CI/local source and the
 * executing machine - and that the pair is omitted entirely from a history that never recorded it.
 */
class HtmlHistoryReportRunOriginColumnsTest {

    /**
     * A row that knows its origin renders both columns with that row's values.
     *
     * @param tempDir JUnit-supplied directory the report is written into
     * @throws Exception if the report cannot be written or read back
     */
    @Test
    void aKnownOrigin_showsTheSourceAndHostColumns(@TempDir File tempDir) throws Exception {
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
        assertTrue(html.contains(">Host<"),
                "history table should have a Host header. Output:\n" + html);
        assertTrue(html.contains(RunOrigin.SOURCE_LOCAL),
                "the row should carry its run source. Output:\n" + html);
        assertTrue(html.contains("dev-laptop-7"),
                "the row should carry its host. Output:\n" + html);
    }

    /**
     * A history recorded entirely before the columns existed renders neither, rather than dashing
     * both on every row.
     *
     * @param tempDir JUnit-supplied directory the report is written into
     * @throws Exception if the report cannot be written or read back
     */
    @Test
    void aHistoryWithNoRecordedOrigin_omitsBothColumns(@TempDir File tempDir) throws Exception {
        // given
        TiaData tiaData = new TiaData();
        tiaData.setTestRunHistory(Collections.singletonList(
                entry("id1", 1_700_000_000_000L, RunOrigin.unknown())));

        // when
        String html = generateAndRead(tiaData, tempDir);

        // then
        assertFalse(html.contains(">Source<"),
                "a history with no recorded origin needs no Source column. Output:\n" + html);
        assertFalse(html.contains(">Host<"),
                "a history with no recorded origin needs no Host column. Output:\n" + html);
    }

    /**
     * A distributed build records a source but no host, so requiring both halves would hide the
     * source entirely on a history made up of distributed builds. Either half turns the pair on,
     * and the missing host is dashed.
     *
     * @param tempDir JUnit-supplied directory the report is written into
     * @throws Exception if the report cannot be written or read back
     */
    @Test
    void aSourceWithNoHost_stillRendersThePairWithTheHostDashed(@TempDir File tempDir) throws Exception {
        // given
        TiaData tiaData = new TiaData();
        tiaData.setTestRunHistory(Collections.singletonList(
                entry("id1", 1_700_000_000_000L, RunOrigin.of(RunOrigin.SOURCE_CI, null))));

        // when
        String html = generateAndRead(tiaData, tempDir);

        // then
        assertTrue(html.contains(">Host<"),
                "a recorded source alone warrants the pair. Output:\n" + html);
        assertTrue(html.contains(RunOrigin.SOURCE_CI),
                "the row should carry its run source. Output:\n" + html);
        assertTrue(html.contains(">-</td>"),
                "the absent host should be dashed, not blank. Output:\n" + html);
    }

    /**
     * In a mixed history a row that predates the columns dashes them, so an absent origin reads as
     * "not recorded" rather than as a rendering slip.
     *
     * @param tempDir JUnit-supplied directory the report is written into
     * @throws Exception if the report cannot be written or read back
     */
    @Test
    void aMixedHistory_dashesTheRowThatPredatesTheColumns(@TempDir File tempDir) throws Exception {
        // given
        TiaData tiaData = new TiaData();
        tiaData.setTestRunHistory(Arrays.asList(
                entry("id1", 1_700_000_000_000L, RunOrigin.of(RunOrigin.SOURCE_CI, "build-agent-3")),
                entry("id2", 1_699_000_000_000L, RunOrigin.unknown())));

        // when
        String html = generateAndRead(tiaData, tempDir);

        // then
        assertTrue(html.contains("build-agent-3"),
                "the row with an origin should carry its host. Output:\n" + html);
        assertTrue(html.contains(">-</td>"),
                "the row without one should be dashed. Output:\n" + html);
    }

    /**
     * Build a history row that varies only in its id, timestamp and origin. Savings are non-zero so
     * the savings cells never dash, keeping the dash assertions above attributable to the origin
     * columns alone.
     *
     * @param id the entry id
     * @param timestampMs the run timestamp in UTC epoch millis
     * @param origin the origin to stamp on the row
     * @return the populated entry
     */
    private TestRunHistoryEntry entry(final String id, final long timestampMs,
                                      final RunOrigin origin) {
        return new TestRunHistoryEntry(id, timestampMs, "main", "abc", 8, 2, 0, 20_000L,
                true, 4000L, 80, null, null, null, origin);
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
