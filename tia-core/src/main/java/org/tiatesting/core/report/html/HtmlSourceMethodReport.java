package org.tiatesting.core.report.html;

import j2html.rendering.FlatHtml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.*;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
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

    /**
     * simple-datatables {@code columns} option for the Source Methods index table. Column 0 (the
     * method link) is typed {@code html} so its {@code <a>} markup renders (and sorts/searches by
     * its text) and is the default ascending sort; the three metric columns are typed
     * {@code number} for numeric ordering. Emitted verbatim into the init script by
     * {@link HtmlLayout#simpleDatatablesInitWithData}.
     */
    private static final String SOURCE_METHODS_COLUMNS_JSON =
            "[{ select: 0, type: \"html\", sort: \"asc\" },"
            + " { select: 1, type: \"number\" },"
            + " { select: 2, type: \"number\" },"
            + " { select: 3, type: \"number\" }]";

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

    /**
     * Write the single source-methods index page: one table row per tracked method with its
     * covering-suite count and line range. Renders through {@link FastTextEscaper#reportConfig()}
     * so text and attribute escaping use the report's fast escaper.
     *
     * @param tiaData the Tia data from the DB
     * @param methodToTestSuites the method-id to covering-suites index built once by the caller
     */
    private void generateSourceMethodsReportFile(TiaData tiaData, Map<Integer, ClassTestSuite> methodToTestSuites){
        long startTime = System.currentTimeMillis();
        String fileName = reportOutputDir + File.separator + TIA_SOURCE_METHODS_HTML;
        log.info("Writing the source methods report to {}", fileName);

        try (Writer writer = HtmlLayout.newReportWriter(fileName)) {
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
                                    // Headers only: the rows are fed to simple-datatables via its
                                    // data option (see HtmlLayout.simpleDatatablesInitWithData) so
                                    // this table can scale to tens of thousands of methods without
                                    // the browser laying out - and the library re-reading - every
                                    // row from the DOM. An empty tbody keeps the table valid before
                                    // the script runs.
                                    table(attrs("#tiaSourceMethodsTable"),
                                            thead(tr(
                                                    th("Method"),
                                                    th("Num Test Suites").attr(numberDataType),
                                                    th("Line start").attr(numberDataType),
                                                    th("Line end").attr(numberDataType)
                                            )),
                                            tbody()
                                    )
                            ),
                            HtmlLayout.pageFooter(),
                            HtmlLayout.simpleDatatablesInitWithData("#tiaSourceMethodsTable", ASSETS_REL,
                                    SOURCE_METHODS_COLUMNS_JSON,
                                    buildSourceMethodsRowsJson(tiaData, methodToTestSuites))
                    )
            ).render(FlatHtml.into(writer, FastTextEscaper.reportConfig())).flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.info("Time to write the report (ms): " + (System.currentTimeMillis() - startTime));
    }

    /**
     * Build the Source Methods index rows as a simple-datatables {@code data.data} JSON literal:
     * one {@code [methodLink, numTestSuites, lineStart, lineEnd]} array per tracked method. The
     * method link column carries the same {@code <a href="{id}.html" title="{fullName}">{shortName}</a>}
     * markup the DOM table previously emitted; the display text and title are HTML-escaped through
     * {@link FastTextEscaper} (byte-identical to the j2html rendering it replaces) and the whole
     * anchor string is then JSON/script-escaped by {@link #appendJsonString(StringBuilder, String)}.
     * Assembled directly into a {@link StringBuilder} rather than via j2html tags so it scales to
     * tens of thousands of methods without allocating a tag object per cell.
     *
     * @param tiaData the Tia data from the DB, used to resolve each method id to its tracker
     * @param methodToTestSuites the method-id to covering-suites index built once by the caller
     * @return a script-safe JSON array-of-arrays literal for the {@code data.data} option
     */
    static String buildSourceMethodsRowsJson(TiaData tiaData, Map<Integer, ClassTestSuite> methodToTestSuites){
        StringBuilder sb = new StringBuilder(Math.max(16, methodToTestSuites.size() * 96));
        sb.append('[');
        boolean first = true;
        for (Map.Entry<Integer, ClassTestSuite> entry : methodToTestSuites.entrySet()) {
            MethodImpactTracker method = tiaData.getMethodsTracked().get(entry.getKey());
            if (!first) {
                sb.append(',');
            }
            first = false;

            String anchor = "<a href=\"" + entry.getKey() + ".html\" title=\""
                    + FastTextEscaper.INSTANCE.escape(method.getNameForDisplay()) + "\">"
                    + FastTextEscaper.INSTANCE.escape(method.getShortNameForDisplay()) + "</a>";

            sb.append('[');
            appendJsonString(sb, anchor);
            sb.append(',').append(entry.getValue().getTestSuites().size())
                    .append(',').append(method.getLineNumberStart())
                    .append(',').append(method.getLineNumberEnd())
                    .append(']');
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Append {@code value} to {@code sb} as a double-quoted JSON string that is also safe to embed
     * inside an inline {@code <script>}. Beyond the standard JSON escapes (backslash, quote,
     * control characters) this unicode-escapes {@code <}, {@code >} and {@code &} so the emitted
     * text can never contain a literal {@code </script>} or {@code <!--} sequence that would
     * terminate the script element - the browser decodes {@code \\u003c} back to {@code <} when
     * parsing the JSON, so the anchor markup still reaches simple-datatables intact.
     *
     * @param sb the builder to append the quoted, escaped string to
     * @param value the raw string value to encode (already HTML-escaped anchor markup)
     */
    static void appendJsonString(StringBuilder sb, String value){
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<':  sb.append("\\u003c"); break;
                case '>':  sb.append("\\u003e"); break;
                case '&':  sb.append("\\u0026"); break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private void generateMethodReportFiles(TiaData tiaData, Map<Integer, ClassTestSuite> methodToTestSuites){
        long startTime = System.currentTimeMillis();
        log.info("Writing the method data including test suites reports to {}", reportOutputDir.getAbsoluteFile());

        methodToTestSuites.entrySet().parallelStream().forEach(entry ->
                writeTestSuitesReportFiles(tiaData, entry.getKey(), entry.getValue()));

        log.info("Time to write the report (ms): " + (System.currentTimeMillis() - startTime));
    }

    /**
     * Write the per-method drill-down page listing the test suites that cover the given method.
     * Renders through {@link FastTextEscaper#reportConfig()} and derives the output file name
     * via {@link #stripAngleBrackets(String)} (no regex) so this scales across the tens of
     * thousands of per-method files.
     *
     * @param tiaData the Tia data from the DB
     * @param methodTrackedHashCode the tracked method's id, used as the file name and title
     * @param classTestSuite the covering test suites for this method
     */
    private void writeTestSuitesReportFiles(TiaData tiaData, Integer methodTrackedHashCode, ClassTestSuite classTestSuite){
        MethodImpactTracker methodImpactTracker = tiaData.getMethodsTracked().get(methodTrackedHashCode);
        String fileName = reportOutputDir + File.separator + methodTrackedHashCode + ".html";
        fileName = stripAngleBrackets(fileName);

        // Drop the package from the heading: keep only the trailing "ClassName.methodName".
        String shortName = methodImpactTracker.getShortNameForDisplay();
        int lastDot = shortName.lastIndexOf('.');
        int secondLastDot = lastDot > 0 ? shortName.lastIndexOf('.', lastDot - 1) : -1;
        String classAndMethod = secondLastDot >= 0 ? shortName.substring(secondLastDot + 1) : shortName;

        try (Writer writer = HtmlLayout.newReportWriter(fileName)) {
            final String numberDataType = "data-type=\"number\"";

            html(
                    HtmlLayout.pageHead("Source Method - " + methodImpactTracker.getNameForDisplay(), ASSETS_REL),
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
            ).render(FlatHtml.into(writer, FastTextEscaper.reportConfig())).flush();
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

    /**
     * Remove every {@code '<'} and {@code '>'} character from the given file name. This is a
     * single-pass, allocation-free-when-clean replacement for the previous
     * {@code replaceAll("<", "").replaceAll(">", "")}, which compiled two regex patterns for every
     * one of the (up to tens of thousands of) per-method report files. The result is byte-identical
     * to the previous behaviour; the common case (a name derived from a numeric method id, which
     * contains no angle brackets) returns the same string instance without copying.
     *
     * @param fileName the file name to sanitise
     * @return the file name with all angle brackets removed
     */
    static String stripAngleBrackets(String fileName) {
        if (fileName.indexOf('<') < 0 && fileName.indexOf('>') < 0) {
            return fileName;
        }
        StringBuilder sb = new StringBuilder(fileName.length());
        for (int i = 0; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            if (c != '<' && c != '>') {
                sb.append(c);
            }
        }
        return sb.toString();
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
                        classTestSuite = new ClassTestSuite();
                        methodTestSuites.put(methodTrackedHashCode, classTestSuite);
                    }

                    classTestSuite.getTestSuites().add(testSuiteTracker);
                }
            }
        });

        return methodTestSuites;
    }

    private static class ClassTestSuite {
        final List<TestSuiteTracker> testSuites = new ArrayList<>();

        public List<TestSuiteTracker> getTestSuites() {
            return testSuites;
        }
    }
}
