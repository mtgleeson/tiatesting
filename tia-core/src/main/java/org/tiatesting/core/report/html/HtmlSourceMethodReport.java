package org.tiatesting.core.report.html;

import j2html.Config;
import j2html.rendering.FlatHtml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.*;

import static j2html.TagCreator.*;
import static org.tiatesting.core.report.html.HtmlSummaryReport.INDEX_HTML;

public class HtmlSourceMethodReport {
    private static final Logger log = LoggerFactory.getLogger(HtmlSourceMethodReport.class);
    protected static final String TIA_SOURCE_METHODS_HTML = "tia-source-methods.html";
    protected static final String TIA_SOURCE_METHODS__TEST_JS = "tia-source-methods-tests.js";
    protected static final String METHODS_FOLDER = "methods";
    private final String filenameExt;
    private final File reportOutputDir;
    private final DecimalFormat avgFormat = new DecimalFormat("###.#");

    public HtmlSourceMethodReport(String filenameExt, File reportOutputDir){
        this.filenameExt = filenameExt;
        this.reportOutputDir = new File(reportOutputDir.getAbsoluteFile() + File.separator + "html"
                + File.separator + filenameExt + File.separator + METHODS_FOLDER);
    }

    public void generateSourceMethodReport(TiaData tiaData) {
        Map<Integer, ClassTestSuite> methodToTestSuites = buildMethodToTestSuiteMap(tiaData);
        createOutputDir();
        generateSourceMethodsReportFile(tiaData, methodToTestSuites);
        generateTestSuitesReportFiles(tiaData, methodToTestSuites);
    }

    private void generateSourceMethodsReportFile(TiaData tiaData, Map<Integer, ClassTestSuite> methodToTestSuites){
        long startTime = System.currentTimeMillis();
        String fileName = reportOutputDir + File.separator + TIA_SOURCE_METHODS_HTML;
        log.info("Writing the source methods report to {}", fileName);

        try {
            FileWriter writer = new FileWriter(fileName);
            final String numberDataType = "data-type=\"number\"";

            html(
                    head(
                            link().attr("href=\"https://cdn.jsdelivr.net/npm/simple-datatables@8.0.1/dist/style.css\" rel=\"stylesheet\" type=\"text/css\""),
                            script().attr("src=\"https://cdn.jsdelivr.net/npm/simple-datatables@8.0.1\" type=\"text/javascript\"")
                    ), body(
                            header(
                                    h2("Tia: Source Methods")
                            ),
                            p(
                                    a("back to Summary").attr("href=\"../"+ INDEX_HTML  +"\"")
                            ),
                            table(attrs("#tiaSourceMethodsTable"),
                                    thead(
                                            tr(
                                                    th("Method"),
                                                    th("Num Test Suites").attr(numberDataType),
                                                    th("Line start").attr(numberDataType),
                                                    th("Line end").attr(numberDataType)
                                            )
                                    ), tbody(
                                            each(methodToTestSuites, mapEntry ->
                                                    tr(
                                                            td(a(tiaData.getMethodsTracked().get(mapEntry.getKey()).getNameForDisplay()).attr("href=\"" + mapEntry.getKey() + ".html\"")),
                                                            td(String.valueOf(mapEntry.getValue().getTestSuites().size())),
                                                            td(String.valueOf(tiaData.getMethodsTracked().get(mapEntry.getKey()).getLineNumberStart())),
                                                            td(String.valueOf(tiaData.getMethodsTracked().get(mapEntry.getKey()).getLineNumberEnd()))
                                                    )
                                            )
                                    )
                            )
                    ),
                    script("const dataTable = new simpleDatatables.DataTable(\"#tiaSourceMethodsTable\", {\n" +
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

    private void generateTestSuitesReportFiles(TiaData tiaData, Map<Integer, ClassTestSuite> methodToTestSuites){
        long startTime = System.currentTimeMillis();
        log.info("Writing the test suites reports to {}", reportOutputDir.getAbsoluteFile());

        writeMethodTestSuiteJSFile();

        methodToTestSuites.entrySet().parallelStream().forEach(entry -> {
            writeTestSuitesReportFiles(tiaData, entry.getKey(), entry.getValue());
        });

        log.info("Time to write the report (ms): " + (System.currentTimeMillis() - startTime));
    }

    private void writeTestSuitesReportFiles(TiaData tiaData, Integer methodTrackedHashCode, ClassTestSuite classTestSuite){
        MethodImpactTracker methodImpactTracker = tiaData.getMethodsTracked().get(methodTrackedHashCode);
        String fileName = reportOutputDir + File.separator + methodTrackedHashCode + ".html";
        fileName = fileName.replaceAll("<", "").replaceAll(">", "");

        try {
            BufferedWriter writer = new BufferedWriter((new FileWriter(fileName)));
            final String numberDataType = "data-type=\"number\"";

            html(
                    head(
                            link().attr("href=\"https://cdn.jsdelivr.net/npm/simple-datatables@8.0.1/dist/style.css\" rel=\"stylesheet\" type=\"text/css\""),
                            script().attr("src=\"https://cdn.jsdelivr.net/npm/simple-datatables@8.0.1\" type=\"text/javascript\"")
                    ),
                    body(
                            header(
                                    h2("Test Suites for " + methodImpactTracker.getNameForDisplay())
                            ),
                            div(
                                    p(
                                            a("back to Source Methods").attr("href=tia-source-methods.html")
                                    )
                            ),
                            table(attrs("#tiaSourceMethodTable"),
                                    thead(
                                            tr(
                                                    th("Test Suite"),
                                                    th("Avg run time (ms)").attr(numberDataType),
                                                    th("Num runs").attr(numberDataType),
                                                    th("Num successes").attr(numberDataType),
                                                    th("Success %").attr(numberDataType),
                                                    th("Num fails").attr(numberDataType),
                                                    th("Fail %").attr(numberDataType)
                                            )
                                    ), tbody(
                                            each(classTestSuite.getTestSuites(), testSuiteTracker ->
                                                    tr(
                                                            td(testSuiteTracker.getName()),
                                                            td(String.valueOf(testSuiteTracker.getTestStats().getAvgRunTime())),
                                                            td(String.valueOf(testSuiteTracker.getTestStats().getNumRuns())),
                                                            td(String.valueOf(testSuiteTracker.getTestStats().getNumSuccessRuns())),
                                                            td(getAvgSuccess(testSuiteTracker.getTestStats())),
                                                            td(String.valueOf(testSuiteTracker.getTestStats().getNumFailRuns())),
                                                            td(getAvgFail(testSuiteTracker.getTestStats()))
                                                    )
                                            )
                                    )
                            )
                    ),
                    script().attr("src=\"" + TIA_SOURCE_METHODS__TEST_JS + "\" type=\"text/javascript\"")
            ).render(FlatHtml.into(writer, Config.defaults().withEmptyTagsClosed(true))).flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void writeMethodTestSuiteJSFile(){
        String jsContent = "const dataTable = new simpleDatatables.DataTable(\"#tiaSourceMethodTable\", { " +
                "columns: [{ select: 0, sort: \"asc\" }], " +
                "searchable: true, " +
                "fixedHeight: true, " +
                "paging: true, " +
                "perPage: 20, " +
                "perPageSelect: [10, 20, 50, [\"All\", -1]] " +
                "});";

        byte[] strToBytes = jsContent.getBytes();
        String fileName = reportOutputDir + File.separator + TIA_SOURCE_METHODS__TEST_JS;
        Path path = Paths.get(fileName);
        try {
            Files.write(path, strToBytes);
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

    /**
     * Convert to a map containing a list of test suites for each impacted method.
     * Use this for convenience lookup when finding the list of test suites to ignore for previously tracked methods
     * that have been changed in the diff.
     *
     * @param tiaData keyed by method name, value is a list of test suites
     * @return map keyed by the method id, with a map of class names to list of
     */
    private Map<Integer, ClassTestSuite> buildMethodToTestSuiteMap(TiaData tiaData){
        Map<Integer, ClassTestSuite> methodTestSuites = new HashMap<>();

        tiaData.getTestSuitesTracked().forEach((testSuiteName, testSuiteTracker) -> {
            for (ClassImpactTracker classImpacted : testSuiteTracker.getClassesImpacted()) {
                for (Integer methodTrackedHashCode : classImpacted.getMethodsImpacted()) {
                    ClassTestSuite classTestSuite = methodTestSuites.get(methodTrackedHashCode);

                    if (classTestSuite == null) {
                        classTestSuite = new ClassTestSuite(classImpacted);
                        methodTestSuites.put(methodTrackedHashCode, classTestSuite);
                    }

                    classTestSuite.getTestSuites().add(testSuiteTracker);
                }
            }
        });

        return methodTestSuites;
    }

    private class ClassTestSuite {
        ClassImpactTracker classImpactTracker;
        List<TestSuiteTracker> testSuites = new ArrayList<>();

        public ClassTestSuite(ClassImpactTracker classImpactTracker){
            this.classImpactTracker = classImpactTracker;
        }

        public ClassImpactTracker getClassImpactTracker() {
            return classImpactTracker;
        }

        public List<TestSuiteTracker> getTestSuites() {
            return testSuites;
        }
    }
}