package org.tiatesting.core.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.report.html.HtmlSummaryReport;
import org.tiatesting.core.report.plaintext.TextSummaryReport;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the three Tia-level summary reports surface the all-tests-run stats: a
 * "Number of all-tests runs" line and an "All tests run time" line (rendered via
 * {@link ReportUtils#prettyDuration(long)}) alongside the existing run-count / average lines.
 */
class SummaryReportAllTestsRunTimeTest {

    private static final long ALL_TESTS_RUN_TIME = 1500L;
    private static final long NUM_ALL_TESTS_RUNS = 4L;
    private static final long NUM_RUNS = 6L;
    private static final long NUM_PARTIAL_RUNS = NUM_RUNS - NUM_ALL_TESTS_RUNS;

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
        tiaData.getTestStats().setAvgRunTime(200L);
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

        // when
        String report = new StatusReportGenerator().generateSummaryReport(dataStore);
        dataStore.close();

        // then
        assertTrue(report.contains("Number of partial runs: " + NUM_PARTIAL_RUNS), report);
        assertTrue(report.contains("Number of all-tests runs: " + NUM_ALL_TESTS_RUNS), report);
        assertTrue(report.contains("All tests run time: " + ReportUtils.prettyDuration(ALL_TESTS_RUN_TIME)), report);
    }

    @Test
    void textSummaryReport_showsAllTestsRunStats(@TempDir File tempDir) throws Exception {
        // given
        TextSummaryReport report = new TextSummaryReport("txt", tempDir);

        // when - generateSummaryReport writes the report to a file and returns its path
        String fileName = report.generateSummaryReport(coreData());
        String text = new String(Files.readAllBytes(new File(fileName).toPath()));

        // then
        assertTrue(text.contains("Number of partial runs: " + NUM_PARTIAL_RUNS), text);
        assertTrue(text.contains("Number of all-tests runs: " + NUM_ALL_TESTS_RUNS), text);
        assertTrue(text.contains("All tests run time: " + ReportUtils.prettyDuration(ALL_TESTS_RUN_TIME)), text);
    }

    @Test
    void htmlSummaryReport_showsAllTestsRunStats(@TempDir File tempDir) throws Exception {
        // given
        HtmlSummaryReport report = new HtmlSummaryReport("html", tempDir);

        // when - the report writes index.html under <outputDir>/html/<ext>
        report.generateSummaryReport(coreData());
        File indexHtml = new File(tempDir, "html" + File.separator + "html" + File.separator + "index.html");
        String html = new String(Files.readAllBytes(indexHtml.toPath()));

        // then
        assertTrue(html.contains("Number of partial runs: " + NUM_PARTIAL_RUNS), html);
        assertTrue(html.contains("Number of all-tests runs: " + NUM_ALL_TESTS_RUNS), html);
        assertTrue(html.contains("All tests run time: " + ReportUtils.prettyDuration(ALL_TESTS_RUN_TIME)), html);
    }
}
