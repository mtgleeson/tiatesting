package org.tiatesting.core.report.html;

import static j2html.TagCreator.*;

import j2html.Config;
import j2html.rendering.IndentedHtml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;

public class HtmlTestSuiteReport {
    private static final Logger log = LoggerFactory.getLogger(HtmlTestSuiteReport.class);
    private final String filenameExt;
    private final File reportOutputDir;
    private final DecimalFormat avgFormat = new DecimalFormat("###.#");

    public HtmlTestSuiteReport(String filenameExt, File reportOutputDir){
        this.filenameExt = filenameExt;
        this.reportOutputDir = new File(reportOutputDir.getAbsoluteFile() + File.separator + "html"
                + File.separator + filenameExt + File.separator + "test-suites");
    }

    public void generateTestSuiteReport(TiaData tiaData) {
        createOutputDir();
        long startTime = System.currentTimeMillis();
        String fileName = reportOutputDir + File.separator + "tia-test-suites.html";
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
                                                th("Num classes").attr(numberDataType)
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
                                                        td(String.valueOf(testSuiteTracker.getClassesImpacted().size()))
                                                )
                                           )
                                )
                           )
                   ),
                   script("const dataTable = new simpleDatatables.DataTable(\"#tiaTable\", {\n" +
                           "\tsearchable: true,\n" +
                           "\tfixedHeight: true,\n" +
                           "\tpaging: false\n" +
                           "})")
           ).render(IndentedHtml.into(writer, Config.defaults().withEmptyTagsClosed(true))).flush();
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
