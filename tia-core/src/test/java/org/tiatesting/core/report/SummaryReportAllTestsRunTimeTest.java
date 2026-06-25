package org.tiatesting.core.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.report.html.HtmlSummaryReport;
import org.tiatesting.core.report.plaintext.TextSummaryReport;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the three Tia-level summary reports surface the all-tests-run stats: the partial /
 * all-tests run counts and times, the percentage of all-tests time on the average-run-time line,
 * and the total savings over all runs (from the history table). Average run time over a minute
 * drops its {@code ms} component.
 */
class SummaryReportAllTestsRunTimeTest {

    private static final long AVG_RUN_TIME = 90500L;        // 1m 30.5s
    private static final long ALL_TESTS_RUN_TIME = 181000L; // 3m 1s; avg is 50% of this
    private static final long NUM_ALL_TESTS_RUNS = 4L;
    private static final long NUM_RUNS = 6L;
    private static final long NUM_PARTIAL_RUNS = NUM_RUNS - NUM_ALL_TESTS_RUNS;

    // avg run time over a minute -> ms dropped ("1m 30s"), and 90500 is 50% of 181000
    private static final String EXPECTED_AVG_LINE = "Average run time: 1m 30s (50%)";
    // one partial run with 180000ms (3m) of frozen savings; the all-tests row saved nothing
    private static final String EXPECTED_SAVINGS_LINE = "Total savings over all runs: 3m";

    /**
     * History with one partial run carrying 3m of frozen savings and one all-tests run (0 saved).
     * The summary "Total savings" line sums the persisted {@code timeSavingsMs}.
     */
    private static List<TestRunHistoryEntry> history() {
        return Arrays.asList(
                new TestRunHistoryEntry("1", 0L, "main", "c1", 1, 3, 0, 1000L, false, 180_000L, 99),
                new TestRunHistoryEntry("2", 0L, "main", "c2", 5, 0, 0, ALL_TESTS_RUN_TIME, false, 0L, 0));
    }

    /**
     * Build an in-memory core {@link TiaData} carrying the all-tests-run stats and enough other
     * stats that the success/fail percentages don't divide by zero.
     */
    private TiaData coreData() {
        TiaData tiaData = new TiaData();
        tiaData.setCommitValue("abc123");
        tiaData.setBranch("main");
        tiaData.setLastUpdated(Instant.now());
        tiaData.getTestStats().setNumRuns(NUM_RUNS);
        tiaData.getTestStats().setNumSuccessRuns(NUM_RUNS);
        tiaData.getTestStats().setAvgRunTime(AVG_RUN_TIME);
        tiaData.getTestStats().setAllTestsRunTime(ALL_TESTS_RUN_TIME);
        tiaData.getTestStats().setNumAllTestsRuns(NUM_ALL_TESTS_RUNS);
        return tiaData;
    }

    @Test
    void statusReport_showsAllTestsRunStats(@TempDir Path tempDir) {
        // given
        H2DataStore dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.toString(), "test"));
        dataStore.getTiaData(true);
        dataStore.persistCoreData(coreData());
        history().forEach(dataStore::persistTestRunHistoryEntry);

        // when
        String report = new StatusReportGenerator().generateSummaryReport(dataStore);
        dataStore.close();

        // then
        assertTrue(report.contains("Number of partial runs: " + NUM_PARTIAL_RUNS), report);
        assertTrue(report.contains(EXPECTED_AVG_LINE), report);
        assertTrue(report.contains("Number of all-tests runs: " + NUM_ALL_TESTS_RUNS), report);
        assertTrue(report.contains("All tests run time: " + ReportUtils.prettyDuration(ALL_TESTS_RUN_TIME)), report);
        assertTrue(report.contains(EXPECTED_SAVINGS_LINE), report);
    }

    @Test
    void textSummaryReport_showsAllTestsRunStats(@TempDir File tempDir) throws Exception {
        // given
        TiaData tiaData = coreData();
        tiaData.setTestRunHistory(history());
        TextSummaryReport report = new TextSummaryReport("txt", tempDir);

        // when - generateSummaryReport writes the report to a file and returns its path
        String fileName = report.generateSummaryReport(tiaData);
        String text = new String(Files.readAllBytes(new File(fileName).toPath()));

        // then
        assertTrue(text.contains("Number of partial runs: " + NUM_PARTIAL_RUNS), text);
        assertTrue(text.contains(EXPECTED_AVG_LINE), text);
        assertTrue(text.contains("Number of all-tests runs: " + NUM_ALL_TESTS_RUNS), text);
        assertTrue(text.contains("All tests run time: " + ReportUtils.prettyDuration(ALL_TESTS_RUN_TIME)), text);
        assertTrue(text.contains(EXPECTED_SAVINGS_LINE), text);
    }

    @Test
    void htmlSummaryReport_showsAllTestsRunStats(@TempDir File tempDir) throws Exception {
        // given
        TiaData tiaData = coreData();
        tiaData.setTestRunHistory(history());
        HtmlSummaryReport report = new HtmlSummaryReport("html", tempDir);

        // when - the report writes index.html under <outputDir>/html/<ext>
        report.generateSummaryReport(tiaData);
        File indexHtml = new File(tempDir, "html" + File.separator + "html" + File.separator + "index.html");
        String html = new String(Files.readAllBytes(indexHtml.toPath()));

        // then
        assertTrue(html.contains("Number of partial runs: " + NUM_PARTIAL_RUNS), html);
        assertTrue(html.contains(EXPECTED_AVG_LINE), html);
        assertTrue(html.contains("Number of all-tests runs: " + NUM_ALL_TESTS_RUNS), html);
        assertTrue(html.contains("All tests run time: " + ReportUtils.prettyDuration(ALL_TESTS_RUN_TIME)), html);
        assertTrue(html.contains(EXPECTED_SAVINGS_LINE), html);
    }

    /**
     * With no all-tests baseline recorded, the average-run-time percentage bracket is omitted; and
     * with no per-run savings on the history rows (the only state consistent with no baseline), the
     * total savings line is omitted too.
     */
    @Test
    void textSummaryReport_noBaseline_omitsPercentAndSavings(@TempDir File tempDir) throws Exception {
        // given - no baseline means every run was persisted with zero frozen savings
        TiaData tiaData = coreData();
        tiaData.getTestStats().setAllTestsRunTime(0L);
        tiaData.getTestStats().setNumAllTestsRuns(0L);
        tiaData.setTestRunHistory(Collections.singletonList(
                new TestRunHistoryEntry("1", 0L, "main", "c1", 1, 3, 0, 1000L, false, 0L, 0)));
        TextSummaryReport report = new TextSummaryReport("txt", tempDir);

        // when
        String fileName = report.generateSummaryReport(tiaData);
        String text = new String(Files.readAllBytes(new File(fileName).toPath()));

        // then
        assertFalse(text.contains("Total savings over all runs:"), text);
        assertFalse(text.contains("Average run time: " + ReportUtils.prettyDurationDropMsAboveMinute(AVG_RUN_TIME) + " ("), text);
    }
}
