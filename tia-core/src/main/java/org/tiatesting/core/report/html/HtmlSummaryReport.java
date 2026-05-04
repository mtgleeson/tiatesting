package org.tiatesting.core.report.html;

import j2html.Config;
import j2html.rendering.FlatHtml;
import j2html.tags.DomContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        String assetsRel = HtmlAssetCopier.ASSETS_DIR_NAME;
        String rootRel = "";
        int pendingCount = tiaData.getPendingLibraryImpactedMethods() != null
                ? tiaData.getPendingLibraryImpactedMethods().size() : 0;

        try (FileWriter writer = new FileWriter(fileName)) {
            int numTestSuites = tiaData.getTestSuitesTracked().size();
            int numSourceMethods = tiaData.getMethodsTracked().size();
            TestStats stats = tiaData.getTestStats();

            html(
                    HtmlLayout.pageHead("Summary", assetsRel),
                    body(
                            HtmlLayout.topNav(HtmlLayout.NavKey.HOME, assetsRel, rootRel, pendingCount),
                            main(
                                    h2("Summary"),
                                    p(small("Report generated at: " + dtf.format(Instant.now()))),

                                    HtmlLayout.sectionHeading(HtmlLayout.ICON_DB, "Tia DB"),
                                    p(
                                            span("DB last updated: " + (tiaData.getLastUpdated() != null
                                                    ? dtf.format(tiaData.getLastUpdated()) : "N/A")), br(),
                                            span("Test mapping valid for commit: "
                                                    + (tiaData.getCommitValue() != null ? tiaData.getCommitValue() : "N/A"))
                                    ),

                                    HtmlLayout.sectionHeading(HtmlLayout.ICON_STATS, "Stats"),
                                    p(
                                            span("Number of test classes with mappings: " + numTestSuites), br(),
                                            span("Number of source methods tracked for tests: " + numSourceMethods)
                                    ),
                                    p(
                                            span("Number of runs: " + stats.getNumRuns()), br(),
                                            span("Average run time: " + ReportUtils.prettyDuration(stats.getAvgRunTime())), br(),
                                            span("Number of successful runs: " + stats.getNumSuccessRuns()
                                                    + " (" + getAvgSuccess(stats) + "%)"), br(),
                                            span("Number of failed runs: " + stats.getNumFailRuns()
                                                    + " (" + getAvgFail(stats) + "%)")
                                    ),

                                    HtmlLayout.sectionHeading(HtmlLayout.ICON_FAILED, "Pending Failed Tests"),
                                    renderPendingFailedTests(tiaData),

                                    HtmlLayout.sectionHeading(HtmlLayout.ICON_LIBRARY, "Pending Library Changes"),
                                    renderPendingLibraryChanges(tiaData)
                            ),
                            HtmlLayout.pageFooter()
                    )
            ).render(FlatHtml.into(writer, Config.defaults().withEmptyTagsClosed(true))).flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.info("Time to write the report (ms): " + (System.currentTimeMillis() - startTime));
    }

    private DomContent renderPendingFailedTests(TiaData tiaData) {
        if (tiaData.getTestSuitesFailed() == null || tiaData.getTestSuitesFailed().isEmpty()) {
            return p(span("No pending failed tests.").withClass("tia-empty"));
        }
        return p(each(tiaData.getTestSuitesFailed(), name -> span(span(name), br())));
    }

    private DomContent renderPendingLibraryChanges(TiaData tiaData) {
        List<PendingLibraryImpactedMethod> pending = tiaData.getPendingLibraryImpactedMethods();
        if (pending == null || pending.isEmpty()) {
            return p(span("No pending library changes.").withClass("tia-empty"));
        }

        // Group by library to surface row count per library and produce a tidy summary row.
        Map<String, List<PendingLibraryImpactedMethod>> byLib = new LinkedHashMap<>();
        for (PendingLibraryImpactedMethod m : pending) {
            byLib.computeIfAbsent(m.getGroupArtifact(), k -> new ArrayList<>()).add(m);
        }

        return table(
                thead(tr(
                        th("Library"),
                        th("Stamp version"),
                        th("Methods pending"),
                        th("Unknown next version"),
                        th("JAR hash (SNAPSHOT)")
                )),
                tbody(each(byLib.entrySet(), entry ->
                        each(entry.getValue(), batch ->
                                tr(
                                        td(entry.getKey()),
                                        td(batch.getStampVersion()),
                                        td(String.valueOf(batch.getSourceMethodIds() != null
                                                ? batch.getSourceMethodIds().size() : 0)),
                                        td(batch.isUnknownNextVersion() ? "yes" : "no"),
                                        td(batch.getStampJarHash() != null
                                                ? truncateHash(batch.getStampJarHash()) : "—")
                                )
                        )
                ))
        );
    }

    private String truncateHash(String hash) {
        return hash.length() > 12 ? hash.substring(0, 12) + "…" : hash;
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

    File getReportOutputDir() {
        return reportOutputDir;
    }
}
