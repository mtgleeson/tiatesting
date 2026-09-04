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
 * Verifies the HTML history page reports a distributed build's two durations distinctly: the
 * Duration column keeps the serial-equivalent time savings are computed from, and a separate wall
 * clock column shows what the build actually took, next to the number of groups it was split
 * across. A history with no distributed runs in it renders neither column.
 */
class HtmlHistoryReportDistributedColumnsTest {

    /**
     * A distributed build's row carries both durations and its group count, so the user can see
     * both what the build cost and what it would have cost unsplit.
     *
     * @param tempDir JUnit-supplied directory the report is written into
     * @throws Exception if the report cannot be written or read back
     */
    @Test
    void distributedRun_showsTheWallClockAndGroupColumns(@TempDir File tempDir) throws Exception {
        // given - a build whose groups summed to 20s but which took 8s across 3 groups
        TiaData tiaData = new TiaData();
        tiaData.setTestRunHistory(Collections.singletonList(
                new TestRunHistoryEntry("id1", 1_700_000_000_000L, "main", "abc", 8, 2, 0, 20_000L,
                        true, 4000L, 80, "run-1", Long.valueOf(8_000L), Integer.valueOf(3), RunOrigin.unknown())));

        // when
        String html = generateAndRead(tiaData, tempDir);

        // then
        assertTrue(html.contains("Wall clock"),
                "history table should have a Wall clock header. Output:\n" + html);
        assertTrue(html.contains("Groups"),
                "history table should have a Groups header. Output:\n" + html);
        assertTrue(html.contains("20s"),
                "the Duration column should carry the serial-equivalent time. Output:\n" + html);
        assertTrue(html.contains("8s"),
                "the Wall clock column should carry the build's actual time. Output:\n" + html);
    }

    /**
     * A history of single-host runs renders the table it always did - the distributed columns would
     * be a dash on every row, so they are omitted entirely.
     *
     * @param tempDir JUnit-supplied directory the report is written into
     * @throws Exception if the report cannot be written or read back
     */
    @Test
    void singleHostOnlyHistory_omitsTheDistributedColumns(@TempDir File tempDir) throws Exception {
        // given
        TiaData tiaData = new TiaData();
        tiaData.setTestRunHistory(Collections.singletonList(
                new TestRunHistoryEntry("id1", 1_700_000_000_000L, "main", "abc", 8, 2, 0, 1000L,
                        true, 4000L, 80, null, null, null, RunOrigin.unknown())));

        // when
        String html = generateAndRead(tiaData, tempDir);

        // then
        assertFalse(html.contains("Wall clock"),
                "a history with no distributed runs needs no wall clock column. Output:\n" + html);
        assertFalse(html.contains(">Groups<"),
                "a history with no distributed runs needs no groups column. Output:\n" + html);
    }

    /**
     * In a mixed history the single-host rows show a dash in the distributed columns rather than a
     * zero, which would read as a build that took no time and used no groups.
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
                        true, 4000L, 80, "run-1", Long.valueOf(8_000L), Integer.valueOf(3), RunOrigin.unknown()),
                new TestRunHistoryEntry("id2", 1_699_000_000_000L, "main", "abc", 10, 0, 0, 5000L,
                        true, 0L, 0, null, null, null, RunOrigin.unknown())));

        // when
        String html = generateAndRead(tiaData, tempDir);

        // then
        assertTrue(html.contains("Wall clock"),
                "the distributed run in the history warrants the column. Output:\n" + html);
        // Four dashed cells in total: the single-host row's two savings cells (it saved nothing)
        // plus its two distributed cells. The distributed row dashes nothing.
        assertEquals(4, countOccurrences(html, ">-</td>"),
                "the single-host row should show a dash in both distributed columns. Output:\n" + html);
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
