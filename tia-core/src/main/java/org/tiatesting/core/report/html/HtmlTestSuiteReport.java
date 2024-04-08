package org.tiatesting.core.report.html;

import static j2html.TagCreator.*;

import j2html.Config;
import j2html.rendering.FlatHtml;
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
        generateTestSuiteReportData(tiaData);
        generateSourceClassReport(tiaData);
        generateSourceMethodReport(tiaData);
    }

    private void generateTestSuiteReportData(TiaData tiaData){
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

    private void generateSourceClassReport(TiaData tiaData) {
        long startTime = System.currentTimeMillis();
        log.info("Writing the source class reports to {}", reportOutputDir.getAbsoluteFile());

        tiaData.getTestSuitesTracked().values().parallelStream().forEach(testSuiteTracker -> {
            writeSourceClassHtmlToFile(testSuiteTracker);
        });

        log.info("Time to write the report (ms): " + (System.currentTimeMillis() - startTime));
    }

    private void writeSourceClassHtmlToFile(TestSuiteTracker testSuiteTracker){
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

    private void generateSourceMethodReport(TiaData tiaData) {
        long startTime = System.currentTimeMillis();
        log.info("Writing the source method reports to {}", reportOutputDir.getAbsoluteFile());

        tiaData.getTestSuitesTracked().values().parallelStream().forEach(testSuiteTracker -> {
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
                                                            td(tiaData.getMethodsTracked().get(methodImpactTracker).getNameForDisplay()),
                                                            td(String.valueOf(tiaData.getMethodsTracked().get(methodImpactTracker).getLineNumberStart())),
                                                            td(String.valueOf(tiaData.getMethodsTracked().get(methodImpactTracker).getLineNumberEnd()))
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

    private void createOutputDir(File testSuiteOutputDir) {
        testSuiteOutputDir.mkdirs();
    }
}
