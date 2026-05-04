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
import java.text.DecimalFormat;
import java.util.*;

import static j2html.TagCreator.*;
import static org.tiatesting.core.report.html.HtmlTestSuiteReport.TEST_SUITES_FOLDER;

public class HtmlSourceMethodReport {
    private static final Logger log = LoggerFactory.getLogger(HtmlSourceMethodReport.class);
    protected static final String TIA_SOURCE_METHODS_HTML = "tia-source-methods.html";
    protected static final String METHODS_FOLDER = "methods";
    private final File reportOutputDir;
    private final DecimalFormat avgFormat = new DecimalFormat("###.#");

    /** Path back to the assets dir from a one-level-deep page. */
    private static final String ASSETS_REL = "../" + HtmlAssetCopier.ASSETS_DIR_NAME;
    /** Path back to the report root from a one-level-deep page. */
    private static final String ROOT_REL = "../";

    public HtmlSourceMethodReport(String filenameExt, File reportOutputDir){
        this.reportOutputDir = new File(reportOutputDir.getAbsoluteFile() + File.separator + "html"
                + File.separator + filenameExt + File.separator + METHODS_FOLDER);
    }

    public void generateSourceMethodReport(TiaData tiaData) {
        Map<Integer, ClassTestSuite> methodToTestSuites = buildMethodToTestSuiteMap(tiaData);
        createOutputDir();
        generateSourceMethodsReportFile(tiaData, methodToTestSuites);
        generateMethodReportFiles(tiaData, methodToTestSuites);
    }

    private int pendingCount(TiaData tiaData) {
        return tiaData.getPendingLibraryImpactedMethods() != null
                ? tiaData.getPendingLibraryImpactedMethods().size() : 0;
    }

    private void generateSourceMethodsReportFile(TiaData tiaData, Map<Integer, ClassTestSuite> methodToTestSuites){
        long startTime = System.currentTimeMillis();
        String fileName = reportOutputDir + File.separator + TIA_SOURCE_METHODS_HTML;
        log.info("Writing the source methods report to {}", fileName);

        try (FileWriter writer = new FileWriter(fileName)) {
            final String numberDataType = "data-type=\"number\"";

            html(
                    HtmlLayout.pageHead("Source Methods", ASSETS_REL),
                    body(
                            HtmlLayout.topNav(HtmlLayout.NavKey.SOURCE_CODE, ASSETS_REL, ROOT_REL, pendingCount(tiaData)),
                            main(
                                    HtmlLayout.breadcrumb(
                                            HtmlLayout.Crumb.link("Home", ROOT_REL + "index.html"),
                                            HtmlLayout.Crumb.link("Source Code", ROOT_REL + "source-code.html"),
                                            HtmlLayout.Crumb.current("Methods")
                                    ),
                                    HtmlLayout.pageHeading(HtmlLayout.ICON_CODE, "Source Methods"),
                                    table(attrs("#tiaSourceMethodsTable"),
                                            thead(tr(
                                                    th("Method"),
                                                    th("Num Test Suites").attr(numberDataType),
                                                    th("Line start").attr(numberDataType),
                                                    th("Line end").attr(numberDataType)
                                            )),
                                            tbody(each(methodToTestSuites, mapEntry -> {
                                                MethodImpactTracker method = tiaData.getMethodsTracked().get(mapEntry.getKey());
                                                return tr(
                                                        td(a(method.getShortNameForDisplay())
                                                                .withHref(mapEntry.getKey() + ".html")
                                                                .attr("title", method.getNameForDisplay())),
                                                        td(String.valueOf(mapEntry.getValue().getTestSuites().size())),
                                                        td(String.valueOf(method.getLineNumberStart())),
                                                        td(String.valueOf(method.getLineNumberEnd()))
                                                );
                                            }))
                                    )
                            ),
                            HtmlLayout.pageFooter(),
                            HtmlLayout.simpleDatatablesInit("#tiaSourceMethodsTable", ASSETS_REL)
                    )
            ).render(FlatHtml.into(writer, Config.defaults().withEmptyTagsClosed(true))).flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.info("Time to write the report (ms): " + (System.currentTimeMillis() - startTime));
    }

    private void generateMethodReportFiles(TiaData tiaData, Map<Integer, ClassTestSuite> methodToTestSuites){
        long startTime = System.currentTimeMillis();
        log.info("Writing the method data including test suites reports to {}", reportOutputDir.getAbsoluteFile());

        methodToTestSuites.entrySet().parallelStream().forEach(entry -> {
            writeTestSuitesReportFiles(tiaData, entry.getKey(), entry.getValue());
        });

        log.info("Time to write the report (ms): " + (System.currentTimeMillis() - startTime));
    }

    private void writeTestSuitesReportFiles(TiaData tiaData, Integer methodTrackedHashCode, ClassTestSuite classTestSuite){
        MethodImpactTracker methodImpactTracker = tiaData.getMethodsTracked().get(methodTrackedHashCode);
        String fileName = reportOutputDir + File.separator + methodTrackedHashCode + ".html";
        fileName = fileName.replaceAll("<", "").replaceAll(">", "");

        // Drop the package from the heading: keep only the trailing "ClassName.methodName".
        String shortName = methodImpactTracker.getShortNameForDisplay();
        int lastDot = shortName.lastIndexOf('.');
        int secondLastDot = lastDot > 0 ? shortName.lastIndexOf('.', lastDot - 1) : -1;
        String classAndMethod = secondLastDot >= 0 ? shortName.substring(secondLastDot + 1) : shortName;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
            final String numberDataType = "data-type=\"number\"";

            html(
                    HtmlLayout.pageHead("Source Method — " + methodImpactTracker.getNameForDisplay(), ASSETS_REL),
                    body(
                            HtmlLayout.topNav(HtmlLayout.NavKey.SOURCE_CODE, ASSETS_REL, ROOT_REL, pendingCount(tiaData)),
                            main(
                                    HtmlLayout.breadcrumb(
                                            HtmlLayout.Crumb.link("Home", ROOT_REL + "index.html"),
                                            HtmlLayout.Crumb.link("Source Code", ROOT_REL + "source-code.html"),
                                            HtmlLayout.Crumb.link("Methods", TIA_SOURCE_METHODS_HTML),
                                            HtmlLayout.Crumb.current(methodImpactTracker.getShortNameForDisplay(),
                                                    methodImpactTracker.getNameForDisplay())
                                    ),
                                    HtmlLayout.pageHeading(HtmlLayout.ICON_CODE,
                                            "Source Method: " + classAndMethod)
                                            .attr("title", methodImpactTracker.getNameForDisplay()),
                                    p(code(methodImpactTracker.getNameForDisplay())),

                                    h3("Coverage"),
                                    p(
                                            span("Line start: " + methodImpactTracker.getLineNumberStart()), br(),
                                            span("Line end: " + methodImpactTracker.getLineNumberEnd())
                                    ),

                                    h3("Impacted Test Suites"),
                                    table(attrs("#tiaSourceMethodTable"),
                                            thead(tr(
                                                    th("Test Suite"),
                                                    th("Avg run time (ms)").attr(numberDataType),
                                                    th("Num runs").attr(numberDataType),
                                                    th("Num successes").attr(numberDataType),
                                                    th("Success %").attr(numberDataType),
                                                    th("Num fails").attr(numberDataType),
                                                    th("Fail %").attr(numberDataType)
                                            )),
                                            tbody(each(classTestSuite.getTestSuites(), testSuiteTracker ->
                                                    tr(
                                                            td(a(testSuiteTracker.getName())
                                                                    .withHref(ROOT_REL + TEST_SUITES_FOLDER + "/" + testSuiteTracker.getName() + ".html")),
                                                            td(String.valueOf(testSuiteTracker.getTestStats().getAvgRunTime())),
                                                            td(String.valueOf(testSuiteTracker.getTestStats().getNumRuns())),
                                                            td(String.valueOf(testSuiteTracker.getTestStats().getNumSuccessRuns())),
                                                            td(getAvgSuccess(testSuiteTracker.getTestStats())),
                                                            td(String.valueOf(testSuiteTracker.getTestStats().getNumFailRuns())),
                                                            td(getAvgFail(testSuiteTracker.getTestStats()))
                                                    )
                                            ))
                                    )
                            ),
                            HtmlLayout.pageFooter(),
                            HtmlLayout.simpleDatatablesInit("#tiaSourceMethodTable", ASSETS_REL)
                    )
            ).render(FlatHtml.into(writer, Config.defaults().withEmptyTagsClosed(true))).flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String getAvgSuccess(TestStats stats){
        if (stats.getNumRuns() == 0) {
            return "0";
        }
        double percSuccess = ((double)stats.getNumSuccessRuns()) / (double)(stats.getNumRuns()) * 100;
        return avgFormat.format(percSuccess);
    }

    private String getAvgFail(TestStats stats){
        if (stats.getNumRuns() == 0) {
            return "0";
        }
        double percFail = ((double)stats.getNumFailRuns()) / (double)(stats.getNumRuns()) * 100;
        return avgFormat.format(percFail);
    }

    private void createOutputDir() {
        reportOutputDir.mkdirs();
    }

    /**
     * Convert to a map containing a list of test suites for each impacted method. Used for
     * convenience lookup when finding the list of test suites to ignore for previously tracked
     * methods that have been changed in the diff.
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
