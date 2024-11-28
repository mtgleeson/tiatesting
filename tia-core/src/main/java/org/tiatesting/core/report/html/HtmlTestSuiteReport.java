package org.tiatesting.core.report.html;

import j2html.Config;
import j2html.rendering.FlatHtml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Set;
import java.util.stream.Collectors;

import static j2html.TagCreator.*;
import static org.tiatesting.core.report.html.HtmlSummaryReport.INDEX_HTML;
import static org.tiatesting.core.report.html.HtmlSourceMethodReport.METHODS_FOLDER;

public class HtmlTestSuiteReport {
    private static final Logger log = LoggerFactory.getLogger(HtmlTestSuiteReport.class);
    protected static final String TEST_SUITES_FOLDER = "test-suites";
    protected static final String TIA_TEST_SUITES_HTML = "tia-test-suites.html";
    private final File reportOutputDir;
    private final DecimalFormat avgFormat = new DecimalFormat("###.#");

    public HtmlTestSuiteReport(String filenameExt, File reportOutputDir){
        this.reportOutputDir = new File(reportOutputDir.getAbsoluteFile() + File.separator + "html"
                + File.separator + filenameExt + File.separator + TEST_SUITES_FOLDER);
    }

    public void generateTestSuiteReport(TiaData tiaData) {
        createOutputDir();
        generateTestSuiteReportData(tiaData);
        generateSourceMethodReport(tiaData);
    }

    private void generateTestSuiteReportData(TiaData tiaData){
        long startTime = System.currentTimeMillis();
        String fileName = reportOutputDir + File.separator + TIA_TEST_SUITES_HTML;
        log.info("Writing the test suite report to {}", fileName);

        try {
            FileWriter writer = new FileWriter(fileName);
            final String numberDataType = "data-type=\"number\"";

            html(
                    head(
                            link().attr("href=\"https://cdn.jsdelivr.net/npm/simple-datatables@8.0.1/dist/style.css\" rel=\"stylesheet\" type=\"text/css\""),
                            script().attr("src=\"https://cdn.jsdelivr.net/npm/simple-datatables@8.0.1\" type=\"text/javascript\"")
                    ), body(
                            header(
                                    h2("Tia: Test Suites")
                            ),
                            p(
                                    a("back to Summary").attr("href=\"../"+ INDEX_HTML  +"\"")
                            ),
                            table(attrs("#tiaTable"),
                                    thead(
                                            tr(
                                                    th("Name"),
                                                    th("Avg run time (ms)").attr(numberDataType),
                                                    th("Num runs").attr(numberDataType),
                                                    th("Num successes").attr(numberDataType),
                                                    th("Success %").attr(numberDataType),
                                                    th("Num fails").attr(numberDataType),
                                                    th("Fail %").attr(numberDataType),
                                                    th("Num methods").attr(numberDataType)
                                            )
                                    ), tbody(
                                            each(tiaData.getTestSuitesTracked().values(), testSuiteTracker ->
                                                    tr(
                                                            td(a(testSuiteTracker.getName()).attr("href=\"" + testSuiteTracker.getName() + ".html\"")),
                                                            td(String.valueOf(testSuiteTracker.getTestStats().getAvgRunTime())),
                                                            td(String.valueOf(testSuiteTracker.getTestStats().getNumRuns())),
                                                            td(String.valueOf(testSuiteTracker.getTestStats().getNumSuccessRuns())),
                                                            td(getAvgSuccess(testSuiteTracker.getTestStats())),
                                                            td(String.valueOf(testSuiteTracker.getTestStats().getNumFailRuns())),
                                                            td(getAvgFail(testSuiteTracker.getTestStats())),
                                                            td(String.valueOf(testSuiteTracker.getClassesImpacted().stream().reduce(0, (sub, classImpactTracker)
                                                                    -> sub + classImpactTracker.getMethodsImpacted().size(), Integer :: sum)))
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

    private void generateSourceMethodReport(TiaData tiaData) {
        long startTime = System.currentTimeMillis();
        log.info("Writing the source method reports to {}", reportOutputDir.getAbsoluteFile());

        tiaData.getTestSuitesTracked().values().parallelStream().forEach(testSuiteTracker -> {
            writeSourceMethodsHtmlToFile(tiaData, testSuiteTracker);
        });

        log.info("Time to write the report (ms): " + (System.currentTimeMillis() - startTime));
    }

    private void writeSourceMethodsHtmlToFile(TiaData tiaData, TestSuiteTracker testSuiteTracker){
        String fileName = reportOutputDir + File.separator + testSuiteTracker.getName() + ".html";

        Set<Integer> methodIds = testSuiteTracker.getClassesImpacted().stream()
                .flatMap(classImpactTracker -> classImpactTracker.getMethodsImpacted().stream())
                .collect(Collectors.toSet());

        try {
            FileWriter writer = new FileWriter(fileName);
            final String numberDataType = "data-type=\"number\"";

            html(
                    head(
                            link().attr("href=\"https://cdn.jsdelivr.net/npm/simple-datatables@8.0.1/dist/style.css\" rel=\"stylesheet\" type=\"text/css\""),
                            script().attr("src=\"https://cdn.jsdelivr.net/npm/simple-datatables@8.0.1\" type=\"text/javascript\"")
                    ), body(
                            header(
                                    h2("Test Suite: " + testSuiteTracker.getName())
                            ),
                            div(
                                    p(
                                            a("go to Test Suites").attr("href=tia-test-suites.html")
                                    ),
                                    h3("Stats"),
                                    p(
                                            div("Avg run time (ms): " + testSuiteTracker.getTestStats().getAvgRunTime()),
                                            br(),
                                            div("Num runs: " + testSuiteTracker.getTestStats().getNumRuns()),
                                            br(),
                                            div("Num successes: " + testSuiteTracker.getTestStats().getNumSuccessRuns()),
                                            br(),
                                            div("Success: " + getAvgSuccess(testSuiteTracker.getTestStats()) + "%"),
                                            br(),
                                            div("Num fails: " + testSuiteTracker.getTestStats().getNumFailRuns()),
                                            br(),
                                            div("Fail: " + getAvgFail(testSuiteTracker.getTestStats()) + "%"),
                                            br()
                                    ),
                                    h3("Source methods executed by the test suite")
                            ),
                            table(attrs("#tiaSourceMethodTable"),
                                    thead(
                                            tr(
                                                    th("Name")
                                            )
                                    ), tbody(
                                            each(methodIds, methodId ->
                                                    tr(
                                                            td(a(tiaData.getMethodsTracked().get(methodId).getNameForDisplay()).attr("href=\"../" + METHODS_FOLDER + "/" + methodId + ".html\""))
                                                    )
                                            )
                                    )
                            )
                    ),
                    script("const dataTable = new simpleDatatables.DataTable(\"#tiaSourceMethodTable\", {\n" +
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