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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the three Tia-level summary reports - the {@code status} console output, the plain-text
 * report and the HTML report's {@code index.html} - report a distributed project's two averages
 * separately, and that a project with no distributed build in its history sees no change at all.
 *
 * <p>The distinction the reports have to carry: {@code avgRunTime} is the <b>serial equivalent</b>
 * in both modes, because that is what savings are computed from and what keeps a project's stats
 * comparable across the build where distributed mode was switched on ({@code
 * DistributedRunSealer.buildRunStats} stores {@code totals.getSerialDurationMs()} into it). The wall
 * clock - what a distributed build actually waited for - lives only on the history rows, so its
 * average is derived from them.
 *
 * <p>All three surfaces are covered together because they render the same two lines through the
 * same helper and are the reason it returns a list rather than pre-joined text; a change that broke
 * only the HTML one would otherwise go unnoticed.
 */
class SummaryReportDistributedAverageTest {

    private static final long AVG_RUN_TIME = 100_000L;      // 1m 40s serial equivalent
    private static final long ALL_TESTS_RUN_TIME = 200_000L; // avg is 50% of this

    /** The qualifier only appears once a distributed build is in the history. */
    private static final String EXPECTED_SERIAL_LINE =
            "Average run time (serial equivalent): 1m 40s (50%)";
    /** Mean of the 40s and 60s wall clocks below, 25% of the all-tests baseline. */
    private static final String EXPECTED_DISTRIBUTED_LINE =
            "Average distributed run time: 50s (25%) over 2 distributed run(s)";
    /** What a project with no distributed build has always seen, and must keep seeing. */
    private static final String EXPECTED_UNQUALIFIED_LINE = "Average run time: 1m 40s (50%)";

    /**
     * History mixing two distributed builds with a single-host one, the shape a project has just
     * after adopting distributed mode - so the test also proves the single-host row's absent wall
     * clock is excluded rather than counted as a zero.
     *
     * @return three history rows, two of them distributed
     */
    private static List<TestRunHistoryEntry> mixedHistory() {
        return Arrays.asList(
                new TestRunHistoryEntry("1", 0L, "main", "c1", 5, 3, 0, 120_000L, false, 0L, 0,
                        "run-1", Long.valueOf(40_000L), Integer.valueOf(3), RunOrigin.unknown()),
                new TestRunHistoryEntry("2", 0L, "main", "c2", 4, 4, 0, 90_000L, false, 0L, 0,
                        null, null, null, RunOrigin.unknown()),
                new TestRunHistoryEntry("3", 0L, "main", "c3", 6, 2, 0, 140_000L, false, 0L, 0,
                        "run-2", Long.valueOf(60_000L), Integer.valueOf(3), RunOrigin.unknown()));
    }

    /**
     * History with no distributed build at all - the state of every project that has never used the
     * feature.
     *
     * @return two single-host history rows
     */
    private static List<TestRunHistoryEntry> singleHostHistory() {
        return Arrays.asList(
                new TestRunHistoryEntry("1", 0L, "main", "c1", 5, 3, 0, 120_000L, false, 0L, 0,
                        null, null, null, RunOrigin.unknown()),
                new TestRunHistoryEntry("2", 0L, "main", "c2", 4, 4, 0, 90_000L, false, 0L, 0,
                        null, null, null, RunOrigin.unknown()));
    }

    /**
     * Build an in-memory core {@link TiaData} carrying the serial-equivalent average and the
     * all-tests baseline both lines report a percentage against.
     *
     * @param history the run history to attach
     * @return the populated core data
     */
    private static TiaData coreData(final List<TestRunHistoryEntry> history) {
        TiaData tiaData = new TiaData();
        tiaData.setCommitValue("abc123");
        tiaData.setBranch("main");
        tiaData.setLastUpdated(Instant.now());
        tiaData.getTestStats().setNumRuns(6L);
        tiaData.getTestStats().setNumSuccessRuns(6L);
        tiaData.getTestStats().setAvgRunTime(AVG_RUN_TIME);
        tiaData.getTestStats().setAllTestsRunTime(ALL_TESTS_RUN_TIME);
        tiaData.getTestStats().setNumAllTestsRuns(2L);
        tiaData.setTestRunHistory(history);
        return tiaData;
    }

    /**
     * Read the status report from a real embedded datastore, since that report reads its history
     * through {@link org.tiatesting.core.persistence.DataStore#readTestRunHistory()} rather than
     * from the in-memory core data the other two use.
     *
     * @param tempDir the directory to root the embedded database in
     * @param history the history rows to persist
     * @return the rendered status report
     */
    private static String statusReport(final Path tempDir, final List<TestRunHistoryEntry> history) {
        JdbcDataStore dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.toString())),
                BranchSchema.schemaName("test", null));
        dataStore.getTiaData(true);
        dataStore.persistCoreData(coreData(history));
        history.forEach(dataStore::persistTestRunHistoryEntry);
        try {
            return new StatusReportGenerator().generateSummaryReport(dataStore);
        } finally {
            dataStore.close();
        }
    }

    /**
     * Verify the status console output reports both averages, qualifying the stored one as the
     * serial equivalent so the pair cannot be read as the same builds having got faster.
     */
    @Test
    void statusReport_withDistributedRuns_showsBothAverages(@TempDir Path tempDir) {
        // given / when
        String report = statusReport(tempDir, mixedHistory());

        // then
        assertTrue(report.contains(EXPECTED_SERIAL_LINE), report);
        assertTrue(report.contains(EXPECTED_DISTRIBUTED_LINE), report);
    }

    /**
     * Verify a project that has never run a distributed build sees exactly the line it always has -
     * no qualifier, no second line. The feature must be invisible until it is used.
     */
    @Test
    void statusReport_withoutDistributedRuns_isUnchanged(@TempDir Path tempDir) {
        // given / when
        String report = statusReport(tempDir, singleHostHistory());

        // then
        assertTrue(report.contains(EXPECTED_UNQUALIFIED_LINE), report);
        assertFalse(report.contains("serial equivalent"), report);
        assertFalse(report.contains("Average distributed run time"), report);
    }

    /**
     * Verify the plain-text report carries both averages too, so the three summary surfaces agree.
     *
     * @throws Exception if the report file cannot be read
     */
    @Test
    void textSummaryReport_withDistributedRuns_showsBothAverages(@TempDir File tempDir) throws Exception {
        // given
        TextSummaryReport report = new TextSummaryReport("txt", tempDir);

        // when
        String fileName = report.generateSummaryReport(coreData(mixedHistory()));
        String text = new String(Files.readAllBytes(new File(fileName).toPath()));

        // then
        assertTrue(text.contains(EXPECTED_SERIAL_LINE), text);
        assertTrue(text.contains(EXPECTED_DISTRIBUTED_LINE), text);
    }

    /**
     * Verify the HTML report's landing page carries both averages, each in its own element. This is
     * the surface the shared helper returns a list for - joining the lines with a line separator
     * here would render them as one run-together line, which no assertion on the console output
     * would catch.
     *
     * @throws Exception if the generated page cannot be read
     */
    @Test
    void htmlSummaryReport_withDistributedRuns_showsBothAveragesAsSeparateLines(@TempDir File tempDir)
            throws Exception {
        // given
        HtmlSummaryReport report = new HtmlSummaryReport("html", tempDir);

        // when - the report writes index.html under <outputDir>/html/<ext>
        report.generateSummaryReport(coreData(mixedHistory()));
        File indexHtml = new File(tempDir, "html" + File.separator + "html" + File.separator + "index.html");
        String html = new String(Files.readAllBytes(indexHtml.toPath()));

        // then - each line is its own span, separated by a break rather than run together
        assertTrue(html.contains("<span>" + EXPECTED_SERIAL_LINE + "</span>"), html);
        assertTrue(html.contains("<span>" + EXPECTED_DISTRIBUTED_LINE + "</span>"), html);
        assertTrue(html.contains("<span>" + EXPECTED_SERIAL_LINE + "</span><br/><span>"
                + EXPECTED_DISTRIBUTED_LINE + "</span><br/>"), html);
    }

    /**
     * Verify the HTML landing page is unchanged for a project with no distributed build, matching
     * the console report's behaviour.
     *
     * @throws Exception if the generated page cannot be read
     */
    @Test
    void htmlSummaryReport_withoutDistributedRuns_isUnchanged(@TempDir File tempDir) throws Exception {
        // given
        HtmlSummaryReport report = new HtmlSummaryReport("html", tempDir);

        // when
        report.generateSummaryReport(coreData(singleHostHistory()));
        File indexHtml = new File(tempDir, "html" + File.separator + "html" + File.separator + "index.html");
        String html = new String(Files.readAllBytes(indexHtml.toPath()));

        // then
        assertTrue(html.contains("<span>" + EXPECTED_UNQUALIFIED_LINE + "</span>"), html);
        assertFalse(html.contains("serial equivalent"), html);
        assertFalse(html.contains("Average distributed run time"), html);
    }
}
