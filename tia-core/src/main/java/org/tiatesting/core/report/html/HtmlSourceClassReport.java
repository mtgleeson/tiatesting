package org.tiatesting.core.report.html;

import j2html.Config;
import j2html.rendering.IndentedHtml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;

import static j2html.TagCreator.*;

public class HtmlSourceClassReport {
    private static final Logger log = LoggerFactory.getLogger(HtmlSourceClassReport.class);
    private final String filenameExt;
    private final File reportOutputDir;
    private final DecimalFormat avgFormat = new DecimalFormat("###.#");

    public HtmlSourceClassReport(String filenameExt, File reportOutputDir){
        this.filenameExt = filenameExt;
        this.reportOutputDir = new File(reportOutputDir.getAbsoluteFile() + File.separator + "html"
                + File.separator + filenameExt + File.separator + "test-suites");
    }

    public void generateSourceClassReport(TiaData tiaData) {
        long startTime = System.currentTimeMillis();
        log.info("Writing the source class reports to {}", reportOutputDir.getAbsoluteFile());

        tiaData.getTestSuitesTracked().values().forEach(testSuiteTracker -> {
            writeSourceClassHtmlToFile(testSuiteTracker);
        });

        log.info("Time to write the report (ms): " + (System.currentTimeMillis() - startTime));
    }

    private void writeSourceClassHtmlToFile(TestSuiteTracker testSuiteTracker){
        createOutputDir();
        String fileName = reportOutputDir + File.separator + testSuiteTracker.getName() + ".html";

        try {
            FileWriter writer = new FileWriter(fileName);
            final String numberDataType = "data-type=\"number\"";

            html(
                    head(
                            link().attr("href=\"https://cdn.jsdelivr.net/npm/simple-datatables@8.0.1/dist/style.css\" rel=\"stylesheet\" type=\"text/css\""),
                            script().attr("src=\"https://cdn.jsdelivr.net/npm/simple-datatables@8.0.1\" type=\"text/javascript\"")
                    ), body(
                            header(
                                    h2("Source Classes For Test Suite: " + testSuiteTracker.getName())
                            ),
                            div(
                                    p(
                                            a("back to Test Suites").attr("href=tia-test-suites.html")
                                    )
                            ),
                            table(attrs("#tiaSourceClassTable"),
                                    thead(
                                            tr(
                                                    th("Name"),
                                                    th("Num methods").attr(numberDataType)
                                            )
                                    ), tbody(
                                            each(testSuiteTracker.getClassesImpacted(), classImpactTracker ->
                                                    tr(
                                                            td(a(classImpactTracker.getSourceFilenameForDisplay())
                                                                    .attr("href=\"" + testSuiteTracker.getName() + File.separator + classImpactTracker.getSourceFilenameForDisplay() + ".html\"")),
                                                            td(String.valueOf(classImpactTracker.getMethodsImpacted().size()))
                                                    )
                                            )
                                    )
                            )
                    ),
                    script("const dataTable = new simpleDatatables.DataTable(\"#tiaSourceClassTable\", {\n" +
                            "\tsearchable: true,\n" +
                            "\tfixedHeight: true,\n" +
                            "\tpaging: false\n" +
                            "})")
            ).render(IndentedHtml.into(writer, Config.defaults().withEmptyTagsClosed(true))).flush();
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
