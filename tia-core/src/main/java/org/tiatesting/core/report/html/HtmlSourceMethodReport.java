package org.tiatesting.core.report.html;

import j2html.Config;
import j2html.rendering.IndentedHtml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;

import static j2html.TagCreator.*;

public class HtmlSourceMethodReport {
    private static final Logger log = LoggerFactory.getLogger(HtmlSourceMethodReport.class);
    private final String filenameExt;
    private final File reportOutputDir;
    private final DecimalFormat avgFormat = new DecimalFormat("###.#");

    public HtmlSourceMethodReport(String filenameExt, File reportOutputDir){
        this.filenameExt = filenameExt;
        this.reportOutputDir = new File(reportOutputDir.getAbsoluteFile() + File.separator + "html"
                + File.separator + filenameExt + File.separator + "test-suites");
    }

    public void generateSourceMethodReport(TiaData tiaData) {
        long startTime = System.currentTimeMillis();
        log.info("Writing the source method reports to {}", reportOutputDir.getAbsoluteFile());

        tiaData.getTestSuitesTracked().values().forEach(testSuiteTracker -> {
            testSuiteTracker.getClassesImpacted().forEach(classImpactTracker -> {
                writeSourceClassHtmlToFile(tiaData, testSuiteTracker, classImpactTracker);
            });
        });

        log.info("Time to write the report (ms): " + (System.currentTimeMillis() - startTime));
    }

    private void writeSourceClassHtmlToFile(TiaData tiaData, TestSuiteTracker testSuiteTracker, ClassImpactTracker classImpactTracker){
        File testSuiteOutputDir = new File(reportOutputDir.getAbsoluteFile() + File.separator + testSuiteTracker.getName());
        createOutputDir(testSuiteOutputDir);
        String fileName = testSuiteOutputDir + File.separator + classImpactTracker.getSourceFilenameForDisplay() + ".html";

        try {
            FileWriter writer = new FileWriter(fileName);
            final String numberDataType = "data-type=\"number\"";

            html(
                    head(
                            link().attr("href=\"https://cdn.jsdelivr.net/npm/simple-datatables@8.0.1/dist/style.css\" rel=\"stylesheet\" type=\"text/css\""),
                            script().attr("src=\"https://cdn.jsdelivr.net/npm/simple-datatables@8.0.1\" type=\"text/javascript\"")
                    ), body(
                            header(
                                    h2("Test Suite: " + testSuiteTracker.getName()),
                                    h3("Source Methods For Class: " + classImpactTracker.getSourceFilenameForDisplay())
                            ),
                            div(
                                    p(
                                            a("back to " + testSuiteTracker.getName()).attr("href=../" + testSuiteTracker.getName() + ".html")
                                    )
                            ),
                            table(attrs("#tiaSourceMethodTable"),
                                    thead(
                                            tr(
                                                    th("Name"),
                                                    th("Line start").attr(numberDataType),
                                                    th("Line end").attr(numberDataType)
                                            )
                                    ), tbody(
                                            each(classImpactTracker.getMethodsImpacted(), methodImpactTracker ->
                                                    tr(
                                                            td(tiaData.getMethodsTracked().get(methodImpactTracker).getMethodName()),
                                                            td(String.valueOf(tiaData.getMethodsTracked().get(methodImpactTracker).getLineNumberStart())),
                                                            td(String.valueOf(tiaData.getMethodsTracked().get(methodImpactTracker).getLineNumberEnd()))
                                                    )
                                            )
                                    )
                            )
                    ),
                    script("const dataTable = new simpleDatatables.DataTable(\"#tiaSourceMethodTable\", {\n" +
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

    private void createOutputDir(File testSuiteOutputDir) {
        testSuiteOutputDir.mkdirs();
    }
}
