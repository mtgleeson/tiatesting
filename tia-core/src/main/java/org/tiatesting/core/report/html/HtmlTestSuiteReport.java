package org.tiatesting.core.report.html;

import j2html.Config;
import j2html.rendering.FlatHtml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.MethodImpactTracker;
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
import static org.tiatesting.core.report.html.HtmlSourceMethodReport.METHODS_FOLDER;

public class HtmlTestSuiteReport {
    private static final Logger log = LoggerFactory.getLogger(HtmlTestSuiteReport.class);
    protected static final String TEST_SUITES_FOLDER = "test-suites";
    protected static final String TIA_TEST_SUITES_HTML = "tia-test-suites.html";
    private final File reportOutputDir;
    private final DecimalFormat avgFormat = new DecimalFormat("###.#");

    /** Path back to the assets dir from a one-level-deep page. */
    private static final String ASSETS_REL = "../" + HtmlAssetCopier.ASSETS_DIR_NAME;
    /** Path back to the report root from a one-level-deep page. */
    private static final String ROOT_REL = "../";

    public HtmlTestSuiteReport(String filenameExt, File reportOutputDir){
        this.reportOutputDir = new File(reportOutputDir.getAbsoluteFile() + File.separator + "html"
                + File.separator + filenameExt + File.separator + TEST_SUITES_FOLDER);
    }

    public void generateTestSuiteReport(TiaData tiaData) {
        createOutputDir();
        generateTestSuiteReportData(tiaData);
        generateSourceMethodReport(tiaData);
    }

    private int pendingCount(TiaData tiaData) {
        return tiaData.getPendingLibraryImpactedMethods() != null
                ? tiaData.getPendingLibraryImpactedMethods().size() : 0;
    }

    private void generateTestSuiteReportData(TiaData tiaData){
        long startTime = System.currentTimeMillis();
        String fileName = reportOutputDir + File.separator + TIA_TEST_SUITES_HTML;
        log.info("Writing the test suite report to {}", fileName);

        try (FileWriter writer = new FileWriter(fileName)) {
            final String numberDataType = "data-type=\"number\"";

            html(
                    HtmlLayout.pageHead("Test Suites", ASSETS_REL),
                    body(
                            HtmlLayout.topNav(HtmlLayout.NavKey.TEST_SUITES, ASSETS_REL, ROOT_REL, pendingCount(tiaData)),
                            main(
                                    HtmlLayout.breadcrumb(
                                            HtmlLayout.Crumb.link("Home", ROOT_REL + "index.html"),
                                            HtmlLayout.Crumb.current("Test Suites")
                                    ),
                                    HtmlLayout.pageHeading(HtmlLayout.ICON_TEST_SUITE, "Test Suites"),
                                    table(attrs("#tiaTable"),
                                            thead(tr(
                                                    th("Name"),
                                                    th("Avg run time (ms)").attr(numberDataType),
                                                    th("Num runs").attr(numberDataType),
                                                    th("Num successes").attr(numberDataType),
                                                    th("Success %").attr(numberDataType),
                                                    th("Num fails").attr(numberDataType),
                                                    th("Fail %").attr(numberDataType),
                                                    th("Num methods").attr(numberDataType)
                                            )),
                                            tbody(each(tiaData.getTestSuitesTracked().values(), testSuiteTracker ->
                                                    tr(
                                                            td(a(testSuiteTracker.getName())
                                                                    .withHref(testSuiteTracker.getName() + ".html")),
                                                            td(String.valueOf(testSuiteTracker.getTestStats().getAvgRunTime())),
                                                            td(String.valueOf(testSuiteTracker.getTestStats().getNumRuns())),
                                                            td(String.valueOf(testSuiteTracker.getTestStats().getNumSuccessRuns())),
                                                            td(getAvgSuccess(testSuiteTracker.getTestStats())),
                                                            td(String.valueOf(testSuiteTracker.getTestStats().getNumFailRuns())),
                                                            td(getAvgFail(testSuiteTracker.getTestStats())),
                                                            td(String.valueOf(testSuiteTracker.getClassesImpacted().stream().reduce(0,
                                                                    (sub, classImpactTracker) -> sub + classImpactTracker.getMethodsImpacted().size(),
                                                                    Integer::sum)))
                                                    )
                                            ))
                                    )
                            ),
                            HtmlLayout.pageFooter(),
                            HtmlLayout.simpleDatatablesInit("#tiaTable", ASSETS_REL)
                    )
            ).render(FlatHtml.into(writer, Config.defaults().withEmptyTagsClosed(true))).flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.info("Time to write the report (ms): " + (System.currentTimeMillis() - startTime));
    }

    private void generateSourceMethodReport(TiaData tiaData) {
        long startTime = System.currentTimeMillis();
        log.info("Writing the source method reports to {}", reportOutputDir.getAbsoluteFile());

        tiaData.getTestSuitesTracked().values().parallelStream()
                .forEach(testSuiteTracker -> writeSourceMethodsHtmlToFile(tiaData, testSuiteTracker));

        log.info("Time to write the report (ms): " + (System.currentTimeMillis() - startTime));
    }

    private void writeSourceMethodsHtmlToFile(TiaData tiaData, TestSuiteTracker testSuiteTracker){
        String fileName = reportOutputDir + File.separator + testSuiteTracker.getName() + ".html";

        String fullName = testSuiteTracker.getName();
        int lastDot = fullName.lastIndexOf('.');
        String shortName = lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;

        Set<Integer> methodIds = testSuiteTracker.getClassesImpacted().stream()
                .flatMap(classImpactTracker -> classImpactTracker.getMethodsImpacted().stream())
                .collect(Collectors.toSet());

        try (FileWriter writer = new FileWriter(fileName)) {
            html(
                    HtmlLayout.pageHead("Test Suite — " + testSuiteTracker.getName(), ASSETS_REL),
                    body(
                            HtmlLayout.topNav(HtmlLayout.NavKey.TEST_SUITES, ASSETS_REL, ROOT_REL, pendingCount(tiaData)),
                            main(
                                    HtmlLayout.breadcrumb(
                                            HtmlLayout.Crumb.link("Home", ROOT_REL + "index.html"),
                                            HtmlLayout.Crumb.link("Test Suites", TIA_TEST_SUITES_HTML),
                                            HtmlLayout.Crumb.current(testSuiteTracker.getName())
                                    ),
                                    HtmlLayout.pageHeading(HtmlLayout.ICON_TEST_SUITE,
                                            "Test Suite: " + shortName)
                                            .attr("title", fullName),
                                    p(code(fullName)),

                                    h3("Stats"),
                                    p(
                                            span("Avg run time (ms): " + testSuiteTracker.getTestStats().getAvgRunTime()), br(),
                                            span("Num runs: " + testSuiteTracker.getTestStats().getNumRuns()), br(),
                                            span("Num successes: " + testSuiteTracker.getTestStats().getNumSuccessRuns()), br(),
                                            span("Success: " + getAvgSuccess(testSuiteTracker.getTestStats()) + "%"), br(),
                                            span("Num fails: " + testSuiteTracker.getTestStats().getNumFailRuns()), br(),
                                            span("Fail: " + getAvgFail(testSuiteTracker.getTestStats()) + "%")
                                    ),

                                    h3("Impacted Source Methods"),
                                    table(attrs("#tiaSourceMethodTable"),
                                            thead(tr(th("Name"))),
                                            tbody(each(methodIds, methodId -> {
                                                MethodImpactTracker method = tiaData.getMethodsTracked().get(methodId);
                                                return tr(td(a(method.getShortNameForDisplay())
                                                        .withHref(ROOT_REL + METHODS_FOLDER + "/" + methodId + ".html")
                                                        .attr("title", method.getNameForDisplay())));
                                            }))
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
        if (!reportOutputDir.exists() && !reportOutputDir.mkdirs()) {
            log.warn("Failed to create report output directory: {}", reportOutputDir.getAbsolutePath());
        }
    }
}
