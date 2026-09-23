package org.tiatesting.core.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.report.html.HtmlSummaryReport;
import org.tiatesting.core.report.plaintext.TextSummaryReport;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the three Tia-level summary reports - the HTML summary page, the {@code status}
 * console output and the plain-text report - each render the Stats sections {@link SummaryStats}
 * builds: every heading, the wall-clock figures derived from the history, and, on the HTML page,
 * the hover hint on the savings line. The arithmetic itself is covered by {@link SummaryStatsTest}.
 */
class SummaryReportStatsTest {

    /**
     * A 6-group all-tests build of 10m that set the 1h serial baseline, then a partial build that
     * needed 1 of its 6 groups for 2m: an average wall clock of 6m against the 10m spread baseline
     * (40% saved), and 3.5 of 6 groups used on average.
     *
     * @return the history rows
     */
    private static List<TestRunHistoryEntry> history() {
        return Arrays.asList(
                new TestRunHistoryEntry("1", 1_000L, "main", "c1", 10, 0, 0, 3_600_000L, true, 0L, 0,
                        0L, 0, "run-1", Long.valueOf(600_000L), Integer.valueOf(6), Integer.valueOf(6),
                        RunOrigin.of(RunOrigin.SOURCE_CI, null), null, null, null, null, null),
                new TestRunHistoryEntry("2", 2_000L, "main", "c2", 1, 9, 0, 120_000L, true, 3_480_000L,
                        97, 480_000L, 80, "run-2", Long.valueOf(120_000L), Integer.valueOf(1),
                        Integer.valueOf(6), RunOrigin.of(RunOrigin.SOURCE_CI, null),
                        null, null, null, null, null));
    }

    /**
     * Build core data with a 1h serial all-tests baseline over two runs, one of them all-tests.
     *
     * @return the core data
     */
    private static TiaData coreData() {
        TiaData tiaData = new TiaData();
        tiaData.setCommitValue("abc123");
        tiaData.setBranch("main");
        tiaData.setLastUpdated(Instant.now());
        tiaData.getTestStats().setNumRuns(2);
        tiaData.getTestStats().setNumSuccessRuns(2);
        tiaData.getTestStats().setNumAllTestsRuns(1);
        tiaData.getTestStats().setAllTestsRunTime(3_600_000L);
        tiaData.getTestStats().setAvgRunTime(120_000L);
        return tiaData;
    }

    /**
     * Assert a rendered report carries every Stats heading.
     *
     * @param report the rendered report text or HTML
     */
    private static void assertHeadingsRendered(String report) {
        for (String heading : Arrays.asList("Source Code", "Test Run Stability", "Test Run Duration",
                "All Tests", "Partial Test Runs", "Savings")) {
            assertTrue(report.contains(heading), "missing heading '" + heading + "':\n" + report);
        }
    }

    @Test
    void statusReport_rendersTheStatsSections(@TempDir Path tempDir) {
        // given
        JdbcDataStore dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(
                H2ConnectionSettings.embedded(tempDir.toString())), BranchSchema.schemaName("test", null));
        dataStore.getTiaData(true);
        dataStore.persistCoreData(coreData());
        history().forEach(dataStore::persistTestRunHistoryEntry);

        // when
        String report = new StatusReportGenerator().generateSummaryReport(dataStore);
        dataStore.close();

        // then
        assertHeadingsRendered(report);
        assertTrue(report.contains("      Run time (distributed): 10m (6 groups)"), report);
        assertTrue(report.contains("      Run time (not distributed): 1h (1 group)"), report);
        assertTrue(report.contains("    Groups used: 3.5 (6 available)"), report);
        assertTrue(report.contains("      Average run time: 2m (20%)"),
                "the partial average is the partial build's wall clock: " + report);
        assertTrue(report.contains("    Average test run savings: 40%"), report);
    }

    @Test
    void textSummaryReport_rendersTheStatsSections(@TempDir File tempDir) throws Exception {
        // given
        TiaData tiaData = coreData();
        tiaData.setTestRunHistory(history());

        // when - generateSummaryReport writes the report to a file and returns its path
        String fileName = new TextSummaryReport("txt", tempDir).generateSummaryReport(tiaData);
        String text = new String(Files.readAllBytes(new File(fileName).toPath()));

        // then
        assertHeadingsRendered(text);
        assertTrue(text.contains("Total savings over all runs: 8m"), text);
        assertTrue(text.contains("Groups used: 3.5 (6 available)"), text);
    }

    @Test
    void htmlSummaryReport_rendersTheStatsSectionsWithHints(@TempDir File tempDir) throws Exception {
        // given
        TiaData tiaData = coreData();
        tiaData.setTestRunHistory(history());

        // when - the report writes index.html under <outputDir>/html/<ext>
        new HtmlSummaryReport("html", tempDir).generateSummaryReport(tiaData);
        File indexHtml = new File(tempDir, "html" + File.separator + "html" + File.separator + "index.html");
        String html = new String(Files.readAllBytes(indexHtml.toPath()));

        // then
        assertHeadingsRendered(html);
        assertTrue(html.contains("<h4>Test Run Duration</h4>"), html);
        assertTrue(html.contains("<h5>All Tests</h5>"), html);
        assertTrue(html.contains("<h5><span class=\"tia-stat-hint\" data-tooltip=\"Runs where Tia "
                + "selected a subset of the tests to run"), html);
        assertTrue(html.contains("<span class=\"tia-stat-hint\" data-tooltip=\"(All tests run time - Average run time)"), html);
        assertTrue(html.contains("<span>Number of all-tests runs: 1</span>"), html);
        assertTrue(html.contains("Average test run savings</span>: 40%"), html);
    }
}
