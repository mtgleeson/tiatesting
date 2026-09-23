package org.tiatesting.core.report.html;

import j2html.rendering.FlatHtml;
import j2html.tags.DomContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.report.SummaryStats;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
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

    public HtmlSummaryReport(String filenameExt, File reportOutputDir){
        this.filenameExt = filenameExt;
        this.reportOutputDir = new File(reportOutputDir.getAbsoluteFile() + File.separator + "html"
                + File.separator + filenameExt);
    }

    public void generateSummaryReport(TiaData tiaData) {
        createOutputDir();
        generateSummaryReportData(tiaData);
    }

    /**
     * Write the summary index page. Renders through {@link FastTextEscaper#reportConfig()} so
     * text and attribute escaping use the report's fast, allocation-light escaper.
     *
     * @param tiaData the Tia data from the DB
     */
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

        try (Writer writer = HtmlLayout.newReportWriter(fileName)) {
            // Built by the same model the console and plain-text summaries render, so the three
            // cannot drift on the wording, grouping or arithmetic.
            List<SummaryStats.Section> statsSections = SummaryStats.build(
                    tiaData.getTestSuitesTracked().size(), tiaData.getMethodsTracked().size(),
                    tiaData.getTestStats(), tiaData.getTestRunHistory());

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
                                            span("Branch: " + (tiaData.getBranch() != null
                                                    ? tiaData.getBranch() : "N/A")), br(),
                                            span("Test mapping valid for commit: "
                                                    + (tiaData.getCommitValue() != null ? tiaData.getCommitValue() : "N/A"))
                                    ),

                                    HtmlLayout.sectionHeading(HtmlLayout.ICON_STATS, "Stats"),
                                    each(statsSections, HtmlSummaryReport::renderStatsSection),

                                    HtmlLayout.sectionHeading(HtmlLayout.ICON_FAILED, "Pending Failed Tests"),
                                    renderPendingFailedTests(tiaData),

                                    HtmlLayout.sectionHeading(HtmlLayout.ICON_LIBRARY, "Pending Library Changes"),
                                    renderPendingLibraryChanges(tiaData)
                            ),
                            HtmlLayout.pageFooter()
                    )
            ).render(FlatHtml.into(writer, FastTextEscaper.reportConfig())).flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.info("Time to write the report (ms): " + (System.currentTimeMillis() - startTime));
    }

    /**
     * Render one Stats section: its heading - an {@code h4} for a top-level section, an {@code h5}
     * for one nested under it - then its lines as a paragraph. A heading or line with a hint
     * carries it as a hover tooltip, so the explanation sits where the reader is looking without
     * adding to the page.
     *
     * @param section the section to render
     * @return the heading and paragraph
     */
    private static DomContent renderStatsSection(SummaryStats.Section section) {
        List<DomContent> lines = new ArrayList<>(section.getLines().size() * 2);
        for (SummaryStats.Line line : section.getLines()) {
            lines.add(line.getHint() == null
                    ? span(line.toText())
                    : span(hinted(line.getLabel(), line.getHint()), text(": " + line.getValue())));
            lines.add(br());
        }
        DomContent heading = section.getHint() == null
                ? text(section.getHeading())
                : hinted(section.getHeading(), section.getHint());
        return each(section.getDepth() == 0 ? h4(heading) : h5(heading),
                p(each(lines, content -> content)));
    }

    /**
     * Wrap text in a span that shows a hover tooltip. The {@code tia-stat-hint} class lets the
     * tooltip wrap and anchors it to the text's left edge, since Pico's default truncates it to
     * one line centred on the text - see {@code tia.css}.
     *
     * @param text the visible text
     * @param hint the tooltip text
     * @return the hinted span
     */
    private static DomContent hinted(String text, String hint) {
        return span(text).withClass("tia-stat-hint").attr("data-tooltip", hint);
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
                        th("Publish seq"),
                        th("Version"),
                        th("Methods pending")
                )),
                tbody(each(byLib.entrySet(), entry ->
                        each(entry.getValue(), batch ->
                                tr(
                                        td(entry.getKey()),
                                        td(String.valueOf(batch.getPublishSeq())),
                                        td(batch.getStampVersion()),
                                        td(String.valueOf(batch.getSourceMethodIds() != null
                                                ? batch.getSourceMethodIds().size() : 0))
                                )
                        )
                ))
        );
    }

    private void createOutputDir() {
        reportOutputDir.mkdirs();
    }

    File getReportOutputDir() {
        return reportOutputDir;
    }
}
