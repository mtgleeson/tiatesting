package org.tiatesting.core.report.html;
import static org.tiatesting.core.report.html.HtmlSourceMethodReport.*;
import static org.tiatesting.core.report.html.HtmlTestSuiteReport.*;
import j2html.Config;
import j2html.rendering.FlatHtml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.report.ReportUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static j2html.TagCreator.*;

public class HtmlSummaryReport {
    private static final Logger log = LoggerFactory.getLogger(HtmlSummaryReport.class);
    protected static final String INDEX_HTML = "index.html";
    private final String filenameExt;
    private final File reportOutputDir;
    private final DecimalFormat avgFormat = new DecimalFormat("###.#");

    public HtmlSummaryReport(String filenameExt, File reportOutputDir){
        this.filenameExt = filenameExt;
        this.reportOutputDir = new File(reportOutputDir.getAbsoluteFile() + File.separator + "html"
                + File.separator + filenameExt);
    }

    public void generateSummaryReport(TiaData tiaData) {
        createOutputDir();
        generateSummaryReportData(tiaData);
    }

    private void generateSummaryReportData(TiaData tiaData){
        long startTime = System.currentTimeMillis();
        String fileName = reportOutputDir + File.separator + INDEX_HTML;
        log.info("Writing the summary report to {}", fileName);

        Locale locale = Locale.getDefault();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss zzz", locale).withZone(ZoneId.systemDefault());

        try {
            FileWriter writer = new FileWriter(fileName);
            int numTestSuites = tiaData.getTestSuitesTracked().size();
            int numSourceMethods = tiaData.getMethodsTracked().size();
            TestStats stats = tiaData.getTestStats();

            html(
                    head(
                            link().attr("href=\"https://cdn.jsdelivr.net/npm/simple-datatables@8.0.1/dist/style.css\" rel=\"stylesheet\" type=\"text/css\""),
                            script().attr("src=\"https://cdn.jsdelivr.net/npm/simple-datatables@8.0.1\" type=\"text/javascript\"")
                    ), body(
                            header(
                                h2("Tia: Summary")
                            ),
                            main(
                                div(
                                    p(
                                      span("Report generated at: " + dtf.format(Instant.now()))
                                    ),
                                    p(
                                        a("Test Suites").attr("href=\""+ TEST_SUITES_FOLDER +"/" + TIA_TEST_SUITES_HTML + "\"")
                                    ),
                                    p(
                                        a("Source Methods").attr("href=\"" + METHODS_FOLDER + "/" + TIA_SOURCE_METHODS_HTML + "\"")
                                    ),
                                    h3("Tia DB"),
                                    p(
                                        span("DB last updated: " + (tiaData.getLastUpdated()!= null ? dtf.format(tiaData.getLastUpdated()) : "N/A")),
                                        br(),
                                        span("Test mapping valid for commit: " + tiaData.getCommitValue())
                                    ),
                                    h3("Stats"),
                                    p(
                                        span("Number of tests classes with mappings: " + numTestSuites),
                                        br(),
                                        span("Number of source methods tracked for tests: " + numSourceMethods)
                                    ),
                                    p(
                                        span("Number of runs: " + stats.getNumRuns()),
                                        br(),
                                        span("Average run time: " + ReportUtils.prettyDuration(stats.getAvgRunTime())),
                                        br(),
                                        span("Number of successful runs: " + stats.getNumSuccessRuns() + " (" + getAvgSuccess(tiaData.getTestStats()) + "%)"),
                                        br(),
                                        span("Number of failed runs: " + stats.getNumFailRuns() + " (" + getAvgFail(tiaData.getTestStats()) + "%)")
                                    ),
                                    h3("Pending Failed Tests"),
                                    p().condWith(tiaData.getTestSuitesFailed().size() == 0, span("none"))
                                            .condWith(tiaData.getTestSuitesFailed().size() > 0,
                                                    span(
                                                            each(tiaData.getTestSuitesFailed(), testSuiteFailed ->
                                                                    span(span(testSuiteFailed), br())
                                                            )
                                                    )
                                            )
                                )
                            )
                    ),
                    script("const dataTable = new simpleDatatables.DataTable(\"#tiaTable\", {\n" +
                            "\tcolumns: [{ select: 0, sort: \"asc\" }],\n" +
                            "\tsearchable: true,\n" +
                            "\tfixedHeight: true,\n" +
                            "\tpaging: true,\n" +
                            "\tperPage: 20,\n" +
                            "\tperPageSelect: [10, 20, 50, [\"All\", -1]]\n" +
                            "})")
            ).render(FlatHtml.into(writer, Config.defaults().withEmptyTagsClosed(true))).flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.info("Time to write the report (ms): " + (System.currentTimeMillis() - startTime));
    }

    private String getAvgSuccess(TestStats stats){
        double percSuccess = ((double)stats.getNumSuccessRuns()) / (double)(stats.getNumRuns()) * 100;
        return avgFormat.format(percSuccess);
    }

    private String getAvgFail(TestStats stats){
        double percFail = ((double)stats.getNumFailRuns()) / (double)(stats.getNumRuns()) * 100;
        return avgFormat.format(percFail);
    }

    private void createOutputDir() {
        reportOutputDir.mkdirs();
    }

}
