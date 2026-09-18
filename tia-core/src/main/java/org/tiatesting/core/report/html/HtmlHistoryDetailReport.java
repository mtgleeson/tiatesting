package org.tiatesting.core.report.html;

import j2html.rendering.FlatHtml;
import j2html.tags.DomContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunTrigger;
import org.tiatesting.core.report.ReportUtils;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static j2html.TagCreator.attrs;
import static j2html.TagCreator.body;
import static j2html.TagCreator.br;
import static j2html.TagCreator.each;
import static j2html.TagCreator.html;
import static j2html.TagCreator.main;
import static j2html.TagCreator.p;
import static j2html.TagCreator.rawHtml;
import static j2html.TagCreator.span;
import static j2html.TagCreator.table;
import static j2html.TagCreator.tbody;
import static j2html.TagCreator.td;
import static j2html.TagCreator.text;
import static j2html.TagCreator.th;
import static j2html.TagCreator.thead;
import static j2html.TagCreator.tr;

/**
 * Renders the per-run "Run detail" HTML page - the summary of a single {@code
 * tia_test_run_history} row plus, when recorded, the breakdown of what drove selection for that
 * run: the per-changed-method and per-static-rule triggers, ranked by how many test suites each
 * one accounts for, and the scalar counts for the other selection sources.
 *
 * <p>The page is a one-level-deep sibling of {@link HtmlHistoryReport}'s {@code
 * tia-history.html}, at {@code history/<entry-id>.html}, so the two pages can link to each other
 * with a plain filename and share the same {@code ASSETS_REL}/{@code ROOT_REL} depth.
 *
 * <p>See the "Run history details" chapter in {@code WIKI.md}.
 */
public class HtmlHistoryDetailReport {
    private static final Logger log = LoggerFactory.getLogger(HtmlHistoryDetailReport.class);
    protected static final String HISTORY_FOLDER = "history";

    /** Path back to the assets dir from a one-level-deep page. */
    private static final String ASSETS_REL = "../" + HtmlAssetCopier.ASSETS_DIR_NAME;
    /** Path back to the report root from a one-level-deep page. */
    private static final String ROOT_REL = "../";

    private final File reportOutputDir;

    /**
     * @param filenameExt the branch-named subfolder under {@code <reportOutputDir>/html/}
     *                    that scopes all report files for the current branch
     * @param reportOutputDir the project's configured report-output root (the parent of
     *                        the per-branch {@code html/<branch>/} tree)
     */
    public HtmlHistoryDetailReport(String filenameExt, File reportOutputDir) {
        this.reportOutputDir = new File(reportOutputDir.getAbsoluteFile() + File.separator + "html"
                + File.separator + filenameExt + File.separator + HISTORY_FOLDER);
    }

    /**
     * Generate the detail page for one history entry. Creates the output folder if needed and
     * writes {@code history/<entry.getId()>.html} into it.
     *
     * @param entry the history row this page describes
     * @param triggers the per-method and per-rule selection triggers recorded for this run;
     *                 empty when none were recorded (an all-tests run, or a row written before
     *                 this feature)
     * @param pendingLibraryCount the current count of pending library-impacted methods, shown as
     *                            the top-nav badge
     */
    public void generateReport(TestRunHistoryEntry entry, List<TestRunTrigger> triggers,
                               int pendingLibraryCount) {
        createOutputDir();
        writeDetailHtmlToFile(entry, triggers, pendingLibraryCount);
    }

    /**
     * Write the run-detail page. Renders through {@link FastTextEscaper#reportConfig()} so text
     * and attribute escaping use the report's fast, allocation-light escaper.
     *
     * @param entry the history row this page describes
     * @param triggers the per-method and per-rule selection triggers recorded for this run
     * @param pendingLibraryCount the current count of pending library-impacted methods, shown as
     *                            the top-nav badge
     */
    private void writeDetailHtmlToFile(TestRunHistoryEntry entry, List<TestRunTrigger> triggers,
                                       int pendingLibraryCount) {
        long startTime = System.currentTimeMillis();
        String fileName = reportOutputDir + File.separator + entry.getId() + ".html";
        log.info("Writing the run detail report to {}", fileName);

        List<TestRunTrigger> sourceMethodTriggers = TestRunTrigger.filterByTypeSortedByCountDesc(
                triggers, TestRunTrigger.Type.SOURCE_METHOD);
        List<TestRunTrigger> staticRuleTriggers = TestRunTrigger.filterByTypeSortedByCountDesc(
                triggers, TestRunTrigger.Type.STATIC_RULE);
        boolean noBreakdownRecorded = (triggers == null || triggers.isEmpty())
                && entry.getNumModifiedTestFiles() == null
                && entry.getNumNewTestFiles() == null
                && entry.getNumPreviouslyFailed() == null
                && entry.getNumUnsealedMapping() == null
                && entry.getNumPendingLibrary() == null;

        try (Writer writer = HtmlLayout.newReportWriter(fileName)) {
            html(
                    HtmlLayout.pageHead("Run detail", ASSETS_REL),
                    body(
                            HtmlLayout.topNav(HtmlLayout.NavKey.HISTORY, ASSETS_REL, ROOT_REL,
                                    pendingLibraryCount),
                            main(
                                    HtmlLayout.breadcrumb(
                                            HtmlLayout.Crumb.link("Home", ROOT_REL + "index.html"),
                                            HtmlLayout.Crumb.link("History", HtmlHistoryReport.TIA_HISTORY_HTML),
                                            HtmlLayout.Crumb.current(firstEightChars(entry.getId()), entry.getId())
                                    ),
                                    HtmlLayout.pageHeading(HtmlLayout.ICON_HISTORY, "Run detail"),
                                    buildSummaryBlock(entry),
                                    buildSelectionSourcesBlock(entry),
                                    noBreakdownRecorded
                                            ? p(span("No selection breakdown was recorded for this run.")
                                                    .withClass("tia-empty"))
                                            : buildTriggerTables(sourceMethodTriggers, staticRuleTriggers)
                            ),
                            HtmlLayout.pageFooter(),
                            // Localize first so simple-datatables captures the already-formatted
                            // cell text into its model, matching the ordering HtmlHistoryReport uses.
                            HtmlLayout.localTimeRenderingScript(),
                            noBreakdownRecorded ? text("") : buildTriggerTablesInit()
                    )
            ).render(FlatHtml.into(writer, FastTextEscaper.reportConfig())).flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.info("Time to write the run detail report (ms): " + (System.currentTimeMillis() - startTime));
    }

    /**
     * Build the summary block: the run's timestamp, branch, commit, suite counts, duration and
     * savings. The timestamp is rendered the same way as {@link HtmlHistoryReport}'s table rows -
     * an HTML5 {@code <time>} element carrying the UTC epoch ms, localized client-side by {@link
     * HtmlLayout#localTimeRenderingScript()}.
     *
     * @param entry the history row this page describes
     * @return the summary block content
     */
    private DomContent buildSummaryBlock(TestRunHistoryEntry entry) {
        long ms = entry.getRunTimestampMs();
        // Fallback text shown only when the localizer script doesn't run (JS disabled). Truncate
        // to whole seconds and drop the UTC 'Z' so the displayed text matches the no-ms /
        // no-tz formatting rule even in that edge case.
        String fallback = Instant.ofEpochMilli(ms)
                .atOffset(ZoneOffset.UTC)
                .toLocalDateTime()
                .withNano(0)
                .toString();
        String savings = entry.getTimeSavingsMs() > 0
                ? ReportUtils.prettyDuration(entry.getTimeSavingsMs(), true) : "-";

        return p(
                span(rawHtml("Date / time: <time data-epoch-ms=\"" + ms + "\">" + fallback + "</time>")), br(),
                span("Branch: " + (entry.getBranch() == null ? "" : entry.getBranch())), br(),
                span("Commit: " + (entry.getCommit() == null ? "" : entry.getCommit())), br(),
                span("Suites ran: " + entry.getNumSuitesRan()
                        + ", ignored: " + entry.getNumSuitesIgnored()
                        + ", failed: " + entry.getNumSuitesFailed()), br(),
                span("Duration: " + ReportUtils.prettyDuration(entry.getDurationMs(), true)), br(),
                span("Savings: " + savings)
        );
    }

    /**
     * Build the five-counter "selection sources" list: the scalar counts for the selection
     * sources that are not broken down into individual triggers. Each counter renders {@code "-"}
     * when its {@link Integer} field is null (not recorded for this row).
     *
     * @param entry the history row this page describes
     * @return the selection-sources block content
     */
    private DomContent buildSelectionSourcesBlock(TestRunHistoryEntry entry) {
        return p(
                span("Modified test files: " + counterOrDash(entry.getNumModifiedTestFiles())), br(),
                span("New test files: " + counterOrDash(entry.getNumNewTestFiles())), br(),
                span("Previously-failed: " + counterOrDash(entry.getNumPreviouslyFailed())), br(),
                span("Unsealed-mapping: " + counterOrDash(entry.getNumUnsealedMapping())), br(),
                span("Pending library: " + counterOrDash(entry.getNumPendingLibrary()))
        );
    }

    /**
     * Render one selection-source counter, or a dash when it was not recorded.
     *
     * @param count the counter value; null when not recorded
     * @return {@code count} as a string, or {@code "-"} when null
     */
    private String counterOrDash(Integer count) {
        return count == null ? "-" : count.toString();
    }

    /**
     * Build the two ranked trigger tables: source-method changes and static rules, each name
     * paired with the number of test suites it accounts for.
     *
     * @param sourceMethodTriggers the source-method triggers, already sorted by count descending
     * @param staticRuleTriggers the static-rule triggers, already sorted by count descending
     * @return the two tables' content
     */
    private DomContent buildTriggerTables(List<TestRunTrigger> sourceMethodTriggers,
                                          List<TestRunTrigger> staticRuleTriggers) {
        List<DomContent> content = new ArrayList<>();
        content.add(HtmlLayout.sectionHeading(HtmlLayout.ICON_CODE, "Source method changes"));
        content.add(buildTriggerTable("sourceMethodTriggers", "Method", sourceMethodTriggers));
        content.add(HtmlLayout.sectionHeading(HtmlLayout.ICON_STATS, "Static rules"));
        content.add(buildTriggerTable("staticRuleTriggers", "Rule", staticRuleTriggers));
        return each(content, c -> c);
    }

    /**
     * Build one ranked, sortable trigger table: name and test count columns.
     *
     * @param tableId the element id used both for the {@code <table>} and its simple-datatables
     *                selector
     * @param nameColumnHeading the heading for the name column ("Method" or "Rule")
     * @param triggers the triggers to render, already sorted by count descending
     * @return the {@code <table>} content
     */
    private DomContent buildTriggerTable(String tableId, String nameColumnHeading,
                                         List<TestRunTrigger> triggers) {
        return table(attrs("#" + tableId),
                thead(tr(th(nameColumnHeading), th("Test count").attr("data-type=\"number\""))),
                tbody(each(triggers, trigger -> tr(
                        td(text(trigger.getName())),
                        td(String.valueOf(trigger.getTestCount()))
                )))
        );
    }

    /**
     * Build the simple-datatables init scripts for both trigger tables, each sorted by its test
     * count column (index 1) descending so the biggest contributors lead.
     *
     * @return the init script tags for both tables
     */
    private DomContent buildTriggerTablesInit() {
        List<DomContent> scripts = new ArrayList<>();
        scripts.add(HtmlLayout.simpleDatatablesInit("#sourceMethodTriggers", ASSETS_REL, 1, "desc"));
        scripts.add(HtmlLayout.simpleDatatablesInit("#staticRuleTriggers", ASSETS_REL, 1, "desc"));
        return each(scripts, s -> s);
    }

    /**
     * First 8 characters of a value, used to keep the breadcrumb's current-page label compact.
     * The full value is available on the crumb's hover title.
     *
     * @param value the source value; may be null defensively
     * @return the first 8 characters of {@code value}, or the whole value if shorter, or
     *         {@code ""} when {@code value} is null
     */
    private static String firstEightChars(String value) {
        if (value == null) return "";
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    /**
     * Create the history output directory if it does not already exist, logging a warning when
     * the directory cannot be created rather than failing the report generation.
     */
    private void createOutputDir() {
        if (!reportOutputDir.exists() && !reportOutputDir.mkdirs()) {
            log.warn("Failed to create report output directory: {}", reportOutputDir.getAbsolutePath());
        }
    }
}
