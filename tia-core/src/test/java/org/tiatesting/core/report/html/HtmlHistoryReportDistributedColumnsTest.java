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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the HTML history page reports every run in wall-clock terms: a Wall clock column that
 * shows how long each run took end to end, and Savings columns carrying the wall-clock savings,
 * with no serial Duration column. A Groups column appears only when the history has a distributed
 * build in it.
 */
class HtmlHistoryReportDistributedColumnsTest {

    /**
     * A distributed build's row shows its wall clock, the groups it used and its wall-clock
     * savings - not its serial duration or serial savings, which belong on the detail page.
     *
     * @param tempDir JUnit-supplied directory the report is written into
     * @throws Exception if the report cannot be written or read back
     */
    @Test
    void distributedRun_showsTheWallClockAndWallClockSavings(@TempDir File tempDir) throws Exception {
        // given - 20s serial across 3 of 6 groups in 8s: 40s (67%) serial savings, 2s (20%) wall clock
        TiaData tiaData = new TiaData();
        tiaData.setTestRunHistory(Collections.singletonList(
                new TestRunHistoryEntry("id1", 1_700_000_000_000L, "main", "abc", 8, 2, 0, 20_000L,
                        true, 40_000L, 67, 2_000L, 20, "run-1", Long.valueOf(8_000L),
                        Integer.valueOf(3), Integer.valueOf(6), RunOrigin.of(RunOrigin.SOURCE_LOCAL, null),
                        null, null, null, null, null)));

        // when
        String html = generateAndRead(tiaData, tempDir);

        // then
        assertTrue(html.contains(">Wall clock<"), "the table should have a Wall clock header. Output:\n" + html);
        assertTrue(html.contains(">Groups<"), "the table should have a Groups header. Output:\n" + html);
        assertFalse(html.contains(">Duration<"), "the table should have no Duration header. Output:\n" + html);
        assertTrue(html.contains(">8s<"), "the Wall clock column should carry the build's time. Output:\n" + html);
        assertTrue(html.contains(">2s<"), "the Savings column should carry the wall-clock savings. Output:\n" + html);
        assertTrue(html.contains(">20%<"), "the Savings % column should follow them. Output:\n" + html);
        assertFalse(html.contains(">20s<"), "the serial duration belongs on the detail page. Output:\n" + html);
        assertFalse(html.contains(">40s<"), "the serial savings belong on the detail page. Output:\n" + html);
    }

    /**
     * A history of single-host runs still shows the Wall clock column - a single machine's duration
     * is its wall clock - but no Groups column, which would be a dash on every row.
     *
     * @param tempDir JUnit-supplied directory the report is written into
     * @throws Exception if the report cannot be written or read back
     */
    @Test
    void singleHostOnlyHistory_showsTheDurationAsTheWallClockAndOmitsGroups(@TempDir File tempDir)
            throws Exception {
        // given
        TiaData tiaData = new TiaData();
        tiaData.setTestRunHistory(Collections.singletonList(
                new TestRunHistoryEntry("id1", 1_700_000_000_000L, "main", "abc", 8, 2, 0, 1000L,
                        true, 4000L, 80, 4000L, 80, null, null, null, null,
                        RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null, null)));

        // when
        String html = generateAndRead(tiaData, tempDir);

        // then
        assertTrue(html.contains(">Wall clock<"), "every history has a Wall clock column. Output:\n" + html);
        assertTrue(html.contains(">1s<"), "a single-host run's duration is its wall clock. Output:\n" + html);
        assertFalse(html.contains(">Groups<"),
                "a history with no distributed runs needs no groups column. Output:\n" + html);
    }

    /**
     * In a mixed history the single-host rows show a dash in the Groups column rather than a zero,
     * which would read as a build that used no groups.
     *
     * @param tempDir JUnit-supplied directory the report is written into
     * @throws Exception if the report cannot be written or read back
     */
    @Test
    void mixedHistory_showsADashForTheSingleHostRows(@TempDir File tempDir) throws Exception {
        // given
        TiaData tiaData = new TiaData();
        tiaData.setTestRunHistory(Arrays.asList(
                new TestRunHistoryEntry("id1", 1_700_000_000_000L, "main", "abc", 8, 2, 0, 20_000L,
                        true, 4000L, 80, 4000L, 80, "run-1", Long.valueOf(8_000L), Integer.valueOf(3),
                        Integer.valueOf(3), RunOrigin.of(RunOrigin.SOURCE_LOCAL, null),
                        null, null, null, null, null),
                new TestRunHistoryEntry("id2", 1_699_000_000_000L, "main", "abc", 10, 0, 0, 5000L,
                        true, 0L, 0, 0L, 0, null, null, null, null,
                        RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, null, null, null, null)));

        // when
        String html = generateAndRead(tiaData, tempDir);

        // then
        // Three dashed cells in total: the single-host row's two savings cells (it saved nothing)
        // plus its Groups cell. The distributed row dashes nothing.
        assertEquals(3, countOccurrences(html, ">-</td>"),
                "the single-host row should show a dash in the Groups column. Output:\n" + html);
    }

    /**
     * Count non-overlapping occurrences of a token in the rendered page.
     *
     * @param html the rendered page
     * @param token the token to count
     * @return the number of occurrences
     */
    private int countOccurrences(final String html, final String token) {
        int count = 0;
        int index = html.indexOf(token);
        while (index >= 0) {
            count++;
            index = html.indexOf(token, index + token.length());
        }
        return count;
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
